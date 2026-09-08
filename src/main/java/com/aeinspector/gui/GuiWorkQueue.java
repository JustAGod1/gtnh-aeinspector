package com.aeinspector.gui;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** Cooperative work on the existing server thread. The budget is shared by every viewer. */
public final class GuiWorkQueue {
    public interface Task {
        boolean step() throws Exception;
        void failed(Exception failure);
    }
    public static final class Ticket {
        private final Task task;
        private boolean cancelled;
        private Ticket(Task task) { this.task = task; }
        public void cancel() { cancelled = true; }
    }
    private final ArrayDeque<Ticket> pending = new ArrayDeque<>();
    private final LongSupplier clock;
    public GuiWorkQueue() { this(System::nanoTime); }
    GuiWorkQueue(LongSupplier clock) { this.clock = clock; }
    public Ticket submit(Task task) { Ticket ticket = new Ticket(task); pending.addLast(ticket); return ticket; }
    public void clear() { pending.clear(); }
    public boolean isEmpty() { return pending.isEmpty(); }
    public void tick(long budgetNanos) {
        if (pending.isEmpty() || budgetNanos <= 0) return;
        long start = clock.getAsLong();
        // Rotate after each small unit, so a large network cannot starve another viewer.
        while (!pending.isEmpty() && clock.getAsLong() - start < budgetNanos) {
            Ticket ticket = pending.removeFirst();
            if (ticket.cancelled) continue;
            try { if (!ticket.task.step() && !ticket.cancelled) pending.addLast(ticket); }
            catch (Exception failure) { ticket.cancelled = true; ticket.task.failed(failure); }
        }
    }
}
