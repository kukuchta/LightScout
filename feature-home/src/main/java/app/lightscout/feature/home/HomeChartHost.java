package app.lightscout.feature.home;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;

/** Provides a real minimum-height chart allocation while Home's scroll child is measured naturally. */
public final class HomeChartHost extends MaterialCardView {

    public HomeChartHost(@NonNull Context context) {
        super(context);
    }

    public HomeChartHost(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public HomeChartHost(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            int minimumHeight = getSuggestedMinimumHeight();
            if (minimumHeight > 0) {
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(minimumHeight, MeasureSpec.EXACTLY);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
