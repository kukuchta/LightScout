package app.lightscout.feature.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.CreationExtras;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.SoundPreview;
import app.lightscout.domain.sound.OwnSound;
import app.lightscout.domain.sound.PhoneCopy;
import app.lightscout.domain.sound.PhoneSound;
import app.lightscout.domain.sound.Removal;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundRef;
import app.lightscout.ui.core.EdgeToEdgeInsets;
import app.lightscout.usecases.sound.AlarmSoundService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * S-14 Sound picker (DCR-51) for the alarm type in the {@code alarmType} argument: LightScout classic, the phone's
 * alarm sounds, and the user's own files. A tap on a row chooses the sound and plays it once. The trash button
 * removes an own sound at once and a message offers Undo; a recreated view shows that message again because the open
 * removal is part of the stored library. An invalid argument leaves the screen.
 */
public final class AlarmSoundPickerFragment extends Fragment {

    /** Name of the one argument: the {@link AlarmType} name. */
    static final String ARG_ALARM_TYPE = "alarmType";
    private static final String AUDIO_MIME = "audio/*";

    private AlarmSoundPickerViewModel viewModel;
    private Executor io;
    private LinearLayout content;
    private SoundRowList rows;
    private Snackbar removalMessage;
    private Snackbar.Callback removalCallback;
    /** The removal the open message was built for; a different open removal needs a new message. */
    private Removal removalShownFor;
    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onFilePicked);

    public AlarmSoundPickerFragment() {
        super(R.layout.fragment_sound_screen);
    }

    @Nullable
    private static AlarmType parse(@Nullable Bundle args) {
        String name = args == null ? null : args.getString(ARG_ALARM_TYPE);
        if (name == null) {
            return null;
        }
        try {
            return AlarmType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AlarmType type = parse(getArguments());
        if (type == null) {
            return;   // onViewCreated leaves the screen
        }
        var dependencies = SettingsHost.dependencies(this);
        AlarmSoundService service = dependencies.get(AlarmSoundService.class);
        SoundPreview preview = dependencies.get(SoundPreview.class);
        io = dependencies.ioExecutor();
        viewModel = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass, @NonNull CreationExtras extras) {
                if (!modelClass.isAssignableFrom(AlarmSoundPickerViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel " + modelClass.getName());
                }
                return modelClass.cast(new AlarmSoundPickerViewModel(service, preview, type));
            }
        }).get(AlarmSoundPickerViewModel.class);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (viewModel == null) {
            NavHostFragment.findNavController(this).navigateUp();
            return;
        }
        MaterialToolbar toolbar = view.findViewById(R.id.sound_toolbar);
        toolbar.setTitle(SoundText.alarmName(viewModel.type()));
        setStandardUpDescription(toolbar);
        toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        EdgeToEdgeInsets.applyStatusBarInset(view.findViewById(R.id.sound_app_bar));
        NestedScrollView scroll = view.findViewById(R.id.sound_scroll);
        EdgeToEdgeInsets.applyScrollBottomInset(scroll, null);
        content = view.findViewById(R.id.sound_content);
        rows = new SoundRowList(content);
        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.message().observe(getViewLifecycleOwner(), this::onMessage);
        viewModel.problem().observe(getViewLifecycleOwner(), this::onProblem);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (viewModel != null) {
            viewModel.onShown();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (viewModel != null) {
            viewModel.stopPreview();
        }
    }

    @Override
    public void onDestroyView() {
        hideRemovalMessage();
        content = null;
        rows = null;
        super.onDestroyView();
    }

    @SuppressLint("PrivateResource")
    private void setStandardUpDescription(MaterialToolbar toolbar) {
        toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description);
    }

    // ------------------------------------------------------------------ rows

    private void render(@Nullable SoundPickerState state) {
        if (state == null || content == null || !state.loaded()) {
            return;
        }
        SoundLibrary library = state.library();
        SoundRef choice = library.choice().get(viewModel.type());
        List<SoundRowList.Spec> specs = new ArrayList<>();

        specs.add(SoundRowList.Spec.header("group:lightscout", getString(R.string.s14_group_lightscout)));
        specs.add(choiceSpec("classic", getString(R.string.sound_classic), null,
                SoundLabels.customName(library, choice) == null, () -> viewModel.select(SoundRef.CLASSIC)));

        if (!state.phoneSounds().isEmpty()) {
            specs.add(SoundRowList.Spec.header("group:phone", getString(R.string.s14_group_phone)));
            for (PhoneSound sound : state.phoneSounds()) {
                specs.add(phoneSpec(library, choice, sound, sound.uri().equals(state.busyPhoneUri())));
            }
        }

        specs.add(SoundRowList.Spec.header("group:own", getString(R.string.s14_group_own)));
        for (OwnSound own : library.ownSounds()) {
            SoundRef ref = SoundRef.own(own.key());
            SoundRowList.Button trash = new SoundRowList.Button(R.drawable.ic_sound_delete,
                    getString(R.string.sound_remove_cd, own.displayName()), () -> viewModel.remove(own.key()));
            specs.add(choiceSpec("own:" + own.key(), own.displayName(), null, ref.equals(choice),
                    () -> viewModel.select(ref), trash));
        }
        specs.add(addSpec(library, state.adding(), state.busyPhoneUri() != null));
        rows.bind(specs);
        syncRemovalMessage(library);
    }

    private SoundRowList.Spec phoneSpec(SoundLibrary library, SoundRef choice, PhoneSound sound, boolean copying) {
        Optional<PhoneCopy> copy = library.phoneCopyFor(sound.uri());
        boolean checked = copy.isPresent() && SoundRef.phone(copy.get().key()).equals(choice);
        return choiceSpec("phone:" + sound.uri(), sound.title(), copying ? getString(R.string.s14_copying) : null,
                checked, () -> viewModel.selectPhone(sound));
    }

    private SoundRowList.Spec choiceSpec(String key, String title, @Nullable String support, boolean checked,
                                         Runnable click, SoundRowList.Button... buttons) {
        String description = support == null ? title : getString(R.string.s04_row_value_a11y, title, support);
        return SoundRowList.Spec.row(key, title, support, description, true, checked, click, buttons);
    }

    private SoundRowList.Spec addSpec(SoundLibrary library, boolean adding, boolean copyingPhoneSound) {
        boolean full = !library.canAddOwn();
        String support = adding ? getString(R.string.s14_adding)
                : full ? getString(R.string.s14_limit_reached) : null;
        String title = getString(R.string.s14_add_from_file);
        return SoundRowList.Spec.row("add", title, support,
                support == null ? title : getString(R.string.s04_row_value_a11y, title, support),
                !full && !adding && !copyingPhoneSound, null,
                () -> filePicker.launch(new String[] {AUDIO_MIME}));
    }

    // ------------------------------------------------------------------ picked file

    private void onFilePicked(@Nullable Uri uri) {
        if (uri == null || viewModel == null) {
            return;
        }
        Context app = requireContext().getApplicationContext();
        AlarmSoundPickerViewModel model = viewModel;
        // the provider is asked for the name off the main thread; the copy itself runs in the service
        io.execute(() -> model.addOwn(uri.toString(), displayNameOf(app, uri)));
    }

    @Nullable
    private static String displayNameOf(Context app, Uri uri) {
        try (Cursor cursor = app.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            // the service names the sound "Sound N"
        }
        return null;
    }

    // ------------------------------------------------------------------ messages

    private void onMessage(@Nullable PickerMessage message) {
        View root = getView();
        if (message == null || root == null) {
            return;
        }
        int text = switch (message) {
            case TOO_LARGE -> R.string.s14_message_too_large;
            case NOT_PLAYABLE -> R.string.s14_message_not_playable;
            case LIMIT_REACHED -> R.string.s14_limit_reached;
            case CANNOT_READ -> R.string.s14_message_cannot_read;
        };
        showMessage(text, Snackbar.LENGTH_LONG);
        viewModel.clearMessage();
    }

    private void onProblem(@Nullable SoundPreviewControl.Problem problem) {
        if (problem == null) {
            return;
        }
        if (problem == SoundPreviewControl.Problem.FAILED) {
            showMessage(R.string.sound_preview_failed, Snackbar.LENGTH_SHORT);
        }
        viewModel.clearProblem();
    }

    // ------------------------------------------------------------------ removal with Undo

    /** Shows the Undo message while the library holds an open removal, and takes it away when it does not. */
    private void syncRemovalMessage(SoundLibrary library) {
        Optional<Removal> removal = library.pendingRemoval();
        if (removal.isEmpty()) {
            hideRemovalMessage();
            return;
        }
        if (removalMessage != null && !removal.get().equals(removalShownFor)) {
            hideRemovalMessage();   // a second removal replaced the first: Undo must act on the one on screen
        }
        if (removalMessage != null || getView() == null) {
            return;
        }
        removalShownFor = removal.get();
        removalCallback = new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar bar, int event) {
                removalMessage = null;
                if (event == Snackbar.Callback.DISMISS_EVENT_ACTION) {
                    viewModel.undo();
                } else if (event != Snackbar.Callback.DISMISS_EVENT_CONSECUTIVE) {
                    viewModel.finalise();
                }
                // CONSECUTIVE: another message took the place; the removal is shown again when that one ends
            }
        };
        removalMessage = Snackbar.make(getView(), removalText(removal.get()), Snackbar.LENGTH_LONG)
                .setAction(R.string.s14_undo, v -> { })
                .addCallback(removalCallback);
        removalMessage.show();
    }

    /** A short message that, when it ends, brings the removal message back if the removal is still open. */
    private void showMessage(int text, int length) {
        View root = getView();
        if (root == null) {
            return;
        }
        Snackbar.make(root, text, length).addCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar bar, int event) {
                SoundPickerState state = viewModel == null ? null : viewModel.state().getValue();
                if (state != null && content != null && removalMessage == null) {
                    syncRemovalMessage(state.library());
                }
            }
        }).show();
    }

    /** Takes the message away without telling the model: the view goes, or the removal is already closed. */
    private void hideRemovalMessage() {
        Snackbar message = removalMessage;
        removalMessage = null;
        if (message != null) {
            message.removeCallback(removalCallback);
            message.dismiss();
        }
    }

    private String removalText(Removal removal) {
        String name = removal.sound().displayName();
        List<AlarmType> reassigned = removal.reassigned();
        if (reassigned.isEmpty()) {
            return getString(R.string.s14_removed, name);
        }
        if (reassigned.size() == 1) {
            return getString(R.string.s14_removed_reassigned_one, name,
                    getString(SoundText.alarmName(reassigned.get(0))));
        }
        List<String> names = new ArrayList<>();
        for (AlarmType type : SoundLabels.ORDER) {
            if (reassigned.contains(type)) {
                names.add(getString(SoundText.alarmName(type)));
            }
        }
        return getString(R.string.s14_removed_reassigned_many, name, String.join(", ", names));
    }
}
