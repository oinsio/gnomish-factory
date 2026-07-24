package com.github.oinsio.gnomish.app.take;

/**
 * Pure mapping from a terminal {@link TakeResult} to its process exit code
 * (design D16, tracker-take spec "Exit codes by take result"): the full table
 * for the codes that are {@link TakeResult}-shaped. Codes 1/2/3 (failure
 * outside a claimed run, usage error, pipeline load failure) are
 * exception-based, not {@link TakeResult}-based, and are out of scope here —
 * see {@link com.github.oinsio.gnomish.app.TakeExitCodeException} for how a future CLI command surfaces the
 * code this class computes.
 *
 * <table>
 *   <caption>TakeResult variant to exit code</caption>
 *   <tr><th>Variant</th><th>Exit code</th><th>Meaning</th></tr>
 *   <tr><td>{@link TakeResult.Delivered}</td><td>0</td><td>delivered</td></tr>
 *   <tr><td>{@link TakeResult.EmptyQueue}</td><td>0</td><td>clean bare-take no-op</td></tr>
 *   <tr><td>{@link TakeResult.AwaitingHuman} ({@code ESCALATION})</td><td>10</td><td>parked, needs a decision</td></tr>
 *   <tr><td>{@link TakeResult.AwaitingHuman} ({@code CHECKPOINT})</td><td>11</td><td>parked, manual pause</td></tr>
 *   <tr><td>{@link TakeResult.AwaitingHuman} ({@code INFRA})</td><td>13</td><td>parked, fuse trip or infra escalation</td></tr>
 *   <tr><td>{@link TakeResult.Aborted}</td><td>12</td><td>infrastructure abort below the fuse</td></tr>
 *   <tr><td>{@link TakeResult.Revoked}</td><td>14</td><td>claim lost mid-run</td></tr>
 *   <tr><td>{@link TakeResult.Skipped}</td><td>15</td><td>refused or skipped</td></tr>
 * </table>
 *
 * <p>Exhaustive switch, no {@code default} arm: a new {@link TakeResult} variant must
 * update this mapping deliberately, matching the project's existing discipline for
 * sealed-type switches (e.g. {@link TakeOutcomeMapper#map}).
 *
 * <p>Implements FR9, FR10, FR15, D16 of add-tracker-port.
 */
public final class TakeExitCodeMapper {

    private TakeExitCodeMapper() {}

    /**
     * Returns the process exit code for {@code result} per design D16's table.
     *
     * @param result the terminal result of one {@code take} run; never null
     * @return the process exit code for {@code result} per design D16's table
     */
    public static int exitCodeFor(TakeResult result) {
        return switch (result) {
            case TakeResult.Delivered ignored -> 0;
            case TakeResult.EmptyQueue ignored -> 0;
            case TakeResult.AwaitingHuman awaitingHuman ->
                switch (awaitingHuman.reason()) {
                    case ESCALATION -> 10;
                    case CHECKPOINT -> 11;
                    case INFRA -> 13;
                };
            case TakeResult.Aborted ignored -> 12;
            case TakeResult.Revoked ignored -> 14;
            case TakeResult.Skipped ignored -> 15;
        };
    }
}
