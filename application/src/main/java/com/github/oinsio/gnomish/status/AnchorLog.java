package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single owner of the operator plane's lifecycle anchor vocabulary (design D2, FR2 of
 * harden-logging-observability): claim acquired, serve started, serve stopping, and the canonical
 * per-task summary.
 *
 * <p>An anchor is the line an operator navigates by — the fixed points a post-mortem grep lands on
 * between which everything else is detail. They are worth one owner because their <em>form</em> is
 * the contract: two claim paths that word the same event differently cost the reader a second
 * search, and a summary rendered per mode drifts per mode. Every emitter here is a plain SLF4J
 * call with no I/O of its own, so no caller needs to guard against it.
 *
 * <p>Static, and deliberately not a Spring bean: it holds no state, and the alternative — threading
 * one instance through the claim walk, the feed cycle, the serve command and every slot — would buy
 * a seam nothing needs. Specs assert its output through the shared log-capture helper, the same way
 * they assert {@link LoggingEventListener}'s. (Its package is outside the component-scan root in any
 * case, exactly like its neighbours here.)
 *
 * <p>What is <em>not</em> here: the remote modules' own lifecycle anchors — a container created or
 * disposed, a task-lifecycle commit written. Those log at their own choke points, in their own
 * modules, following the same policy; pulling them through this class would add a cross-module
 * dependency for the sake of a log line (design D2).
 *
 * <p>Implements FR2, FR3 of harden-logging-observability.
 */
public final class AnchorLog {

    private static final Logger log = LoggerFactory.getLogger(AnchorLog.class);

    private AnchorLog() {}

    /**
     * The claim anchor, emitted by every claim path the instant a claim is held — the first
     * correlated line of a task's story, before any engine event of that task.
     *
     * <p>The occupancy the claim leaves behind is part of the anchor: an operator reading "claimed
     * X, 0 of 2 slots free" learns both that the task started and that the instance just saturated,
     * without correlating two lines.
     *
     * @param taskId the claimed task's tracker id; never blank
     * @param freeSlots work slots still free on this instance after the claim; never negative
     * @param slots the instance's configured slot count; positive
     */
    public static void claimAcquired(String taskId, int freeSlots, int slots) {
        log.info("claim acquired for task {}: {} of {} slot(s) free", taskId, freeSlots, slots);
    }

    /**
     * The serve start anchor: the configuration the daemon is actually running with, so a
     * post-mortem never has to guess which properties were in effect.
     *
     * @param config the effective serve configuration; never null
     */
    public static void serveStarted(ServeConfig config) {
        log.info(
                "serve started: instance={}, slots={}, wipLimit={}, idlePoll={}, sigtermGrace={}",
                config.instanceId(),
                config.slots(),
                config.wipLimit(),
                config.idlePollInterval(),
                config.sigtermGrace());
    }

    /**
     * The serve stop anchor, emitted as the shutdown sequence begins — before the drain, so the
     * lines the drain itself writes are bracketed by it.
     *
     * @param reason why the daemon is stopping, in the same wire-safe vocabulary the snapshot
     *     records ({@code signal}, {@code drainComplete}); never blank
     */
    public static void serveStopping(String reason) {
        log.info("serve stopping: reason={}", reason);
    }

    /**
     * The canonical per-task summary — the one renderer for every mode (design D3, FR3). Logged at
     * WARN for the outcomes an operator should look at and INFO for the two that are lifecycle
     * anchors, per {@link TaskSummary.Outcome#worthLookingAt()}.
     *
     * <p>The line carries no taskId of its own: it is emitted under the task's own {@code taskId}
     * MDC, which is what makes it the last line of a {@code grep taskId=<id>} (UX2).
     *
     * @param summary the terminal facts of one task; never null
     */
    public static void taskSummary(TaskSummary summary) {
        String rendered = render(summary);
        if (summary.outcome().worthLookingAt()) {
            log.warn("{}", rendered);
            return;
        }
        log.info("{}", rendered);
    }

    /**
     * The one rendering of {@link TaskSummary}, shared by both levels above so the form cannot
     * differ between an aborted task and a delivered one.
     */
    private static String render(TaskSummary summary) {
        return "task summary: outcome=" + summary.outcome().word()
                + (summary.parkReason() == null ? "" : " (" + summary.parkReason() + ")")
                + ", stage=" + (summary.stage() == null ? "pipelineEnd" : summary.stage())
                + ", attempts=" + summary.attemptsUsed()
                + ", wall=" + summary.wall()
                + ", tokens=" + renderTokens(summary.tokensByModel());
    }

    /**
     * Renders the per-model token counts compactly — {@code model=in/out/cacheWrite/cacheRead} —
     * rather than through the record's own {@code toString}, which would spend most of the line on
     * repeated field names. An empty map renders as {@code unreported}, never as zeros: "not
     * measured" and "measured as none" are different facts ({@link TokenUsage}'s own rule).
     */
    private static String renderTokens(Map<String, TokenUsage> tokensByModel) {
        if (tokensByModel.isEmpty()) {
            return "unreported";
        }
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        tokensByModel.forEach((model, usage) -> joiner.add(model + "=" + usage.input() + "/" + usage.output() + "/"
                + usage.cacheCreation() + "/" + usage.cacheRead()));
        return joiner.toString();
    }

    /**
     * The effective serve configuration named by {@link #serveStarted}. A parameter object rather
     * than five positional arguments: two {@code int}s and two {@code Duration}s side by side are
     * the transposition hazard {@code process-invariants.md} names, and a silently swapped grace
     * and poll interval would misreport the daemon's own settings.
     *
     * @param instanceId this instance's full id; never blank
     * @param slots the configured work-slot count; positive
     * @param wipLimit the configured open-front WIP limit; positive
     * @param idlePollInterval the feed's idle poll interval; never null
     * @param sigtermGrace how long a signal-initiated stop waits for in-flight slots; never null
     */
    public record ServeConfig(
            String instanceId, int slots, int wipLimit, Duration idlePollInterval, Duration sigtermGrace) {}
}
