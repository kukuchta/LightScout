package app.lightscout.feature.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import com.google.android.material.color.MaterialColors;

/** One fixed-bounds track: first-load sweep, or a fill that runs to the next planned check (D-151). */
public final class FreshnessTrackView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect visible = new Rect();
    private FreshnessPresentation presentation;
    private ValueAnimator sweep;
    private float position;
    private boolean started;
    /** Smooth fill (D-150): the bar grows between states from this base, only while visible. */
    private long fillBaseRealtime;
    private final Runnable fillTick = this::onFillTick;

    public FreshnessTrackView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setPresentation(FreshnessPresentation value) {
        if (!value.equals(presentation)) {
            fillBaseRealtime = SystemClock.elapsedRealtime();   // a new state corrects the extrapolation
        }
        presentation = value;
        updateAnimation();
        scheduleFill();
        invalidate();
    }

    /** Fraction of the five-minute track at this instant: the state's value plus the time since it was built. */
    private float fillFraction() {
        FreshnessPresentation p = presentation;
        if (p == null || p.spanMs() <= 0 || !ValueAnimator.areAnimatorsEnabled()) {
            return p == null ? 0f : p.percent() / 100f;
        }
        long elapsed = p.elapsedMs() + Math.max(0L, SystemClock.elapsedRealtime() - fillBaseRealtime);
        return Math.min(1f, (float) elapsed / p.spanMs());
    }

    private boolean fillRuns() {
        FreshnessPresentation p = presentation;
        return p != null && p.phase() != FreshnessPresentation.Phase.LOADING
                && p.phase() != FreshnessPresentation.Phase.EMPTY && p.spanMs() > 0
                && ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && getWidth() > 0 && fillFraction() < 1f;
    }

    /** Redraws only when the bar grows by about one pixel (a few times per second), never off-screen. */
    private void scheduleFill() {
        removeCallbacks(fillTick);
        if (fillRuns()) {
            long msPerPixel = Math.max(50L, presentation.spanMs() / Math.max(1, getWidth()));
            postDelayed(fillTick, msPerPixel);
        }
    }

    private void onFillTick() {
        invalidate();
        scheduleFill();
    }

    void setStarted(boolean value) {
        started = value;
        updateAnimation();
    }

    void updateAnimation() {
        boolean animate = presentation != null && FreshnessPresentation.shouldAnimate(started, presentation.phase(),
                isShown() && getGlobalVisibleRect(visible), ValueAnimator.areAnimatorsEnabled());
        if (animate && sweep == null) {
            sweep = ValueAnimator.ofFloat(0f, .65f);
            sweep.setDuration(1_800L);
            sweep.setInterpolator(new LinearInterpolator());
            sweep.setRepeatMode(ValueAnimator.REVERSE);
            sweep.setRepeatCount(ValueAnimator.INFINITE);
            sweep.addUpdateListener(animation -> {
                position = (float) animation.getAnimatedValue();
                if (!isShown() || !getGlobalVisibleRect(visible) || !ValueAnimator.areAnimatorsEnabled()) {
                    stopAnimation();
                }
                invalidate();
            });
            sweep.start();
        } else if (!animate) {
            stopAnimation();
        }
    }

    private void stopAnimation() {
        if (sweep != null) {
            sweep.cancel();
            sweep = null;
        }
        position = 0f;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleFill();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        scheduleFill();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(fillTick);
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateAnimation();
        scheduleFill();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateAnimation();
        scheduleFill();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (presentation == null) {
            return;
        }
        int track = getContext().getColor(app.lightscout.ui.core.R.color.glass_progress_track);
        float radius = 3f * getResources().getDisplayMetrics().density;
        paint.setColor(track);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
        FreshnessPresentation.Phase phase = presentation.phase();
        if (phase == FreshnessPresentation.Phase.EMPTY) {
            return;
        }
        if (phase == FreshnessPresentation.Phase.LOADING) {
            paint.setColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary));
            canvas.drawRoundRect(getWidth() * position, 0, getWidth() * (position + .35f),
                    getHeight(), radius, radius, paint);
            return;
        }
        // D-151: one fill that runs to the next planned check; its colour says how to read the wait.
        paint.setColor(phase == FreshnessPresentation.Phase.MISSED
                ? getContext().getColor(app.lightscout.ui.core.R.color.glass_progress_missed)
                : phase == FreshnessPresentation.Phase.STALE
                ? getContext().getColor(app.lightscout.ui.core.R.color.glass_progress_nodata)
                : MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary));
        canvas.drawRoundRect(0, 0, getWidth() * fillFraction(), getHeight(), radius, radius, paint);
    }
}
