package app.lightscout.platform.sound;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmSoundResolver;
import app.lightscout.domain.port.SoundPreview;
import app.lightscout.domain.sound.PlayableSound;

/**
 * IF-71 on Android (CMP-82, DCR-51 plan section 6). A small player of its own: it has none of the alarm's
 * escalation, wake lock or call logic, and it can sit in {@code :platform} where the settings screen can reach it.
 *
 * <ul>
 *   <li>{@code MediaPlayer} with alarm attributes, so it plays at the current alarm volume like the alarm does. It
 *       never calls {@code setStreamVolume} and never touches Do Not Disturb (SR-26);</li>
 *   <li>audio focus is {@code AUDIOFOCUS_GAIN_TRANSIENT} only, so an alarm that ignores a transient loss is never
 *       made quieter by a preview (IR-82); any focus loss stops the preview;</li>
 *   <li>all work runs on one {@code HandlerThread} (opening a file is disk access); events are posted to the
 *       executor given at construction (the main thread).</li>
 * </ul>
 * The thread starts on the first {@code play}; the constructor does no work.
 */
public final class SoundPreviewPlayer implements SoundPreview {

    private static final String THREAD_NAME = "SoundPreview";

    private final Context context;
    private final AlarmSoundResolver sounds;
    private final BooleanSupplier alarmRinging;
    private final Executor eventExecutor;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private Handler handler;
    private PreviewController controller;
    private MediaPlayer player;
    private AudioFocusRequest focusRequest;

    /**
     * Creates the preview.
     *
     * @param context       application context
     * @param sounds        IF-69, the sound the alarm would play for a type
     * @param alarmRinging  true while an alarm rings; read on the preview thread
     * @param eventExecutor where listener events are delivered (the main thread)
     */
    public SoundPreviewPlayer(Context context, AlarmSoundResolver sounds, BooleanSupplier alarmRinging,
                              Executor eventExecutor) {
        this.context = Objects.requireNonNull(context, "context");
        this.sounds = Objects.requireNonNull(sounds, "sounds");
        this.alarmRinging = Objects.requireNonNull(alarmRinging, "alarmRinging");
        this.eventExecutor = Objects.requireNonNull(eventExecutor, "eventExecutor");
    }

    @Override
    public void play(AlarmType type, Listener listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listener, "listener");
        Listener onMain = (t, e) -> eventExecutor.execute(() -> listener.onEvent(t, e));
        threadHandler().post(() -> controller.play(type, onMain));
    }

    @Override
    public void stop() {
        Handler h;
        synchronized (this) {
            h = handler;
        }
        if (h != null) {
            h.post(() -> controller.stop());
        }
    }

    private synchronized Handler threadHandler() {
        if (handler == null) {
            HandlerThread thread = new HandlerThread(THREAD_NAME);
            thread.start();
            handler = new Handler(thread.getLooper());
            controller = new PreviewController(sounds, alarmRinging, new Output(), new Focus(),
                    (task, delayMs) -> {
                        handler.postDelayed(task, delayMs);
                        return () -> handler.removeCallbacks(task);
                    });
        }
        return handler;
    }

    /** {@code MediaPlayer}, played once. */
    private final class Output implements PreviewController.Output {

        @Override
        public boolean start(PlayableSound sound) {
            release();
            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setAudioAttributes(attributes);
                if (!setSource(mp, sound)) {
                    mp.release();
                    return false;
                }
                mp.setLooping(false);
                mp.setOnCompletionListener(p -> {
                    if (p == player) {
                        controller.onCompleted();
                    }
                });
                mp.setOnErrorListener((p, what, extra) -> {
                    if (p == player) {
                        controller.onError();
                    }
                    return true;
                });
                mp.prepare();   // blocking: files are at most 10 MB and local, and a preview is a user tap
                mp.start();
                player = mp;
                return true;
            } catch (IOException | RuntimeException e) {
                mp.release();
                return false;
            }
        }

        private boolean setSource(MediaPlayer mp, PlayableSound sound) throws IOException {
            switch (sound.kind()) {
                case CLASSIC:
                    return setRaw(mp, BundledSounds.classicRaw());
                case BUNDLED:
                    OptionalInt raw = BundledSounds.rawFor(sound.name());
                    return raw.isPresent() && setRaw(mp, raw.getAsInt());
                case OWN:
                    try (FileInputStream in = new FileInputStream(sound.name())) {
                        mp.setDataSource(in.getFD());
                    }
                    return true;
                default:
                    return false;
            }
        }

        private boolean setRaw(MediaPlayer mp, int rawId) throws IOException {
            try (AssetFileDescriptor afd = context.getResources().openRawResourceFd(rawId)) {
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            }
            return true;
        }

        @Override
        public void stop() {
            release();
        }

        private void release() {
            MediaPlayer mp = player;
            player = null;
            if (mp != null) {
                try {
                    mp.stop();
                } catch (IllegalStateException e) {
                    // not started or already in error: release below is enough
                }
                mp.release();
            }
        }
    }

    /** {@code AUDIOFOCUS_GAIN_TRANSIENT} with alarm attributes; a loss of any kind ends the preview. */
    private final class Focus implements PreviewController.Focus {

        @Override
        public boolean request() {
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null) {
                return false;
            }
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attributes)
                        .setOnAudioFocusChangeListener(change -> {
                            if (change != AudioManager.AUDIOFOCUS_GAIN) {
                                controller.onFocusLost();
                            }
                        }, handler)
                        .build();
            }
            return audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }

        @Override
        public void abandon() {
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio != null && focusRequest != null) {
                audio.abandonAudioFocusRequest(focusRequest);
            }
        }
    }
}
