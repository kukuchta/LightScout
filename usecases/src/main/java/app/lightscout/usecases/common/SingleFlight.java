package app.lightscout.usecases.common;

import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.Result;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * GIR-1 duplicate handling for use cases with a callback (SS-06 rulings A-6, IR-47, IR-48). Per key (an entry
 * identifier, or one constant for S-06 verify) operations run one at a time on the given executor:
 * <ul>
 *   <li>a call with the <b>same arguments</b> as a queued or running operation joins it: every callback receives the
 *       same {@code Result} exactly once and the work runs once;</li>
 *   <li>a call with different arguments is serialised: it runs after the operations before it completed;</li>
 *   <li>a throwing callback never starves the other waiters; a throwing operation, or an executor that rejects the
 *       task, gives every waiter the configured failure result (never a success value) and leaves no stale entry.</li>
 * </ul>
 *
 * @param <T> result value type
 */
public final class SingleFlight<T> {

    private final Executor executor;
    private final Result<T> unexpectedFailure;
    private final Map<Object, Deque<Op<T>>> queues = new HashMap<>();

    /**
     * Creates the coordinator.
     *
     * @param executor          executor the operations and callbacks run on
     * @param unexpectedFailure result given to waiters when the operation throws or cannot be scheduled
     */
    public SingleFlight(Executor executor, Result<T> unexpectedFailure) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.unexpectedFailure = Objects.requireNonNull(unexpectedFailure, "unexpectedFailure");
    }

    private static final class Op<T> {
        final Object args;
        final Supplier<Result<T>> work;
        final List<Callback<T>> waiters = new ArrayList<>();

        Op(Object args, Supplier<Result<T>> work) {
            this.args = args;
            this.work = work;
        }
    }

    /**
     * Submits an operation.
     *
     * @param key  serialisation key
     * @param args the operation and its normalised arguments (compared with {@code equals} for joining)
     * @param work the operation
     * @param cb   receives the result exactly once
     */
    public void submit(Object key, Object args, Supplier<Result<T>> work, Callback<T> cb) {
        Objects.requireNonNull(cb, "callback");
        Op<T> op;
        synchronized (queues) {
            Deque<Op<T>> q = queues.computeIfAbsent(key, k -> new ArrayDeque<>());
            for (Op<T> o : q) {
                if (o.args.equals(args)) {
                    o.waiters.add(cb);   // identical operation: join
                    return;
                }
            }
            op = new Op<>(args, work);
            op.waiters.add(cb);
            q.addLast(op);
            if (q.size() > 1) {
                return;                  // different arguments: runs after the ones before it
            }
        }
        launch(key, op);
    }

    private void launch(Object key, Op<T> op) {
        try {
            executor.execute(() -> run(key, op));
        } catch (RuntimeException rejected) {
            List<Op<T>> dropped;
            synchronized (queues) {
                Deque<Op<T>> q = queues.remove(key);
                dropped = q == null ? List.of() : new ArrayList<>(q);
            }
            for (Op<T> d : dropped) {
                deliver(d.waiters, unexpectedFailure);
            }
        }
    }

    private void run(Object key, Op<T> op) {
        Result<T> r;
        try {
            r = op.work.get();
            if (r == null) {
                r = unexpectedFailure;
            }
        } catch (RuntimeException e) {
            r = unexpectedFailure;
        }
        List<Callback<T>> waiters;
        Op<T> next;
        synchronized (queues) {
            Deque<Op<T>> q = queues.get(key);
            q.pollFirst();
            waiters = List.copyOf(op.waiters);
            next = q.peekFirst();
            if (next == null) {
                queues.remove(key);
            }
        }
        deliver(waiters, r);
        if (next != null) {
            launch(key, next);
        }
    }

    private static <T> void deliver(List<Callback<T>> waiters, Result<T> r) {
        for (Callback<T> w : waiters) {
            try {
                w.onResult(r);
            } catch (RuntimeException e) {
                // one failing callback must not starve the others (SR-37)
            }
        }
    }
}
