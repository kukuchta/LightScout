package app.lightscout.usecases;

import java.util.Objects;

/**
 * One S-06 result row (CP-00A R-10, R-73). Rows after the failing check are not delivered.
 *
 * @param row           the row
 * @param passed        ✓ when {@code true}, ✕ otherwise
 * @param failure       failure code when not passed; nullable
 * @param serverVersion raw server version for the VERSION row (and its failure text); nullable
 */
public record CheckResult(CheckRow row, boolean passed, CheckFailure failure, String serverVersion) {

    /**
     * Creates the row.
     *
     * @throws NullPointerException if {@code row} is null
     */
    public CheckResult {
        Objects.requireNonNull(row, "row");
    }
}
