package com.github.oinsio.gnomish.app;

/**
 * Builds a ready-to-use {@link TakeClaimAndWork} from the slot's {@link SlotWiring}, which it holds
 * for the slot's lifetime, wiring the host and container resume runners in one place. The routing table above them is
 * assembled per resume by {@link TakeWorkRouter}, once the run's execution mode is known (design D8
 * of add-serve-sandbox-lifecycle). Extracted so that identical wiring is not triplicated across {@link
 * TakeBareAuto}, {@link TakeDisposition}, and {@code app.serve.TakeSlotRunner} — the sole crossing
 * point through which a caller (including one outside this package, task 4.3 of add-factory-serve)
 * gets a working instance without the three package-private resume-machinery classes being widened.
 *
 * <p>Implements FR9, FR10, D3 of add-tracker-port. Implements FR1, M2 of add-factory-serve.
 * Implements FR5 of introduce-slot-wiring.
 *
 * @param wiring the slot's equipment, fixed for as long as the slot exists (D2 of
 *     introduce-slot-wiring); the slot wiring every {@link TakeClaimAndWork} this factory builds
 *     works with
 */
public record TakeClaimAndWorkFactory(SlotWiring wiring) {

    /**
     * Wires the resume chain and returns a {@link TakeClaimAndWork} bound to it (see class javadoc).
     *
     * <p>Implements FR1, M2 of add-factory-serve; NFR-O1 of harden-task-branch-contract; FR5 of
     * introduce-slot-wiring.
     */
    public TakeClaimAndWork forSlot() {
        return new TakeClaimAndWork(wiring, new TakeResumeRunner(wiring), new TakeContainerResumeRunner(wiring));
    }
}
