package com.github.oinsio.gnomish.app.serve;

/**
 * A {@link DirtyNotifier} that starts as {@link DirtyNotifier#NOOP} and is rebound, exactly once,
 * to the real {@code SnapshotWriter::markDirty} implementation once that writer exists (task 5.1
 * wiring).
 *
 * <p>Breaks a construction-order cycle: {@link SlotLedger}, {@link FeedAutomaton}, and {@link
 * LifecycleStateTracker} each need a {@link DirtyNotifier} at construction time (design D4), but
 * the real notifier — {@code SnapshotWriter::markDirty} — can only exist once the writer's {@code
 * Supplier<Snapshot>} (which reads those SAME state holders) has been built, which in turn needs
 * those state holders to already exist. This class lets every state holder be constructed first
 * against a stand-in, with the real delegate {@link #bind}-ed in immediately after the writer is
 * constructed and before it starts — so no transition after {@link #bind} is ever missed, and any
 * transition that could only happen before it (none do, in the actual wiring order) would simply
 * forward to the harmless {@link DirtyNotifier#NOOP} default.
 *
 * <p>Implements FR1 of add-serve-observability (design D4).
 */
public final class ForwardingDirtyNotifier implements DirtyNotifier {

    private volatile DirtyNotifier delegate = DirtyNotifier.NOOP;

    /**
     * Rebinds subsequent {@link #markDirty()} calls to {@code delegate}. Called exactly once, at
     * wiring time, right after the real notifier (the snapshot writer) is constructed.
     *
     * @param delegate the real notifier to forward to from now on; never null
     */
    public void bind(DirtyNotifier delegate) {
        this.delegate = delegate;
    }

    @Override
    public void markDirty() {
        delegate.markDirty();
    }
}
