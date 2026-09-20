package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.app.port.git.BaseRefKind;
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome;
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery;
import com.github.oinsio.gnomish.app.port.git.OriginContact;
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
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
 * ResumeBaseOutcome.Bound} confirm a refresh <em>only when their {@link OriginContact} says origin
 * was contacted</em>; the {@code Unavailable} arm of either opens the gate, already sanitized by
 * the gate itself. A {@code Refused} arm signals nothing: origin answered, but no base was
 * refreshed. {@link #discoverDefaultBranch} and {@link #probe} pass through untouched — discovery
 * is a startup read that precedes the gate, and the probe IS the gate's own recovery check.
 *
 * <p><b>Why the contact fact and not the success arm.</b> A success arm answers "is the base
 * bound", not "did origin answer": a commit-pinned base the clone already holds, and a resume in a
 * clone with no {@code origin} at all, both bind with no round trip. Read as refreshes they would
 * spend the pending interval reset a genuine post-close refresh needs, so the next flap would meet
 * the idle floor instead of the grown pause FR14 promises, and the remote's last-contact time
 * would name a moment origin was never asked. The adapter states the fact at the path it took, and
 * this is the only consumer that branches on it (FR2, FR3 of
 * signal-outage-gate-on-origin-contact).
 *
 * <p>Implements FR14, D9 of add-base-ref-resolution; FR2, FR3 of
 * signal-outage-gate-on-origin-contact.
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
            case BaseRefreshOutcome.Refreshed(var _, var _, var _, OriginContact contact) -> confirm(contact);
            case BaseRefreshOutcome.Unavailable(UntrustedText reason) -> gate.openOnFailure(reason.forLog());
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
            case ResumeBaseOutcome.Bound(var _, var _, OriginContact contact) -> confirm(contact);
            case ResumeBaseOutcome.Unavailable(UntrustedText reason) -> gate.openOnFailure(reason.forLog());
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

    /**
     * Reports a success outcome to the gate, but only when it reached origin: a clone-served
     * success is silent on both the probe interval and the remote's last successful contact, the
     * one branch design D3 of signal-outage-gate-on-origin-contact draws.
     */
    private void confirm(OriginContact contact) {
        if (contact == OriginContact.CONTACTED) {
            gate.onSuccessfulRefresh();
        }
    }
}
