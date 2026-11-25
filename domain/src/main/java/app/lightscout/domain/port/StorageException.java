package app.lightscout.domain.port;

/**
 * The one unchecked failure signalled by store ports (CP-00A R-20, ADR-070).
 *
 * <p>Stores catch every SQLite / I/O / Keystore error, log {@code STORAGE_ERROR:<op>} and throw this.
 * Use cases map it to {@link FetchOutcome#STORAGE_ERROR} (detail {@link DetailCode#STORAGE}).
 */
public final class StorageException extends RuntimeException {

    /** Serialization version. */
    private static final long serialVersionUID = 1L;

    /** The failed store operation name. */
    private final String op;

    /**
     * Creates the exception.
     *
     * @param op    the failed store operation name (fixed vocabulary, no values — SR-07)
     * @param cause the underlying error; may be {@code null}
     */
    public StorageException(String op, Throwable cause) {
        super(op, cause);
        this.op = op;
    }

    /**
     * Returns the failed operation name.
     *
     * @return the operation name
     */
    public String op() {
        return op;
    }
}
