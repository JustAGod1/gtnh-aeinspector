package com.aeinspector.core;

/** Separates changes detected by a storage monitor from the AE transfer that triggered that scan. */
public final class ExternalStorageObserver {
    private final LongCounters changes = new LongCounters();
    private final LongCounters.Consumer sink;
    private boolean initialized;
    private int operationDepth;
    private boolean operationHadBaseline;
    private boolean reported;

    public ExternalStorageObserver(LongCounters.Consumer sink) { this.sink = sink; }
    public void initialized() { initialized = true; }
    public void invalidate() { initialized = false; changes.clear(); }

    public void beginOperation() {
        if (operationDepth++ == 0) {
            operationHadBaseline = initialized;
            reported = false;
        }
    }

    public void difference(int resource, long delta) {
        if (!initialized) return;
        if (operationDepth != 0) reported = true;
        changes.add(resource, delta);
    }

    public void endReport() { if (operationDepth == 0) publish(); }

    public void endOperation(int resource, long signedAeChange) {
        if (operationDepth <= 0) throw new IllegalStateException("Unbalanced external storage operation");
        if (operationHadBaseline && reported) changes.add(resource, -signedAeChange);
        if (--operationDepth == 0) publish();
    }

    public void abortOperation() {
        if (operationDepth <= 0) throw new IllegalStateException("Unbalanced external storage operation");
        operationDepth--;
        // The operation's actual result is unknown, so re-baseline instead of inventing production.
        invalidate();
    }

    private void publish() {
        changes.forEach(sink);
        changes.clear();
    }
}
