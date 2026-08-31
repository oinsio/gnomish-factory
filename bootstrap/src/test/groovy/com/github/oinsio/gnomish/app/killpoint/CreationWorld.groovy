package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskBranches
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import java.nio.file.Path

/**
 * One task under the creation kill-point run.
 *
 * <p>A world of its own rather than {@link KillPointWorld}, because the creation transition
 * observes a different medium and involves a second instance. The other transitions start from a
 * created, claimed task and watch one repository; this one starts from nothing, and the state that
 * matters is what <em>{@code origin}</em> holds — the only copy of a task branch the fleet can
 * see. A branch that exists on the crashed instance's disk and nowhere else is, to every other
 * instance, a branch that does not exist; classifying at {@code origin} is what makes that the
 * assertion rather than a comment (FR7).
 *
 * <p>Hence two clones. {@code creating} is the instance that dies mid-creation; {@code recovering}
 * is the instance that picks the task up afterwards and has no access to the first one's disk.
 */
class CreationWorld implements BareGitRepoFixture {

    /** The bare {@code origin} — the medium shapes are classified in, and the fleet's only view. */
    Path origin

    /** The dying instance's clone: where the STARTED commit lands before the push. */
    Path creatingClone

    /** The dying instance's strict lifecycle writer — commits locally, pushes nothing. */
    TaskLifecycleStore creating

    /**
     * A second instance's push-decorated repository, over its own clone of {@code origin}. Its
     * {@code createTask} is the production path under test: the STARTED commit plus the
     * load-bearing first push, exactly as a fresh take would run it.
     */
    TaskRepository recovering

    String taskId

    /** The task branch's name on any of the three repositories. */
    String branch() {
        "gnomish/${taskId}"
    }

    /**
     * The shape {@code origin} presents, read through the production classifier. {@code Bare} here
     * is not a damaged branch — it is the classifier's answer for a ref that is not there, which is
     * precisely what an unpushed branch looks like to everyone but its author.
     */
    String shape() {
        new GitTaskBranches(new GitProcessRunner()).classifyShape(origin, taskId).label()
    }

    /** Whether {@code origin} carries the task branch at all. */
    boolean published() {
        gitExitCode(origin, 'rev-parse', '--verify', '--quiet', "refs/heads/${branch()}") == 0
    }

    /**
     * What a second recovery pass must leave untouched: whether the branch is published, and the
     * commit subjects {@code origin} holds for it. Deliberately not the commit id — the recovering
     * instance writes its own STARTED commit, so the id legitimately differs between the window
     * where the push was lost and the window where it landed; what must not change is that a
     * further pickup adds nothing.
     */
    Map fingerprint() {
        [
            published: published(),
            commits: published() ? gitOutput(origin, 'log', '--format=%s', branch()).readLines() : [],
        ]
    }
}
