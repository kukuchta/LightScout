package app.lightscout.feature.entry;

import android.content.Context;
import android.os.Bundle;
import android.app.Dialog;
import android.view.WindowManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import app.lightscout.domain.entry.EntryMode;
import app.lightscout.domain.entry.EntryRules;
import app.lightscout.domain.entry.SourceKind;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.Clock;
import app.lightscout.feature.entry.EntryFormUiState.Direction;
import app.lightscout.feature.entry.EntryFormUiState.Similar;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.Formatters;
import app.lightscout.ui.core.SingleActionGuard;
import app.lightscout.usecases.EntryWriter;
import app.lightscout.usecases.StatusRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;

/**
 * CMP-73 S-02 Entry form (CP-13 §7.3; C-S02-01…14; A-S02-1…5). Renders {@link EntryFormUiState} and forwards input
 * to {@link EntryFormModel}; every rule lives in the model (IF-06 / IF-23). The ViewModel is scoped to this
 * destination (one form session per opening, GIR-3); its {@code SavedStateHandle} keeps type, value, time, the
 * identifier and the „save in flight” flag across process death (CP-13 §7.7).
 *
 * <p>R-92 replaces automatic dialog dismissal with a common close guard; Back hides a visible IME first.
 */
public final class EntryFormFragment extends BottomSheetDialogFragment {

    private static final String TAG_DATE = "s02_date_picker";
    private static final String TAG_TIME = "s02_time_picker";
    private static final int[] CHIP_IDS = {
        R.id.s02_chip_carbs, R.id.s02_chip_bolus, R.id.s02_chip_bg_check
    };
    private static final EntryType[] CHIP_TYPES = {
        EntryType.CARBS, EntryType.BOLUS, EntryType.BG_CHECK
    };

    private final SingleActionGuard saveGuard = new SingleActionGuard();
    private EntryFormViewModel viewModel;
    private Clock clock;
    private OnBackPressedCallback backGuard;

    private MaterialToolbar toolbar;
    private MaterialButton save;
    private LinearProgressIndicator progress;
    private MaterialButtonToggleGroup types;
    private TextView typeHelper;
    private TextInputLayout valueLayout;
    private TextInputEditText valueField;
    private TextInputLayout timeLayout;
    private TextInputEditText timeField;
    private View similarCard;
    private TextView similarText;
    private View messageCard;
    private ImageView messageIcon;
    private TextView messageText;
    private MaterialButton delete;
    private MaterialButton cancel;
    private MaterialButton now;
    private MaterialButton pick;
    private View controls;
    private BottomSheetDialog sheet;
    private boolean sheetCallbacksAdded;
    private OnBackPressedCallback dialogBackGuard;
    private AlertDialog discardDialog;
    private AlertDialog deleteDialog;

    private boolean programmatic;
    private int appliedValueVersion = -1;
    /** The value is focused (keyboard open) once, when a new sheet instance first shows it (D-146). */
    private boolean focusPending;
    private EntryFormUiState last;

