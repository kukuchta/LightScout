package app.lightscout.widget;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import java.util.function.Supplier;

/** Measures the same framework Views used by the launcher, before drawing its chart. */
final class WidgetLayout {

    private static final int LARGEST_READING_SP = 44;
    private static final int SMALLEST_READING_SP = 28;

    /** Gap between the widget edge and the first line, and the chart's left, right and bottom edges (DCR-38). */
    private static final int INSET_DP = 20;

    private WidgetLayout() {
    }

    record Measured(RemoteViews views, View root) {
    }

    /**
     * Applies the views at the launcher size; when the chart would be lower than 64 dp the reading text shrinks
     * step by step (down to 28 sp) to give it the height.
     */
    static Measured measure(Context context, Supplier<RemoteViews> factory, int widthPx, int heightPx) {
        RemoteViews views = factory.get();
        int minimumChartHeight = Math.round(64 * context.getResources().getDisplayMetrics().density);
        int readingSp = LARGEST_READING_SP;
        View root;
        while (true) {
            views.setTextViewTextSize(R.id.widget_reading, TypedValue.COMPLEX_UNIT_SP, readingSp);
            applyInsets(context, views, readingSp);
            root = views.apply(context, new FrameLayout(context));
            root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, widthPx, heightPx);
            if (root.findViewById(R.id.widget_chart).getHeight() >= minimumChartHeight
                    || readingSp <= SMALLEST_READING_SP) {
                break;
            }
            readingSp -= 2;
        }
        return new Measured(views, root);
    }

    /**
     * Same visible gap above the digits as at the sides: the text box starts at the font ascent, which is higher than
     * the digits, so that difference is taken off the top padding.
     */
    private static void applyInsets(Context context, RemoteViews views, int readingSp) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int inset = Math.round(INSET_DP * metrics.density);
        Paint paint = new Paint();
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, readingSp, metrics));
        Rect digits = new Rect();
        paint.getTextBounds("8", 0, 1, digits);
        int aboveDigits = Math.round(-paint.getFontMetrics().ascent - digits.height());
        views.setViewPadding(R.id.widget_root, inset, Math.max(0, inset - aboveDigits), inset, inset);
    }
}
