package com.aeinspector.gui;

/** One view's live request, owned by the server thread. Closing I/O does not cancel the main view. */
final class GuiSubscription {
    InspectorProtocol.Request request = new InspectorProtocol.Request();
    GuiWorkQueue.Ticket work;
    int nextRefresh, responseBatch;
    private int sequence = -1;
    private boolean subscribed;

    void accept(InspectorProtocol.Request latest, int age) {
        if (latest.sequence > sequence) {
            cancelWork(); request = latest.copy(); sequence = latest.sequence;
            subscribed = latest.subscribe; nextRefresh = age;
        } else if (latest.sequence == sequence && subscribed && latest.subscribe && work == null) {
            nextRefresh = age; // Retry without restarting an in-progress query.
        }
    }
    boolean ready(int age) { return subscribed && work == null && age >= nextRefresh; }
    boolean active(int revision) { return subscribed && sequence == revision; }
    void cancelWork() { if (work != null) { work.cancel(); work = null; } }
    void close() { subscribed = false; cancelWork(); }
}
