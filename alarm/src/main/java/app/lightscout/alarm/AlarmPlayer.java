package app.lightscout.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmSoundResolver;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.sound.PlayableSound;
import app.lightscout.platform.sound.BundledSounds;

import java.io.FileInputStream;
import java.util.OptionalInt;
import java.util.function.LongSupplier;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * CMP-41 AlarmPlayer (CP-07 §3, §8.2–§8.5; F3: process-wide, one per process, owned by {@link AlarmRuntime}). Plays on
 * a dedicated {@link HandlerThread}; escalation steps run by {@code Handler} (§4.1). Android glue around
 * {@link RingEngine} (logic) and {@link CallWatch} (F10):
 * <ul>
 *   <li>{@code MediaPlayer} with {@code AudioAttributes.USAGE_ALARM}, looping; audio focus
 *       {@code AUDIOFOCUS_GAIN_TRANSIENT}, re-requested on every keep-alive while not held; focus only lowers the
 *       level, the call state comes from the audio mode ({@link CallWatch}, IR-82);</li>
 *   <li>alarm-stream volume via {@code AudioManager.setStreamVolume(STREAM_ALARM)} only per D-023 (SR-26: no DND,
 *       interruption-filter or zen-rule calls);</li>
 *   <li>vibration waveform 800 ms on / 400 ms off, repeating, alarm usage (R-80);</li>
 *   <li>partial wake lock while ringing, re-armed with a 2-min timeout (F16).</li>
 * </ul>
 * Calls from other threads block until the player thread ran them (≤ 2 s), so the F8 order „commit the saved volume,
 * then change the volume” and „stop, then restore” holds; {@link #silence} does not block.
 */
final class AlarmPlayer implements AlarmSound {

    private static final long SYNC_TIMEOUT_MS = 2_000L;
    private static final long[] VIBRATION_WAVEFORM = {0L, 800L, 400L};
    private static final String WAKE_LOCK_TAG = "LightScout:alarm";

    private final Context context;
    private final AlarmSoundResolver soundResolver;
    private final Handler handler;
    private final AudioManager audio;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    private final RingEngine engine;
    private final RingSession session;

    private MediaPlayer player;
    private AudioFocusRequest focusRequest;
    private AudioManager.OnModeChangedListener modeListener;
    private BroadcastReceiver screenReceiver;

    /**
     * Creates the player and its thread.
     *
     * @param context      application context
     * @param logger       IF-66
     * @param stepListener escalation progress (posted to {@code monitorExecutor} by the runtime)
     * @param onCallEnded  F10 call end (re-post CH-01 with the FSI)
     * @param elapsedMs    IF-15 monotonic time (snooze fail-safe)
     * @param soundResolver IF-69, the sound the user chose per alarm type (DCR-51); first used on the player thread
     */
    AlarmPlayer(Context context, Logger logger, RingEngine.StepListener stepListener, Runnable onCallEnded,
                LongSupplier elapsedMs, AlarmSoundResolver soundResolver) {
        this.context = context;
        this.soundResolver = soundResolver;
        HandlerThread thread = new HandlerThread("AlarmPlayer");
        thread.start();
        this.handler = new Handler(thread.getLooper());
        this.audio = context.getSystemService(AudioManager.class);
        Scheduler scheduler = (task, delayMs) -> {
            handler.postDelayed(task, delayMs);
            return () -> handler.removeCallbacks(task);
        };
        this.engine = new RingEngine(new StreamVolume(), new Output(), new PartialWakeLock(context), scheduler,
                logger, stepListener, elapsedMs, new ResolverSoundChoice(soundResolver));
        this.session = new RingSession(engine, Build.VERSION.SDK_INT, new Modes(), new Focus(), scheduler,
                onCallEnded);
    }

    @Override
    public void start(AlarmType type, int step, int userVolume) {
        runSync(() -> {
            session.start(type, step, userVolume);
            watchScreen();
        });
    }

    /** RISK-25: the OS may cancel the vibration on screen off; re-issue it on every screen change while ringing. */
    private void watchScreen() {
        if (screenReceiver != null) {
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                engine.refreshVibration();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        ContextCompat.registerReceiver(context, receiver, filter, null, handler,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        screenReceiver = receiver;
    }

    private void unwatchScreen() {
        if (screenReceiver != null) {
            context.unregisterReceiver(screenReceiver);
            screenReceiver = null;
        }
    }

    @Override
    public void switchType(AlarmType type) {
        runSync(() -> session.switchType(type));
    }

    @Override
    public void keepAlive(AlarmType type, int step, int userVolume) {
        runSync(() -> {
            session.keepAlive(type, step, userVolume);
            watchScreen();
        });
    }

    @Override
    public void silence(AlarmType type) {
        handler.post(() -> session.silence(type));
    }

    @Override
    public void stop() {
        runSync(() -> {
            try {
                session.stop();
            } finally {
                unwatchScreen();
            }
        });
    }

    @Override
    public void restoreVolume(int volume) {
        runSync(() -> session.restoreVolume(volume));
    }

    @Override
    public int currentVolume() {
        return audio.getStreamVolume(AudioManager.STREAM_ALARM);
    }

    @Override
    public int currentStep() {
        int[] out = new int[1];
        runSync(() -> out[0] = session.currentStep());
        return out[0];
    }

    private void runSync(Runnable r) {
        if (Looper.myLooper() == handler.getLooper()) {
            r.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        handler.post(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code STREAM_ALARM} volume. */
    private final class StreamVolume implements RingEngine.Volume {

        @Override
        public int current() {
            return audio.getStreamVolume(AudioManager.STREAM_ALARM);
        }

        @Override
        public int min() {
            return audio.getStreamMinVolume(AudioManager.STREAM_ALARM);
        }

        @Override
        public int max() {
            return audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        }

        @Override
        public boolean set(int index) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, index, 0);
            } catch (SecurityException e) {
                return false;   // VR-12: refused under DND → player-gain escalation
            }
            return audio.getStreamVolume(AudioManager.STREAM_ALARM) == index;
        }
    }

    /** {@code MediaPlayer} + {@code Vibrator}. */
    private final class Output implements RingEngine.Output {

        @Override
        public boolean play(SoundStage stage, AlarmType type) {
            stopPlayback();
            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setAudioAttributes(attributes);
                SoundSource source = SoundSource.of(stage);
                if (source == SoundSource.SELECTED) {
                    if (!setSelectedSource(mp, type)) {
                        mp.release();
                        reportUnplayable(type);
                        return false;
                    }
                } else if (source == SoundSource.RAW_ALARM_TONE) {
                    try (AssetFileDescriptor afd = context.getResources().openRawResourceFd(BundledSounds.classicRaw())) {
                        mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                    }
                } else if (source == SoundSource.SYSTEM_DEFAULT_ALARM_URI) {
                    mp.setDataSource(context, Settings.System.DEFAULT_ALARM_ALERT_URI);   // IR-84
                } else {
                    mp.release();
                    return false;
                }
                mp.setLooping(true);
                mp.setOnErrorListener((p, what, extra) -> {
                    engine.onPlaybackError();
                    return true;
                });
                mp.prepare();
                if (stage == SoundStage.SELECTED_SOUND && mp.getDuration() <= 0) {
                    // a file that opens but has nothing to play would ring silently: let the chain go on
                    throw new java.io.IOException("Chosen sound has no duration");
                }
                mp.start();
                player = mp;
                return true;
            } catch (java.io.IOException | RuntimeException e) {
                mp.release();
                if (stage == SoundStage.SELECTED_SOUND) {
                    reportUnplayable(type);
                }
                return false;
            }
        }

        /** The report is only a hint for the settings screen: it must never stop the alarm chain. */
        private void reportUnplayable(AlarmType type) {
            try {
                soundResolver.reportUnplayable(type);
            } catch (RuntimeException e) {
                // the chain goes on with the classic tone either way
            }
        }

        /**
         * Points the player at the sound the user chose for a type (DCR-51): a bundled resource or a file in app
         * storage that the resolver has checked. Anything else, or an id this build does not know, is {@code false}
         * and the chain goes on with the classic tone.
         */
        private boolean setSelectedSource(MediaPlayer mp, AlarmType type) throws java.io.IOException {
            PlayableSound sound = soundResolver.playable(type);
            switch (sound.kind()) {
                case BUNDLED:
                    OptionalInt raw = BundledSounds.rawFor(sound.name());
                    if (raw.isEmpty()) {
                        return false;
                    }
                    try (AssetFileDescriptor afd = context.getResources().openRawResourceFd(raw.getAsInt())) {
                        mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                    }
                    return true;
                case OWN:
                    try (FileInputStream in = new FileInputStream(sound.name())) {
                        mp.setDataSource(in.getFD());
                    }
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public void setGain(float gain) {
            if (player != null) {
                try {
                    player.setVolume(gain, gain);
                } catch (IllegalStateException e) {
                    // player in an error state: onError moves on to the next stage
                }
            }
        }

        @Override
        public void stopPlayback() {
            if (player != null) {
                try {
                    player.stop();
                } catch (IllegalStateException e) {
                    // not started / already in error: release below is enough
                }
                player.release();
                player = null;
            }
        }

        @Override
        public void startVibration() {
            Vibrator v = vibrator();
            if (v == null) {
                return;
            }
            VibrationEffect effect = VibrationEffect.createWaveform(VIBRATION_WAVEFORM, 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
            } else {
                vibrateLegacy(v, effect);
            }
        }

        @SuppressWarnings("deprecation")
        private void vibrateLegacy(Vibrator v, VibrationEffect effect) {
            v.vibrate(effect, attributes);   // API 29–32: alarm usage through AudioAttributes
        }

        @Override
        public void stopVibration() {
            Vibrator v = vibrator();
            if (v != null) {
                v.cancel();
            }
        }

        private Vibrator vibrator() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = context.getSystemService(VibratorManager.class);
                return vm == null ? null : vm.getDefaultVibrator();
            }
            return legacyVibrator();
        }

        @SuppressWarnings("deprecation")
        private Vibrator legacyVibrator() {
            return context.getSystemService(Vibrator.class);
        }
    }

    /** {@code AUDIOFOCUS_GAIN_TRANSIENT} request with alarm attributes; changes go to {@link CallWatch}. */
    private final class Focus implements CallWatch.FocusPort {

        @Override
        public boolean request() {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attributes)
                        .setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(change -> session.onFocusChange(change), handler)
                        .build();
            }
            return audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }

        @Override
        public void abandon() {
            if (focusRequest != null) {
                audio.abandonAudioFocusRequest(focusRequest);
            }
        }
    }

    /** {@code AudioManager} mode (F10). */
    private final class Modes implements CallWatch.ModeSource {

        @Override
        public int mode() {
            return audio.getMode();
        }

        @Override
        public void addModeListener(CallWatch.ModeListener listener) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modeListener = listener::onMode;
                audio.addOnModeChangedListener(handler::post, modeListener);
            }
        }

        @Override
        public void removeModeListener() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modeListener != null) {
                audio.removeOnModeChangedListener(modeListener);
                modeListener = null;
            }
        }
    }

    /** Partial wake lock (PERM-12, F16). */
    private static final class PartialWakeLock implements RingEngine.WakeLock {

        private final PowerManager.WakeLock lock;

        PartialWakeLock(Context context) {
            PowerManager pm = context.getSystemService(PowerManager.class);
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            lock.setReferenceCounted(false);
        }

        @Override
        public void acquire(long timeoutMs) {
            lock.acquire(timeoutMs);
        }

        @Override
        public void release() {
            if (lock.isHeld()) {
                lock.release();
            }
        }
    }
}
