package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sandboxed twin of {@link MidRoundPushListener} (FR5, git-task-persistence
 * "Best-effort push"): notices a gnome commit landing mid-round <em>inside the
 * environment</em> and mirrors it out — harvest first, then a best-effort push.
 * The factory cannot watch the in-box filesystem, so tip observation is a
 * <b>rate-limited poll</b> on the factory side: each agent progress event is an
 * opportunity, but an actual harvest runs at most once per {@code minInterval},
 * so a commit-spamming gnome can never cause a fetch storm (design D3). A
 * harvest of an unchanged tip is close to a no-op, which is what makes polling
 * affordable. Event-driven tip detection, if it is ever added, must watch
 * {@code .git/logs/HEAD} — refs may be silently packed into {@code packed-refs}
 * — and may only <em>wake</em> this rate-limited poll, never command a fetch
 * itself (D3); it is deliberately not implemented here.
 *
 * <p>Push preconditions differ from the host listener by design: the
 * fast-forward-only harvest itself proves ancestry, so a completed harvest is
 * the precondition — a refused or failed harvest skips the push with one WARN
 * and leaves the authoritative verdict to the round-boundary check
 * (git-task-persistence "Push safety rules"). Like every {@link
 * AgentProgressListener}, {@link #onProgress} never throws.
 *
 * <p><b>Lifecycle: one instance per round</b>, constructed right before the
 * round's {@code execute()} and discarded after, mirroring {@link
 * MidRoundPushListener}. The tip baseline starts at the factory clone's branch
 * tip at construction.
 *
 * <p>Implements FR5 of add-sandbox-core; coordinates with FR11 of
 * add-git-workflow.
 */
public final class MidRoundHarvestListener implements AgentProgressListener {

    private static final Logger log = LoggerFactory.getLogger(MidRoundHarvestListener.class);

    private final TaskExecutionEnvironment environment;
    private final GitProcessRunner runner;
    private final Path cloneDir;
    private final BranchPush push;
    private final String taskId;
    private final String branch;
    private final Clock clock;
    private final Duration minInterval;

    private @Nullable Instant lastPollAt;
    private String lastObservedTip;

    /**
     * @param environment the task's bound environment; {@code harvest()} is the only call made
     * @param runner the git subprocess runner, shared with the run's other git-adapter machinery
     * @param cloneDir the factory clone harvest lands in; tip reads and the push run here
     * @param taskId the task whose branch is being watched, for WARN log context
     * @param branch the task branch name, e.g. {@link TaskIdSanitizer#branchName}
     * @param clock the poll rate-limit time source
     * @param minInterval the minimum time between two actual harvests; the factory-side rate
     *     limit of design D3
     */
    public MidRoundHarvestListener(
            TaskExecutionEnvironment environment,
            GitProcessRunner runner,
            Path cloneDir,
            String taskId,
            String branch,
            Clock clock,
            Duration minInterval) {
        this.environment = environment;
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.push = new BranchPush(runner);
        this.taskId = taskId;
        this.branch = branch;
        this.clock = clock;
        this.minInterval = minInterval;
        this.lastObservedTip = currentTip();
    }

    /**
     * Harvests and pushes best-effort if the rate limit allows and the harvested
     * tip moved since the last observation; otherwise a cheap no-op. Never
     * throws: a refused or failed harvest is one WARN and no push.
     *
     * @param event the progress event that just occurred; unused beyond being the
     *     poll opportunity — tip movement, not event content, is what matters
     */
    @Override
    public void onProgress(AgentProgressEvent event) {
        Instant now = clock.now();
        if (lastPollAt != null && Duration.between(lastPollAt, now).compareTo(minInterval) < 0) {
            return;
        }
        lastPollAt = now;

        try {
            environment.harvest();
        } catch (RuntimeException e) {
            log.warn("mid-round harvest skipped: taskId={}, branch={}, reason={}", taskId, branch, e.toString());
            return;
        }

        String tip = currentTip();
        if (tip.equals(lastObservedTip)) {
            return;
        }
        lastObservedTip = tip;
        push.pushBestEffort(cloneDir, branch);
    }

    private String currentTip() {
        return runner.run(cloneDir, "rev-parse", "--verify", "refs/heads/" + branch)
                .stdout()
                .trim();
    }
}