    /** Creates the fragment (no-argument constructor required by the fragment framework). */
    public EntryFormFragment() {
        super(R.layout.fragment_entry_form);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep Material's automatic cancellation/predictive dismissal off; all user paths use the model guard.
        setCancelable(false);
        EntryFormFragmentArgs route = EntryFormFragmentArgs.fromBundle(requireArguments());
        final String entryId = route.getEntryId();
        final EntryType initialType = route.getInitialType() == null ? null : EntryType.valueOf(route.getInitialType());
        EntryMode mode = EntryMode.valueOf(route.getMode());
        if (mode == EntryMode.CREATE ? entryId != null || initialType == null : entryId == null || initialType != null) {
            throw new IllegalArgumentException("mode-appropriate entry argument required");
        }
        Dependencies deps = EntryHost.dependencies(this);
        EntryWriter writer = deps.get(EntryWriter.class);
        EntryRules rules = deps.get(EntryRules.class);
        StatusRepository status = deps.get(StatusRepository.class);
        clock = deps.get(Clock.class);
        final Clock c = clock;
        Executor main = ContextCompat.getMainExecutor(requireContext().getApplicationContext());
        viewModel = new ViewModelProvider(this, ViewModelFactories.of(EntryFormViewModel.class,
                handle -> new EntryFormViewModel(new EntryFormModel(entryId, initialType, writer, rules, c,
                        new SavedStateHandleStore(handle), main, status.current().online()), status, main)))
                .get(EntryFormViewModel.class);
        backGuard = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                requestBack();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, backGuard);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        sheetCallbacksAdded = false;
        sheet = new BottomSheetDialog(requireContext(), getTheme()) {
            @Override
            public void cancel() {
                // Outside tap and native cancellation must ask before DialogFragment/Navigation can pop.
                viewModel.model().onCloseRequested(EntryFormModel.ClosePath.OUTSIDE, false);
            }
        };
        dialogBackGuard = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                requestBack();
            }
        };
        return sheet;
    }

    @Override
    public void onStart() {
        super.onStart();
        // The keyboard opens with the sheet (D-146: typing the value is the primary input), see focusValueOnOpen.
        sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        View outside = sheet.findViewById(com.google.android.material.R.id.touch_outside);
        if (outside != null) {
            outside.setOnClickListener(v -> viewModel.model().onCloseRequested(
                    EntryFormModel.ClosePath.OUTSIDE, false));
        }
        BottomSheetBehavior<?> behavior = sheet.getBehavior();
        behavior.setHideable(false);
        behavior.setPeekHeight(0);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        styleSheetSurface();
        if (!sheetCallbacksAdded) {
            sheetCallbacksAdded = true;
            behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                @Override
                public void onStateChanged(@NonNull View bottomSheet, int state) {
                    if (state == BottomSheetBehavior.STATE_COLLAPSED) {
                        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                        viewModel.model().onCloseRequested(EntryFormModel.ClosePath.SWIPE, false);
                    }
                }

                @Override
                public void onSlide(@NonNull View bottomSheet, float offset) {
                }
            });
        }
        render(viewModel.model().state());
    }

    private void showKeyboard() {
        if (valueField == null || sheet == null || !valueField.isEnabled()) {
            return;
        }
        valueField.requestFocus();
        valueField.post(() -> {
            if (valueField != null && sheet != null) {
                new WindowInsetsControllerCompat(sheet.getWindow(), valueField).show(WindowInsetsCompat.Type.ime());
            }
        });
    }

    private void styleSheetSurface() {
        View container = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (container == null) {
            return;
        }
        container.post(() -> {
            if (container.getBackground() instanceof MaterialShapeDrawable) {
                // the view's own resources: the fragment may be detached (recreate, dismiss) before this runs
                ((MaterialShapeDrawable) container.getBackground()).setStroke(
                        container.getResources().getDisplayMetrics().density,
                        MaterialColors.getColor(container, com.google.android.material.R.attr.colorOutlineVariant));
            }
        });
    }

    private void requestBack() {
        View root = getView();
        WindowInsetsCompat insets = root == null ? null : ViewCompat.getRootWindowInsets(root);
        viewModel.model().onCloseRequested(EntryFormModel.ClosePath.BACK,
                insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        appliedValueVersion = -1;
        last = null;
        focusPending = savedInstanceState == null;

        toolbar = view.findViewById(R.id.s02_toolbar);
        save = view.findViewById(R.id.s02_save);
        progress = view.findViewById(R.id.s02_progress);
        types = view.findViewById(R.id.s02_types);
        typeHelper = view.findViewById(R.id.s02_type_helper);
        valueLayout = view.findViewById(R.id.s02_value_layout);
        valueField = view.findViewById(R.id.s02_value);
        timeLayout = view.findViewById(R.id.s02_time_layout);
        timeField = view.findViewById(R.id.s02_time);
        similarCard = view.findViewById(R.id.s02_similar_card);
        similarText = view.findViewById(R.id.s02_similar_text);
        messageCard = view.findViewById(R.id.s02_message_card);
        messageIcon = view.findViewById(R.id.s02_message_icon);
        messageText = view.findViewById(R.id.s02_message_text);
        delete = view.findViewById(R.id.s02_delete);
        cancel = view.findViewById(R.id.s02_cancel);
        now = view.findViewById(R.id.s02_now);
        pick = view.findViewById(R.id.s02_pick_time);
        now.setCheckable(true);
        pick.setCheckable(true);
        controls = view.findViewById(R.id.s02_controls);

        // The sheet window already resizes for the keyboard (SOFT_INPUT_ADJUST_RESIZE); a second IME padding on
        // the content doubled the keyboard height (BUG-35).

        toolbar.setNavigationOnClickListener(v -> viewModel.model().onCloseRequested());
        cancel.setOnClickListener(v -> viewModel.model().onCloseRequested());
        now.setOnClickListener(v -> {
            viewModel.model().onTimeNow();
            syncTimePills();
        });
        pick.setOnClickListener(v -> {
            openDatePicker();
            syncTimePills();   // a checkable button toggles itself; the model decides which pill is on
        });
        valueField.setOnClickListener(v -> showKeyboard());
        saveGuard.attach(save, () -> {
            viewModel.model().onSave();
            if (!viewModel.model().state().progress()) {
                saveGuard.complete();
            }
        });
        types.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!programmatic && isChecked) {
                viewModel.model().onTypeSelected(typeOf(checkedId));
                showKeyboard();   // the value of the new type is typed next
            }
        });
        valueField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // not needed
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // not needed
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!programmatic) {
                    viewModel.model().onValueChanged(s.toString());
                }
            }
        });
        valueField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.model().onSave();
                return true;
            }
            return false;
        });
        valueLayout.setTextInputAccessibilityDelegate(new TextInputLayout.AccessibilityDelegate(valueLayout) {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                EntryFormUiState s = last;
                if (s != null && s.type() != null && EntryCopy.spokenUnit(s.type()) != 0) {
                    // „Carbs, grams”: the suffix is read with the field (§7.10)
                    info.setHintText(getString(R.string.s02_value_a11y, getString(EntryCopy.typeName(s.type())),
                            getString(EntryCopy.spokenUnit(s.type()))));
                }
            }
        });
        timeField.setOnClickListener(v -> openDatePicker());
        timeLayout.setEndIconOnClickListener(v -> viewModel.model().onTimeNow());
        timeLayout.setTextInputAccessibilityDelegate(new TextInputLayout.AccessibilityDelegate(timeLayout) {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                // M3-19: „Time, {value}, button, double-tap to change”
                info.setClassName("android.widget.Button");
                info.setContentDescription(getString(R.string.s02_time_a11y, timeLabel(last)));
                info.setHintText(null);
                info.setEditable(false);
            }
        });
        delete.setOnClickListener(v -> viewModel.model().onDeleteRequested());
        reattachPickers();
        // Date picker title: same top offset as the time picker's (24 dp to the cap line).
        getChildFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                                      @NonNull View v, @Nullable Bundle savedInstanceState) {
                        if (f instanceof MaterialDatePicker) {
                            TextView title = v.findViewById(com.google.android.material.R.id.mtrl_picker_title_text);
                            if (title != null) {
                                title.setFirstBaselineToTopHeight(
                                        Math.round(34 * getResources().getDisplayMetrics().density));
                            }
                        }
                    }
                }, false);

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.events().observe(getViewLifecycleOwner(), event -> {
            EntryFormModel.Event e = event == null ? null : event.take();
            if (e != null) {
                handle(e);
            }
        });
    }

    private void render(EntryFormUiState s) {
        last = s;
        toolbar.setTitle(s.mode() == EntryMode.EDIT ? R.string.s02_title_edit : R.string.s02_title_new);
        save.setEnabled(s.saveEnabled());
        if (!s.progress()) {
            saveGuard.complete();
        }
        progress.setVisibility(s.progress() ? View.VISIBLE : View.INVISIBLE);
        if (sheet != null) {
            sheet.getBehavior().setHideable(false);
            sheet.getBehavior().setDraggable(viewModel.model().dismissalEnabled());
            if (sheet.isShowing()) {
                dialogBackGuard.remove();
                sheet.getOnBackPressedDispatcher().addCallback(this, dialogBackGuard);
            }
        }
        cancel.setEnabled(viewModel.model().dismissalEnabled());
        now.setEnabled(s.fieldsEnabled());
        pick.setEnabled(s.fieldsEnabled());
        syncTimePills();
        // The second pill carries the picked time ("Today 13:45") instead of a separate read-only field.
        pick.setText(s.time() != null ? timeLabel(s) : getString(R.string.s02_pick_time));
        // The picked time is spoken as "Time, Today 13:45, button" (the separate time field is hidden).
        pick.setContentDescription(s.time() != null ? getString(R.string.s02_time_a11y, timeLabel(s)) : null);
        types.setVisibility(s.valueVisible() ? View.VISIBLE : View.GONE);
        controls.setVisibility(s.valueVisible() ? View.VISIBLE : View.GONE);

        programmatic = true;
        try {
            for (int i = 0; i < CHIP_IDS.length; i++) {
                MaterialButton chip = types.findViewById(CHIP_IDS[i]);
                boolean selected = CHIP_TYPES[i] == s.type();
                if (chip.isChecked() != selected) {
                    chip.setChecked(selected);
                }
                chip.setEnabled(s.fieldsEnabled() && (!s.typeLocked() || selected));
            }
        } finally {
            programmatic = false;
        }
        typeHelper.setVisibility(s.helperChooseType() ? View.VISIBLE : View.GONE);

        renderValue(s);

        timeField.setText(timeLabel(s));
        timeField.setEnabled(s.fieldsEnabled());
        timeLayout.setEnabled(s.fieldsEnabled());
        timeLayout.setVisibility(s.timeError() != null ? View.VISIBLE : View.GONE);
        timeLayout.setError(s.timeError() == null ? null : getString(EntryCopy.validation(s.timeError())));

        Similar similar = s.similar();
        similarCard.setVisibility(similar == null ? View.GONE : View.VISIBLE);
        if (similar != null) {
            similarText.setText(similarText(similar));
        }

        int messageRes = EntryCopy.message(s.message());
        messageCard.setVisibility(messageRes == 0 ? View.GONE : View.VISIBLE);
        if (messageRes != 0) {
            messageText.setText(messageRes);
            messageIcon.setImageResource(EntryCopy.isOffline(s.message()) ? R.drawable.ic_s02_info
                    : R.drawable.ic_s02_error);
        }

        delete.setVisibility(s.deleteVisible() ? View.VISIBLE : View.GONE);
        delete.setEnabled(s.deleteEnabled());
        View actions = (View) save.getParent();
        actions.post(() -> {
            if (save == null || cancel == null || delete == null) {
                return;
            }
            int required = 0;
            for (MaterialButton button : new MaterialButton[] {delete, cancel, save}) {
                if (button.getVisibility() != View.GONE) {
                    required += Math.max(button.getMinimumWidth(), (int) Math.ceil(
                            button.getPaint().measureText(button.getText().toString()))
                            + button.getPaddingLeft() + button.getPaddingRight());
                }
            }
            ((android.widget.LinearLayout) actions).setOrientation(required > actions.getWidth()
                    ? android.widget.LinearLayout.VERTICAL : android.widget.LinearLayout.HORIZONTAL);
        });

        backGuard.setEnabled(true);
        renderDiscardDialog(s);
        renderDeleteDialog(s.deleteDialog());
    }

    private void syncTimePills() {
        EntryFormUiState s = last;
        if (s == null || now == null || pick == null) {
            return;
        }
        now.setChecked(s.time() == null);
        pick.setChecked(s.time() != null);
    }

    private void renderValue(EntryFormUiState s) {
        valueLayout.setVisibility(s.valueVisible() ? View.VISIBLE : View.GONE);
        if (appliedValueVersion != s.valueFieldVersion()) {
            appliedValueVersion = s.valueFieldVersion();
            programmatic = true;
            try {
                valueField.setText(s.valueText());
                Editable e = valueField.getText();
                valueField.setSelection(e == null ? 0 : e.length());
            } finally {
                programmatic = false;
            }
        }
        if (s.type() != null && s.valueVisible()) {
            valueLayout.setHint(getString(EntryCopy.typeName(s.type())));
            int suffix = EntryCopy.suffix(s.type());
            valueLayout.setSuffixText(suffix == 0 ? null : getString(suffix));
            valueLayout.setHelperText(getString(EntryCopy.range(s.type())));
            int inputType = s.type() == EntryType.BOLUS
                    ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_NUMBER;
            if (valueField.getInputType() != inputType) {
                valueField.setInputType(inputType);
            }
        }
        valueField.setEnabled(s.fieldsEnabled());
        valueLayout.setError(s.valueError() == null ? null : getString(EntryCopy.validation(s.valueError())));
        focusValueOnOpen(s);
    }

    /**
     * D-146: a freshly opened sheet (create, or edit once loaded) focuses the value and opens the keyboard; an edited
     * value is selected so typing replaces it. Not repeated after a configuration change or process restore.
     */
    private void focusValueOnOpen(EntryFormUiState s) {
        if (!focusPending || !s.valueVisible() || !s.fieldsEnabled() || s.type() == null) {
            return;
        }
        focusPending = false;
        valueField.requestFocus();
        if (s.mode() == EntryMode.EDIT) {
            valueField.selectAll();
        }
        showKeyboard();
    }

    private String timeLabel(EntryFormUiState s) {
        if (s == null || s.time() == null) {
            return getString(R.string.s02_time_now);
        }
        return Formatters.timeLabel(requireContext(), s.time(), clock.now(), clock.zone());
    }

    private String similarText(Similar similar) {
        Context c = requireContext();
        String value = Formatters.entryValue(c, similar.entry());
        if (value == null || value.isEmpty()) {
            value = getString(EntryCopy.typeName(similar.entry().type()));
        }
        String source = similar.source().kind() == SourceKind.UPLOADER
                ? similar.source().uploaderName() : getString(EntryCopy.source(similar.source().kind()));
        if (similar.direction() == Direction.SAME_TIME) {
            return getString(EntryCopy.similar(Direction.SAME_TIME), value, source);
        }
        return getString(EntryCopy.similar(similar.direction()), (int) similar.minutes(), value, source);
    }

    // ------------------------------------------------------------------ pickers (C-S02-09)

    private void openDatePicker() {
        EntryFormUiState s = last;
        FragmentManager fm = getChildFragmentManager();
        if (s == null || !s.fieldsEnabled() || fm.findFragmentByTag(TAG_DATE) != null
                || fm.findFragmentByTag(TAG_TIME) != null) {
            return;
        }
        Instant t = s.time() != null ? s.time() : clock.now();
        long selection = t.atZone(clock.zone()).toLocalDate().toEpochDay() * Duration.ofDays(1).toMillis();
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTheme(app.lightscout.ui.core.R.style.ThemeOverlay_LightScout_MaterialCalendar)
                .setTitleText(R.string.s02_picker_date_title)
                .setSelection(selection)
                .build();
        listenDate(picker);
        picker.show(fm, TAG_DATE);
    }

    private void openTimePicker() {
        int[] hm = viewModel.model().pickerClock();
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTheme(app.lightscout.ui.core.R.style.ThemeOverlay_LightScout_MaterialTimePicker)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .setTimeFormat(DateFormat.is24HourFormat(requireContext()) ? TimeFormat.CLOCK_24H
                        : TimeFormat.CLOCK_12H)
                .setHour(hm[0])
                .setMinute(hm[1])
                .setTitleText(R.string.s02_picker_time_title)
                .build();
        listenTime(picker);
        picker.show(getChildFragmentManager(), TAG_TIME);
    }

    @SuppressWarnings("unchecked")
    private void reattachPickers() {
        FragmentManager fm = getChildFragmentManager();
        Fragment date = fm.findFragmentByTag(TAG_DATE);
        if (date instanceof MaterialDatePicker) {
            listenDate((MaterialDatePicker<Long>) date);
        }
        Fragment time = fm.findFragmentByTag(TAG_TIME);
        if (time instanceof MaterialTimePicker) {
            listenTime((MaterialTimePicker) time);
        }
    }

    private void listenDate(MaterialDatePicker<Long> picker) {
        picker.clearOnPositiveButtonClickListeners();
        picker.clearOnNegativeButtonClickListeners();
        picker.clearOnCancelListeners();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null) {
                viewModel.model().onPickerCancelled();
                return;
            }
            viewModel.model().onDatePicked(selection);
            openTimePicker();
        });
        picker.addOnNegativeButtonClickListener(v -> viewModel.model().onPickerCancelled());
        picker.addOnCancelListener(d -> viewModel.model().onPickerCancelled());
    }

    private void listenTime(MaterialTimePicker picker) {
        picker.clearOnPositiveButtonClickListeners();
        picker.clearOnNegativeButtonClickListeners();
        picker.clearOnCancelListeners();
        picker.addOnPositiveButtonClickListener(v -> viewModel.model().onTimePicked(picker.getHour(),
                picker.getMinute()));
        picker.addOnNegativeButtonClickListener(v -> viewModel.model().onPickerCancelled());
        picker.addOnCancelListener(d -> viewModel.model().onPickerCancelled());
    }

    // ------------------------------------------------------------------ dialogs (C-S02-13, C-S02-14)

    private void renderDiscardDialog(EntryFormUiState s) {
        if (s.discardDialog() && discardDialog == null) {
            discardDialog = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(s.mode() == EntryMode.EDIT ? R.string.s02_discard_title_edit
                            : R.string.s02_discard_title_create)
                    .setNegativeButton(R.string.s02_discard_keep_editing,
                            (d, w) -> viewModel.model().onDiscardCancelled())
                    .setPositiveButton(R.string.s02_discard_confirm, (d, w) -> viewModel.model().onDiscardConfirmed())
                    .setOnCancelListener(d -> viewModel.model().onDiscardCancelled())
                    .create();
            discardDialog.setOnDismissListener(d -> discardDialog = null);
            discardDialog.show();
        } else if (!s.discardDialog() && discardDialog != null) {
            AlertDialog d = discardDialog;
            discardDialog = null;
            d.dismiss();
        }
    }

    private void renderDeleteDialog(boolean show) {
        if (show && deleteDialog == null) {
            deleteDialog = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.s02_delete_dialog_title)
                    .setMessage(R.string.s02_delete_dialog_body)
                    .setNegativeButton(R.string.s02_delete_dialog_cancel,
                            (d, w) -> viewModel.model().onDeleteCancelled())
                    .setPositiveButton(R.string.s02_delete_dialog_confirm,
                            (d, w) -> viewModel.model().onDeleteConfirmed())
                    .setOnCancelListener(d -> viewModel.model().onDeleteCancelled())
                    .create();
            deleteDialog.setOnDismissListener(d -> deleteDialog = null);
            deleteDialog.show();
        } else if (!show && deleteDialog != null) {
            AlertDialog d = deleteDialog;
            deleteDialog = null;
            d.dismiss();
        }
    }

    // ------------------------------------------------------------------ events

    private void handle(EntryFormModel.Event e) {
        switch (e.kind()) {
            case FINISH:
                backGuard.setEnabled(false);
                EntryHost.navigator(this).finishWithResult(e.result());
                break;
            case HIDE_IME:
                new WindowInsetsControllerCompat(requireDialog().getWindow(), requireView())
                        .hide(WindowInsetsCompat.Type.ime());
                break;
            case CLOSE:
            default:
                backGuard.setEnabled(false);
                NavHostFragment.findNavController(this).popBackStack();
                break;
        }
    }

    private static EntryType typeOf(int chipId) {
        for (int i = 0; i < CHIP_IDS.length; i++) {
            if (CHIP_IDS[i] == chipId) {
                return CHIP_TYPES[i];
            }
        }
        return null;
    }

    @Override
    public void onDestroyView() {
        dismissQuietly(discardDialog);   // the model keeps the flag, so the new view shows it again
        dismissQuietly(deleteDialog);
        discardDialog = null;
        deleteDialog = null;
        toolbar = null;
        save = null;
        progress = null;
        types = null;
        typeHelper = null;
        valueLayout = null;
        valueField = null;
        timeLayout = null;
        timeField = null;
        similarCard = null;
        similarText = null;
        messageCard = null;
        messageIcon = null;
        messageText = null;
        delete = null;
        super.onDestroyView();
    }

    private static void dismissQuietly(AlertDialog d) {
        if (d != null) {
            d.setOnDismissListener(null);
            d.setOnCancelListener(null);
            d.dismiss();
        }
    }
}
