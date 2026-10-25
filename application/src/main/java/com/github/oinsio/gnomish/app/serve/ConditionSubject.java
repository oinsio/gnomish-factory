package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import java.nio.file.Path;

/**
 * What one {@link DaemonCondition} is about (design D19 of add-sandbox-base-image): the
 * precondition's kind and the probe that tells it is met again. Kept apart from the condition's
 * state machine, which is the same for every kind; only this pair differs per precondition.
 *
 * <p>Implements FR15 of add-sandbox-base-image.
 *
 * @param kind the precondition this condition holds the feed on; never null
 * @param probe the check run again while the condition is open; never null
 */
record ConditionSubject(DaemonConditionKind kind, ConditionProbe probe) {

    /** The reason a remote probe records when origin stays silent. */
    static final String REMOTE_SILENT = "origin did not answer ls-remote HEAD";

    /**
     * The remote outage gate's subject (FR14 of add-base-ref-resolution): origin answering {@code
     * git ls-remote HEAD} from the daemon's clone.
     *
     * @param baseRefGit the base-ref port whose probe asks origin; never null
     * @param cloneDir the daemon's registered clone; never null
     * @return the remote's subject; never null
     */
    static ConditionSubject remote(BaseRefGit baseRefGit, Path cloneDir) {
        return new ConditionSubject(
                DaemonConditionKind.REMOTE,
                () -> baseRefGit.probe(cloneDir)
                        ? new ConditionProbe.Answer.Met()
                        : new ConditionProbe.Answer.Unmet(REMOTE_SILENT));
    }

    /**
     * An operator precondition's subject (FR15): the startup check of {@code kind}, run again. A
     * refusal of {@code kind} keeps the condition open with the check's message; a refusal of
     * another kind is that kind's to open.
     *
     * @param kind an operator precondition, never {@link DaemonConditionKind#REMOTE}
     * @param rechecks the startup checks bound to the daemon; never null
     * @return the precondition's subject; never null
     */
    static ConditionSubject precondition(DaemonConditionKind kind, PreconditionRecheck rechecks) {
        return new ConditionSubject(
                kind,
                () -> rechecks.recheck(kind)
                        .<ConditionProbe.Answer>map(refusal -> refusal.kind() == kind
                                ? new ConditionProbe.Answer.Unmet(
                                        refusal.reason().forLog())
                                : new ConditionProbe.Answer.RefusedElsewhere(refusal))
                        .orElseGet(ConditionProbe.Answer.Met::new));
    }
}
