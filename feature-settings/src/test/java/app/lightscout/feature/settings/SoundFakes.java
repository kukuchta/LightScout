package app.lightscout.feature.settings;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.SoundPreview;
import app.lightscout.domain.sound.ImportOutcome;
import app.lightscout.domain.sound.OwnSound;
import app.lightscout.domain.sound.PhoneCopy;
import app.lightscout.domain.sound.PhoneSound;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundRef;
import app.lightscout.usecases.sound.AlarmSoundService;
import app.lightscout.usecases.sound.AlarmSoundsView;
import app.lightscout.usecases.sound.SoundAction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** Hand-written fakes for the sound screens (no Mockito, CP-00 §2): a service over a real library, and a preview. */
final class SoundFakes {

    static final String KEY_A = "0123456789abcdef";
    static final String KEY_B = "fedcba9876543210";

    private SoundFakes() {
    }

    static OwnSound own(String key, String name) {
        return new OwnSound(key, name, "own-" + key, 10L);
    }

    static PhoneCopy phoneCopy(String key, String uri, String title) {
        return new PhoneCopy(key, uri, title, "phone-" + key, 10L);
    }

    /** LiveData in JVM tests: everything runs on the calling thread. */
    static void useInstantExecutor() {
        ArchTaskExecutor.getInstance().setDelegate(new TaskExecutor() {
            @Override
            public void executeOnDiskIO(Runnable runnable) {
                runnable.run();
            }

            @Override
            public void postToMainThread(Runnable runnable) {
                runnable.run();
            }

            @Override
            public boolean isMainThread() {
                return true;
            }
        });
    }

    static void resetExecutor() {
        ArchTaskExecutor.getInstance().setDelegate(null);
    }

    /** The service on a real {@link SoundLibrary}; callbacks run at once, or when {@link #release()} is called. */
    static final class FakeService implements AlarmSoundService {
        SoundLibrary library = SoundLibrary.EMPTY;
        List<PhoneSound> phoneSounds = new ArrayList<>();
        ImportOutcome failWith;
        boolean hold;
        final List<String> calls = new ArrayList<>();
        private final Deque<Runnable> held = new ArrayDeque<>();
        private int nextKey = 1;

        void release() {
            while (!held.isEmpty()) {
                held.poll().run();
            }
        }

        private void run(Runnable work) {
            if (hold) {
                held.add(work);
            } else {
                work.run();
            }
        }

        private void act(Consumer<SoundAction> cb, java.util.function.UnaryOperator<SoundLibrary> change) {
            run(() -> {
                if (failWith != null) {
                    cb.accept(new SoundAction(failWith, library));
                    return;
                }
                library = change.apply(library);
                cb.accept(new SoundAction(ImportOutcome.OK, library));
            });
        }

        @Override
        public void load(Consumer<AlarmSoundsView> cb) {
            calls.add("load");
            run(() -> cb.accept(new AlarmSoundsView(library, phoneSounds)));
        }

        @Override
        public void loadLibrary(Consumer<SoundLibrary> cb) {
            calls.add("loadLibrary");
            run(() -> cb.accept(library));
        }

        @Override
        public void select(AlarmType type, SoundRef ref, Consumer<SoundAction> cb) {
            calls.add("select:" + type + ":" + ref.encode());
            act(cb, lib -> lib.select(type, ref));
        }

        @Override
        public void selectPhone(AlarmType type, PhoneSound sound, Consumer<SoundAction> cb) {
            calls.add("selectPhone:" + type + ":" + sound.uri());
            act(cb, lib -> {
                String key = lib.phoneCopyFor(sound.uri()).map(PhoneCopy::key)
                        .orElse(String.format("%016x", nextKey++));
                SoundLibrary withCopy = lib.phoneCopyFor(sound.uri()).isPresent() ? lib
                        : lib.addPhoneCopy(phoneCopy(key, sound.uri(), sound.title()));
                return withCopy.select(type, SoundRef.phone(key));
            });
        }

        @Override
        public void addOwn(String documentUri, String displayName, AlarmType selectFor, Consumer<SoundAction> cb) {
            calls.add("addOwn:" + documentUri + ":" + displayName + ":" + selectFor);
            run(() -> {
                if (failWith != null) {
                    cb.accept(new SoundAction(failWith, library));
                    return;
                }
                if (!library.canAddOwn()) {
                    cb.accept(new SoundAction(ImportOutcome.LIMIT_REACHED, library));
                    return;
                }
                String key = String.format("%016x", 0x100 + nextKey++);
                SoundLibrary next = library.addOwn(own(key, displayName));
                if (selectFor != null) {
                    next = next.select(selectFor, SoundRef.own(key));
                }
                library = next;
                cb.accept(new SoundAction(ImportOutcome.OK, library));
            });
        }

        @Override
        public void remove(String ownKey, Consumer<SoundAction> cb) {
            calls.add("remove:" + ownKey);
            act(cb, lib -> lib.remove(ownKey));
        }

        @Override
        public void undoRemoval(Consumer<SoundAction> cb) {
            calls.add("undo");
            act(cb, SoundLibrary::undoRemoval);
        }

        @Override
        public void finaliseRemoval(Consumer<SoundAction> cb) {
            calls.add("finalise");
            act(cb, SoundLibrary::finaliseRemoval);
        }

        @Override
        public void sweep() {
            calls.add("sweep");
        }
    }

    /** Records the calls; {@link #emit} plays the part of the main-thread events. */
    static final class FakePreview implements SoundPreview {
        final List<String> calls = new ArrayList<>();
        private final java.util.Map<AlarmType, Listener> listeners = new java.util.EnumMap<>(AlarmType.class);

        @Override
        public void play(AlarmType type, Listener listener) {
            calls.add("play:" + type);
            listeners.put(type, listener);
        }

        @Override
        public void stop() {
            calls.add("stop");
        }

        void emit(AlarmType type, Event event) {
            listeners.get(type).onEvent(type, event);
        }
    }
}
