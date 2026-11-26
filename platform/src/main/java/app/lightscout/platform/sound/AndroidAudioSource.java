package app.lightscout.platform.sound;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.lightscout.domain.port.AudioSourcePort;
import app.lightscout.domain.sound.ImportOutcome;
import app.lightscout.domain.sound.PhoneSound;

/**
 * {@link AudioSourcePort} (IF-68, DCR-51): the phone's alarm sounds and a copy of any sound by URI. No permission is
 * needed for either (the device test SP-1 on RD-1 confirmed it). A sound is copied byte for byte; the copy is
 * accepted when {@code MediaPlayer} can open it and reports a positive duration. Everything here reads storage:
 * call off the main thread (a {@code RingtoneManager} cursor read is disk access).
 */
public final class AndroidAudioSource implements AudioSourcePort {

    private final Context context;

    /**
     * @param context any context; its application context is kept
     */
    public AndroidAudioSource(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public List<PhoneSound> phoneAlarmSounds() {
        List<PhoneSound> sounds = new ArrayList<>();
        try {
            RingtoneManager manager = new RingtoneManager(context);
            manager.setType(RingtoneManager.TYPE_ALARM);
            try (Cursor cursor = manager.getCursor()) {
                for (int i = 0; i < cursor.getCount(); i++) {
                    Uri uri = manager.getRingtoneUri(i);
                    if (uri == null || !cursor.moveToPosition(i)) {
                        continue;
                    }
                    String title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX);
                    if (title != null && !title.isBlank()) {
                        sounds.add(new PhoneSound(uri.toString(), title));
                    }
                }
            }
        } catch (RuntimeException e) {
            return List.of();   // a phone without a usable list simply offers no phone sounds
        }
        return sounds;
    }

    @Override
    public ImportOutcome copyTo(String uri, File target, long maxBytes, BooleanSupplier cancelled) {
        try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(Uri.parse(uri),
                "r")) {
            if (descriptor == null) {
                return ImportOutcome.CANNOT_READ;
            }
            try (InputStream in = descriptor.createInputStream(); OutputStream out = new FileOutputStream(target)) {
                ImportOutcome copied = BoundedCopy.copy(in, out, maxBytes, cancelled);
                if (copied != ImportOutcome.OK) {
                    return copied;
                }
            }
        } catch (IOException | RuntimeException e) {
            return ImportOutcome.CANNOT_READ;   // includes a provider that refuses (SecurityException)
        }
        return opens(target) ? ImportOutcome.OK : ImportOutcome.NOT_PLAYABLE;
    }

    private static boolean opens(File file) {
        MediaPlayer player = new MediaPlayer();
        try (FileInputStream in = new FileInputStream(file)) {
            player.setDataSource(in.getFD());
            player.prepare();
            return player.getDuration() > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            player.release();
        }
    }
}
