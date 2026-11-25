package app.lightscout.domain.alarm;

import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.settings.Settings;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Alarm decision table (CMP-02, IF-01; 06 §5.1 AL-1…AL-9, §5.2, §11 F2 / F12 / F20; CP-00A R-4, R-60).
 *
 * <p>Pure and stateless: the decision depends only on {@link AlarmInputs}. Rules applied:
 * <ul>
 *   <li>AL-1 glucose conditions on a reading at most 15 min old: Urgent low {@code sgv ≤ urgentLow}, Low
 *       {@code urgentLow < sgv ≤ low}, High {@code sgv ≥ high}. A future-dated low (M-9 amended) counts as age 0.
 *       No value bound applies (M-10).</li>
 *   <li>AL-2 No data when the data-loss status is {@link DataLossKind#DATA_LOSS}; Not running when the watchdog
 *       is stale.</li>
 *   <li>AL-3 a type is eligible when its condition holds, it is enabled (Low / High switches; others always),
 *       „Alarms” is on and it is not snoozed ({@code now < snoozedUntil}).</li>
 *   <li>AL-4 the ringing type keeps ringing ({@link AlarmCommand.Update}) even if its condition cleared;
 *       {@link AlarmCommand.Stop} is emitted only by the switch-off rule R-60.</li>
 *   <li>AL-6 priority = {@link AlarmType} declaration order; a higher eligible type replaces the ringing one.
 *       Pending is derived: a lower eligible type starts when nothing rings (e.g. after the higher one was
 *       snoozed).</li>
 *   <li>AL-5a (D-127 / D-128) a running snooze ends ({@link AlarmCommand.ClearSnooze}) when its condition is
 *       gone: glucose types on a fresh reading outside the type's range, No data when the data loss is over,
 *       Not running when the watchdog is fresh. A stale reading never clears. The cleared type is eligible in
 *       the same evaluation.</li>
 *   <li>AL-7 / AL-8: no hysteresis; snoozing Low does not suppress Urgent low.</li>
 *   <li>F12 {@code nextEvaluation} = earliest future instant among {@code lastReading.time + threshold}
 *       (first-use anchor + threshold when no reading) and every {@code snoozedUntil}.</li>
 * </ul>
 */
public final class AlarmEvaluator {

    /** AL-1: readings older than this never fire a glucose alarm. */
    private static final Duration MAX_GLUCOSE_AGE = Duration.ofMinutes(15);

    /**
     * Creates the stateless rule component.
     */
    public AlarmEvaluator() {
        // no state
    }

    /**
     * Evaluates the alarm decision table.
     *
     * @param in the inputs
     * @return the commands for IF-16 and the next evaluation instant
     */
    public AlarmDecision evaluate(AlarmInputs in) {
        Instant now = in.now();
        AlarmState state = in.state();
        Settings settings = in.settings();
        List<AlarmCommand> commands = new ArrayList<>();

        AlarmType active = state.activeType();
        if (active != null && isSwitchedOff(active, settings)) {
            commands.add(new AlarmCommand.Stop(active));
            active = null;
        }

        Set<AlarmType> cleared = EnumSet.noneOf(AlarmType.class);
        for (AlarmType type : AlarmType.values()) {
            if (isSnoozed(type, state, now) && conditionGone(type, in)) {
                cleared.add(type);
                commands.add(new AlarmCommand.ClearSnooze(type));
            }
        }

        AlarmType best = null;
        for (AlarmType type : AlarmType.values()) {
            if (isEligible(type, in, cleared)) {
                best = type;
                break;
            }
        }

        if (active == null) {
            if (best != null) {
                commands.add(new AlarmCommand.Start(best, view(best, in)));
            }
        } else if (best != null && best.ordinal() < active.ordinal()) {
            commands.add(new AlarmCommand.Replace(active, best, view(best, in)));
        } else {
            commands.add(new AlarmCommand.Update(view(active, in)));
        }

        return new AlarmDecision(commands, nextEvaluation(in, cleared));
    }

    /**
     * AL-5a: a snooze ends once its condition is gone. Glucose types need a fresh reading outside the type's
     * range (a stale reading proves nothing); No data needs the data loss to be over; Not running needs a fresh
     * watchdog.
     */
    private static boolean conditionGone(AlarmType type, AlarmInputs in) {
        return switch (type) {
            case URGENT_LOW, LOW, HIGH -> in.latest() != null && isFresh(in.latest(), in.now())
                    && glucoseReadingFor(type, in) == null;
            case NO_DATA, NOT_RUNNING -> !conditionHolds(type, in);
        };
    }

    /** R-60 / D-035: „Alarms” off stops every ringing type; Low / High off stops that type. */
    private static boolean isSwitchedOff(AlarmType type, Settings settings) {
        if (!settings.alarmsEnabled()) {
            return true;
        }
        return (type == AlarmType.LOW && !settings.lowEnabled())
                || (type == AlarmType.HIGH && !settings.highEnabled());
    }

    private static boolean isEligible(AlarmType type, AlarmInputs in, Set<AlarmType> cleared) {
        return conditionHolds(type, in)
                && !isSwitchedOff(type, in.settings())
                && (cleared.contains(type) || !isSnoozed(type, in.state(), in.now()));
    }

    private static boolean isSnoozed(AlarmType type, AlarmState state, Instant now) {
        AlarmTypeState typeState = state.perType().get(type);
        return typeState != null && typeState.snoozedUntil() != null && now.isBefore(typeState.snoozedUntil());
    }

    private static boolean conditionHolds(AlarmType type, AlarmInputs in) {
        return switch (type) {
            case URGENT_LOW, LOW, HIGH -> glucoseReadingFor(type, in) != null;
            case NO_DATA -> in.dataLoss().kind() == DataLossKind.DATA_LOSS;
            case NOT_RUNNING -> in.watchdogStale();
        };
    }

    /**
     * Returns the reading that satisfies the glucose condition of {@code type}, or {@code null}. The fresh latest
     * reading is checked first, then the future-dated low (evaluated as age 0).
     */
    private static Reading glucoseReadingFor(AlarmType type, AlarmInputs in) {
        Settings settings = in.settings();
        Reading latest = in.latest();
        if (latest != null && isFresh(latest, in.now()) && matches(type, latest.sgv(), settings)) {
            return latest;
        }
        Reading future = in.futureDatedLow();
        if (future != null && type != AlarmType.HIGH && future.sgv() <= settings.lowMgdl()
                && matches(type, future.sgv(), settings)) {
            return future;
        }
        return null;
    }

    private static boolean isFresh(Reading r, Instant now) {
        return r.time() != null && Duration.between(r.time(), now).compareTo(MAX_GLUCOSE_AGE) <= 0;
    }

    private static boolean matches(AlarmType type, int sgv, Settings settings) {
        return switch (type) {
            case URGENT_LOW -> sgv <= settings.urgentLowMgdl();
            case LOW -> sgv <= settings.lowMgdl() && sgv > settings.urgentLowMgdl();
            case HIGH -> sgv >= settings.highMgdl();
            case NO_DATA, NOT_RUNNING -> false;
        };
    }

    /**
     * Builds the view of a type. Glucose types show the triggering reading (or the latest reading once the
     * condition cleared); No data and Not running show no glucose value.
     */
    private static AlarmView view(AlarmType type, AlarmInputs in) {
        int snooze = in.settings().snoozeMinutes(type);
        Reading latest = in.latest();
        Instant latestTime = latest == null ? null : latest.time();
        return switch (type) {
            case URGENT_LOW, LOW, HIGH -> {
                Reading shown = glucoseReadingFor(type, in);
                if (shown == null) {
                    shown = latest;
                }
                yield shown == null
                        ? new AlarmView(type, null, Trend.UNKNOWN, null, null, null, null, snooze)
                        : new AlarmView(type, shown.sgv(), trendOf(shown), shown.time(), null, null, null, snooze);
            }
            case NO_DATA -> {
                DataLossStatus dataLoss = in.dataLoss();
                yield new AlarmView(type, null, Trend.UNKNOWN, latestTime,
                        dataLoss.cause(), null, dataLoss.since(), snooze);
            }
            case NOT_RUNNING -> new AlarmView(type, null, Trend.UNKNOWN, latestTime,
                    null, in.lastCheckCompleted(), null, snooze);
        };
    }

    private static Trend trendOf(Reading r) {
        return r.trend() == null ? Trend.UNKNOWN : r.trend();
    }

    /** F12: earliest future instant at which the decision may change, or {@code null}. */
    private static Instant nextEvaluation(AlarmInputs in, Set<AlarmType> cleared) {
        Instant now = in.now();
        Instant next = null;
        Duration threshold = in.settings().dataLossThreshold();
        Reading latest = in.latest();
        Instant anchor = latest != null ? latest.time() : firstUseAnchor(in.dataLoss());
        if (anchor != null) {
            next = earliestFuture(next, anchor.plus(threshold), now);
        }
        for (Map.Entry<AlarmType, AlarmTypeState> e : in.state().perType().entrySet()) {
            Instant until = e.getValue().snoozedUntil();
            if (until != null && !cleared.contains(e.getKey())) {
                next = earliestFuture(next, until, now);
            }
        }
        return next;
    }

    private static Instant firstUseAnchor(DataLossStatus dataLoss) {
        return dataLoss.kind() == DataLossKind.FIRST_USE ? dataLoss.since() : null;
    }

    private static Instant earliestFuture(Instant current, Instant candidate, Instant now) {
        if (!candidate.isAfter(now)) {
            return current;
        }
        return current == null || candidate.isBefore(current) ? candidate : current;
    }
}
