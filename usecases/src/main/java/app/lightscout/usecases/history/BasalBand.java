package app.lightscout.usecases.history;

import app.lightscout.domain.model.BasalSegment;
import app.lightscout.domain.model.BasalStep;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Profile;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Basal band of a chart window (ADR-031, CP-00A R-26): only from {@code TEMP_BASAL} entries with a non-null rate;
 * the profile is the baseline in the gaps of the window only when at least one temp segment exists; otherwise the
 * band is empty (hidden — D-017). A missing profile ({@code latestProfile} ok(null) — Q-35) means no baseline.
 */
final class BasalBand {

    private static final int SECONDS_PER_DAY = 86_400;
    private static final int MAX_PROFILE_SEGMENTS = 10_000;

    private BasalBand() {
    }

    /**
     * Computes the band for {@code [from, to)}.
     *
     * @param entries entries around the window (other types are ignored)
     * @param profile the latest profile; nullable
     * @param from    inclusive start
     * @param to      exclusive end
     * @return segments sorted by start; empty when no temp basal touches the window
     */
    static List<BasalSegment> compute(List<Entry> entries, Profile profile, Instant from, Instant to) {
        List<Entry> temps = new ArrayList<>();
        for (Entry e : entries) {
            if (e != null && e.type() == EntryType.TEMP_BASAL && e.basalRateMilli() != null
                    && e.durationMin() != null && e.durationMin() > 0 && e.time() != null) {
                temps.add(e);
            }
        }
        temps.sort(Comparator.comparing(Entry::time));
        List<BasalSegment> segments = new ArrayList<>();
        for (int i = 0; i < temps.size(); i++) {
            Entry t = temps.get(i);
            Instant end = t.time().plus(Duration.ofMinutes(t.durationMin()));
            if (i + 1 < temps.size() && temps.get(i + 1).time().isBefore(end)) {
                end = temps.get(i + 1).time();   // a later temp basal replaces a running one
            }
            addClipped(segments, t.time(), end, t.basalRateMilli(), false, from, to);
        }
        if (segments.isEmpty() || profile == null || profile.basal().isEmpty()) {
            return List.copyOf(segments);
        }
        List<BasalSegment> all = new ArrayList<>(segments);
        Instant cursor = from;
        for (BasalSegment s : segments) {
            if (s.start().isAfter(cursor)) {
                fillFromProfile(all, profile, cursor, s.start());
            }
            Instant end = s.start().plus(s.duration());
            if (end.isAfter(cursor)) {
                cursor = end;
            }
        }
        if (cursor.isBefore(to)) {
            fillFromProfile(all, profile, cursor, to);
        }
        all.sort(Comparator.comparing(BasalSegment::start));
        return List.copyOf(all);
    }

    private static void addClipped(List<BasalSegment> out, Instant start, Instant end, int rate, boolean fromProfile,
                                   Instant from, Instant to) {
        Instant s = start.isBefore(from) ? from : start;
        Instant e = end.isAfter(to) ? to : end;
        if (e.isAfter(s)) {
            out.add(new BasalSegment(s, Duration.between(s, e), rate, fromProfile));
        }
    }

    /** Profile basal steps (wall-clock seconds of day in the profile zone) over {@code [a, b)}. */
    private static void fillFromProfile(List<BasalSegment> out, Profile profile, Instant a, Instant b) {
        List<BasalStep> steps = new ArrayList<>();
        for (BasalStep s : profile.basal()) {
            if (s != null && s.startSecondOfDay() >= 0 && s.startSecondOfDay() < SECONDS_PER_DAY) {
                steps.add(s);
            }
        }
        if (steps.isEmpty()) {
            return;
        }
        steps.sort(Comparator.comparingInt(BasalStep::startSecondOfDay));
        ZoneId zone = profile.zone() != null ? profile.zone() : ZoneOffset.UTC;
        Instant t = a;
        for (int guard = 0; t.isBefore(b) && guard < MAX_PROFILE_SEGMENTS; guard++) {
            ZonedDateTime zt = t.atZone(zone);
            int sec = zt.toLocalTime().toSecondOfDay();
            BasalStep current = steps.get(steps.size() - 1);   // before the first step: the previous day's last one
            BasalStep next = null;
            for (BasalStep s : steps) {
                if (s.startSecondOfDay() <= sec) {
                    current = s;
                } else {
                    next = s;
                    break;
                }
            }
            LocalDate date = zt.toLocalDate();
            Instant boundary = next != null
                    ? date.atTime(LocalTime.ofSecondOfDay(next.startSecondOfDay())).atZone(zone).toInstant()
                    : date.plusDays(1).atStartOfDay(zone).toInstant();
            if (!boundary.isAfter(t)) {
                boundary = t.plusSeconds(60);   // DST gap guard: always make progress
            }
            Instant end = boundary.isAfter(b) ? b : boundary;
            out.add(new BasalSegment(t, Duration.between(t, end), current.rateMilliPerHour(), true));
            t = end;
        }
    }
}
