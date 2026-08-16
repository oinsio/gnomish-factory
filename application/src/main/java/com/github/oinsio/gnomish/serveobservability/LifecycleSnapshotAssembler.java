package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.serve.DaemonLifecycleState;
import com.github.oinsio.gnomish.app.serve.DaemonLifecycleView;
import com.github.oinsio.gnomish.app.serve.LifecycleStateTracker;

/**
 * Builds the snapshot's {@code lifecycle} section (FR4) from {@link LifecycleStateTracker#view()}:
 * maps {@code app.serve}'s {@link DaemonLifecycleState} onto this package's sealed {@link
 * LifecycleState} by name — the two types are kept distinct so the document model has no
 * compile-time dependency on the serve-wiring package, exactly like {@link FeedSnapshotAssembler}
 * does for {@link FeedPhase} — and carries {@link DaemonLifecycleView#reason()} into {@link
 * LifecycleState.Stopped} only for the {@code STOPPED} state.
 *
 * <p>Stateless: holds no fields, only assembles a fresh {@link LifecycleState} from the view
 * handed to it on each call.
 *
 * <p>Implements FR4 of add-serve-observability.
 */
public final class LifecycleSnapshotAssembler {

    private LifecycleSnapshotAssembler() {}

    /**
     * Assembles the {@code lifecycle} section from {@code tracker}'s current view.
     *
     * @param tracker the lifecycle holder whose current {@link LifecycleStateTracker#view()}
     *     becomes the snapshot's {@code lifecycle} section; never null
     * @return the assembled {@link LifecycleState}; never null
     */
    public static LifecycleState assemble(LifecycleStateTracker tracker) {
        return assemble(tracker.view());
    }

    /**
     * Assembles the {@code lifecycle} section from a raw {@link DaemonLifecycleView}.
     *
     * @param view the tracker's observability view to translate; never null
     * @return the assembled {@link LifecycleState}; never null
     */
    public static LifecycleState assemble(DaemonLifecycleView view) {
        return switch (view.state()) {
            case RUNNING -> new LifecycleState.Running();
            case DRAINING -> new LifecycleState.Draining();
            case STOPPING -> new LifecycleState.Stopping();
            case STOPPED -> new LifecycleState.Stopped(requireReason(view));
        };
    }

    private static String requireReason(DaemonLifecycleView view) {
        String reason = view.reason();
        if (reason == null) {
            throw new IllegalStateException("STOPPED view must carry a reason");
        }
        return reason;
    }
}
