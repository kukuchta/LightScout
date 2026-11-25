package app.lightscout.ui.core;

import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * GIR-6 edge-to-edge inset helpers (CMP-33; CP-06 §7.3, §8.3; M3-01, M3-02; targetSdk 36 enforces edge-to-edge,
 * HN-02).
 *
 * <p>Each helper installs a window-insets listener on the given view that remembers the view's own padding /
 * margins from the layout and adds the current inset on every dispatch (no accumulation). A view has one insets
 * listener, so use one helper per view. Main thread only.
 */
public final class EdgeToEdgeInsets {

    private EdgeToEdgeInsets() {
    }

    /**
     * Enables edge-to-edge drawing for an activity (transparent system bars, bar icon colours following the
     * light / dark theme). Call in {@code onCreate} before {@code setContentView}.
     *
     * @param activity the activity
     */
    public static void enable(ComponentActivity activity) {
        EdgeToEdge.enable(activity);
    }

    /**
     * {@code AppBarLayout} takes the status-bar inset (and a display cutout) as top padding; system-bar side insets
     * become side padding.
     *
     * @param appBar the app bar
     */
    public static void applyStatusBarInset(View appBar) {
        final int top = appBar.getPaddingTop();
        final int left = appBar.getPaddingLeft();
        final int right = appBar.getPaddingRight();
        ViewCompat.setOnApplyWindowInsetsListener(appBar, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, v.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(appBar);
    }

    /**
     * Scroll content bottom padding = navigation-bar inset (+ FAB height + 16 dp when {@code fabOrNull} is given,
     * S-01). Turns off {@code clipToPadding} so that content scrolls behind the navigation bar.
     *
     * @param scrollContent the scrolling view (e.g. {@code RecyclerView}, {@code NestedScrollView})
     * @param fabOrNull     the FAB that floats over the content, or {@code null}
     */
    public static void applyScrollBottomInset(View scrollContent, View fabOrNull) {
        final int bottom = scrollContent.getPaddingBottom();
        final int margin = px(scrollContent, InsetMath.STICKY_MARGIN_DP);
        if (scrollContent instanceof ViewGroup) {
            ((ViewGroup) scrollContent).setClipToPadding(false);
        }
        ViewCompat.setOnApplyWindowInsetsListener(scrollContent, (v, insets) -> {
            int nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            int fab = fabOrNull == null ? 0 : fabOrNull.getHeight();
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    InsetMath.scrollBottomPadding(bottom, nav, fab, margin));
            return insets;
        });
        if (fabOrNull != null) {
            fabOrNull.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (b - t != ob - ot) {
                    ViewCompat.requestApplyInsets(scrollContent);
                }
            });
        }
        ViewCompat.requestApplyInsets(scrollContent);
    }

    /**
     * Sticky bottom button (S-05, S-06, S-07) or FAB: bottom margin = navigation-bar inset + 16 dp. The view must
     * have {@link ViewGroup.MarginLayoutParams}.
     *
     * @param view the bottom button or FAB
     */
    public static void applyStickyBottomInset(View view) {
        final int margin = px(view, InsetMath.STICKY_MARGIN_DP);
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            setBottomMargin(v, InsetMath.stickyBottomMargin(nav, margin));
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * IME resize (S-02, S-06): bottom padding = the larger of keyboard and navigation-bar insets, so the focused
     * field, its error and the bottom button stay visible above the keyboard.
     *
     * @param root the screen's content root
     */
    public static void applyImeInset(View root) {
        final int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    InsetMath.imeBottomPadding(bottom, ime, nav));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /**
     * S-09 action area (M3-02, C-S09-04): bottom margin = navigation-bar inset + 24 dp; side margins = system-bar
     * side inset + 16 dp. Centring in the lower third is the layout's job (CP-07). The view must have
     * {@link ViewGroup.MarginLayoutParams}.
     *
     * @param actions the S-09 bottom action container
     */
    public static void applyAlarmActionInsets(View actions) {
        final int bottomExtra = px(actions, InsetMath.ALARM_BOTTOM_DP);
        final int sideExtra = px(actions, InsetMath.ALARM_SIDE_DP);
        ViewCompat.setOnApplyWindowInsetsListener(actions, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                m.leftMargin = InsetMath.sideMargin(bars.left, sideExtra);
                m.rightMargin = InsetMath.sideMargin(bars.right, sideExtra);
                m.bottomMargin = InsetMath.stickyBottomMargin(bars.bottom, bottomExtra);
                v.setLayoutParams(m);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(actions);
    }

    private static void setBottomMargin(View v, int bottomMargin) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            if (m.bottomMargin != bottomMargin) {
                m.bottomMargin = bottomMargin;
                v.setLayoutParams(m);
            }
        }
    }

    private static int px(View v, int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                v.getResources().getDisplayMetrics()));
    }
}
