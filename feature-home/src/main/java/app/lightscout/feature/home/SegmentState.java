package app.lightscout.feature.home;

/** State shown over a chart time window that has not produced readings. */
public enum SegmentState {
    LOADING,
    OFFLINE,
    ERROR
}
