package app.lightscout.feature.onboarding.disclaimer;

import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;

import app.lightscout.feature.onboarding.A11y;
import app.lightscout.feature.onboarding.OnboardingHost;
import app.lightscout.feature.onboarding.R;
import app.lightscout.feature.onboarding.SavedStateHandleStore;
import app.lightscout.feature.onboarding.ViewModelFactories;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.EdgeToEdgeInsets;
import app.lightscout.ui.core.SingleActionGuard;
import app.lightscout.usecases.OnboardingState;

/**
 * S-05 Disclaimer (CP-14 §7.2; C-S05-01, -03, -04, -05; A-S05-0…2). First-launch start destination until the
 * acceptance is stored (DS:D-058). No navigation icon; system back exits the app because S-05 is the bottom of the
 * stack, and nothing is stored (A-S05-2) — no back interception here.
 */
public final class DisclaimerFragment extends Fragment {

    private static final int[] BULLET_IDS = {
        R.id.s05_bullet_1, R.id.s05_bullet_2, R.id.s05_bullet_3, R.id.s05_bullet_4, R.id.s05_bullet_5,
    };
    private static final int BULLET_GAP_DP = 8;

    private final SingleActionGuard continueGuard = new SingleActionGuard();
    private DisclaimerViewModel viewModel;

    /** Creates the fragment (no-argument constructor required by the fragment framework). */
    public DisclaimerFragment() {
        super(R.layout.fragment_disclaimer);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Dependencies deps = OnboardingHost.dependencies(this);
        OnboardingState onboarding = deps.get(OnboardingState.class);
        viewModel = new ViewModelProvider(this, ViewModelFactories.of(DisclaimerViewModel.class,
                handle -> new DisclaimerViewModel(new DisclaimerModel(onboarding, new SavedStateHandleStore(handle),
                        deps.ioExecutor(), ContextCompat.getMainExecutor(requireContext().getApplicationContext())))))
                .get(DisclaimerViewModel.class);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MaterialToolbar toolbar = view.findViewById(R.id.s05_toolbar);
        A11y.markTitleAsHeading(toolbar);
        EdgeToEdgeInsets.applyStatusBarInset(view.findViewById(R.id.s05_app_bar));
        EdgeToEdgeInsets.applyStickyBottomInset(view.findViewById(R.id.s05_sticky));

        int gap = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, BULLET_GAP_DP,
                getResources().getDisplayMetrics()));
        for (int id : BULLET_IDS) {
            TextView bullet = view.findViewById(id);
            SpannableString text = new SpannableString(bullet.getText());
            text.setSpan(new BulletSpan(gap), 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            bullet.setText(text);
        }
        A11y.markAsList(view.findViewById(R.id.s05_bullets));

        MaterialCheckBox acknowledge = view.findViewById(R.id.s05_acknowledge);
        TextView helper = view.findViewById(R.id.s05_helper);
        MaterialButton continueButton = view.findViewById(R.id.s05_continue);

        acknowledge.setOnCheckedChangeListener((b, checked) -> viewModel.model().setAcknowledged(checked));
        continueGuard.attach(continueButton, () -> viewModel.model().onContinue());

        viewModel.state().observe(getViewLifecycleOwner(), s -> {
            if (acknowledge.isChecked() != s.acknowledged()) {
                acknowledge.setChecked(s.acknowledged());
            }
            continueButton.setEnabled(s.continueEnabled());
            // M3-26: the helper is linked to the disabled button („Continue, disabled. Tick the box to continue”)
            continueButton.setContentDescription(s.helperVisible()
                    ? getString(R.string.s05_continue_disabled_a11y) : null);
            helper.setVisibility(s.helperVisible() ? View.VISIBLE : View.GONE);
            if (s.continueEnabled()) {
                continueGuard.complete();
            }
        });
        viewModel.accepted().observe(getViewLifecycleOwner(), event -> {
            if (event != null && event.take() != null) {
                openConnection();
            }
        });
    }

    private void openConnection() {
        NavController nav = NavHostFragment.findNavController(this);
        NavDestination current = nav.getCurrentDestination();
        if (current != null && current.getId() == R.id.disclaimerFragment) {
            nav.navigate(DisclaimerFragmentDirections.actionDisclaimerFragmentToConnectionFragment());
        }
        continueGuard.complete();
    }
}
