package app.lightscout.ui.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import app.lightscout.domain.model.Reading;

/**
 * Pure geometry of the S-12 mini chart (C-S12-04): which readings are drawn and where.
 */
final class MiniChartGeometry {

    private MiniChartGeometry() {
    }

    /**
     * Readings inside {@code [from, to]}, sorted by time; {@code null} readings or times are skipped.
     *
     * @param readings input readings
     * @param from     window start
     * @param to       window end
     * @return the visible readings
     */
    static List<Reading> visible(List<Reading> readings, Instant from, Instant to) {
        List<Reading> out = new ArrayList<>();
        if (readings == null) {
            return out;
        }
        for (Reading r : readings) {
            if (r != null && r.time() != null && !r.time().isBefore(from) && !r.time().isAfter(to)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(Reading::time));
        return out;
    }

    /**
     * Maps a time to x linearly over the window.
     *
     * @param t     instant
     * @param from  window start (x = left)
     * @param to    window end (x = right)
     * @param left  left edge
     * @param right right edge
     * @return the x coordinate
     */
    static float x(Instant t, Instant from, Instant to, float left, float right) {
        long span = Math.max(1L, to.toEpochMilli() - from.toEpochMilli());
        float fraction = (float) (t.toEpochMilli() - from.toEpochMilli()) / (float) span;
        return left + fraction * (right - left);
    }

    /**
     * Polyline points {@code x0, y0, x1, y1, …} of the visible readings.
     *
     * @param visible readings from {@link #visible}
     * @param from    window start
     * @param to      window end
     * @param scale   y scale
     * @param left    plot left
     * @param top     plot top
     * @param right   plot right
     * @param bottom  plot bottom
     * @return the coordinates
     */
    static float[] points(List<Reading> visible, Instant from, Instant to, GlucoseScale scale,
                          float left, float top, float right, float bottom) {
        float[] xy = new float[visible.size() * 2];
        for (int i = 0; i < visible.size(); i++) {
            Reading r = visible.get(i);
            xy[2 * i] = x(r.time(), from, to, left, right);
            xy[2 * i + 1] = scale.y(r.sgv(), top, bottom);
        }
        return xy;
    }
}
