package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.app.port.git.BaseRefKind;
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome;
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery;
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The {@link BaseRefGit} a serve slot is handed: the real one, with every claim-time base read
 * reported to the {@link RemoteOutageGate} at the moment it returns (FR14, D9 of
 * add-base-ref-resolution).
 *
 * <p><b>Why here and not from the slot's terminal result.</b> The gate's two signals are facts
 * about {@code origin} at an instant: "it did not answer" opens the gate, "a base refresh
 * succeeded" resets the probe interval — the latter only when it follows the close. A slot's
 * terminal {@code TakeResult} arrives hours after its refresh, so a signal derived from it carries
 * a stale fact: a slot that refreshed before an outage and finished after the gate closed would
 * reset the interval although no refresh followed the close, defeating the flapping-remote pause
 * FR14 exists for. Reporting at the port call itself makes the signal's time the fact's time by
 * construction — nothing downstream has to guess which outage a result belongs to.
 *
 * <p><b>Which outcomes signal.</b> {@link BaseRefreshOutcome.Refreshed} and {@link
 * ResumeBaseOutcome.Bound} confirm a refresh; the {@code Unavailable} arm of either opens the
 * gate, already sanitized by the gate itself. A {@code Refused} arm signals nothing: origin
 * answered, but no base was refreshed. {@link #discoverDefaultBranch} and {@link #probe} pass
 * through untouched — discovery is a startup read that precedes the gate, and the probe IS the
 * gate's own recovery check.
 *
 * <p><b>Known imprecision, accepted.</b> A commit-pinned base the clone already holds refreshes
 * without a network round trip and still reports {@code Refreshed}; a resume in a clone with no
 * {@code origin} binds from the local tip the same way. Both are read here as a successful
 * refresh, since the port's outcome does not say whether origin was contacted. The cost is a
 * possible early interval reset for a SHA-pinned task, never a wrongly opened or closed gate —
 * recovery is confirmed by a probe alone.
 *
 * <p>Implements FR14, D9 of add-base-ref-resolution.
 *
 * @param delegate the real base-ref capability every read is forwarded to; never null
 * @param gate the gate the slot's reads report to — the SAME instance the feed consults; never
 *     null
 */
record RemoteOutageSignalingBaseRefGit(BaseRefGit delegate, RemoteOutageGate gate) implements BaseRefGit {

    @Override
    public DefaultBranchDiscovery discoverDefaultBranch(Path cloneDir) {
        return delegate.discoverDefaultBranch(cloneDir);
    }

    @Override
    public BaseRefreshOutcome refresh(Path cloneDir, String ref) {
        BaseRefreshOutcome outcome = delegate.refresh(cloneDir, ref);
        switch (outcome) {
            case BaseRefreshOutcome.Refreshed _ -> gate.onSuccessfulRefresh();
            case BaseRefreshOutcome.Unavailable(String reason) -> gate.openOnFailure(reason);
            case BaseRefreshOutcome.Refused _ -> {
                // origin answered; nothing about its reachability changed either way.
            }
        }
        return outcome;
    }

    @Override
    public ResumeBaseOutcome resolveForResume(Path cloneDir, String ref, @Nullable BaseRefKind kind) {
        ResumeBaseOutcome outcome = delegate.resolveForResume(cloneDir, ref, kind);
        switch (outcome) {
            case ResumeBaseOutcome.Bound _ -> gate.onSuccessfulRefresh();
            case ResumeBaseOutcome.Unavailable(String reason) -> gate.openOnFailure(reason);
            case ResumeBaseOutcome.Refused _ -> {
                // origin answered; nothing about its reachability changed either way.
            }
        }
        return outcome;
    }

    @Override
    public boolean probe(Path cloneDir) {
        return delegate.probe(cloneDir);
    }
}
