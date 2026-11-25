/**
 * Local persistence of LightScout (CP-04, MOD-04): the SQLite cache {@code cache.db} (ST-01), the preference
 * stores {@code settings}, {@code connection}, {@code runtime} (ST-02…ST-04, credential-encrypted storage), the
 * device-protected {@code boot} preferences (ST-05) and the Keystore-protected token (ST-06).
 *
 * <p>The only public type is {@link app.lightscout.storage.StorageFactory}; {@code :app} builds the stores through
 * it and hands them to the rest of the app as {@code :domain} ports (CP-00 R-2). Every store signals failure with
 * the unchecked {@link app.lightscout.domain.port.StorageException} after logging {@code STORAGE_ERROR:<op>}
 * (CP-00A R-20). No store may be called on the main thread (ADR-024).
 */
package app.lightscout.storage;
