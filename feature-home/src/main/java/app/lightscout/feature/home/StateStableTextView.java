package app.lightscout.feature.home;

import android.content.Context;
import android.content.res.TypedArray;
import android.text.Layout;
import android.text.StaticLayout;
import android.util.AttributeSet;
import android.view.View;
import androidx.appcompat.widget.AppCompatTextView;

/** Reserves the tallest allowed label at the actual width/font scale, without changing text or digit advances. */
public final class StateStableTextView extends AppCompatTextView {
    private final CharSequence[] reserved;
    /** Width taken by a view next to the text in some states only (an icon): the reserved height assumes it. */
    private int reservedInsetPx;

    public StateStableTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray values = context.obtainStyledAttributes(attrs, R.styleable.StateStableTextView);
        int id = values.getResourceId(R.styleable.StateStableTextView_reservedTexts, 0);
        reserved = id == 0 ? new CharSequence[0] : getResources().getTextArray(id);
        values.recycle();
    }

    /**
     * Sets the width the reserved texts must also leave free, so the reserved height is the same whether or not the
     * neighbour (an icon) is shown.
     *
     * @param px width in pixels, 0 when the neighbour is shown (the text view is already narrower)
     */
    void setReservedInset(int px) {
        if (px != reservedInsetPx) {
            reservedInsetPx = px;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int measuredWidth = getMeasuredWidth();
        int chrome = getCompoundPaddingLeft() + getCompoundPaddingRight();
        if (View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.EXACTLY) {
            // Wrap-content labels reserve the widest text, so an empty label does not wrap reserved text per letter.
            int widest = 0;
            for (CharSequence text : reserved) {
                widest = Math.max(widest, (int) Math.ceil(Layout.getDesiredWidth(text, getPaint())));
            }
            measuredWidth = Math.max(measuredWidth, widest + chrome);
            if (View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.AT_MOST) {
                measuredWidth = Math.min(measuredWidth, View.MeasureSpec.getSize(widthSpec));
            }
        }
        if (measuredWidth != getMeasuredWidth()) {
            // Re-measure at the reserved width so the text layout spans it and gravity (e.g. centre) applies.
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(measuredWidth, View.MeasureSpec.EXACTLY), heightSpec);
        }
        int width = Math.max(1, measuredWidth - chrome - reservedInsetPx);
        int height = getMeasuredHeight();
        for (CharSequence text : reserved) {
            StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), getPaint(), width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(getIncludeFontPadding())
                    .setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier()).build();
            height = Math.max(height, layout.getHeight() + getCompoundPaddingTop() + getCompoundPaddingBottom());
        }
        setMeasuredDimension(measuredWidth, resolveSizeAndState(height, heightSpec, 0));
    }
}
