package app.lightscout.feature.home;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import android.content.res.ColorStateList;

import com.google.android.material.button.MaterialButton;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import app.lightscout.domain.entry.EntryRules;
import app.lightscout.domain.entry.ReadingRules;
import app.lightscout.domain.model.BasalSegment;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Reading;
import app.lightscout.ui.core.Formatters;
import app.lightscout.ui.core.GlucosePalette;
import app.lightscout.ui.core.GlucoseRendering;
import app.lightscout.ui.core.GlucoseScale;
import app.lightscout.usecases.Segment;

/**
 * CMP-71 glucose chart (CP-11; CP-00A R-14/R-77). The host owns history loading and the „Now” chip; callbacks
 * report viewport, missing-window and edit requests. This view performs no disk or network work.
 */
public final class GlucoseChartView extends View {

    private static final Duration COVERAGE_TOLERANCE = Duration.ofMinutes(1);
    private static final int DEFAULT_HEIGHT_DP = 256;
    private static final int PLOT_LEFT_DP = 16;   // card text inset; the 50 mg/dL captions are added to it
    private static final int PLOT_RIGHT_DP = 42;
    private static final int MARKER_ICON_DP = 24;
    private static final int CARBS_ICON_DP = 20;
    private static final Duration BG_CHECK_SHIFT = Duration.ofMinutes(15);
    private static final float BG_DOT_RADIUS_DP = 3.5f;
    private static final EntryRules ENTRY_RULES = new EntryRules();

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lowGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beyondPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subtleGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    // Reused by onDraw so that drawing allocates nothing per frame.
    private final android.graphics.Rect digitBounds = new android.graphics.Rect();
    private final Paint.FontMetrics valueMetrics = new Paint.FontMetrics();
    private final Paint.FontMetrics timeMetrics = new Paint.FontMetrics();
    private final TextPaint messagePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Reading> readings = new ArrayList<>();
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final ArrayList<BasalSegment> basal = new ArrayList<>();
    private final ArrayList<WindowState> windowStates = new ArrayList<>();
    private final ArrayList<WindowState> cacheWindowStates = new ArrayList<>();
    private final ArrayList<Window> preparedCacheRanges = new ArrayList<>();
    private final ArrayList<Window> unpreparedCacheRanges = new ArrayList<>();
    private final ArrayList<Window> loadedWindows = new ArrayList<>();
    private final ArrayList<Window> requestedWindows = new ArrayList<>();
    private final ArrayList<Reading> visibleReadings = new ArrayList<>();
    private final ArrayList<ReadingPoint> visiblePoints = new ArrayList<>();
    private final ArrayList<Marker> visibleMarkers = new ArrayList<>();
    private final ArrayList<DayTick> dayTicks = new ArrayList<>();
    private final ArrayList<HourTick> hourTicks = new ArrayList<>();
    private final ArrayList<WindowDraw> windowDraws = new ArrayList<>();
    private final Drawable[] markerDrawables = new Drawable[6];
    private final RectF plot = new RectF();
    /** Top and bottom of the glucose area: the plot minus the icon lanes, so readings never reach the icons. */
    private float readingTop;
    private float readingBottom;
    private float laneBand;
    private final RectF reusableRect = new RectF();
    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private final ScaleGestureDetector scaleDetector;
    private final OverScroller flingScroller;
    private final Runnable flingStep = this::stepFling;
    private final Runnable windowRequestStep = this::dispatchWindowRequest;
    private final Runnable longPressStep = this::startCursor;
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bubbleValuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bubbleTimePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private Instant now = Instant.EPOCH;
    private ZoneId zone = ZoneId.systemDefault();
    private ChartViewport viewport = ChartViewportRules.defaultViewport(Instant.EPOCH);
    private int urgentLowMgdl = 55;
    private int lowMgdl = 70;
    private int highMgdl = 250;
    private String lowLabel = "70";
    private String highLabel = "250";
    private String offlineLabel;
    private List<String> offlineLines = List.of();
    private List<String> errorLines = List.of();
    private List<String> emptyLines = List.of();
    private CharSequence emptyMessage;
    private Listener listener;
    private PopupWindow tooltip;
    private float density;
    private float downX;
    private float downY;
    private float lastX;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private VelocityTracker velocityTracker;
    private Instant flingStartEnd;
    private double flingMillisPerPx;
    private int flingLastX;
    private boolean flingActive;
    private float markerDownX;
    private float markerDownY;
    private Marker touchedMarker;
    private boolean dragging;
    private boolean scaling;
    private boolean cursorActive;
    private int cursorIndex = -1;
    private boolean yieldedToParent;
    private int surfaceColor;
    private GlucosePalette palette;
    private GlucoseScale scale = new GlucoseScale(40, 250);
    private boolean windowRequestPosted;

    public GlucoseChartView(Context context) {
        this(context, null);
    }

