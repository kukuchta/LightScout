package app.lightscout.feature.settings;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageButton;

import com.google.android.material.radiobutton.MaterialRadioButton;

/** View pieces for S-13 and S-14 (DCR-51); {@link SoundRowList} puts them together. Used from the main thread. */
final class SoundScreenUi {

    private SoundScreenUi() {
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** A round icon button with a ripple; {@link SoundRowList} sets its icon, label and action. */
    static ImageButton iconButton(Context context) {
        ImageButton button = new ImageButton(context);
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        button.setBackgroundResource(ripple.resourceId);
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
}
