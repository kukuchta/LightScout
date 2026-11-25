package app.lightscout.storage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderState;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.monitor.CollectionCursor;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.FetchOutcome;

/**
 * JSON form of the ST-04 runtime records ENT-07 ({@link MonitorState}), ENT-08 ({@link AlarmState}) and ENT-09
 * ({@link ReminderState}). One record = one preference value, so a {@code commit()} writes it atomically.
 *
 * <p>Instants are UTC epoch milliseconds (T-1); a {@code null} component is an absent key. Forward compatibility
 * (ADR-033): unknown keys are ignored, missing keys and unknown enum names read as the default of
 * {@link MonitorState#initial()} / {@link AlarmState#empty()} for that component.
 */
final class StateJson {

    private static final String MONITORING_SINCE = "monitoringSince";
    private static final String LAST_SUCCESSFUL_CHECK = "lastSuccessfulCheck";
    private static final String LAST_CHECK_COMPLETED = "lastCheckCompleted";
    private static final String LAST_OUTCOME = "lastOutcome";
    private static final String CONNECTION_PROBLEM = "connectionProblem";
    private static final String LAST_READING_TIME = "lastReadingTime";
    private static final String ARRIVAL_LAG_MEDIAN_SEC = "arrivalLagMedianSec";
    private static final String RECENT_LAG_SEC = "recentLagSec";
    /** D-152: marks lag samples taken from the server time; older states hold censored samples and are dropped. */
    private static final String LAG_SOURCE = "lagSource";
    private static final String LAG_SOURCE_SERVER = "server";
    private static final String LAST_DAILY_MAINTENANCE = "lastDailyMaintenance";
    private static final String CURSORS = "cursors";
    private static final String LAST_MODIFIED_SEEN = "lastModifiedSeen";
    private static final String HOT_WINDOW_FROM = "hotWindowFrom";
    private static final String LAST_RECONCILE = "lastReconcile";
    private static final String COMPLETE_FROM = "completeFrom";
    private static final String HISTORY_CAPABLE = "historyCapable";
    private static final String HISTORY_CURSOR_MS = "historyCursorMs";

    private static final String ACTIVE_TYPE = "activeType";
    private static final String PER_TYPE = "perType";
    private static final String SAVED_USER_ALARM_VOLUME = "savedUserAlarmVolume";
    private static final String RINGING_SINCE = "ringingSince";
    private static final String SNOOZED_UNTIL = "snoozedUntil";
    private static final String ESCALATION_STEP = "escalationStep";
    private static final String TRIGGER_READING_TIME = "triggerReadingTime";

    private static final String BASED_ON_ENTRY_ID = "basedOnEntryId";
    private static final String DUE_24H_AT = "due24hAt";
    private static final String DUE_AT = "dueAt";
    private static final String NOTIFIED_24H = "notified24h";
    private static final String NOTIFIED_AT = "notifiedAt";

    private StateJson() {
    }

