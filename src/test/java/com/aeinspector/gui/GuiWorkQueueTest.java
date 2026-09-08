package com.aeinspector.gui;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class GuiWorkQueueTest {
    @Test public void viewersShareOneBudgetAndTakeTurnsAcrossTicks() {
        AtomicLong clock = new AtomicLong();
        GuiWorkQueue queue = new GuiWorkQueue(clock::get);
        List<Integer> work = new ArrayList<>();
        queue.submit(task(() -> { clock.addAndGet(3); work.add(1); return false; }));
        queue.submit(task(() -> { clock.addAndGet(3); work.add(2); return false; }));
        queue.tick(10);
        assertEquals(java.util.Arrays.asList(1, 2, 1, 2), work);
        assertEquals(12, clock.get()); // at most the indivisible final unit exceeds the soft budget
        work.clear(); queue.tick(4);
        assertEquals(java.util.Arrays.asList(1, 2), work);
    }
    @Test public void closingOrReplacingAQueryCancelsItBeforeItsNextStep() {
        AtomicLong clock = new AtomicLong();
        GuiWorkQueue queue = new GuiWorkQueue(clock::get);
        int[] steps = {0};
        GuiWorkQueue.Ticket old = queue.submit(task(() -> { steps[0]++; clock.incrementAndGet(); return false; }));
        queue.tick(1); assertEquals(1, steps[0]);
        old.cancel();
        queue.submit(task(() -> { clock.incrementAndGet(); return true; }));
        queue.tick(5);
        assertEquals(1, steps[0]); assertTrue(queue.isEmpty());
    }
    @Test public void completedAndFailedJobsDoNotKeepRunning() {
        AtomicLong clock = new AtomicLong();
        GuiWorkQueue queue = new GuiWorkQueue(clock::get);
        int[] failed = {0}, completed = {0};
        queue.submit(new GuiWorkQueue.Task() {
            public boolean step() { clock.incrementAndGet(); throw new IllegalStateException("test"); }
            public void failed(Exception problem) { failed[0]++; }
        });
        queue.submit(task(() -> { clock.incrementAndGet(); completed[0]++; return true; }));
        queue.tick(10); queue.tick(10);
        assertEquals(1, failed[0]); assertEquals(1, completed[0]); assertTrue(queue.isEmpty());
        queue.submit(task(() -> { fail("shutdown work"); return true; })); queue.clear(); queue.tick(10);
        assertTrue(queue.isEmpty());
    }
    private static GuiWorkQueue.Task task(java.util.function.BooleanSupplier work) {
        return new GuiWorkQueue.Task() {
            public boolean step() { return work.getAsBoolean(); }
            public void failed(Exception problem) { throw new AssertionError(problem); }
        };
    }
}
