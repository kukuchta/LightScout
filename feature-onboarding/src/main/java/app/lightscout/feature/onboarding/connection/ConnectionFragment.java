package app.lightscout.feature.onboarding.connection;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import app.lightscout.feature.onboarding.OnboardingHost;
import app.lightscout.feature.onboarding.R;
import app.lightscout.feature.onboarding.SavedStateHandleStore;
import app.lightscout.feature.onboarding.ViewModelFactories;
import app.lightscout.feature.onboarding.connection.ConnectionUiState.TokenMode;
import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.EdgeToEdgeInsets;
import app.lightscout.ui.core.SingleActionGuard;
import app.lightscout.ui.core.UiMessage;
import app.lightscout.usecases.CheckFailure;
import app.lightscout.usecases.CheckResult;
import app.lightscout.usecases.CheckRow;
import app.lightscout.usecases.ConnectionService;
import app.lightscout.usecases.StatusRepository;

import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * S-06 Nightscout connection (CP-14 §7.3; C-S06-01…08; A-S06-1…5). Renders {@link ConnectionUiState} and forwards
 * input to {@link ConnectionModel}. The ViewModel is scoped to {@code onboarding_graph} (M3-27).
 *
 * <p>Token field (SR-02, SR-28): password input, no suggestions, no personalised learning, no autofill,
 * {@code saveEnabled=false}; its text is read as {@code char[]} only and overwritten in {@link #onDestroyView()}.
 * The token is never in a Bundle, the {@code SavedStateHandle} or a navigation argument.
 *
 * <p>Back (06 §11 F17): the {@link OnBackPressedCallback} is enabled only while edit mode has unsaved changes, so
 * predictive back stays native otherwise; then C-S06-08 „Discard changes?” is shown.
 */
public final class ConnectionFragment extends Fragment {

    private final SingleActionGuard primaryGuard = new SingleActionGuard();
    private ConnectionViewModel viewModel;
    private ConnectionMode mode;
    private OnBackPressedCallback backGuard;

    private TextInputLayout urlLayout;
    private TextInputEditText urlField;
    private TextInputLayout tokenLayout;
    private TextInputEditText tokenField;
    private View checkingRow;
    private LinearLayout resultsList;
    private TextView helper;
    private MaterialButton primary;
    private AlertDialog discardDialog;

    private boolean programmatic;
    private int appliedUrlVersion = -1;
    private int appliedTokenVersion = -1;
    private Boolean appliedTokenVisible;
    private List<CheckResult> appliedResults;
    private TokenMode lastTokenMode = TokenMode.EMPTY;

    /** Creates the fragment (no-argument constructor required by the fragment framework). */
    public ConnectionFragment() {
        super(R.layout.fragment_connection);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        mode = args == null ? ConnectionMode.ONBOARDING : ConnectionFragmentArgs.fromBundle(args).getMode();
        Dependencies deps = OnboardingHost.dependencies(this);
        ConnectionService service = deps.get(ConnectionService.class);
        StatusRepository status = deps.get(StatusRepository.class);
        Executor main = ContextCompat.getMainExecutor(requireContext().getApplicationContext());
        NavBackStackEntry graphEntry = NavHostFragment.findNavController(this).getBackStackEntry(R.id.onboarding_graph);
        final ConnectionMode m = mode;
        viewModel = new ViewModelProvider(graphEntry, ViewModelFactories.of(ConnectionViewModel.class,
                handle -> new ConnectionViewModel(new ConnectionModel(m, service, new SavedStateHandleStore(handle),
                        main, status.current().online()), status, main)))
                .get(ConnectionViewModel.class);
        backGuard = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                viewModel.model().onBackRequested();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, backGuard);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        appliedUrlVersion = -1;
        appliedTokenVersion = -1;
        appliedTokenVisible = null;
        appliedResults = null;

        MaterialToolbar toolbar = view.findViewById(R.id.s06_toolbar);
        // Q-74: NavigationUI sets the Up icon and its localised „Navigate up” description; the click is then routed
        // through the model so that edit mode with unsaved changes asks first (A-S06-5).
        NavigationUI.setupWithNavController(toolbar, NavHostFragment.findNavController(this));
        toolbar.setTitle(ConnectionCopy.title(mode));
        toolbar.setNavigationOnClickListener(v -> viewModel.model().onBackRequested());
        EdgeToEdgeInsets.applyStatusBarInset(view.findViewById(R.id.s06_app_bar));
        EdgeToEdgeInsets.applyImeInset(view.findViewById(R.id.s06_root));

        urlLayout = view.findViewById(R.id.s06_url_layout);
        urlField = view.findViewById(R.id.s06_url);
        tokenLayout = view.findViewById(R.id.s06_token_layout);
        tokenField = view.findViewById(R.id.s06_token);
        checkingRow = view.findViewById(R.id.s06_checking_row);
        resultsList = view.findViewById(R.id.s06_results);
        helper = view.findViewById(R.id.s06_helper);
        primary = view.findViewById(R.id.s06_primary);

        urlField.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!programmatic) {
                    viewModel.model().onUrlEdited(s.toString());
                }
            }
        });
        urlField.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                viewModel.model().onUrlFocusLost();
            }
        });
        tokenField.addTextChangedListener(new TokenWatcher());
        tokenLayout.setEndIconOnClickListener(v -> viewModel.model().onTokenToggle());
        primaryGuard.attach(primary, () -> viewModel.model().onPrimaryAction());

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.events().observe(getViewLifecycleOwner(), event -> {
            ConnectionModel.Event e = event == null ? null : event.take();
            if (e != null) {
                handle(e);
            }
        });
    }

    private void render(ConnectionUiState s) {
        lastTokenMode = s.tokenMode();
        if (appliedUrlVersion != s.urlFieldVersion()) {
            appliedUrlVersion = s.urlFieldVersion();
            setFieldText(urlField, s.url());
        }
        urlLayout.setError(s.urlError() == null ? null : getString(ConnectionCopy.inputError(s.urlError())));

        if (appliedTokenVersion != s.tokenFieldVersion()) {
            appliedTokenVersion = s.tokenFieldVersion();
            rewriteToken(s.tokenMode());
        }
        if (appliedTokenVisible == null || appliedTokenVisible != s.tokenVisible()) {
            appliedTokenVisible = s.tokenVisible();
            applyTokenVisibility(s.tokenVisible());
        }
        tokenLayout.setEndIconVisible(s.tokenToggleEnabled());   // IR-94
        tokenLayout.setHelperText(getString(s.tokenReenter() ? R.string.s06_token_reenter : R.string.s06_token_helper));
        tokenLayout.setError(s.tokenError() == null ? null : getString(ConnectionCopy.inputError(s.tokenError())));

        urlField.setEnabled(s.fieldsEnabled());
        tokenField.setEnabled(s.fieldsEnabled());
        checkingRow.setVisibility(s.checking() ? View.VISIBLE : View.GONE);
        if (!s.results().equals(appliedResults)) {
            appliedResults = s.results();
            renderResults(s.results());
        }

        primary.setText(ConnectionCopy.primary(s.primary()));
        primary.setEnabled(s.primaryEnabled());
        if (s.fieldsEnabled()) {
            primaryGuard.complete();
        }
        int helperRes = ConnectionCopy.helper(s.helper());
        helper.setVisibility(helperRes == 0 ? View.GONE : View.VISIBLE);
        if (helperRes != 0) {
            helper.setText(helperRes);
        }

        backGuard.setEnabled(s.backGuard());
        renderDiscardDialog(s.discardDialog());
    }

    private void rewriteToken(TokenMode tokenMode) {
        programmatic = true;
        try {
            TokenChars.wipe(tokenField.getText());
            if (tokenMode == TokenMode.STORED_MASKED) {
                tokenField.setText(R.string.s06_token_masked);
            } else {
                char[] chars = viewModel.model().tokenForField();
                if (chars != null) {
                    tokenField.setText(CharBuffer.wrap(chars));
                    Arrays.fill(chars, '\0');
                }
            }
            Editable text = tokenField.getText();
            tokenField.setSelection(text == null ? 0 : text.length());
        } finally {
            programmatic = false;
        }
    }

    private void applyTokenVisibility(boolean visible) {
        int selection = tokenField.getSelectionEnd();
        tokenField.setTransformationMethod(visible ? null : PasswordTransformationMethod.getInstance());
        Editable text = tokenField.getText();
        if (text != null && selection >= 0 && selection <= text.length()) {
            tokenField.setSelection(selection);
        }
        tokenLayout.setEndIconDrawable(visible ? R.drawable.ic_s06_token_hide : R.drawable.ic_s06_token_show);
        tokenLayout.setEndIconContentDescription(visible ? R.string.s06_token_hide_cd : R.string.s06_token_show_cd);
    }

    private void renderResults(List<CheckResult> rows) {
        resultsList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        int passedColor = MaterialColors.getColor(resultsList, androidx.appcompat.R.attr.colorPrimary);
        int failedColor = MaterialColors.getColor(resultsList, androidx.appcompat.R.attr.colorError);
        for (CheckResult r : rows) {
            View item = inflater.inflate(R.layout.item_s06_check, resultsList, false);
            ImageView icon = item.findViewById(R.id.s06_row_icon);
            TextView label = item.findViewById(R.id.s06_row_label);
            TextView message = item.findViewById(R.id.s06_row_message);
            String version = r.serverVersion() == null ? "" : r.serverVersion();
            String labelText = r.row() == CheckRow.VERSION
                    ? getString(R.string.s06_check_version, version) : getString(ConnectionCopy.rowLabel(r.row()));
            label.setText(labelText);
            icon.setImageResource(r.passed() ? R.drawable.ic_s06_check_passed : R.drawable.ic_s06_check_failed);
            icon.setColorFilter(r.passed() ? passedColor : failedColor);
            if (r.passed() || r.failure() == null) {
                message.setVisibility(View.GONE);
                item.setContentDescription(getString(R.string.s06_check_done_a11y, labelText));
            } else {
                String messageText = r.failure() == CheckFailure.VERSION_TOO_OLD
                        ? getString(R.string.s06_error_version, version)
                        : getString(ConnectionCopy.failureMessage(r.failure()));
                message.setText(messageText);
                message.setVisibility(View.VISIBLE);
                item.setContentDescription(getString(R.string.s06_check_failed_a11y, labelText, messageText));
            }
            resultsList.addView(item);
        }
    }

    private void renderDiscardDialog(boolean show) {
        if (show && discardDialog == null) {
            discardDialog = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.s06_discard_title)
                    .setNegativeButton(R.string.s06_discard_keep_editing,
                            (d, w) -> viewModel.model().onDiscardCancelled())
                    .setPositiveButton(R.string.s06_discard_confirm, (d, w) -> viewModel.model().onDiscardConfirmed())
                    .setOnCancelListener(d -> viewModel.model().onDiscardCancelled())
                    .create();
            discardDialog.setOnDismissListener(d -> discardDialog = null);
            discardDialog.show();
        } else if (!show && discardDialog != null) {
            AlertDialog d = discardDialog;
            discardDialog = null;
            d.dismiss();
        }
    }

    private void handle(ConnectionModel.Event e) {
        NavController nav = NavHostFragment.findNavController(this);
        switch (e) {
            case OPEN_READINESS:
                NavDestination current = nav.getCurrentDestination();
                if (current != null && current.getId() == R.id.connectionFragment) {
                    nav.navigate(ConnectionFragmentDirections.actionConnectionFragmentToReadinessFragment());
                }
                break;
            case FINISH_SAVED:
                backGuard.setEnabled(false);
                OnboardingHost.navigator(this).finishWithResult(UiMessage.CONNECTION_SAVED);
                break;
            case LEAVE:
            default:
                backGuard.setEnabled(false);
                nav.navigateUp();
                break;
        }
    }

    private void setFieldText(TextInputEditText field, String text) {
        programmatic = true;
        try {
            field.setText(text);
            Editable e = field.getText();
            field.setSelection(e == null ? 0 : e.length());
        } finally {
            programmatic = false;
        }
    }

    @Override
    public void onDestroyView() {
        if (discardDialog != null) {
            AlertDialog d = discardDialog;
            discardDialog = null;
            d.setOnDismissListener(null);
            d.setOnCancelListener(null);
            d.dismiss();   // the model keeps discardDialog = true, so the new view shows it again
        }
        programmatic = true;
        TokenChars.wipe(tokenField.getText());   // SR-02: the plaintext stays only in the model (memory)
        programmatic = false;
        urlLayout = null;
        urlField = null;
        tokenLayout = null;
        tokenField = null;
        checkingRow = null;
        resultsList = null;
        helper = null;
        primary = null;
        super.onDestroyView();
    }

    /** Reads the token field as {@code char[]}; the first keystroke on the placeholder replaces it (R-73). */
    private final class TokenWatcher extends SimpleWatcher {

        private int changeStart;
        private int changeCount;

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            changeStart = start;
            changeCount = count;
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (programmatic) {
                return;
            }
            if (lastTokenMode == TokenMode.STORED_MASKED) {
                viewModel.model().onPlaceholderKeystroke(TokenChars.copy(s, changeStart, changeStart + changeCount));
            } else {
                viewModel.model().onTokenTyped(TokenChars.copy(s, 0, s.length()));
            }
        }
    }

    /** No-op {@link TextWatcher} base. */
    private abstract static class SimpleWatcher implements TextWatcher {

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // not needed
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // not needed
        }
    }
}