    /**
     * @param s monitor state
     * @return JSON text
     * @throws JSONException never in practice (only finite numbers are written)
     */
    static String monitorState(MonitorState s) throws JSONException {
        JSONObject o = new JSONObject();
        putInstant(o, MONITORING_SINCE, s.monitoringSince());
        putInstant(o, LAST_SUCCESSFUL_CHECK, s.lastSuccessfulCheck());
        putInstant(o, LAST_CHECK_COMPLETED, s.lastCheckCompleted());
        o.put(LAST_OUTCOME, s.lastOutcome().name());
        if (s.connectionProblem() != null) {
            o.put(CONNECTION_PROBLEM, s.connectionProblem().name());
        }
        putInstant(o, LAST_READING_TIME, s.lastReadingTime());
        o.put(ARRIVAL_LAG_MEDIAN_SEC, s.arrivalLagMedianSec());
        JSONArray lags = new JSONArray();
        for (Integer lag : s.recentLagSec()) {
            lags.put(lag.intValue());
        }
        o.put(RECENT_LAG_SEC, lags);
        o.put(LAG_SOURCE, LAG_SOURCE_SERVER);
        putInstant(o, LAST_DAILY_MAINTENANCE, s.lastDailyMaintenance());
        JSONObject cursors = new JSONObject();
        for (Map.Entry<NsCollection, CollectionCursor> e : s.cursors().entrySet()) {
            CollectionCursor c = e.getValue();
            JSONObject co = new JSONObject();
            putLong(co, LAST_MODIFIED_SEEN, c.lastModifiedSeen());
            putInstant(co, HOT_WINDOW_FROM, c.hotWindowFrom());
            putInstant(co, LAST_RECONCILE, c.lastReconcile());
            putInstant(co, COMPLETE_FROM, c.completeFrom());
            co.put(HISTORY_CAPABLE, c.historyCapable());
            putLong(co, HISTORY_CURSOR_MS, c.historyCursorMs());
            cursors.put(e.getKey().name(), co);
        }
        o.put(CURSORS, cursors);
        return o.toString();
    }

    /**
     * @param json JSON text written by {@link #monitorState(MonitorState)} (this or a later version)
     * @return the monitor state
     * @throws JSONException if the text is not a JSON object
     */
    static MonitorState monitorState(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        MonitorState d = MonitorState.initial();
        FetchOutcome lastOutcome = enumOrNull(FetchOutcome.class, o, LAST_OUTCOME);
        List<Integer> lags = new ArrayList<>();
        // Samples written before D-152 were "tick start - reading time": censored by the schedule, they pinned the
        // median at 180 s. Drop them and start from the initial lag.
        boolean serverLag = LAG_SOURCE_SERVER.equals(o.optString(LAG_SOURCE, ""));
        JSONArray lagArray = serverLag ? o.optJSONArray(RECENT_LAG_SEC) : null;
        if (lagArray != null) {
            for (int i = 0; i < lagArray.length(); i++) {
                lags.add(lagArray.getInt(i));
            }
        }
        Map<NsCollection, CollectionCursor> cursors = new EnumMap<>(NsCollection.class);
        JSONObject cursorObject = o.optJSONObject(CURSORS);
        if (cursorObject != null) {
            for (NsCollection collection : NsCollection.values()) {
                JSONObject co = cursorObject.optJSONObject(collection.name());
                if (co != null) {
                    cursors.put(collection, new CollectionCursor(getLong(co, LAST_MODIFIED_SEEN),
                            getInstant(co, HOT_WINDOW_FROM), getInstant(co, LAST_RECONCILE),
                            getInstant(co, COMPLETE_FROM), co.optBoolean(HISTORY_CAPABLE, false),
                            getLong(co, HISTORY_CURSOR_MS)));
                }
            }
        }
        return new MonitorState(getInstant(o, MONITORING_SINCE), getInstant(o, LAST_SUCCESSFUL_CHECK),
                getInstant(o, LAST_CHECK_COMPLETED), lastOutcome == null ? d.lastOutcome() : lastOutcome,
                enumOrNull(FetchOutcome.class, o, CONNECTION_PROBLEM), getInstant(o, LAST_READING_TIME),
                serverLag && o.has(ARRIVAL_LAG_MEDIAN_SEC) ? o.getInt(ARRIVAL_LAG_MEDIAN_SEC)
                        : d.arrivalLagMedianSec(),
                lags, getInstant(o, LAST_DAILY_MAINTENANCE), cursors);
    }

