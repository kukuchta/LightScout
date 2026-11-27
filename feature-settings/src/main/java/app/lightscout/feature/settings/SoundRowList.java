package app.lightscout.feature.settings;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.radiobutton.MaterialRadioButton;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows of S-13 and S-14 (DCR-51), in the look of the S-04 rows: text in a column, 56 or 68 dp high, theme colours
 * only. {@link #bind} keeps the views while the shape of the list stays the same (same keys, same radio and buttons)
 * and only changes what they show, so a screen reader keeps its focus when a choice or a play button changes. Another
 * shape rebuilds the list. Used from the main thread.
 */
final class SoundRowList {

    private static final int ROW_DP = 56;
    private static final int ROW_WITH_SUPPORT_DP = 68;
    private static final int BUTTON_DP = 48;

    /** One icon button after the text of a row. */
    record Button(@DrawableRes int icon, String description, Runnable click) {
    }

    /**
     * One line of the list. {@code key} names the line across renders; the same key must always give the same shape.
     * A header has only a title. A row has a spoken description for the whole row; {@code checked} is {@code null}
     * for a row without a radio indicator.
     */
    record Spec(String key, boolean header, String title, @Nullable String support, @Nullable String description,
                boolean enabled, @Nullable Boolean checked, @Nullable Runnable click, List<Button> buttons) {

        static Spec header(String key, String title) {
            return new Spec(key, true, title, null, null, true, null, null, List.of());
        }

        static Spec row(String key, String title, @Nullable String support, String description, boolean enabled,
                        @Nullable Boolean checked, @Nullable Runnable click, Button... buttons) {
            return new Spec(key, false, title, support, description, enabled, checked, click, List.of(buttons));
        }

        /** What must stay equal for the views to be reused. */
        String shape() {
            return key + '|' + (checked != null) + '|' + buttons.size();
        }
    }

    /** The views of one line, found again on the next bind. */
    private static final class Views {
        final View root;
        TextView title;
        @Nullable TextView support;
        @Nullable MaterialRadioButton radio;
        final List<ImageButton> buttons = new ArrayList<>();
        boolean checked;

        Views(View root) {
            this.root = root;
        }
    }

    private final LinearLayout content;
    private final List<Views> views = new ArrayList<>();
    private List<String> shapes = List.of();

    SoundRowList(LinearLayout content) {
        this.content = content;
    }

    /** Shows the lines; reuses the views when the shape is the one shown before. */
    void bind(List<Spec> specs) {
        List<String> next = new ArrayList<>();
        for (Spec spec : specs) {
            next.add(spec.shape());
        }
        if (!next.equals(shapes)) {
            rebuild(specs);
            shapes = next;
        }
        for (int i = 0; i < specs.size(); i++) {
            apply(views.get(i), specs.get(i));
        }
    }

    private void rebuild(List<Spec> specs) {
        content.removeAllViews();
        views.clear();
        for (Spec spec : specs) {
            views.add(spec.header() ? buildHeader() : buildRow(spec));
        }
    }

    private Views buildHeader() {
        Context context = content.getContext();
        TextView header = new TextView(context);
        header.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        header.setTextColor(MaterialColors.getColor(header, androidx.appcompat.R.attr.colorPrimary));
        header.setAccessibilityHeading(true);
        header.setPadding(0, SoundScreenUi.dp(context, 20), 0, SoundScreenUi.dp(context, 8));
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Views built = new Views(header);
        built.title = header;
        return built;
    }

    private Views buildRow(Spec spec) {
        Context context = content.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, SoundScreenUi.dp(context, 8), 0, SoundScreenUi.dp(context, 8));
        row.setFocusable(true);
        row.setScreenReaderFocusable(true);
        Views built = new Views(row);
        if (spec.checked() != null) {
            built.radio = SoundScreenUi.radioIndicator(context, false);
            row.addView(built.radio, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            announceAsRadio(row, built);
        }
        row.addView(buildText(context, built), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        for (Button ignored : spec.buttons()) {
            ImageButton button = SoundScreenUi.iconButton(context);
            built.buttons.add(button);
            row.addView(button, new LinearLayout.LayoutParams(
                    SoundScreenUi.dp(context, BUTTON_DP), SoundScreenUi.dp(context, BUTTON_DP)));
        }
        content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View separator = new View(context);
        separator.setBackgroundColor(MaterialColors.getColor(row,
                com.google.android.material.R.attr.colorOutlineVariant));
        content.addView(separator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SoundScreenUi.dp(context, 1)));
        return built;
    }

    /** The text column; the row speaks its own description, so the texts are hidden from a screen reader. */
    private static LinearLayout buildText(Context context, Views built) {
        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        built.title = new TextView(context);
        built.title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        text.addView(built.title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        built.support = new TextView(context);
        built.support.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        built.support.setTextColor(MaterialColors.getColor(built.support,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        text.addView(built.support, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return text;
    }

    private void apply(Views built, Spec spec) {
        built.title.setText(spec.title());
        if (spec.header()) {
            return;
        }
        Context context = content.getContext();
        boolean hasSupport = spec.support() != null && !spec.support().isEmpty();
        built.support.setText(hasSupport ? spec.support() : "");
        built.support.setVisibility(hasSupport ? View.VISIBLE : View.GONE);
        built.root.setMinimumHeight(SoundScreenUi.dp(context, hasSupport ? ROW_WITH_SUPPORT_DP : ROW_DP));
        built.root.setContentDescription(spec.description());
        built.root.setEnabled(spec.enabled());
        Runnable click = spec.click();
        built.root.setClickable(spec.enabled() && click != null);
        built.root.setOnClickListener(click == null ? null : v -> {
            if (spec.enabled()) {
                click.run();
            }
        });
        if (built.radio != null) {
            boolean checked = Boolean.TRUE.equals(spec.checked());
            boolean changed = checked != built.checked;
            built.checked = checked;
            built.radio.setChecked(checked);
            if (changed) {
                // the radio is hidden from a screen reader, so the row tells it that its state changed
                built.root.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            }
        }
        for (int i = 0; i < spec.buttons().size(); i++) {
            Button button = spec.buttons().get(i);
            ImageButton view = built.buttons.get(i);
            view.setImageResource(button.icon());
            view.setContentDescription(button.description());
            view.setEnabled(spec.enabled());
            view.setOnClickListener(v -> button.click().run());
        }
    }

    /** Makes a screen reader announce the row as a radio button with its current state. */
    private static void announceAsRadio(View row, Views built) {
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(RadioButton.class.getName());
                info.setCheckable(true);
                info.setChecked(built.checked);
            }
        });
    }
}
