package app.lightscout.widget;

import java.util.Arrays;
import java.util.Objects;

/**
 * Coalesces throttled widget updates, retaining the newest snapshot and all requested widget ids.
 */
final class WidgetRenderQueue<T> {

    private T snapshot;
    private int[] widgetIds;

    synchronized void offer(T newSnapshot, int[] newWidgetIds) {
        snapshot = Objects.requireNonNull(newSnapshot, "newSnapshot");
        if (newWidgetIds == null) {
            widgetIds = null;
            hasAllWidgets = true;
            return;
        }
        if (hasAllWidgets) {
            return;
        }
        if (widgetIds == null) {
            widgetIds = newWidgetIds.clone();
        } else {
            widgetIds = merge(widgetIds, newWidgetIds);
        }
    }

    synchronized void replaceSnapshotIfPending(T newSnapshot) {
        if (snapshot != null) {
            snapshot = Objects.requireNonNull(newSnapshot, "newSnapshot");
        }
    }

    private boolean hasAllWidgets;

    synchronized Request<T> poll() {
        if (snapshot == null) {
            return null;
        }
        Request<T> request = new Request<>(snapshot, widgetIds);
        snapshot = null;
        widgetIds = null;
        hasAllWidgets = false;
        return request;
    }

    private static int[] merge(int[] first, int[] second) {
        if (first == null) {
            return second.clone();
        }
        return Arrays.stream(concat(first, second)).distinct().toArray();
    }

    private static int[] concat(int[] first, int[] second) {
        int[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    record Request<T>(T snapshot, int[] widgetIds) {
        Request {
            widgetIds = widgetIds == null ? null : widgetIds.clone();
        }

        @Override
        public int[] widgetIds() {
            return widgetIds == null ? null : widgetIds.clone();
        }
    }
}