    public GlucoseChartView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GlucoseChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(new ContextThemeWrapper(context, R.style.ThemeOverlay_LightScout_HomeChart), attrs, defStyleAttr);
        context = getContext();
        density = getResources().getDisplayMetrics().density;
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        maxFlingVelocity = configuration.getScaledMaximumFlingVelocity();
        flingScroller = new OverScroller(context);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setWillNotDraw(false);
        palette = GlucoseRendering.palette(context);
        surfaceColor = resolveColor(com.google.android.material.R.attr.colorSurface,
                ContextCompat.getColor(context, app.lightscout.ui.core.R.color.md_theme_surface));
        offlineLabel = context.getString(R.string.s01_chart_older_offline);
        labelPaint.setTextSize(labelSmallTextSize(context));
        labelPaint.setColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(context, app.lightscout.ui.core.R.color.md_theme_onSurfaceVariant)));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        messagePaint.setTextSize(textAppearanceSize(context,
                com.google.android.material.R.attr.textAppearanceBodyMedium, 14));
        messagePaint.setColor(labelPaint.getColor());
        messagePaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(ResourcesCompat.getFont(context, app.lightscout.ui.core.R.font.glass_inter));
        messagePaint.setTypeface(labelPaint.getTypeface());
    
        backgroundPaint.setColor(surfaceColor);
        gridPaint.setColor(palette.threshold());
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setPathEffect(new android.graphics.DashPathEffect(new float[] {dp(2), dp(3)}, 0));
        // Reference grid (every 50 mg/dL, every full hour): the guide colour at about a third of its strength, so it
        // stays darker than the dashed Low / High guides.
        subtleGridPaint.setColor((palette.threshold() & 0x00FFFFFF) | 0x59000000);
        subtleGridPaint.setStrokeWidth(dp(1));
        subtleGridPaint.setStyle(Paint.Style.STROKE);
        subtleGridPaint.setPathEffect(new android.graphics.DashPathEffect(new float[] {dp(3), dp(4)}, 0));
        // Solid, coloured Low / High guides (dark red / dark yellow) and a faint tint beyond them.
        int lowGuide = ContextCompat.getColor(context, app.lightscout.ui.core.R.color.glass_guide_low);
        int highGuide = ContextCompat.getColor(context, app.lightscout.ui.core.R.color.glass_guide_high);
        for (Paint guide : new Paint[] {lowGuidePaint, highGuidePaint}) {
            guide.setStrokeWidth(dp(1.5f));
            guide.setStyle(Paint.Style.STROKE);
        }
        lowGuidePaint.setColor(lowGuide);
        highGuidePaint.setColor(highGuide);
        beyondPaint.setStyle(Paint.Style.FILL);
        pointStrokePaint.setStyle(Paint.Style.STROKE);
        pointStrokePaint.setStrokeWidth(dp(GlucosePalette.STROKE_DP));
        placeholderPaint.setColor(resolveColor(com.google.android.material.R.attr.colorSurfaceVariant,
                ContextCompat.getColor(context, app.lightscout.ui.core.R.color.md_theme_surfaceVariant)));
        markerDrawables[0] = loadDrawable(R.drawable.ic_chart_marker_bg);
        markerDrawables[1] = loadDrawable(R.drawable.ic_chart_marker_carbs);
        markerDrawables[2] = loadDrawable(R.drawable.ic_chart_marker_bolus);
        markerDrawables[3] = loadDrawable(R.drawable.ic_chart_marker_site);
        markerDrawables[4] = loadDrawable(R.drawable.ic_chart_marker_sensor);
        markerDrawables[5] = loadDrawable(R.drawable.ic_chart_marker_pump);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaling = true;
                float left = plotLeftPx();
                float width = Math.max(1f, getWidth() - left - dp(PLOT_RIGHT_DP));
                viewport = ChartViewportRules.zoom(viewport, detector.getScaleFactor(),
                        (detector.getFocusX() - left) / width, now);
                viewportChanged(true);
                return true;
            }
        });
        // FL-08: double tap has no meaning, so the double-tap-and-drag quick scale is off as well.
        scaleDetector.setQuickScaleEnabled(false);
    }

    /** Sets the Low and High guides and the value-band boundaries for readings. */
    public void setThresholds(int urgentLowMgdl, int lowMgdl, int highMgdl) {
        this.urgentLowMgdl = urgentLowMgdl;
        this.lowMgdl = lowMgdl;
        this.highMgdl = highMgdl;
        lowLabel = Integer.toString(lowMgdl);
        highLabel = Integer.toString(highMgdl);
        invalidate();
    }

    /**
     * Reconciles points read from the local cache without claiming that the requested range is complete or resolving
     * any loading/offline state. The cache snapshot replaces prior in-memory data in its range, including deletions.
     */
    public void setCachedSegment(Segment s) {
        Objects.requireNonNull(s, "s");
        removeWindows(preparedCacheRanges, s.from(), s.to());
        preparedCacheRanges.add(new Window(s.from(), s.to()));
        removeInRange(readings, s.from(), s.to(), Reading::time);
        removeInRange(entries, s.from(), s.to(), Entry::time);
        basal.removeIf(segment -> segment.start().isBefore(s.to())
                && segment.start().plus(segment.duration()).isAfter(s.from()));
        readings.addAll(s.readings());
        entries.addAll(s.entries());
        basal.addAll(s.basal());
        readings.sort(Comparator.comparing(Reading::time));
        entries.sort(Comparator.comparing(Entry::time));
        basal.sort(Comparator.comparing(BasalSegment::start));
        removeWindowStates(cacheWindowStates, s.from(), s.to());
        List<Reading> displayable = ReadingRules.displayable(readings, now);
        readings.clear();
        readings.addAll(displayable);
        rebuildGeometry();
        invalidate();
    }

    /** Supplies IF-15 time and zone. A viewport at now follows new readings. */
    public void setNow(Instant now, ZoneId zone) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(zone, "zone");
        boolean initialize = this.now.equals(Instant.EPOCH) && viewport.end().equals(Instant.EPOCH);
        boolean follow = !initialize && atRightEdge();
        this.now = now;
        this.zone = zone;
        if (initialize) {
            viewport = ChartViewportRules.defaultViewport(now);
        } else if (follow) {
            viewport = ChartViewportRules.clamp(new ChartViewport(now, viewport.span()), now);
        } else {
            viewport = ChartViewportRules.clamp(viewport, now);
        }
        rebuildGeometry();
        if (initialize) {
            notifyViewport();
            requestWindowIfMissing();
        }
        invalidate();
    }

    /** Merges a complete IF-22 range, replacing prior data within {@code [s.from, s.to)}. */
    public void setSegment(Segment s) {
        Objects.requireNonNull(s, "s");
        removeInRange(readings, s.from(), s.to(), Reading::time);
        removeInRange(entries, s.from(), s.to(), Entry::time);
        removeInRange(basal, s.from(), s.to(), segment -> segment.start());
        readings.addAll(s.readings());
        entries.addAll(s.entries());
        basal.addAll(s.basal());
        readings.sort(Comparator.comparing(Reading::time));
        entries.sort(Comparator.comparing(Entry::time));
        basal.sort(Comparator.comparing(BasalSegment::start));
        List<Reading> displayable = ReadingRules.displayable(readings, now);
        readings.clear();
        readings.addAll(displayable);
        loadedWindows.add(new Window(s.from(), s.to()));
        clearWindowStates(s.from(), s.to());
        rebuildGeometry();
        invalidate();
    }

    /** Marks an uncovered range as loading or offline. */
    public void setSegmentState(Instant from, Instant to, SegmentState state) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(state, "state");
        removeWindowStates(windowStates, from, to);
        removeWindowStates(cacheWindowStates, from, to);
        windowStates.add(new WindowState(from, to, state));
        if (state == SegmentState.LOADING) {
            addRequestedWindow(from, to);
        } else {
            requestedWindows.removeIf(w -> !w.to().isBefore(from) && !w.from().isAfter(to));
        }
        rebuildGeometry();
        invalidate();
    }

    /** Marks a cache-preparation failure without altering any on-demand request in flight. */
    public void setCachePreparationError(Instant from, Instant to) {
        ArrayList<Window> gaps = new ArrayList<>();
        gaps.add(new Window(from, to));
        for (WindowState state : windowStates) {
            removeWindows(gaps, state.from(), state.to());
        }
        for (Window loaded : loadedWindows) {
            removeWindows(gaps, loaded.from(), loaded.to());
        }
        removeWindowStates(cacheWindowStates, from, to);
        for (Window gap : gaps) {
            cacheWindowStates.add(new WindowState(gap.from(), gap.to(), SegmentState.ERROR));
        }
        rebuildGeometry();
        invalidate();
    }

    /** Sets the empty-window message supplied by S-01. */
    public void setEmptyMessage(CharSequence messageOrNull) {
        emptyMessage = messageOrNull;
        rebuildMessageLines();
        invalidate();
    }

    /** Sets the chart's only TalkBack node; chart content and markers have no virtual child nodes/actions. */
    public void setSummary(CharSequence contentDescription) {
        setContentDescription(contentDescription);
    }

    public ChartViewport getViewport() {
        return viewport;
    }

    public void setViewport(ChartViewport viewport) {
        ChartViewport clamped = ChartViewportRules.clamp(Objects.requireNonNull(viewport, "viewport"), now);
        if (!clamped.equals(this.viewport)) {
            stopFling();
        }
        this.viewport = clamped;
        viewportChanged(false);
        requestWindowIfMissing();
    }

    /** Restores the approved default time window and closes any marker tooltip. */
    public void resetToNow() {
        stopFling();
        dismissTooltip();
        viewport = ChartViewportRules.defaultViewport(now);
        viewportChanged(true);
        requestWindowIfMissing();
    }

    /** Drops in-memory history on foreground re-entry before re-reading the stable local-cache range. */
    public void clearHistoryData() {
        stopFling();
        dismissTooltip();
        readings.clear();
        entries.clear();
        basal.clear();
        loadedWindows.clear();
        preparedCacheRanges.clear();
        requestedWindows.clear();
        windowStates.clear();
        cacheWindowStates.clear();
        rebuildGeometry();
        invalidate();
        requestWindowIfMissing();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            notifyViewport();
            requestWindowIfMissing();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desired = getPaddingTop() + getPaddingBottom() + Math.round(dp(DEFAULT_HEIGHT_DP));
        int measuredHeight = resolveSize(desired, heightMeasureSpec);
        int measuredWidth = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildGeometry();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(surfaceColor);
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        drawAxes(canvas);
        drawWindowStates(canvas);
        drawThresholds(canvas);
        drawReadings(canvas);
        drawMarkers(canvas);
        drawCursor(canvas);
        drawEmptyMessage(canvas);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            stopFling();
            if (velocityTracker != null) {
                velocityTracker.clear();
            }
        }
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);
        scaleDetector.onTouchEvent(event);
        switch (action) {
            case MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0);
                downX = lastX = event.getX();
                downY = event.getY();
                touchedMarker = hitMarker(event.getX(), event.getY());
                markerDownX = event.getX();
                markerDownY = event.getY();
                dragging = false;
                scaling = false;
                yieldedToParent = false;
                // Claim the gesture; a vertical move hands it back to a scrolling parent in onMove.
                disallowParentIntercept(true);
                cursorActive = false;
                cursorIndex = -1;
                removeCallbacks(longPressStep);
                postDelayed(longPressStep, ViewConfiguration.getLongPressTimeout());
            }
            case MotionEvent.ACTION_POINTER_DOWN -> {
                cancelCursor();
                touchedMarker = null;
                scaling = true;
                yieldedToParent = false;
                disallowParentIntercept(true);
            }
            case MotionEvent.ACTION_MOVE -> onMove(event);
            case MotionEvent.ACTION_POINTER_UP -> onPointerUp(event);
            case MotionEvent.ACTION_UP -> onUp(event);
            case MotionEvent.ACTION_CANCEL -> {
                cancelCursor();
                endGesture();
                requestWindowIfMissing();
            }
            default -> {
            }
        }
        return true;
    }

    private void onMove(MotionEvent event) {
        int index = event.findPointerIndex(activePointerId);
        if (index < 0) {
            return;
        }
        float x = event.getX(index);
        if (cursorActive) {
            updateCursor(x);
            return;
        }
        if (Math.abs(x - downX) > touchSlop || Math.abs(event.getY(index) - downY) > touchSlop) {
            removeCallbacks(longPressStep);
        }
        if (event.getPointerCount() > 1 || scaleDetector.isInProgress()) {
            touchedMarker = null;
            scaling = true;
            lastX = x;
            return;
        }
        if (yieldedToParent) {
            return;
        }
        if (!dragging) {
            float dx = Math.abs(x - downX);
            float dy = Math.abs(event.getY(index) - downY);
            if (dx > touchSlop && dx >= dy) {
                dragging = true;
                touchedMarker = null;
            } else if (dy > touchSlop) {
                yieldedToParent = true;
                touchedMarker = null;
                disallowParentIntercept(false);
                return;
            }
        }
        if (dragging) {
            panByPixels(x - lastX);
            lastX = x;
        }
    }

    private void onPointerUp(MotionEvent event) {
        int upIndex = event.getActionIndex();
        if (event.getPointerId(upIndex) == activePointerId) {
            int newIndex = upIndex == 0 ? 1 : 0;
            activePointerId = event.getPointerId(newIndex);
            downY = event.getY(newIndex);
        }
        int activeIndex = event.findPointerIndex(activePointerId);
        if (activeIndex < 0) {
            return;
        }
        lastX = event.getX(activeIndex);
        downX = lastX;
        if (event.getPointerCount() == 2) {
            // The remaining finger continues the gesture as a pan without a jump.
            dragging = true;
        }
    }

    private void onUp(MotionEvent event) {
        removeCallbacks(longPressStep);
        if (cursorActive) {
            // Releasing a long-press cursor only hides it: no tooltip, no fling.
            cancelCursor();
            endGesture();
            requestWindowIfMissing();
            return;
        }
        if (!dragging && !scaling && !yieldedToParent && touchedMarker != null
                && Math.hypot(event.getX() - markerDownX, event.getY() - markerDownY) <= touchSlop) {
            showTooltip(touchedMarker);
        } else if (!dragging && !scaling && !yieldedToParent) {
            dismissTooltip();
        }
        float velocity = 0f;
        boolean mayFling = dragging && !scaling && velocityTracker != null;
        if (mayFling) {
            velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
            velocity = velocityTracker.getXVelocity(activePointerId);
        }
        endGesture();
        performClick();
        if (mayFling && Math.abs(velocity) >= minFlingVelocity) {
            if (startFling(velocity)) {
                return;
            }
        }
        requestWindowIfMissing();
    }

    /** Long-press cursor: a vertical line locked to the nearest reading, with a value and HH:mm bubble. */
    private void startCursor() {
        if (!isAttachedToWindow() || dragging || scaling || yieldedToParent || visiblePoints.isEmpty()
                || activePointerId == MotionEvent.INVALID_POINTER_ID) {
            return;
        }
        touchedMarker = null;
        cursorActive = true;
        dismissTooltip();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        disallowParentIntercept(true);
        updateCursor(lastX);
    }

    private void cancelCursor() {
        removeCallbacks(longPressStep);
        if (cursorActive || cursorIndex >= 0) {
            cursorActive = false;
            cursorIndex = -1;
            invalidate();
        }
    }

    private void updateCursor(float x) {
        float clamped = Math.max(plot.left, Math.min(plot.right, x));
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < visiblePoints.size(); i++) {
            float distance = Math.abs(visiblePoints.get(i).x() - clamped);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        // Nothing within reach (a gap in the data): no cursor, so the bubble never shows a distant reading.
        cursorIndex = bestDistance <= dp(36) ? best : -1;
        invalidate();
    }

    private void drawCursor(Canvas canvas) {
        if (!cursorActive || cursorIndex < 0 || cursorIndex >= visiblePoints.size()
                || cursorIndex >= visibleReadings.size()) {
            return;
        }
        ReadingPoint point = visiblePoints.get(cursorIndex);
        Reading reading = visibleReadings.get(cursorIndex);
        int onSurface = resolveColor(com.google.android.material.R.attr.colorOnSurface, 0xff000000);
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(dp(1));
        cursorPaint.setColor((onSurface & 0x00FFFFFF) | 0xB3000000);
        canvas.drawLine(point.x(), plot.top, point.x(), plot.bottom, cursorPaint);
        cursorPaint.setStrokeWidth(dp(2));
        cursorPaint.setColor(onSurface);
        canvas.drawCircle(point.x(), point.y(), dp(6), cursorPaint);

        String value = reading.sgv() + " " + getContext().getString(R.string.s01_unit_mgdl);
        String time = Formatters.clockTime(getContext(), reading.time(), zone);
        bubbleValuePaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18,
                getResources().getDisplayMetrics()));
        bubbleValuePaint.setColor(onSurface);
        bubbleTimePaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                getResources().getDisplayMetrics()));
        bubbleTimePaint.setColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, onSurface));
        bubbleValuePaint.setTypeface(labelPaint.getTypeface());
        bubbleTimePaint.setTypeface(labelPaint.getTypeface());
    
        float textWidth = Math.max(bubbleValuePaint.measureText(value), bubbleTimePaint.measureText(time));
        float width = Math.max(dp(96), textWidth + dp(32));
        bubbleValuePaint.getFontMetrics(valueMetrics);
        bubbleTimePaint.getFontMetrics(timeMetrics);
        float valueHeight = valueMetrics.descent - valueMetrics.ascent;
        float timeHeight = timeMetrics.descent - timeMetrics.ascent;
        float height = dp(24) + valueHeight + timeHeight;
        float left = Math.max(dp(8), Math.min(point.x() - width / 2f, getWidth() - dp(8) - width));
        float top = dp(8);
        if (point.y() < top + height + dp(14)) {
            // A high reading would sit under the bubble: show the bubble below the point instead.
            float below = point.y() + dp(14);
            if (below + height <= getHeight() - dp(8)) {
                top = below;
            }
        }
        reusableRect.set(left, top, left + width, top + height);
        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setColor(tooltipFillColor());
        canvas.drawRoundRect(reusableRect, dp(12), dp(12), bubblePaint);
        bubblePaint.setStyle(Paint.Style.STROKE);
        bubblePaint.setStrokeWidth(Math.max(1, dp(1)));
        bubblePaint.setColor(tooltipStrokeColor());
        canvas.drawRoundRect(reusableRect, dp(12), dp(12), bubblePaint);
        bubbleValuePaint.setTextAlign(Paint.Align.CENTER);
        bubbleTimePaint.setTextAlign(Paint.Align.CENTER);
        float centerX = left + width / 2f;
        canvas.drawText(value, centerX, top + dp(12) - valueMetrics.ascent, bubbleValuePaint);
        canvas.drawText(time, centerX, top + dp(12) + valueHeight - timeMetrics.ascent, bubbleTimePaint);
    }

    private int tooltipFillColor() {
        return ContextCompat.getColor(getContext(), app.lightscout.ui.core.R.color.glass_surface_container_high);
    }

    private int tooltipStrokeColor() {
        return ContextCompat.getColor(getContext(), app.lightscout.ui.core.R.color.glass_hero_border);
    }

    private void endGesture() {
        touchedMarker = null;
        dragging = false;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    /** Pans so the content follows the finger: a positive (rightward) move shows older readings. */
    private void panByPixels(float dxPx) {
        float plotWidth = Math.max(1f, plot.width());
        long movementMs = Math.round(viewport.span().toMillis() * (double) dxPx / plotWidth);
        if (movementMs == 0) {
            return;
        }
        ChartViewport before = viewport;
        viewport = ChartViewportRules.pan(viewport, Duration.ofMillis(movementMs), now);
        if (!viewport.equals(before)) {
            viewportChanged(true);
            requestWindowIfMissing();
        }
    }

    private boolean startFling(float velocityX) {
        flingMillisPerPx = viewport.span().toMillis() / (double) Math.max(1f, plot.width());
        Instant oldestEnd = now.minus(ChartViewportRules.MAX_HISTORY).plus(viewport.span());
        int olderPx = boundPixels(Duration.between(oldestEnd, viewport.end()));
        int newerPx = boundPixels(Duration.between(viewport.end(), now));
        if ((velocityX > 0 && olderPx == 0) || (velocityX < 0 && newerPx == 0)) {
            return false;
        }
        flingStartEnd = viewport.end();
        flingLastX = 0;
        flingActive = true;
        flingScroller.fling(0, 0, Math.round(velocityX), 0, -newerPx, olderPx, 0, 0);
        postOnAnimation(flingStep);
        return true;
    }

    private int boundPixels(Duration distance) {
        long millis = Math.max(0L, distance.toMillis());
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.ceil(millis / flingMillisPerPx));
    }

    private void stepFling() {
        if (!isShown()) {
            stopFling();
            return;
        }
        if (!flingScroller.computeScrollOffset()) {
            finishFling();
            return;
        }
        int x = flingScroller.getCurrX();
        ChartViewport before = viewport;
        viewport = ChartViewportRules.pan(new ChartViewport(flingStartEnd, viewport.span()),
                Duration.ofMillis(Math.round(x * flingMillisPerPx)), now);
        if (!viewport.equals(before)) {
            viewportChanged(true);
            requestWindowIfMissing();
        } else if (x != flingLastX) {
            // The clamp held the viewport: the fling reached now or the 7-day limit.
            finishFling();
            return;
        }
        flingLastX = x;
        postOnAnimation(flingStep);
    }

    private void stopFling() {
        flingScroller.forceFinished(true);
        removeCallbacks(flingStep);
        flingActive = false;
    }

    private void finishFling() {
        stopFling();
        requestWindowIfMissing();
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (isChartActive()) {
            requestWindowIfMissing();
        } else {
            stopFling();
            dismissTooltip();
            cancelWindowRequest();
        }
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (isChartActive()) {
            requestWindowIfMissing();
        } else {
            stopFling();
            dismissTooltip();
            cancelWindowRequest();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isChartActive()) {
            requestWindowIfMissing();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopFling();
        dismissTooltip();
        cancelWindowRequest();
        super.onDetachedFromWindow();
    }

    /** Height of a digit in the caption font; captions are placed by it so gaps are measured from the glyphs. */
    private float digitHeightPx() {
        labelPaint.getTextBounds("0", 0, 1, digitBounds);
        return -digitBounds.top;
    }

    /** Space under the plot: 5 dp gap, the hour captions' digit height and the 16 dp card inset below them. */
    private float plotBottomInsetPx() {
        return dp(16) + dp(5) + digitHeightPx();
    }

    /** Plot rectangle in view pixels (layout fact for device tests). */
    android.graphics.RectF plotBounds() {
        return new android.graphics.RectF(plot);
    }

    /** Top of the glucose area, below the icon lane (layout fact for device tests). */
    float readingTopPx() {
        return readingTop;
    }

    /** Bottom of the glucose area, above the icon lane (layout fact for device tests). */
    float readingBottomPx() {
        return readingBottom;
    }

    /** Left plot edge: the 16 dp card inset, then room for the widest caption ("600") and a 5 dp gap. */
    private float plotLeftPx() {
        return dp(PLOT_LEFT_DP) + labelPaint.measureText("000") + dp(5);
    }

    private void rebuildGeometry() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        float left = plotLeftPx();
        float right = Math.max(left + dp(40), getWidth() - dp(PLOT_RIGHT_DP));
        float top = dp(16) + digitHeightPx() + dp(5);
        float bottom = Math.max(top + dp(40), getHeight() - plotBottomInsetPx());
        plot.set(left, top, right, bottom);
        visibleReadings.clear();
        Instant start = viewport.end().minus(viewport.span());
        for (Reading reading : readings) {
            if (!reading.time().isBefore(start) && reading.time().isBefore(viewport.end().plusNanos(1))) {
                visibleReadings.add(reading);
            }
        }
        scale = GlucoseRendering.scaleFor(visibleReadings);
        visiblePoints.clear();
        // Fixed icon lanes: a band at the top (bolus, site, sensor, pump) and one at the bottom (carbs); the glucose
        // scale maps into what is left, 4 dp clear of the 24 dp icons. Bands shrink on very short charts so the
        // glucose area keeps at least 60 % of the plot.
        laneBand = Math.min(dp(34f), plot.height() * 0.2f);
        readingTop = plot.top + laneBand;
        readingBottom = plot.bottom - laneBand;
        for (Reading reading : visibleReadings) {
            visiblePoints.add(new ReadingPoint(reading.sgv(), xFor(reading.time()),
                    scale.y(reading.sgv(), readingTop, readingBottom), readingColor(reading.sgv())));
        }
        if (cursorActive && cursorIndex >= visiblePoints.size()) {
            cursorIndex = -1;
        }
        visibleMarkers.clear();
        for (Entry entry : entries) {
            if (isMarker(entry) && !entry.time().isBefore(start) && !entry.time().isAfter(viewport.end())) {
                boolean bgPoint = entry.type() == EntryType.BG_CHECK && entry.glucose() != null;
                // A blood-glucose check is a red dot at its value, shifted 15 minutes later so it does not sit on
                // the CGM dot of the same moment; its drop icon sits in the bottom lane, next to the carbs icons.
                float x = bgPoint ? Math.min(plot.right, xFor(entry.time().plus(BG_CHECK_SHIFT)))
                        : xFor(entry.time());
                float circleY = bgPoint ? scale.y(entry.glucose(), readingTop, readingBottom) : Float.NaN;
                float y = bgPoint || entry.type() == EntryType.CARBS ? plot.bottom - laneBand / 2f
                        : plot.top + laneBand / 2f;
                float target = ChartMarkerHitAreas.minimumSizePx(density);
                visibleMarkers.add(new Marker(entry, x, y, circleY, new ChartMarkerHitAreas.Target(entry, x, y,
                        target, target)));
            }
        }
        windowDraws.clear();
        for (WindowState state : windowStates) {
            float windowLeft = Math.max(plot.left, xFor(state.from()));
            float windowRight = Math.min(plot.right, xFor(state.to()));
            if (state.state() == SegmentState.LOADING) {
                unpreparedCacheRanges.clear();
                findUnpreparedCacheRanges(state.from(), state.to(), unpreparedCacheRanges);
                for (Window gap : unpreparedCacheRanges) {
                    windowDraws.add(new WindowDraw(SegmentState.LOADING,
                            Math.max(plot.left, xFor(gap.from())), Math.min(plot.right, xFor(gap.to())),
                            true, false));
                }
                windowDraws.add(new WindowDraw(SegmentState.LOADING, windowLeft, windowRight, false, true));
            } else {
                windowDraws.add(new WindowDraw(state.state(), windowLeft, windowRight, true, false));
            }
        }
        for (WindowState state : cacheWindowStates) {
            windowDraws.add(new WindowDraw(state.state(), Math.max(plot.left, xFor(state.from())),
                    Math.min(plot.right, xFor(state.to())), true, false));
        }
        rebuildTicks();
        rebuildMessageLines();
        if (tooltip != null && tooltip.isShowing()) {
            dismissTooltip();
        }
    }

    private void rebuildTicks() {
        dayTicks.clear();
        hourTicks.clear();
        for (Instant boundary : ChartTimeAxis.dayBoundaries(viewport, zone)) {
            dayTicks.add(new DayTick(xFor(boundary), ChartTimeAxis.dayLabel(boundary, zone,
                    getResources().getConfiguration().getLocales().get(0),
                    getContext().getString(R.string.s01_chart_day_label))));
        }
        float minimumTickSpacing = labelPaint.measureText("00") + dp(6);
        for (ChartTimeAxis.HourTick tick : ChartTimeAxis.nonOverlappingHourTicks(
                viewport, zone, plot.left, plot.width(), minimumTickSpacing)) {
            hourTicks.add(new HourTick(tick.x(), tick.label()));
        }
    }

    /** Faint dashed reference lines: every 50 mg/dL in the visible scale and every full hour in the viewport. */
    private void drawReferenceGrid(Canvas canvas) {
        int first = (int) (Math.ceil(scale.minMgdl() / 50.0) * 50);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        for (int value = first; value <= scale.maxMgdl(); value += 50) {
            float y = scale.y(value, readingTop, readingBottom);
            canvas.drawText(Integer.toString(value), plot.left - dp(5), y + labelPaint.getTextSize() / 3f,
                    labelPaint);
            if (value == lowMgdl || value == highMgdl) {
                continue;   // the labelled guide is drawn there
            }
            canvas.drawLine(plot.left, y, plot.right, y, subtleGridPaint);
        }
        labelPaint.setTextAlign(Paint.Align.CENTER);
        Instant start = viewport.end().minus(viewport.span());
        ZonedDateTime hour = start.atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        if (hour.toInstant().isBefore(start)) {
            hour = hour.plusHours(1);
        }
        for (int guard = 0; guard < 96 && !hour.toInstant().isAfter(viewport.end()); guard++) {
            if (hour.getHour() != 0) {   // midnight already has the day divider
                float x = xFor(hour.toInstant());
                if (x >= plot.left && x <= plot.right) {
                    canvas.drawLine(x, plot.top, x, plot.bottom, subtleGridPaint);
                }
            }
            hour = hour.plusHours(1);
        }
    }

    private void drawAxes(Canvas canvas) {
        drawBeyondGuides(canvas);
        drawReferenceGrid(canvas);
        float axisBottom = plot.bottom;
        for (DayTick tick : dayTicks) {
            float x = tick.x();
            canvas.drawLine(x, plot.top, x, axisBottom, gridPaint);
            float width = labelPaint.measureText(tick.label());
            float center = Math.min(plot.right - width / 2, x + width / 2 + dp(3));
            canvas.drawText(tick.label(), center, dp(16) + digitHeightPx(), labelPaint);
        }
        float hourBaseline = plot.bottom + dp(5) + digitHeightPx();
        for (HourTick tick : hourTicks) {
            // 5 dp below the plot, the same gap the value captions keep from its left and right edges.
            canvas.drawText(tick.label(), tick.x(), hourBaseline, labelPaint);
        }
    }

    private void drawThresholds(Canvas canvas) {
        drawThreshold(canvas, lowMgdl);
        drawThreshold(canvas, highMgdl);
    }

    private void drawThreshold(Canvas canvas, int value) {
        float y = scale.y(value, readingTop, readingBottom);
        Paint guide = value == lowMgdl ? lowGuidePaint : highGuidePaint;
        canvas.drawLine(plot.left, y, plot.right, y, guide);
        int labelColor = labelPaint.getColor();
        labelPaint.setColor(guide.getColor());
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(value == lowMgdl ? lowLabel : highLabel, plot.right + dp(5),
                y + labelPaint.getTextSize() / 3f, labelPaint);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(labelColor);
    }

    /**
     * Slight translucent tint over the High guide and under the Low guide, in the guide colours: a gradient from that
     * colour at the guide to full transparency at the far edge of the plot.
     */
    private void drawBeyondGuides(Canvas canvas) {
        float highY = Math.max(plot.top, Math.min(plot.bottom, scale.y(highMgdl, readingTop, readingBottom)));
        float lowY = Math.max(plot.top, Math.min(plot.bottom, scale.y(lowMgdl, readingTop, readingBottom)));
        int high = (highGuidePaint.getColor() & 0x00FFFFFF) | 0x17000000;
        int low = (lowGuidePaint.getColor() & 0x00FFFFFF) | 0x1C000000;
        if (highY > plot.top) {
            beyondPaint.setShader(new LinearGradient(0, highY, 0, plot.top, high, high & 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(plot.left, plot.top, plot.right, highY, beyondPaint);
        }
        if (lowY < plot.bottom) {
            beyondPaint.setShader(new LinearGradient(0, lowY, 0, plot.bottom, low, low & 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(plot.left, lowY, plot.right, plot.bottom, beyondPaint);
        }
        beyondPaint.setShader(null);
    }

    private void drawReadings(Canvas canvas) {
        for (ReadingPoint point : visiblePoints) {
            pointPaint.setColor(point.color());
            pointPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(point.x(), point.y(), dp(2), pointPaint);
        }
    }

    private int markerTint(EntryType type) {
        if (type == EntryType.PUMP_ALERT) {
            return palette.pumpAlert();
        }
        if (type == EntryType.SITE_CHANGE || type == EntryType.SENSOR_CHANGE) {
            return ContextCompat.getColor(getContext(), app.lightscout.ui.core.R.color.glass_marker_site_sensor);
        }
        if (type == EntryType.CARBS) {
            return ContextCompat.getColor(getContext(), app.lightscout.ui.core.R.color.glass_marker_carbs);
        }
        if (type == EntryType.BG_CHECK) {
            return ContextCompat.getColor(getContext(), app.lightscout.ui.core.R.color.glass_marker_bg);
        }
        return palette.marker();
    }

    private void drawMarkers(Canvas canvas) {
        for (Marker marker : visibleMarkers) {
            if (!Float.isNaN(marker.circleY())) {
                pointPaint.setStyle(Paint.Style.FILL);
                pointPaint.setColor(markerTint(EntryType.BG_CHECK));
                canvas.drawCircle(marker.x(), marker.circleY(), dp(BG_DOT_RADIUS_DP), pointPaint);
            }
            int index = markerIndex(marker.entry().type());
            Drawable drawable = markerDrawables[index];
            if (drawable == null) {
                continue;
            }
            drawable.setTint(markerTint(marker.entry().type()));
            // The carbs glyph is dense; drawn at 20 dp it carries the same visual weight as the lighter icons.
            int half = Math.round(dp(marker.entry().type() == EntryType.CARBS ? CARBS_ICON_DP : MARKER_ICON_DP) / 2f);
            drawable.setBounds(Math.round(marker.x() - half), Math.round(marker.y() - half),
                    Math.round(marker.x() + half), Math.round(marker.y() + half));
            drawable.draw(canvas);
        }
    }

    private void drawWindowStates(Canvas canvas) {
        for (WindowDraw state : windowDraws) {
            float left = state.left();
            float right = state.right();
            if (right <= left) {
                continue;
            }
            if (state.drawShade()) {
                reusableRect.set(left, plot.top, right, plot.bottom);
                canvas.drawRect(reusableRect, placeholderPaint);
            }
            if (state.state() == SegmentState.LOADING && state.drawIndicator()) {
                float cx = (left + right) / 2f;
                float cy = (plot.top + plot.bottom) / 2f;
                placeholderPaint.setStyle(Paint.Style.STROKE);
                placeholderPaint.setStrokeWidth(dp(2));
                canvas.drawArc(cx - dp(10), cy - dp(10), cx + dp(10), cy + dp(10),
                        (SystemClock.uptimeMillis() / 8L) % 360, 260, false, placeholderPaint);
                placeholderPaint.setStyle(Paint.Style.FILL);
                postInvalidateOnAnimation();
            } else if (state.state() == SegmentState.OFFLINE) {
                drawCenteredLines(canvas, offlineLines, (left + right) / 2f,
                        (plot.top + plot.bottom) / 2f, messagePaint);
            } else {
                drawCenteredLines(canvas, errorLines, (left + right) / 2f,
                        (plot.top + plot.bottom) / 2f, messagePaint);
            }
        }
    }

    private void drawEmptyMessage(Canvas canvas) {
        if (!visibleReadings.isEmpty() || emptyMessage == null || emptyMessage.length() == 0) {
            return;
        }
        drawCenteredLines(canvas, emptyLines, plot.centerX(), plot.centerY(), messagePaint);
    }

    private void rebuildMessageLines() {
        float maximumWidth = Math.max(dp(24), plot.width() - dp(16));
        offlineLines = wrapLines(offlineLabel, maximumWidth, messagePaint);
        errorLines = wrapLines(getContext().getString(R.string.s01_chart_older_error), maximumWidth, messagePaint);
        emptyLines = emptyMessage == null ? List.of()
                : wrapLines(emptyMessage.toString(), maximumWidth, messagePaint);
    }

    private static List<String> wrapLines(String text, float maximumWidth, Paint paint) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        ArrayList<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && paint.measureText(candidate) > maximumWidth) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return List.copyOf(lines);
    }

    private static void drawCenteredLines(Canvas canvas, List<String> lines, float centerX, float centerY,
                                          Paint paint) {
        if (lines.isEmpty()) {
            return;
        }
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float lineHeight = metrics.descent - metrics.ascent;
        float firstBaseline = centerY - (lines.size() - 1) * lineHeight / 2f - (metrics.ascent + metrics.descent) / 2f;
        for (int i = 0; i < lines.size(); i++) {
            canvas.drawText(lines.get(i), centerX, firstBaseline + i * lineHeight, paint);
        }
    }

    private void showTooltip(Marker selected) {
        dismissTooltip();
        ArrayList<ChartMarkerHitAreas.Target> targets = new ArrayList<>();
        for (Marker marker : visibleMarkers) {
            targets.add(marker.target());
        }
        List<Entry> grouped = ChartMarkerHitAreas.overlappingEntries(targets, selected.target());
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(12));
        content.setMinimumWidth(dp(160));
        boolean first = true;
        for (Entry entry : grouped) {
            if (!first) {
                addTooltipDivider(content);
            }
            String value = tooltipValue(entry);
            String optionalValue = value.isEmpty() ? "" : " " + value;
            TextView row = tooltipText(getContext().getString(R.string.s01_tooltip_marker_row,
                    typeName(entry.type()), optionalValue,
                    Formatters.clockTime(getContext(), entry.time(), zone)));
            row.setSingleLine(entry.type() != EntryType.PUMP_ALERT);
            Drawable.ConstantState iconState = markerDrawables[markerIndex(entry.type())] == null ? null
                    : markerDrawables[markerIndex(entry.type())].getConstantState();
            if (iconState != null) {
                Drawable icon = iconState.newDrawable().mutate();
                icon.setTint(markerTint(entry.type()));
                icon.setBounds(0, 0, dp(20), dp(20));
                row.setCompoundDrawablesRelative(icon, null, null, null);
                row.setCompoundDrawablePadding(dp(8));
            }
            content.addView(row, wrapLayout());
            if (ENTRY_RULES.isEditable(entry)) {
                MaterialButton edit = new MaterialButton(getContext());
                edit.setText(R.string.s01_tooltip_edit);
                edit.setMinHeight(dp(48));
                edit.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
                edit.setTextColor(ContextCompat.getColor(getContext(), app.lightscout.ui.core.R.color.glass_accent));
                edit.setStrokeWidth(0);
                edit.setOnClickListener(v -> {
                    dismissTooltip();
                    if (listener != null) {
                        listener.onEditRequested(entry.id());
                    }
                });
                content.addView(edit, wrapLayout());
            }
            first = false;
        }
        // Rounded, outlined, slightly lighter than the chart card so the tooltip stands out (option A).
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(tooltipFillColor());
        background.setStroke(Math.max(1, dp(1)), tooltipStrokeColor());
        // Stay inside the chart card, with a delicate margin on both sides.
        int[] location = new int[2];
        getLocationOnScreen(location);
        int cardLeft = location[0];
        int cardRight = location[0] + getWidth();
        ViewParent parent = getParent();
        while (parent instanceof View) {
            View host = (View) parent;
            if (host instanceof com.google.android.material.card.MaterialCardView) {
                int[] hostLocation = new int[2];
                host.getLocationOnScreen(hostLocation);
                cardLeft = hostLocation[0];
                cardRight = hostLocation[0] + host.getWidth();
                break;
            }
            parent = host.getParent();
        }
        int margin = dp(8);
        int available = Math.max(dp(160), cardRight - cardLeft - 2 * margin);
        content.measure(View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int minLeft = cardLeft + margin;
        final int maxRight = cardRight - margin;
        final float markerScreenX = location[0] + selected.x();
        final int top = Math.max(0, location[1] + Math.round(selected.y() + dp(20)));
        int width = Math.min(content.getMeasuredWidth(), available);
        int left = Math.max(minLeft, Math.min(Math.round(markerScreenX - width / 2f), maxRight - width));
        tooltip = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        tooltip.setBackgroundDrawable(background);
        tooltip.setElevation(dp(6));
        tooltip.setOutsideTouchable(true);
        tooltip.setOnDismissListener(() -> {
            tooltip = null;
            requestFocus();
        });
        tooltip.showAtLocation(this, android.view.Gravity.TOP | android.view.Gravity.START, left, top);
        // Re-clamp with the real laid-out width (text can need more than the first measurement).
        content.post(() -> {
            if (tooltip == null || content.getWidth() <= 0) {
                return;
            }
            int actual = content.getWidth();
            int fixed = Math.max(minLeft, Math.min(Math.round(markerScreenX - actual / 2f), maxRight - actual));
            tooltip.update(fixed, top, -1, -1);
        });
    }

    private TextView tooltipText(CharSequence text) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setMaxWidth(dp(288));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, 0xff000000));
        view.setTypeface(labelPaint.getTypeface());
    
        return view;
    }

    private void addTooltipDivider(LinearLayout content) {
        View divider = new View(getContext());
        divider.setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorOutlineVariant,
                placeholderPaint.getColor()));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1)));
        params.topMargin = dp(4);
        params.bottomMargin = dp(4);
        content.addView(divider, params);
    }

    private String typeName(EntryType type) {
        return switch (type) {
            case BG_CHECK -> getContext().getString(R.string.s01_chart_type_bg_check);
            case CARBS -> getContext().getString(R.string.s01_chart_type_carbs);
            case BOLUS -> getContext().getString(R.string.s01_chart_type_bolus);
            case SITE_CHANGE -> getContext().getString(R.string.s01_chart_type_site_change);
            case SENSOR_CHANGE -> getContext().getString(R.string.s01_chart_type_sensor_change);
            case PUMP_ALERT -> getContext().getString(R.string.s01_chart_type_pump_alert);
            default -> "";
        };
    }

    private String tooltipValue(Entry entry) {
        return switch (entry.type()) {
            case BG_CHECK -> entry.glucose() == null ? ""
                    : getContext().getString(app.lightscout.ui.core.R.string.s04_value_mgdl, entry.glucose());
            case CARBS -> Formatters.entryValue(getContext(), entry);
            case BOLUS -> Formatters.entryValue(getContext(), entry);
            case PUMP_ALERT -> entry.text() == null ? "" : entry.text();
            default -> "";
        };
    }

    private void viewportChanged(boolean gesture) {
        rebuildGeometry();
        notifyViewport();
        invalidate();
    }

    private void notifyViewport() {
        if (listener != null) {
            listener.onViewportChanged(viewport, ChartViewportRules.followsNow(viewport, now));
        }
    }

    private void requestWindowIfMissing() {
        if (!isChartActive() || listener == null || windowRequestPosted || dragging || flingActive) {
            return;
        }
        Instant from = viewport.end().minus(viewport.span());
        Instant to = viewport.end();
        if (isCovered(from, to) || wasRequested(from, to)) {
            return;
        }
        windowRequestPosted = true;
        post(windowRequestStep);
    }

    private void dispatchWindowRequest() {
        windowRequestPosted = false;
        if (!isChartActive() || listener == null || dragging || flingActive) {
            return;
        }
        Instant from = viewport.end().minus(viewport.span());
        Instant to = viewport.end();
        if (!isCovered(from, to) && !wasRequested(from, to)) {
            addRequestedWindow(from, to);
            listener.onWindowNeeded(from, to);
        }
    }

    private boolean isChartActive() {
        return isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE;
    }

    private void cancelWindowRequest() {
        if (windowRequestPosted) {
            removeCallbacks(windowRequestStep);
            windowRequestPosted = false;
        }
    }

    private boolean isCovered(Instant from, Instant to) {
        for (Window window : loadedWindows) {
            if (!window.from().isAfter(from) && !window.to().isBefore(to)) {
                return true;
            }
        }
        return false;
    }

    private void findUnpreparedCacheRanges(Instant from, Instant to, ArrayList<Window> gaps) {
        Instant coveredTo = from;
        while (coveredTo.isBefore(to)) {
            Instant nextStart = to;
            Instant nextTo = coveredTo;
            for (Window window : preparedCacheRanges) {
                if (!window.from().isAfter(coveredTo) && window.to().isAfter(nextTo)) {
                    nextTo = window.to();
                } else if (window.from().isAfter(coveredTo) && window.from().isBefore(nextStart)) {
                    nextStart = window.from();
                }
            }
            if (nextTo.isAfter(coveredTo)) {
                coveredTo = nextTo.isAfter(to) ? to : nextTo;
            } else {
                gaps.add(new Window(coveredTo, nextStart));
                coveredTo = nextStart;
            }
        }
    }

    private boolean wasRequested(Instant from, Instant to) {
        for (Window window : requestedWindows) {
            if (!window.from().isAfter(from) && !window.to().isBefore(to)) {
                return true;
            }
        }
        return false;
    }

    private void addRequestedWindow(Instant from, Instant to) {
        requestedWindows.add(new Window(from, to));
    }

    private void clearWindowStates(Instant from, Instant to) {
        removeWindowStates(windowStates, from, to);
        removeWindowStates(cacheWindowStates, from, to);
        removeWindows(requestedWindows, from, to);
    }

    private static void removeWindowStates(ArrayList<WindowState> windows, Instant from, Instant to) {
        ArrayList<WindowState> remainder = new ArrayList<>();
        for (WindowState window : windows) {
            if (!window.from().isBefore(to) || !window.to().isAfter(from)) {
                remainder.add(window);
                continue;
            }
            if (window.from().isBefore(from)) {
                remainder.add(new WindowState(window.from(), from, window.state()));
            }
            if (window.to().isAfter(to)) {
                remainder.add(new WindowState(to, window.to(), window.state()));
            }
        }
        windows.clear();
        windows.addAll(remainder);
    }

    private static void removeWindows(ArrayList<Window> windows, Instant from, Instant to) {
        ArrayList<Window> remainder = new ArrayList<>();
        for (Window window : windows) {
            if (!window.from().isBefore(to) || !window.to().isAfter(from)) {
                remainder.add(window);
                continue;
            }
            if (window.from().isBefore(from)) {
                remainder.add(new Window(window.from(), from));
            }
            if (window.to().isAfter(to)) {
                remainder.add(new Window(to, window.to()));
            }
        }
        windows.clear();
        windows.addAll(remainder);
    }

    private static <T> void removeInRange(ArrayList<T> values, Instant from, Instant to,
                                          java.util.function.Function<T, Instant> time) {
        values.removeIf(value -> {
            Instant instant = time.apply(value);
            return instant != null && !instant.isBefore(from) && instant.isBefore(to);
        });
    }

    private Marker hitMarker(float x, float y) {
        for (Marker marker : visibleMarkers) {
            if (marker.target().contains(x, y)) {
                return marker;
            }
        }
        return null;
    }

    private void dismissTooltip() {
        if (tooltip != null && tooltip.isShowing()) {
            tooltip.dismiss();
        } else {
            tooltip = null;
        }
    }

    private boolean atRightEdge() {
        return Duration.between(viewport.end(), now).abs().compareTo(COVERAGE_TOLERANCE) <= 0;
    }

    private float xFor(Instant time) {
        double elapsed = Duration.between(viewport.end().minus(viewport.span()), time).toNanos();
        double fraction = elapsed / viewport.span().toNanos();
        return plot.left + (float) (fraction * plot.width());
    }

    private int readingColor(int value) {
        // Very low / very high share the Low / High colour: one dot style per side of the range.
        if (value <= lowMgdl) {
            return palette.low();
        }
        if (value >= highMgdl) {
            return palette.high();
        }
        return palette.inRange();
    }

    private static boolean isMarker(Entry entry) {
        return entry.type() != EntryType.TEMP_BASAL;
    }

    private int markerIndex(EntryType type) {
        return switch (type) {
            case BG_CHECK -> 0;
            case CARBS -> 1;
            case BOLUS -> 2;
            case SITE_CHANGE -> 3;
            case SENSOR_CHANGE -> 4;
            case PUMP_ALERT -> 5;
            default -> 0;
        };
    }

    private Drawable loadDrawable(int resource) {
        Drawable drawable = AppCompatResources.getDrawable(getContext(), resource);
        return drawable == null ? null : drawable.mutate();
    }

    private float dp(float value) {
        return value * density;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private int resolveColor(int attr, int fallback) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (!getContext().getTheme().resolveAttribute(attr, value, true)) {
            return fallback;
        }
        if (value.resourceId != 0) {
            return ContextCompat.getColor(getContext(), value.resourceId);
        }
        return value.data;
    }

    private float labelSmallTextSize(Context context) {
        return textAppearanceSize(context, com.google.android.material.R.attr.textAppearanceLabelSmall, 12);
    }

    private float textAppearanceSize(Context context, int appearanceAttribute, float fallbackSp) {
        TypedValue appearance = new TypedValue();
        if (context.getTheme().resolveAttribute(appearanceAttribute, appearance, true)
                && appearance.resourceId != 0) {
            TypedArray values = context.obtainStyledAttributes(appearance.resourceId,
                    new int[] {android.R.attr.textSize});
            try {
                return values.getDimension(0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fallbackSp,
                        getResources().getDisplayMetrics()));
            } finally {
                values.recycle();
            }
        }
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fallbackSp, getResources().getDisplayMetrics());
    }

    private static LinearLayout.LayoutParams wrapLayout() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private record Window(Instant from, Instant to) {
    }

    private record WindowState(Instant from, Instant to, SegmentState state) {
    }

    private record Marker(Entry entry, float x, float y, float circleY, ChartMarkerHitAreas.Target target) {
    }

    private record DayTick(float x, String label) {
    }

    private record HourTick(float x, String label) {
    }

    private record ReadingPoint(int sgv, float x, float y, int color) {
    }

    private record WindowDraw(SegmentState state, float left, float right, boolean drawShade, boolean drawIndicator) {
    }

    /** Listener contract consumed by CP-12 for history requests, chip state and guarded entry navigation. */
    public interface Listener {
        void onViewportChanged(ChartViewport viewport, boolean atNow);

        void onWindowNeeded(Instant from, Instant to);

        void onEditRequested(String entryId);
    }
}
