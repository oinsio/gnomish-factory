package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.RoundToken;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import java.util.Optional;

/**
 * What an admission point admits (design D18 of tighten-spec-contract): the paths a gnome commit of
 * the range may change, and — at a closing harvest — the factory's own in-box closing commit
 * expected at the top of the range, by its subject and the paths it may change. A closing admission
 * lands the top with its register in one ref transaction; a mid-round one moves the branch alone.
 *
 * <p>The top's subject is matched exactly: a range whose top is not the closing commit the factory
 * just asked the box for is refused, so a box that replaced it cannot have its own commit
 * registered, and the factory's commit buried below it carries a {@code gnomish: } subject the
 * gnome range refuses.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 *
 * @param gnome the paths a gnome commit in the range may change
 * @param top the factory's closing commit at the top, or empty mid-round
 */
record AdmissionRule(PathPermit gnome, Optional<FactoryTop> top) {

    /**
     * The factory's own in-box closing commit.
     *
     * @param subject its exact subject line
     * @param permit the paths it may change
     */
    record FactoryTop(String subject, PathPermit permit) {}

    /**
     * The host round close ({@link GitAttemptPersistence}): gnome commits only, no path under the
     * state directory permitted — the host request never enters the working tree.
     */
    static AdmissionRule hostRound() {
        return new AdmissionRule(PathPermit.none(), Optional.empty());
    }

    /** A mid-round harvest of round {@code key}: gnome commits only, its request the one permitted path. */
    static AdmissionRule midRound(AttemptKey key, RoundToken token) {
        return new AdmissionRule(PathPermit.only(GnomeRangeInspection.decisionPath(key, token)), Optional.empty());
    }

    /**
     * The snapshot of round {@code key} ({@link EnvironmentRoundSnapshot}): the gnome's commits and
     * the in-box snapshot of its working tree, the round's own request the one permitted path in
     * either (FR23 of add-sandbox-core; FR16 of make-checkpoint-gate-durable).
     */
    static AdmissionRule snapshot(AttemptKey key, RoundToken token) {
        String decision = GnomeRangeInspection.decisionPath(key, token);
        return closing(
                PathPermit.only(decision),
                ServiceCommitMessages.snapshot(key.stage(), key.attempt(), token),
                PathPermit.only(decision));
    }

    /**
     * The state commit of round {@code key} ({@link EnvironmentAttemptPersistence}): the factory's
     * {@code state.json} and the round's trace, above the admitted snapshot.
     */
    static AdmissionRule state(AttemptKey key, RoundToken token, String tracePath) {
        return closing(
                PathPermit.only(GnomeRangeInspection.decisionPath(key, token)),
                ServiceCommitMessages.round(key.stage(), key.attempt()),
                PathPermit.only(EnvelopePaths.STATE_JSON_PATH, tracePath));
    }

    /**
     * An in-box salvage ({@link EnvironmentSalvage}): no round owns it, so the gnome-writable
     * subtree is what its commits may change — the salvage restored every factory-owned path from
     * the box's {@code HEAD} before it staged ({@link FactoryOwnedPaths}).
     */
    static AdmissionRule salvage() {
        return closing(PathPermit.decisionsSubtree(), ServiceCommitMessages.salvage(), PathPermit.decisionsSubtree());
    }

    private static AdmissionRule closing(PathPermit gnome, String subject, PathPermit permit) {
        return new AdmissionRule(gnome, Optional.of(new FactoryTop(subject, permit)));
    }

    boolean closing() {
        return top.isPresent();
    }
}
