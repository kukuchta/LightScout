package app.lightscout.feature.onboarding.readiness;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import app.lightscout.feature.onboarding.A11y;
import app.lightscout.feature.onboarding.OnboardingHost;
import app.lightscout.feature.onboarding.R;
import app.lightscout.feature.onboarding.ViewModelFactories;
import app.lightscout.feature.onboarding.readiness.ReadinessUiState.Action;
import app.lightscout.feature.onboarding.readiness.ReadinessUiState.Item;
import app.lightscout.feature.onboarding.readiness.ReadinessUiState.Status;
import app.lightscout.platform.ManufacturerDetector;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.platform.ReadinessItemId;
import app.lightscout.platform.SystemSettingsIntents;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.EdgeToEdgeInsets;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.ui.core.SingleActionGuard;
import app.lightscout.usecases.OnboardingState;
import app.lightscout.usecases.StatusRepository;

import java.util.Optional;

/**
 * S-07 Alarm readiness (CP-14 §7.4; C-S07-01, -02, -04, -05, -07; A-S07-1…4; items 1–7). The logic lives in
 * {@link ReadinessModel}; this fragment renders it, re-checks on every resume, launches the IF-55 pages through
 * CMP-28 {@link SystemSettingsIntents} (fallback {@code appDetails()}, then the inline M3-30 error), asks for
 * {@code POST_NOTIFICATIONS} on API 33+ (P1: only here), opens S-08 with the detected manufacturer and, on
 * „Done”, leaves through IF-29 {@code finishOnboarding()}.
 */
public final class ReadinessFragment extends Fragment {

    private final SingleActionGuard doneGuard = new SingleActionGuard();
    private ReadinessViewModel viewModel;
    private SystemSettingsIntents intents;
    private ActivityResultLauncher<String> notificationPermission;
    private AlertDialog missingDialog;
    private LinearLayout itemsList;
    private TextView allSet;
    private MaterialButton doneButton;

    /** Creates the fragment (no-argument constructor required by the fragment framework). */
    public ReadinessFragment() {
        super(R.layout.fragment_readiness);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        final ReadinessMode mode = args == null ? ReadinessMode.ONBOARDING
                : ReadinessFragmentArgs.fromBundle(args).getMode();
        Dependencies deps = OnboardingHost.dependencies(this);
        intents = deps.get(SystemSettingsIntents.class);
        ReadinessChecker checker = deps.get(ReadinessChecker.class);
        NotificationChannels channels = deps.get(NotificationChannels.class);
        StatusRepository status = deps.get(StatusRepository.class);
        OnboardingState onboarding = deps.get(OnboardingState.class);
        ManufacturerDetector detector = deps.get(ManufacturerDetector.class);
        boolean runtimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        viewModel = new ViewModelProvider(this, ViewModelFactories.of(ReadinessViewModel.class,
                handle -> new ReadinessViewModel(new ReadinessModel(mode, checker, channels, status, onboarding,
                        detector, runtimePermission, deps.ioExecutor(),
                        ContextCompat.getMainExecutor(requireContext().getApplicationContext())))))
                .get(ReadinessViewModel.class);
        notificationPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> viewModel.model().onNotificationPermissionResult(notificationRationale()));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MaterialToolbar toolbar = view.findViewById(R.id.s07_toolbar);
        // Q-74 pattern: NavigationUI sets the Up icon and its localised „Navigate up” description; Up → S-06
        // (onboarding) or the caller (check mode) — the previous back-stack entry in both cases.
        NavigationUI.setupWithNavController(toolbar, NavHostFragment.findNavController(this));
        toolbar.setTitle(R.string.s07_title);
        A11y.markTitleAsHeading(toolbar);
        EdgeToEdgeInsets.applyStatusBarInset(view.findViewById(R.id.s07_app_bar));