    /**
     * @param s alarm state
     * @return JSON text
     * @throws JSONException never in practice
     */
    static String alarmState(AlarmState s) throws JSONException {
        JSONObject o = new JSONObject();
        if (s.activeType() != null) {
            o.put(ACTIVE_TYPE, s.activeType().name());
        }
        if (s.savedUserAlarmVolume() != null) {
            o.put(SAVED_USER_ALARM_VOLUME, s.savedUserAlarmVolume().intValue());
        }
        JSONObject perType = new JSONObject();
        for (Map.Entry<AlarmType, AlarmTypeState> e : s.perType().entrySet()) {
            AlarmTypeState t = e.getValue();
            JSONObject to = new JSONObject();
            putInstant(to, RINGING_SINCE, t.ringingSince());
            putInstant(to, SNOOZED_UNTIL, t.snoozedUntil());
            to.put(ESCALATION_STEP, t.escalationStep());
            putInstant(to, TRIGGER_READING_TIME, t.triggerReadingTime());
            perType.put(e.getKey().name(), to);
        }
        o.put(PER_TYPE, perType);
        return o.toString();
    }

    /**
     * @param json JSON text written by {@link #alarmState(AlarmState)}
     * @return the alarm state
     * @throws JSONException if the text is not a JSON object
     */
    static AlarmState alarmState(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        Map<AlarmType, AlarmTypeState> perType = new EnumMap<>(AlarmType.class);
        JSONObject perTypeObject = o.optJSONObject(PER_TYPE);
        if (perTypeObject != null) {
            for (AlarmType type : AlarmType.values()) {
                JSONObject to = perTypeObject.optJSONObject(type.name());
                if (to != null) {
                    perType.put(type, new AlarmTypeState(getInstant(to, RINGING_SINCE),
                            getInstant(to, SNOOZED_UNTIL), to.optInt(ESCALATION_STEP, 0),
                            getInstant(to, TRIGGER_READING_TIME)));
                }
            }
        }
        Integer volume = has(o, SAVED_USER_ALARM_VOLUME) ? o.getInt(SAVED_USER_ALARM_VOLUME) : null;
        return new AlarmState(enumOrNull(AlarmType.class, o, ACTIVE_TYPE), perType, volume);
    }

    /**
     * @param s reminder state
     * @return JSON text (the kind is part of the preference key)
     * @throws JSONException never in practice
     */
    static String reminderState(ReminderState s) throws JSONException {
        JSONObject o = new JSONObject();
        o.put(BASED_ON_ENTRY_ID, s.basedOnEntryId());
        putInstant(o, DUE_24H_AT, s.due24hAt());
        putInstant(o, DUE_AT, s.dueAt());
        o.put(NOTIFIED_24H, s.notified24h());
        o.put(NOTIFIED_AT, s.notifiedAt());
        return o.toString();
    }

    /**
     * @param kind consumable the state belongs to
     * @param json JSON text written by {@link #reminderState(ReminderState)}
     * @return the reminder state
     * @throws JSONException if the text is not a complete reminder state
     */
    static ReminderState reminderState(ConsumableKind kind, String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        return new ReminderState(kind, o.getString(BASED_ON_ENTRY_ID),
                Instant.ofEpochMilli(o.getLong(DUE_24H_AT)), Instant.ofEpochMilli(o.getLong(DUE_AT)),
                o.optBoolean(NOTIFIED_24H, false), o.optBoolean(NOTIFIED_AT, false));
    }

    private static void putInstant(JSONObject o, String key, Instant value) throws JSONException {
        if (value != null) {
            o.put(key, value.toEpochMilli());
        }
    }

    private static void putLong(JSONObject o, String key, Long value) throws JSONException {
        if (value != null) {
            o.put(key, value.longValue());
        }
    }

    private static boolean has(JSONObject o, String key) {
        return o.has(key) && !o.isNull(key);
    }

    private static Instant getInstant(JSONObject o, String key) throws JSONException {
        return has(o, key) ? Instant.ofEpochMilli(o.getLong(key)) : null;
    }

    private static Long getLong(JSONObject o, String key) throws JSONException {
        return has(o, key) ? o.getLong(key) : null;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, JSONObject o, String key) {
        if (!has(o, key)) {
            return null;
        }
        String name = o.optString(key, "");
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        return null;
    }
}
