package app.lightscout.feature.settings;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.radiobutton.MaterialRadioButton;

/**
 * Row building for S-13 and S-14 (DCR-51), in the look of the S-04 rows: text in a column, 56 or 68 dp high, rows
 * wrap at large font sizes, theme colours only. Used from the main thread.
 */
final class SoundScreenUi {

    private static final int BUTTON_DP = 48;

    private SoundScreenUi() {
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static void header(LinearLayout content, String text) {
        Context context = content.getContext();
        TextView header = new TextView(context);
        header.setText(text);
        header.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        header.setTextColor(MaterialColors.getColor(header, androidx.appcompat.R.attr.colorPrimary));
        header.setAccessibilityHeading(true);
        header.setPadding(0, dp(context, 20), 0, dp(context, 8));
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * Adds one row and a separator below it.
     *
     * @param leading     a view before the text (the radio indicator), or {@code null}
     * @param support     second line, or {@code null}
     * @param description what a screen reader says for the whole row, or {@code null} when it reads the text
     * @param click       what a tap does, or {@code null}
     * @param trailing    views after the text (play and trash buttons)
     * @return the row
     */
    static LinearLayout row(LinearLayout content, @Nullable View leading, String title, @Nullable String support,
                            @Nullable String description, boolean enabled, @Nullable Runnable click,
                            View... trailing) {
        Context context = content.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, support == null ? 56 : 68));
        row.setPadding(0, dp(context, 8), 0, dp(context, 8));
        row.setEnabled(enabled);
        row.setFocusable(true);
        row.setScreenReaderFocusable(true);
        if (description != null) {
            row.setContentDescription(description);
        }
        if (click != null) {
            row.setClickable(enabled);
            row.setOnClickListener(v -> {
                if (enabled) {
                    click.run();
                }
            });
        }
        if (leading != null) {
            row.addView(leading, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        row.addView(textColumn(context, title, support, description != null), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        for (View view : trailing) {
            row.addView(view, new LinearLayout.LayoutParams(dp(context, BUTTON_DP), dp(context, BUTTON_DP)));
        }
        content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View separator = new View(context);
        separator.setBackgroundColor(MaterialColors.getColor(row,
                com.google.android.material.R.attr.colorOutlineVariant));
        content.addView(separator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        return row;
    }

    private static LinearLayout textColumn(Context context, String title, @Nullable String support,
                                           boolean hiddenFromReader) {
        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        if (hiddenFromReader) {
            text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        text.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (support != null && !support.isEmpty()) {
            TextView supportView = new TextView(context);
            supportView.setText(support);
            supportView.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            supportView.setTextColor(MaterialColors.getColor(supportView,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            text.addView(supportView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return text;
    }

    /** A 48 dp round icon button with a spoken label. */
    static ImageButton iconButton(Context context, @DrawableRes int icon, String description, Runnable click) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(icon);
        button.setContentDescription(description);
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        button.setBackgroundResource(ripple.resourceId);
        button.setOnClickListener(v -> click.run());
        return button;
    }

    /** The indicator before a choice row; it is not a focus target, the row is. */
    static MaterialRadioButton radioIndicator(Context context, boolean checked) {
        MaterialRadioButton radio = new MaterialRadioButton(context);
        radio.setChecked(checked);
        radio.setClickable(false);
        radio.setFocusable(false);
        radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return radio;
    }

    /** Makes a screen reader announce the row as a radio button with its state. */
    static void announceAsRadio(View row, boolean checked) {
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(RadioButton.class.getName());
                info.setCheckable(true);
                info.setChecked(checked);
            }
        });
    }
}
