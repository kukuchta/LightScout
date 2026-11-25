package app.lightscout.usecases.common;

import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;

/**
 * Server identity, version and permission policy applied by use cases (ADR-039, ADR-041; CP-02 §8.2 daily
 * maintenance and §8.6 checks 3, 5, 6; CP-00A R-50). Shared by SyncCycle (daily checks) and, later,
 * ConnectionService (CP-02b).
 */
public final class ServerPolicy {

    private static final int[] MIN_VERSION = {15, 0, 9};

    private ServerPolicy() {
    }

    /**
     * Checks the raw IF-31 answer: no parsable {@code version} → NOT_NIGHTSCOUT / {@code NO_VERSION};
     * {@code apiVersion} not 3.x → NOT_NIGHTSCOUT / {@code NOT_API_V3}; version &lt; 15.0.9 → SERVER_TOO_OLD /
     * {@code VERSION_TOO_OLD}.
     *
     * @param info the server info; nullable
     * @return ok(info) when acceptable, else a failure with outcome and detail code
     */
    public static Result<ServerInfo> checkServer(ServerInfo info) {
        int[] version = info == null ? null : parseVersion(info.version());
        if (version == null) {
            return Result.fail(FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NO_VERSION);
        }
        if (!isApiV3(info.apiVersion())) {
            return Result.fail(FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NOT_API_V3);
        }
        if (compare(version, MIN_VERSION) < 0) {
            return Result.fail(FetchOutcome.SERVER_TOO_OLD, DetailCode.VERSION_TOO_OLD);
        }
        return Result.ok(info);
    }

    /**
     * Checks IF-32 permissions: read = {@code r} on entries and treatments; write = {@code c}, {@code u} and
     * {@code d} on treatments.
     *
     * @param p the permissions; nullable
     * @return ok(p) when both are present, else PERMISSION_MISSING with {@code MISSING_READ} or
     *         {@code MISSING_WRITE} (the daily check turns the latter into {@code PERMISSION_WRITE_MISSING})
     */
    public static Result<Permissions> checkPermissions(Permissions p) {
        if (!hasRead(p)) {
            return Result.fail(FetchOutcome.PERMISSION_MISSING, DetailCode.MISSING_READ);
        }
        if (!hasWrite(p)) {
            return Result.fail(FetchOutcome.PERMISSION_MISSING, DetailCode.MISSING_WRITE);
        }
        return Result.ok(p);
    }

    /**
     * Read permission rule (C-12 check 5).
     *
     * @param p permissions; nullable
     * @return {@code true} when entries and treatments contain {@code r}
     */
    public static boolean hasRead(Permissions p) {
        return p != null && p.has(NsCollection.ENTRIES, 'r') && p.has(NsCollection.TREATMENTS, 'r');
    }

    /**
     * Write permission rule (C-12 check 6, D-018).
     *
     * @param p permissions; nullable
     * @return {@code true} when treatments contain {@code c}, {@code u} and {@code d}
     */
    public static boolean hasWrite(Permissions p) {
        return p != null && p.has(NsCollection.TREATMENTS, 'c') && p.has(NsCollection.TREATMENTS, 'u')
                && p.has(NsCollection.TREATMENTS, 'd');
    }

    /**
     * API v3 rule: {@code apiVersion} is „3” or starts with „3.”.
     *
     * @param apiVersion raw value; nullable
     * @return {@code true} for 3.x
     */
    public static boolean isApiV3(String apiVersion) {
        if (apiVersion == null) {
            return false;
        }
        String v = apiVersion.trim();
        return v.equals("3") || v.startsWith("3.");
    }

    /**
     * Parses the leading {@code major.minor.patch} numbers of a version string („15.0.6-dev” → 15, 0, 6;
     * missing parts are 0).
     *
     * @param version raw version; nullable
     * @return three numbers, or {@code null} when the string does not start with a number
     */
    public static int[] parseVersion(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.trim().split("\\.", -1);
        int[] out = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            String digits = leadingDigits(parts[i]);
            if (digits.isEmpty()) {
                if (i == 0) {
                    return null;
                }
                break;
            }
            out[i] = Integer.parseInt(digits);
            if (digits.length() < parts[i].length()) {
                break;
            }
        }
        return out;
    }

    private static String leadingDigits(String s) {
        int n = 0;
        while (n < s.length() && n < 9 && Character.isDigit(s.charAt(n))) {
            n++;
        }
        return s.substring(0, n);
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }
}
