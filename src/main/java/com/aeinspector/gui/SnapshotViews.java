package com.aeinspector.gui;

/** Two bounded mailboxes: the hidden statistics view and the open I/O view never replace each other's batches. */
final class SnapshotViews {
    private final SnapshotBatch.Inbox main = new SnapshotBatch.Inbox(), devices = new SnapshotBatch.Inbox();
    SnapshotViews() { close(); }
    private SnapshotBatch.Inbox view(boolean io) { return io ? devices : main; }
    void expect(boolean io, int window, int sequence) { view(io).expect(window, sequence); }
    void receive(InspectorProtocol.Snapshot packet) { main.receive(packet); devices.receive(packet); }
    SnapshotBatch take(boolean io) { return view(io).take(); }
    void close(boolean io) { view(io).close(); }
    void close() { main.close(); devices.close(); }
}