        itemsList = view.findViewById(R.id.s07_items);
        allSet = view.findViewById(R.id.s07_all_set);
        doneButton = view.findViewById(R.id.s07_done);
        EdgeToEdgeInsets.applyStickyBottomInset(doneButton);
        doneGuard.attach(doneButton, () -> viewModel.model().onDone());

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.commands().observe(getViewLifecycleOwner(), event -> {
            ReadinessModel.Command c = event == null ? null : event.take();
            if (c != null) {
                handle(c);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // WF-08: statuses are re-checked every time the screen resumes (return from system settings).
        viewModel.model().onResume(notificationRationale());
    }

    @Override
    public void onDestroyView() {
        if (missingDialog != null) {
            AlertDialog d = missingDialog;
            missingDialog = null;
            d.setOnDismissListener(null);
            d.dismiss();
        }
        itemsList = null;
        allSet = null;
        doneButton = null;
        super.onDestroyView();
    }

    private boolean notificationRationale() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void render(ReadinessUiState s) {
        if (itemsList == null) {
            return;
        }
        allSet.setVisibility(s.allSet() ? View.VISIBLE : View.GONE);
        doneButton.setVisibility(s.doneVisible() ? View.VISIBLE : View.GONE);
        doneButton.setEnabled(s.doneEnabled());
        if (s.doneEnabled()) {
            doneGuard.complete();
        }
        renderItems(s);
        renderMissingDialog(s.missingDialogVisible());
    }

    private void renderItems(ReadinessUiState s) {
        itemsList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(itemsList.getContext());
        int done = MaterialColors.getColor(itemsList, androidx.appcompat.R.attr.colorPrimary);
        int attention = MaterialColors.getColor(itemsList, androidx.appcompat.R.attr.colorError);
        int recommended = MaterialColors.getColor(itemsList,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        for (Item item : s.items()) {
            View row = inflater.inflate(R.layout.item_s07_readiness, itemsList, false);
            ImageView icon = row.findViewById(R.id.s07_item_icon);
            TextView headline = row.findViewById(R.id.s07_item_headline);
            TextView supporting = row.findViewById(R.id.s07_item_supporting);
            TextView error = row.findViewById(R.id.s07_item_error);
            MaterialButton action = row.findViewById(R.id.s07_item_action);
            View text = row.findViewById(R.id.s07_item_text);

            String headlineText = getString(ReadinessCopy.headline(item.id()));
            headline.setText(headlineText);
            String supportingText;
            String statusWord;
            switch (item.status()) {
                case DONE:
                    icon.setImageResource(R.drawable.ic_s07_status_done);
                    icon.setImageTintList(ColorStateList.valueOf(done));
                    supportingText = getString(R.string.s07_status_done);
                    statusWord = getString(R.string.s07_status_done);
                    break;
                case PERMANENTLY_DENIED:
                    icon.setImageResource(R.drawable.ic_s07_status_attention);
                    icon.setImageTintList(ColorStateList.valueOf(attention));
                    supportingText = getString(R.string.s07_permanently_denied);
                    statusWord = getString(R.string.s07_status_needs_attention);
                    break;
                case RECOMMENDED:
                    icon.setImageResource(R.drawable.ic_s07_status_recommended);
                    icon.setImageTintList(ColorStateList.valueOf(recommended));
                    supportingText = getString(ReadinessCopy.rationale(item.id()));
                    statusWord = null;
                    break;
                case NEEDS_ATTENTION:
                default:
                    icon.setImageResource(R.drawable.ic_s07_status_attention);
                    icon.setImageTintList(ColorStateList.valueOf(attention));
                    supportingText = getString(ReadinessCopy.rationale(item.id()));
                    statusWord = getString(R.string.s07_status_needs_attention);
                    break;
            }
            supporting.setText(supportingText);
            // Q-81: the status icon carries its status word (shape + word, never colour alone); the text block
            // already speaks it, so the icon itself stays out of the focus order (no double reading).
            icon.setContentDescription(statusWord);
            if (statusWord == null) {
                // Item 6 (recommended, no status word): TalkBack merges headline + rationale of the focus unit.
                text.setContentDescription(null);
                headline.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                supporting.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            } else {
                // Q-80: status read first, „{headline}, {status}, {supporting}” from s07_item_a11y; a done item
                // speaks its rationale as the supporting part (the visible supporting text is the word „Done”).
                String spokenSupporting = item.status() == Status.DONE
                        ? getString(ReadinessCopy.rationale(item.id())) : supportingText;
                text.setContentDescription(getString(R.string.s07_item_a11y, headlineText, statusWord,
                        spokenSupporting));
            }

            error.setVisibility(item.error() ? View.VISIBLE : View.GONE);
            if (item.action() == Action.NONE) {
                action.setVisibility(View.GONE);
            } else {
                action.setText(ReadinessCopy.action(item.action()));
                action.setVisibility(View.VISIBLE);
                final ReadinessItemId id = item.id();
                action.setOnClickListener(v -> viewModel.model().onItemAction(id));
            }
            itemsList.addView(row);
        }
        A11y.markAsList(itemsList);
    }

    private void renderMissingDialog(boolean show) {
        if (show && missingDialog == null) {
            missingDialog = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.s07_missing_dialog_title)
                    .setMessage(R.string.s07_missing_dialog_body)
                    .setNegativeButton(R.string.s07_missing_dialog_back,
                            (d, w) -> viewModel.model().onMissingDialogGoBack())
                    .setPositiveButton(R.string.s07_missing_dialog_continue,
                            (d, w) -> viewModel.model().onMissingDialogContinue())
                    .setOnCancelListener(d -> viewModel.model().onMissingDialogGoBack())
                    .create();
            missingDialog.setOnDismissListener(d -> missingDialog = null);
            missingDialog.show();
        } else if (!show && missingDialog != null) {
            AlertDialog d = missingDialog;
            missingDialog = null;
            d.dismiss();
        }
    }

    private void handle(ReadinessModel.Command c) {
        switch (c.kind()) {
            case REQUEST_NOTIFICATION_PERMISSION:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                }
                break;
            case OPEN_ITEM_SETTINGS:
                launch(c.item(), intents.forItem(c.item()));
                break;
            case OPEN_APP_NOTIFICATION_SETTINGS:
                launch(c.item(), Optional.of(intents.appNotificationSettings()));
                break;
            case OPEN_GUIDE:
                NavController nav = NavHostFragment.findNavController(this);
                NavDestination current = nav.getCurrentDestination();
                if (current != null && current.getId() == R.id.readinessFragment) {
                    nav.navigate(ReadinessFragmentDirections
                            .actionReadinessFragmentToManufacturerGuideFragment(c.manufacturer()));
                }
                break;
            case FINISH_ONBOARDING:
            default:
                OnboardingHost.navigator(this).finishOnboarding();
                break;
        }
    }

    /**
     * IF-55 launch with the R-74 fallback chain: the item page, then {@code appDetails()}, then the inline error.
     */
    private void launch(ReadinessItemId id, Optional<Intent> intent) {
        if (intent.isPresent() && start(intent.get())) {
            return;
        }
        if (start(intents.appDetails())) {
            return;
        }
        viewModel.model().onLaunchFailed(id);
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
