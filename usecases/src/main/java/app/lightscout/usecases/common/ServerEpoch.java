package app.lightscout.usecases.common;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Server epoch (SS-06 IR-49; DS:A-23, R-22, R-71): advanced by the R-71 server reset while it clears ST-01. Cache
 * writes of operations that started before a reset (HistoryLoader fetches, EntryWriter cache updates on
 * {@code ioExecutor}) are dropped, so an old server's data never lands in the new server's cache. The check and the
 * write happen under the read lock and the reset's clears under the write lock, so neither can interleave. The
 * composition root shares one instance between {@code ConnectionStateReset}, the HistoryLoader and the EntryWriter.
 */
public final class ServerEpoch {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile long epoch;

    /**
     * Returns the current epoch; an operation captures it when it starts.
     *
     * @return the epoch
     */
    public long current() {
        return epoch;
    }

    /**
     * Runs a cache write only if no reset happened since {@code started}.
     *
     * @param started epoch captured when the operation started
     * @param write   the cache write (may throw {@code StorageException}, which propagates)
     * @return {@code true} when written, {@code false} when dropped
     */
    public boolean writeIfCurrent(long started, Runnable write) {
        lock.readLock().lock();
        try {
            if (epoch != started) {
                return false;
            }
            write.run();
            return true;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Runs a result callback only while the operation's server is still configured.
     *
     * @param started epoch captured when the operation began
     * @param action  callback or other result publication
     * @return {@code true} when the epoch was current
     */
    public boolean runIfCurrent(long started, Runnable action) {
        lock.readLock().lock();
        try {
            if (epoch != started) {
                return false;
            }
            action.run();
            return true;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Advances the epoch and runs the reset's clears while no guarded cache write can run.
     *
     * @param clears the clears
     */
    public void advance(Runnable clears) {
        lock.writeLock().lock();
        try {
            epoch++;
            clears.run();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
