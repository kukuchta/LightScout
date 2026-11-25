package app.lightscout.alarm;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.domain.port.Clock;
import app.lightscout.ui.core.EdgeToEdgeInsets;
import app.lightscout.ui.core.Formatters;
import app.lightscout.ui.core.TrendIcons;

/**
 * CMP-42 AlarmActivity — S-09 Alarm screen over the lock screen (CP-07 §7.1 with ADR-047 / 06 §11 amendments;
 * IF-50, IF-52; SR-22, SR-25; CP-00A R-36, R-80).
 *
 * <ul>
 *   <li>Content = the latest {@code AlarmView} from IF-16 ({@link AlarmScreenModel}); closes when the alarm stops
 *       ({@code Stop}) and after Snooze.</li>
 *   <li>Snooze only by the C-S09-04 button or a volume key (A-S09-1); screen-off, the power key and the screen
 *       timeout never snooze (F1): keep-screen-on for the whole ringing time.</li>
 *   <li>Back and predictive back do nothing (always-enabled callback, F17, DS:D-078).</li>
 *   <li>{@code showWhenLocked} / {@code turnScreenOn}; no navigation into the app or any other screen (SR-25);
 *       excluded from recents, own task (manifest).</li>
 *   <li>Dark theme 22:00–07:00 regardless of the system setting, else the system's (DS:D-061); the age text and the
 *       window are re-checked every minute and the screen is recreated at a boundary (R-80).</li>
 *   <li>TalkBack announces „Alarm. {type}, {value}, {trend}” on open; Snooze gets the first focus (GIR-7).</li>
 * </ul>
 * The {@code alarmType} extra is validated against the enum; a missing / unknown extra or no ringing alarm closes the
 * screen (SR-22).
 */
public final class AlarmActivity extends ComponentActivity {

    private static final long MINUTE_MS = 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = this::onMinute;
    private final AlarmScreenModel.Listener modelListener = view -> main.post(() -> onAlarmView(view));

    private AlarmRuntime runtime;
    private AlarmTextRenderer texts;
    private boolean darkApplied;
    private AlarmView shown;
    private boolean snoozed;

    private TextView title;
    private TextView reading;
    private ImageView trend;
    private TextView unit;
    private TextView age;
    private TextView cause;
    private MaterialButton snooze;
    private View root;

