package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Arrays;

/**
 * The harvest-boundary integrity check for a sandboxed round's <b>state commit</b> (FR22,
 * design D16). Its sibling {@link HarvestedBoundaryCheck} (FR23) is factory-side too, but judges
 * the other commit of the round — the gnome's <em>snapshot</em> commit, against the previous tip.
 * This one judges the commit the factory itself asked the environment for: once {@link
 * EnvironmentAttemptPersistence} has harvested the in-box state commit, two properties must hold
 * before that tip may be accepted as the round's new baseline.
 *
 * <ul>
 *   <li><b>parent-check</b> — the round's snapshot commit must be the harvested state commit's
 *       <em>only</em> parent; a commit inserted inside the environment (a daemon, a gnome that
 *       kept running) aborts the round, and so does a second parent, which an in-box {@code
 *       MERGE_HEAD} could otherwise attach to the factory's own commit to carry unreviewed
 *       history onto the branch while both read-backs still pass;
 *   <li><b>read-back</b> — the harvested {@code state.json} and trace blob must be
 *       byte-identical to what the factory wrote through the environment channel, so in-box
 *       tampering between {@code putFile} and the commit aborts it too.
 * </ul>
 *
 * <p>Both reads are bare-object reads against the factory clone ({@link GitObjects}): no
 * checkout and no hooks, so nothing authored inside the environment is executed in order to
 * judge the environment. Every mismatch throws {@link RoundBoundaryViolationException}, which
 * the engine turns into {@code Aborted} while the branch keeps the evidence.
 *
 * <p>What this check does <b>not</b> establish: the state commit's tree beyond those two blobs.
 * {@code git commit} publishes whatever the in-box index holds, so a path a daemon staged
 * alongside the two factory files rides the commit unjudged — and since that commit becomes the
 * next round's baseline, {@link HarvestedBoundaryCheck} does not see it either. FR22 scopes the
 * read-back to exactly the channel-delivered files (design D16); a wider guarantee would need a
 * snapshot-to-state tree diff this check deliberately does not run.
 *
 * <p>Implements FR22 of add-sandbox-core.
 */
final class HarvestedStateCommitCheck {

    private final GitObjects gitObjects;

    /**
     * @param gitObjects the bare-object facade opened against the factory clone the harvest
     *     landed in; never null
     */
    HarvestedStateCommitCheck(GitObjects gitObjects) {
        this.gitObjects = gitObjects;
    }

    /**
     * Runs the parent-check and both read-backs against the harvested tip, cheapest first.
     *
     * @param taskId the task being checked, for the violation message
     * @param tip the harvested state commit
     * @param snapshot the round's snapshot commit, the only permitted parent of {@code tip}
     * @param tracePath the branch-relative path the round's trace was written to
     * @param stateBytes the {@code state.json} bytes the factory wrote through the channel
     * @param traceBytes the trace bytes the factory wrote through the channel
     * @throws RoundBoundaryViolationException if the parent is wrong, unreadable or not the only
     *     one, or if either harvested blob differs from what the factory wrote
     */
    void verify(String taskId, String tip, String snapshot, String tracePath, byte[] stateBytes, byte[] traceBytes) {
        verifyParent(taskId, tip, snapshot);
        // Resolved once for both read-backs rather than per blob. It cannot be empty here:
        // verifyParent has already resolved tip^, which no unresolvable tip has.
        ObjectId tipCommit = gitObjects.resolveRef(tip).orElseThrow();
        readBack(taskId, tipCommit, EnvelopePaths.STATE_JSON_PATH, stateBytes);
        readBack(taskId, tipCommit, tracePath, traceBytes);
    }

    private void verifyParent(String taskId, String tip, String snapshot) {
        String parent = gitObjects
                .resolveRef(tip + "^")
                .map(ObjectId::hex)
                .orElseThrow(() -> new RoundBoundaryViolationException(
                        taskId, UntrustedText.factory("harvested state commit " + tip + " has no readable parent")));
        if (!parent.equals(snapshot)) {
            throw new RoundBoundaryViolationException(
                    taskId,
                    UntrustedText.factory("harvested state commit's parent " + parent + " is not the snapshot commit "
                            + snapshot + " (a commit was inserted inside the environment)"));
        }
        // "The parent", singular (FR22): git commits whatever MERGE_HEAD names as a second parent,
        // so a first parent that matches is not yet proof that nothing else was attached in-box.
        if (gitObjects.resolveRef(tip + "^2").isPresent()) {
            throw new RoundBoundaryViolationException(
                    taskId,
                    UntrustedText.factory("harvested state commit " + tip + " has a second parent besides the"
                            + " snapshot commit (history was merged in inside the environment)"));
        }
    }

    private void readBack(String taskId, ObjectId tip, String path, byte[] written) {
        byte[] harvested;
        try {
            // Cap = written length + 1: byte-identical content fits exactly, and any longer in-box
            // replacement trips GitObjects' cap, which throws rather than truncating — so it aborts
            // through the catch below instead of the comparison silently passing on a prefix.
            harvested = gitObjects.readBlob(tip, path, written.length + 1L);
        } catch (RuntimeException e) {
            // The throwable is interpolated raw, so the sentence keeps the family of what it
            // quotes rather than claiming to be factory prose (design D3 of type-untrusted-text).
            throw new RoundBoundaryViolationException(
                    taskId, UntrustedText.subprocess("read-back of " + path + " failed: " + e));
        }
        if (!Arrays.equals(harvested, written)) {
            throw new RoundBoundaryViolationException(
                    taskId,
                    UntrustedText.factory(
                            path + " harvested from the environment differs from what the factory wrote (in-box"
                                    + " tampering)"));
        }
    }
}
