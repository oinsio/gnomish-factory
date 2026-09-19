package com.github.oinsio.gnomish.app.take;

/**
 * The short, grep-able outcome description for one terminal {@link TakeResult} (NFR-O2 of
 * add-factory-serve): a fixed vocabulary per variant, shared by every closing report that names a
 * task's outcome — {@code DrainReport} (a drain run's summary, task 5.4) and the batch {@code
 * take} summary (task 6.3) — so the wording stays identical between the two sibling reports
 * instead of drifting via two independent switches.
 *
 * <p>Every free-text field it names is untrusted text (design D4 of type-untrusted-text), and this
 * description is read on the log plane — a drain summary, a batch summary, a slot line — so each
 * carrier leaves through the log exit here: one line, capped, inert. {@code AwaitingHuman.report}
 * and {@code Delivered.summary} are the exceptions and need none: each is a report builder's
 * finished output, already rendered once through the comment exit for its two readers (design D6).
 *
 * <p>Implements NFR-O2 of add-factory-serve; FR5 of type-untrusted-text.
 */
public final class TakeResultDescription {

    private TakeResultDescription() {}

    /**
     * Returns a one-line, terse description of {@code result}, e.g. {@code "delivered: shipped
     * it"} or {@code "parked (ESCALATION): needs a human"}.
     *
     * @param result the terminal result to describe; never null
     */
    public static String describe(TakeResult result) {
        return switch (result) {
            case TakeResult.Delivered delivered -> "delivered: " + delivered.summary();
            case TakeResult.AwaitingHuman awaitingHuman ->
                "parked (" + awaitingHuman.reason() + "): " + awaitingHuman.report();
            case TakeResult.Aborted aborted -> "aborted: " + aborted.cause().forLog();
            case TakeResult.Revoked revoked -> "revoked: " + revoked.note().forLog();
            case TakeResult.Skipped skipped -> "skipped: " + skipped.reason().forLog();
            case TakeResult.EmptyQueue _ -> "unexpected empty-queue result";
            case TakeResult.InfrastructureUnavailable infrastructureUnavailable ->
                "released (infrastructure unavailable): "
                        + infrastructureUnavailable.reason().forLog();
        };
    }
}
