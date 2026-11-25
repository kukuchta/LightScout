package app.lightscout.feature.home;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Preserves the chart minimum during natural-height measurement, then restores its weight before fillViewport's
 * exact-height pass can allocate the remaining space.
 */
public final class HomeContentLayout extends LinearLayout {

    public HomeContentLayout(@NonNull Context context) {
        super(context);
    }

    public HomeContentLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public HomeContentLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        View chartHost = findViewById(R.id.s01_chart_host);
        ViewGroup.LayoutParams rawParams = chartHost == null ? null : chartHost.getLayoutParams();
        LinearLayout.LayoutParams chartParams = rawParams instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) rawParams : null;
        float weight = chartParams == null ? 0f : chartParams.weight;
        boolean naturalHeight = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED;
        if (naturalHeight && chartParams != null && weight > 0f) {
            chartParams.weight = 0f;
        }
        try {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        } finally {
            if (naturalHeight && chartParams != null) {
                chartParams.weight = weight;
            }
        }
    }
}
