package app.lightscout.usecases.common;

import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.usecases.fakes.Cp02bFakes;
import app.lightscout.usecases.fakes.Fakes;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** GIR-1 coordination (SS-06 IR-47, IR-48) and the IR-49 server epoch. */
public class SingleFlightTest {

    private static final Result<String> FAILURE = Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE);

    @Test
    public void ir47_identicalArgumentsJoin_workRunsOnce_sameResultOnceEach() {
        Fakes.QueueExecutor io = new Fakes.QueueExecutor();
        SingleFlight<String> f = new SingleFlight<>(io, FAILURE);
        int[] runs = {0};
        Cp02bFakes.Recorder<String> a = new Cp02bFakes.Recorder<>();
        Cp02bFakes.Recorder<String> b = new Cp02bFakes.Recorder<>();
        f.submit("k", List.of("save", 1), () -> Result.ok("r" + ++runs[0]), a);
        f.submit("k", List.of("save", 1), () -> Result.ok("r" + ++runs[0]), b);
        io.runAll();
        assertEquals(1, runs[0]);
        assertSame(a.only(), b.only());
    }

    @Test
    public void ir47_differentArguments_serialisedInOrder_eachOwnResult() {
        Fakes.QueueExecutor io = new Fakes.QueueExecutor();
        SingleFlight<String> f = new SingleFlight<>(io, FAILURE);
        List<String> order = new ArrayList<>();
        Cp02bFakes.Recorder<String> save = new Cp02bFakes.Recorder<>();
        Cp02bFakes.Recorder<String> delete = new Cp02bFakes.Recorder<>();
        f.submit("k", List.of("save"), () -> { order.add("save"); return Result.ok("CREATED"); }, save);
        f.submit("k", List.of("delete"), () -> { order.add("delete"); return Result.ok("DELETED"); }, delete);
        assertEquals("only the first is scheduled", 1, io.queue.size());
        io.runAll();
        assertEquals(List.of("save", "delete"), order);
        assertEquals("CREATED", save.only().value());
        assertEquals("DELETED", delete.only().value());
    }

    @Test
    public void ir48_throwingCallback_doesNotStarveOtherWaiters() {
        Fakes.QueueExecutor io = new Fakes.QueueExecutor();
        SingleFlight<String> f = new SingleFlight<>(io, FAILURE);
        Cp02bFakes.Recorder<String> b = new Cp02bFakes.Recorder<>();
        f.submit("k", "x", () -> Result.ok("v"), r -> { throw new IllegalStateException("ui bug"); });
        f.submit("k", "x", () -> Result.ok("v"), b);
        io.runAll();
        assertEquals("v", b.only().value());
    }

    @Test
    public void ir48_throwingOrNullOperation_givesFailureNeverASuccess() {
        Fakes.QueueExecutor io = new Fakes.QueueExecutor();
        SingleFlight<String> f = new SingleFlight<>(io, FAILURE);
        Cp02bFakes.Recorder<String> a = new Cp02bFakes.Recorder<>();
        Cp02bFakes.Recorder<String> b = new Cp02bFakes.Recorder<>();
        f.submit("k", "x", () -> { throw new IllegalStateException("bug"); }, a);
        f.submit("k2", "x", () -> null, b);
        io.runAll();
        assertFalse(a.only().ok());
        assertEquals(FetchOutcome.STORAGE_ERROR, a.only().outcome());
        assertFalse(b.only().ok());
    }

    @Test
    public void ir48_rejectedExecute_failsAllWaiters_noStaleEntry() {
        boolean[] reject = {true};
        Fakes.QueueExecutor queue = new Fakes.QueueExecutor();
        Executor io = r -> {
            if (reject[0]) {
                throw new RejectedExecutionException("shut down");
            }
            queue.execute(r);
        };
        SingleFlight<String> f = new SingleFlight<>(io, FAILURE);
        Cp02bFakes.Recorder<String> a = new Cp02bFakes.Recorder<>();
        f.submit("k", "x", () -> Result.ok("v"), a);
        assertSame(FAILURE, a.only());
        reject[0] = false;
        Cp02bFakes.Recorder<String> later = new Cp02bFakes.Recorder<>();
        f.submit("k", "x", () -> Result.ok("v"), later);   // not joined to a dead entry
        queue.runAll();
        assertEquals("v", later.only().value());
    }

    @Test
    public void ir49_epoch_writesOfAnOlderEpochAreDropped() {
        ServerEpoch epoch = new ServerEpoch();
        long started = epoch.current();
        List<String> writes = new ArrayList<>();
        assertTrue(epoch.writeIfCurrent(started, () -> writes.add("a")));
        epoch.advance(() -> writes.add("reset"));
        assertFalse(epoch.writeIfCurrent(started, () -> writes.add("stale")));
        assertTrue(epoch.writeIfCurrent(epoch.current(), () -> writes.add("b")));
        assertEquals(List.of("a", "reset", "b"), writes);
    }
}
