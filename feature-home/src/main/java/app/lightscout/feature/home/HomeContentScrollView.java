package app.lightscout.feature.home;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/** Home's measured page viewport; scrolling is enabled only for actual overflow or large text. */
public final class HomeContentScrollView extends NestedScrollView {

    private boolean scrollingEnabled = true;

    public HomeContentScrollView(@NonNull Context context) {
        super(context);
    }

    public HomeContentScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public HomeContentScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setScrollingEnabled(boolean enabled) {
        if (scrollingEnabled == enabled) {
            return;
        }
        scrollingEnabled = enabled;
        setVerticalScrollBarEnabled(enabled);
        setOverScrollMode(enabled ? View.OVER_SCROLL_IF_CONTENT_SCROLLS : View.OVER_SCROLL_NEVER);
        if (!enabled) {
            scrollTo(0, 0);
        }
    }

    public boolean isScrollingEnabled() {
        return scrollingEnabled;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        View content = getChildCount() == 0 ? null : getChildAt(0);
        View chartHost = content == null ? null : content.findViewById(R.id.s01_chart_host);
        if (content == null || chartHost == null) {
            return;
        }

        int viewportHeight = Math.max(0, getMeasuredHeight() - getPaddingTop() - getPaddingBottom());
        int fixedContentHeight = Math.max(0, content.getMeasuredHeight() - chartHost.getMeasuredHeight());
        boolean allowScrolling = HomeLayoutPolicy.shouldScroll(
                getResources().getConfiguration().fontScale,
                viewportHeight,
                fixedContentHeight,
                chartHost.getMinimumHeight());
        setScrollingEnabled(allowScrolling);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return scrollingEnabled && super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return scrollingEnabled && super.onTouchEvent(event);
    }

    @Override
    public void scrollTo(int x, int y) {
        super.scrollTo(x, scrollingEnabled ? y : 0);
    }

    @Override
    public void fling(int velocityY) {
        if (scrollingEnabled) {
            super.fling(velocityY);
        }
    }

    @Override
    public void computeScroll() {
        if (scrollingEnabled) {
            super.computeScroll();
        } else {
            scrollTo(0, 0);
        }
    }
}
