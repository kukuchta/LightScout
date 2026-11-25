package app.lightscout.feature.home;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays its children out left to right (start to end in RTL) and wraps to a new line when a child does not fit
 * (CP-12 §7.11 / A4: „Display Large reading must not truncate at 200 % font scale — status block wraps”). Children
 * on one line are centred vertically; {@code MarginLayoutParams} are honoured. No fixed heights.
 */
public final class WrapRowLayout extends ViewGroup {

    /**
     * Creates the layout from code.
     *
     * @param context context
     */
    public WrapRowLayout(Context context) {
        super(context);
    }

    /**
     * Creates the layout from XML.
     *
     * @param context context
     * @param attrs   XML attributes
     */
    public WrapRowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int maxWidth = widthMode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
                : MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int lineWidth = 0;
        int lineHeight = 0;
        int usedWidth = 0;
        int totalHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int w = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int h = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (lineWidth > 0 && lineWidth + w > maxWidth) {
                usedWidth = Math.max(usedWidth, lineWidth);
                totalHeight += lineHeight;
                lineWidth = 0;
                lineHeight = 0;
            }
            lineWidth += w;
            lineHeight = Math.max(lineHeight, h);
        }
        usedWidth = Math.max(usedWidth, lineWidth);
        totalHeight += lineHeight;
        int width = resolveSize(usedWidth + getPaddingLeft() + getPaddingRight(), widthMeasureSpec);
        int height = resolveSize(totalHeight + getPaddingTop() + getPaddingBottom(), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxWidth = r - l - getPaddingLeft() - getPaddingRight();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int top = getPaddingTop();
        int start = 0;
        while (start < getChildCount()) {
            // Find the children of this line and its height.
            int end = start;
            int lineWidth = 0;
            int lineHeight = 0;
            while (end < getChildCount()) {
                View child = getChildAt(end);
                if (child.getVisibility() != GONE) {
                    MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                    int w = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                    if (lineWidth > 0 && lineWidth + w > maxWidth) {
                        break;
                    }
                    lineWidth += w;
                    lineHeight = Math.max(lineHeight, child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
                }
                end++;
            }
            int x = 0;
            for (int i = start; i < end; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                int ct = top + lp.topMargin + (lineHeight - lp.topMargin - lp.bottomMargin - ch) / 2;
                int cl = rtl ? getWidth() - getPaddingRight() - x - lp.rightMargin - cw
                        : getPaddingLeft() + x + lp.leftMargin;
                child.layout(cl, ct, cl + cw, ct + ch);
                x += cw + lp.leftMargin + lp.rightMargin;
            }
            top += lineHeight;
            start = end;
        }
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams ? new MarginLayoutParams((MarginLayoutParams) p)
                : new MarginLayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }
}
