package app.lightscout.feature.onboarding.guide;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import app.lightscout.feature.onboarding.A11y;
import app.lightscout.feature.onboarding.OnboardingHost;
import app.lightscout.feature.onboarding.R;
import app.lightscout.feature.onboarding.SavedStateHandleStore;
import app.lightscout.feature.onboarding.ViewModelFactories;
import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.OemSettingId;
import app.lightscout.platform.SystemSettingsIntents;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.EdgeToEdgeInsets;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * S-08 Manufacturer guide (CP-14 §7.5; C-S08-01…06; A-S08-1…3). The logic lives in {@link GuideModel}; this fragment
 * renders the selector, the bundled steps ({@link ResourceGuideContent}), the resolvable-only IF-56 „Open …” buttons
 * and the IF-57 link, all through CMP-28 {@link SystemSettingsIntents}. A page that does not open shows the
 * {@code s08_error_open_setting} Snackbar. No WebView, no dynamic content (SR-31).
 */
public final class ManufacturerGuideFragment extends Fragment {

    private GuideViewModel viewModel;
    private SystemSettingsIntents intents;
    private MaterialAutoCompleteTextView selector;
    private LinearLayout stepsList;
    private TextView noExtraSteps;
    private List<String> labels;

    /** Creates the fragment (no-argument constructor required by the fragment framework). */
    public ManufacturerGuideFragment() {
        super(R.layout.fragment_manufacturer_guide);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        final Manufacturer detected = args == null ? Manufacturer.OTHER
                : ManufacturerGuideFragmentArgs.fromBundle(args).getManufacturer();
        Dependencies deps = OnboardingHost.dependencies(this);
        intents = deps.get(SystemSettingsIntents.class);
        final GuideContent content = new ResourceGuideContent(getResources());
        final SystemSettingsIntents oem = intents;
        viewModel = new ViewModelProvider(this, ViewModelFactories.of(GuideViewModel.class,
                handle -> new GuideViewModel(new GuideModel(detected, new SavedStateHandleStore(handle), content,
                        id -> oem.oem(id).isPresent()))))
                .get(GuideViewModel.class);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MaterialToolbar toolbar = view.findViewById(R.id.s08_toolbar);
        // Q-74 pattern: NavigationUI sets the Up icon and its localised „Navigate up” description; Up → S-07.
        NavigationUI.setupWithNavController(toolbar, NavHostFragment.findNavController(this));
        toolbar.setTitle(R.string.s08_title);
        A11y.markTitleAsHeading(toolbar);
        EdgeToEdgeInsets.applyStatusBarInset(view.findViewById(R.id.s08_app_bar));
        EdgeToEdgeInsets.applyScrollBottomInset(view.findViewById(R.id.s08_scroll), null);

        selector = view.findViewById(R.id.s08_selector);
        stepsList = view.findViewById(R.id.s08_steps);
        noExtraSteps = view.findViewById(R.id.s08_no_extra_steps);
        MaterialButton moreDetails = view.findViewById(R.id.s08_more_details);

        labels = new ArrayList<>();
        for (Manufacturer m : Manufacturer.values()) {
            labels.add(getString(GuideCopy.label(m)));
        }
        // IR-97: non-filtering adapter, and the field text is not restored by the view (render sets the label from
        // the model with setText(label, false)), so the dropdown always lists all six options.
        selector.setSaveEnabled(false);
        selector.setAdapter(new AllOptionsAdapter(requireContext(), R.layout.item_s08_dropdown, labels));
        selector.setOnItemClickListener((parent, v, position, id) -> {
            // Map by label, not by position: the dropdown adapter may be filtered.
            int index = labels.indexOf(String.valueOf(parent.getItemAtPosition(position)));
            if (index >= 0) {
                viewModel.model().select(Manufacturer.values()[index]);
            }
        });
        // Accessibility: heading on the manufacturer name (CP-14 §7.5).
        ViewCompat.setAccessibilityHeading(selector, true);
        moreDetails.setOnClickListener(v -> viewModel.model().onMoreDetails());

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.commands().observe(getViewLifecycleOwner(), event -> {
            GuideModel.Command c = event == null ? null : event.take();
            if (c != null) {
                handle(c);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        viewModel.model().refresh();
    }

    @Override
    public void onDestroyView() {
        selector = null;
        stepsList = null;
        noExtraSteps = null;
        super.onDestroyView();
    }

    private void render(GuideUiState s) {
        if (stepsList == null) {
            return;
        }
        String label = labels.get(s.manufacturer().ordinal());
        if (!label.contentEquals(selector.getText())) {
            selector.setText(label, false);
        }
        noExtraSteps.setVisibility(s.noExtraSteps() ? View.VISIBLE : View.GONE);
        stepsList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(stepsList.getContext());
        NumberFormat numbers = NumberFormat.getIntegerInstance();
        for (GuideUiState.Step step : s.steps()) {
            View row = inflater.inflate(R.layout.item_s08_step, stepsList, false);
            TextView number = row.findViewById(R.id.s08_step_number);
            TextView text = row.findViewById(R.id.s08_step_text);
            View block = row.findViewById(R.id.s08_step_text_block);
            MaterialButton open = row.findViewById(R.id.s08_step_open);
            number.setText(numbers.format(step.number()));
            text.setText(step.text());
            block.setContentDescription(getString(R.string.s08_step_a11y, step.number(), step.total(), step.text()));
            if (step.open() == null) {
                open.setVisibility(View.GONE);
            } else {
                open.setText(getString(R.string.s08_step_open, getString(GuideCopy.settingName(step.open()))));
                open.setVisibility(View.VISIBLE);
                final int n = step.number();
                open.setOnClickListener(v -> viewModel.model().onOpenStep(n));
            }
            stepsList.addView(row);
        }
        A11y.markAsList(stepsList);
    }

    private void handle(GuideModel.Command c) {
        switch (c.kind()) {
            case OPEN_SETTING:
                openSetting(c.setting());
                break;
            case OPEN_MORE_DETAILS:
                // IF-57: external browser; with no browser installed nothing happens (no copy for that case).
                start(intents.dontKillMyApp(c.manufacturer()));
                break;
            case SHOW_OPEN_FAILED:
            default:
                View root = getView();
                if (root != null) {
                    Snackbar.make(root, R.string.s08_error_open_setting, Snackbar.LENGTH_LONG).show();
                }
                break;
        }
    }

    private void openSetting(OemSettingId id) {
        Optional<Intent> intent = intents.oem(id);
        if (intent.isEmpty() || !start(intent.get())) {
            viewModel.model().onOpenFailed();
        }
    }

    private boolean start(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