    /** Creates the activity (instantiated by the system). */
    public AlarmActivity() {
        super();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        Clock clock = AlarmComponents.runtime(newBase).clock();
        darkApplied = DarkWindow.isDark(clock.now(), clock.zone());
        if (darkApplied) {
            // DS:D-061: dark 22:00–07:00 regardless of the system setting (DayNight resolves -night resources)
            Configuration override = new Configuration();
            int current = newBase.getResources().getConfiguration().uiMode;
            override.uiMode = Configuration.UI_MODE_NIGHT_YES | (current & ~Configuration.UI_MODE_NIGHT_MASK);
            applyOverrideConfiguration(override);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdgeInsets.enable(this);
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // F17 / DS:D-078: back does nothing; only „Snooze” ends the alarm
            }
        });

        runtime = AlarmComponents.runtime(this);
        texts = new AlarmTextRenderer(this);
        setContentView(R.layout.activity_alarm);
        root = findViewById(R.id.alarm_root);
        title = findViewById(R.id.alarm_title);
        reading = findViewById(R.id.alarm_reading);
        trend = findViewById(R.id.alarm_trend);
        unit = findViewById(R.id.alarm_unit);
        age = findViewById(R.id.alarm_age);
        cause = findViewById(R.id.alarm_cause);
        snooze = findViewById(R.id.alarm_snooze);
        EdgeToEdgeInsets.applyStatusBarInset(findViewById(R.id.alarm_content_scroll));
        EdgeToEdgeInsets.applyAlarmActionInsets(snooze);
        snooze.setOnClickListener(v -> snooze());

        if (!accept(getIntent())) {
            finishAndRemoveTask();
            return;
        }
        runtime.screen().addListener(modelListener);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!accept(intent)) {
            finishAndRemoveTask();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (runtime == null) {
            return;
        }
        runtime.screen().setForeground(true);
        AlarmView current = runtime.screen().current();
        if (current == null) {
            finishAndRemoveTask();
            return;
        }
        render(current);
        scheduleMinute();
        snooze.requestFocus();
        snooze.post(() -> snooze.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                null));
    }

    @Override
    protected void onPause() {
        if (runtime != null) {
            runtime.screen().setForeground(false);
        }
        main.removeCallbacks(minuteTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (runtime != null) {
            runtime.screen().removeListener(modelListener);
        }
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (SnoozeKeys.consumes(keyCode)) {
            if (SnoozeKeys.snoozes(keyCode, event.getAction(), event.getRepeatCount())) {
                snooze();   // A-S09-1: volume up / down snooze (DS:A-26, DS:D-084)
            }
            return true;   // consumed: the volume does not change while S-09 is shown
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (SnoozeKeys.consumes(keyCode)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** Validates the {@code alarmType} extra (SR-22) and that an alarm rings in this process. */
    private boolean accept(Intent intent) {
        AlarmType type = intent == null ? null : AlarmTypeExtra.parse(intent.getStringExtra(AlarmTypeExtra.NAME));
        if (type == null) {
            return false;
        }
        AlarmView current = runtime.screen().current();
        if (current == null) {
            return false;
        }
        render(current);   // the latest view wins (R-80), even if the extra names the replaced type
        return true;
    }

    private void onAlarmView(AlarmView view) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (view == null) {
            finishAndRemoveTask();   // Stop: S-09 closes (WF-02 step 5)
            return;
        }
        render(view);
    }

    private void snooze() {
        if (snoozed || shown == null) {
            return;
        }
        snoozed = true;
        runtime.requestSnooze(shown.type());
        finishAndRemoveTask();   // SR-25: finishes after snooze
    }

    private void onMinute() {
        Clock clock = runtime.clock();
        if (DarkWindow.isDark(clock.now(), clock.zone()) != darkApplied) {
            recreate();   // R-80: the 22:00 / 07:00 boundary passed
            return;
        }
        if (shown != null) {
            render(shown);
        }
        scheduleMinute();
    }

    private void scheduleMinute() {
        main.removeCallbacks(minuteTick);
        main.postDelayed(minuteTick, MINUTE_MS);
    }

    private void render(AlarmView v) {
        boolean typeChanged = shown == null || shown.type() != v.type();
        shown = v;
        Clock clock = runtime.clock();

        title.setText(texts.title(v));
        styleTitle(v.type());

        reading.setText(texts.reading(v));
        boolean hasReading = texts.hasReading(v);
        unit.setVisibility(hasReading ? View.VISIBLE : View.GONE);
        int arrow = hasReading ? TrendIcons.drawableFor(v.trend()) : 0;
        if (arrow == 0) {
            trend.setVisibility(View.GONE);
            trend.setContentDescription(null);
        } else {
            trend.setVisibility(View.VISIBLE);
            trend.setImageResource(arrow);
            ImageViewCompat.setImageTintList(trend, ColorStateList.valueOf(
                    MaterialColors.getColor(trend, com.google.android.material.R.attr.colorOnSurface)));
            trend.setContentDescription(Formatters.trendWords(this, v.trend()));   // M3-34
        }

        setOptional(age, texts.ageLine(v, clock.now(), clock.zone()));
        setOptional(cause, texts.causeLine(v));
        snooze.setText(texts.snoozeLabel(v));

        if (typeChanged) {
            String announcement = texts.announcement(v, clock.now(), clock.zone());
            setTitle(announcement);   // window title → TalkBack announcement on open (GIR-7)
            ViewCompat.setAccessibilityPaneTitle(root, announcement);
        }
    }

    private void styleTitle(AlarmType type) {
        int textColor;
        switch (AlarmTexts.titleStyle(type)) {
            case BADGE_ERROR_CONTAINER:
                textColor = MaterialColors.getColor(title, com.google.android.material.R.attr.colorOnErrorContainer);
                title.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_alarm_badge));
                ViewCompat.setBackgroundTintList(title, ColorStateList.valueOf(
                        MaterialColors.getColor(title, com.google.android.material.R.attr.colorErrorContainer)));
                break;
            case WARNING:
                textColor = MaterialColors.getColor(title, app.lightscout.ui.core.R.attr.warning);
                title.setBackground(null);
                break;
            default:
                // GIR-10 "error" role: the theme's colorError value (CMP-30 static scheme, day / night)
                textColor = ContextCompat.getColor(this, app.lightscout.ui.core.R.color.md_theme_error);
                title.setBackground(null);
                break;
        }
        title.setTextColor(textColor);
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_alarm, 0, 0, 0);
        TextViewCompat.setCompoundDrawableTintList(title, ColorStateList.valueOf(textColor));
    }

    private static void setOptional(TextView view, String text) {
        if (text == null || text.isEmpty()) {
            view.setVisibility(View.GONE);
        } else {
            view.setVisibility(View.VISIBLE);
            view.setText(text);
        }
    }
}
