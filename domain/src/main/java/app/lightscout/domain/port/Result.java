package app.lightscout.domain.port;

import java.util.Objects;

/**
 * Result of a remote call (03 §6.2, CP-00A R-20). No checked exception crosses a port.
 *
 * <p>{@code ok() = false ∧ outcome() = OK} means the server answered and refused (semantic rejection);
 * {@link #detailCode()} then names the reason (R-50).
 *
 * @param <T> type of the value on success
 */
public final class Result<T> {

    private final boolean ok;
    private final T value;
    private final FetchOutcome outcome;
    private final String detailCode;

    private Result(boolean ok, T value, FetchOutcome outcome, String detailCode) {
        this.ok = ok;
        this.value = value;
        this.outcome = outcome;
        this.detailCode = detailCode;
    }

    /**
     * Creates a successful result with outcome {@link FetchOutcome#OK} and no detail code.
     *
     * @param value the value
     * @param <T>   value type
     * @return the result
     */
    public static <T> Result<T> ok(T value) {
        return new Result<>(true, value, FetchOutcome.OK, null);
    }

    /**
     * Creates a failed result; {@link #value()} is {@code null}.
     *
     * @param o          the outcome (non-null; {@link FetchOutcome#OK} for a semantic rejection)
     * @param detailCode one of the {@link DetailCode} values
     * @param <T>        value type
     * @return the result
     * @throws NullPointerException if {@code o} is null
     */
    public static <T> Result<T> fail(FetchOutcome o, String detailCode) {
        return new Result<>(false, null, Objects.requireNonNull(o, "outcome"), detailCode);
    }

    /**
     * Tells whether the call succeeded.
     *
     * @return {@code true} on success
     */
    public boolean ok() {
        return ok;
    }

    /**
     * Returns the value.
     *
     * @return the value on success, {@code null} on failure
     */
    public T value() {
        return value;
    }

    /**
     * Returns the outcome.
     *
     * @return {@link FetchOutcome#OK} on success, otherwise the failure outcome
     */
    public FetchOutcome outcome() {
        return outcome;
    }

    /**
     * Returns the detail code.
     *
     * @return a {@link DetailCode} value on failure, {@code null} when {@link #ok()}
     */
    public String detailCode() {
        return detailCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Result<?> other)) {
            return false;
        }
        return ok == other.ok && Objects.equals(value, other.value)
                && outcome == other.outcome && Objects.equals(detailCode, other.detailCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ok, value, outcome, detailCode);
    }

    @Override
    public String toString() {
        // No value in the text: values may be health data (SR-07).
        return "Result[ok=" + ok + ", outcome=" + outcome + ", detailCode=" + detailCode + "]";
    }
}
