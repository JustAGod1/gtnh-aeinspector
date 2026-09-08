package com.aeinspector.gui;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class GuiSubscriptionTest {
    @Test public void openingChangingAndClosingIoDoesNotRestartOrCancelStatisticsWork() {
        AtomicLong clock = new AtomicLong(); GuiWorkQueue queue = new GuiWorkQueue(clock::getAndIncrement);
        GuiSubscription main = new GuiSubscription(), io = new GuiSubscription();
        main.accept(request(1, false, true), 0);
        int[] completed = {0, 0};
        main.work = queue.submit(task(completed, 0)); GuiWorkQueue.Ticket original = main.work;
        io.accept(request(2, true, true), 1); io.work = queue.submit(task(completed, 1));
        io.accept(request(3, true, true), 2); // changing I/O cancels only its own work
        io.work = queue.submit(task(completed, 1));
        io.accept(request(4, true, false), 3); // back stops I/O, not the original main query
        assertSame(original, main.work); assertTrue(main.active(1)); assertFalse(io.ready(100));
        queue.tick(100);
        assertEquals(1, completed[0]); assertEquals(0, completed[1]); assertTrue(queue.isEmpty());
        io.accept(request(3, true, true), 4); assertFalse(io.ready(100)); // late request cannot resurrect I/O
        io.accept(request(5, true, true), 5); assertTrue(io.ready(5)); // opening I/O again
        assertEquals(1, main.request.sequence);
    }
    @Test public void retryAndNewRevisionsRespectWorkOwnershipAndRefreshDelay() {
        GuiSubscription view = new GuiSubscription(); GuiWorkQueue queue = new GuiWorkQueue();
        InspectorProtocol.Request request = request(10, false, true); request.selected = new int[] {23};
        view.accept(request, 0); request.selected[0] = 99;
        assertEquals(23, view.request.selected[0]);
        view.work = queue.submit(task(new int[1], 0)); GuiWorkQueue.Ticket original = view.work;
        view.accept(request(10, false, true), 4); assertSame(original, view.work);
        view.work = null; view.nextRefresh = 25;
        assertFalse(view.ready(24)); assertTrue(view.ready(25));
        view.accept(request(10, false, true), 6); assertTrue(view.ready(6));
        view.accept(request(11, false, true), 7); assertFalse(view.active(10)); assertTrue(view.active(11));
        view.close(); assertFalse(view.ready(100)); assertFalse(view.active(11));
    }
    @Test public void closingContainerCancelsBothViews() {
        GuiSubscription main = new GuiSubscription(), io = new GuiSubscription(); GuiWorkQueue queue = new GuiWorkQueue();
        int[] completed = new int[2];
        main.accept(request(1, false, true), 0); io.accept(request(2, true, true), 0);
        main.work = queue.submit(task(completed, 0)); io.work = queue.submit(task(completed, 1));
        main.close(); io.close(); queue.tick(1_000_000);
        assertArrayEquals(new int[2], completed); assertTrue(queue.isEmpty());
    }
    private static InspectorProtocol.Request request(int sequence, boolean devices, boolean subscribe) {
        InspectorProtocol.Request request = new InspectorProtocol.Request();
        request.sequence = sequence; request.devices = devices; request.subscribe = subscribe; return request;
    }
    private static GuiWorkQueue.Task task(int[] completed, int index) {
        return new GuiWorkQueue.Task() {
            public boolean step() { completed[index]++; return true; }
            public void failed(Exception failure) { throw new AssertionError(failure); }
        };
    }
}
