package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.app.serve.PreconditionRecheck;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The three steps {@code gnomish serve} takes before its first claim, each of which ends the
 * process as a startup failure — the plain sentence on the console, {@link ServeExitCodeException}
 * with exit code 1, nothing claimed (FR12 of add-factory-serve): binding the startup law from
 * origin's default branch (FR13 of add-base-ref-resolution), assessing the static operator
 * preconditions (design D17 of add-sandbox-base-image), and the label-provisioning smoke test
 * (design D7 of add-factory-serve). {@link ServeCommand} runs them in that order.
 *
 * <p>A facade over the command's startup equipment (ADR 0010): the three are used together, each
 * step maps its own failure onto the one startup exit, and "startup failure" is the name FR12
 * already gives them.
 *
 * <p>Implements FR12, D7 of add-factory-serve; FR13 of add-base-ref-resolution; FR13, NFR-R3, UX3
 * of add-sandbox-base-image.
 */
final class ServeStartupGates {

    private static final Logger log = LoggerFactory.getLogger(ServeStartupGates.class);

    private final TrackerWiring trackerWiring;
    private final TaskGit git;
    private final ServePreconditions preconditions;
    private final ConsoleIO errorConsole;

    /**
     * @param trackerWiring binds the startup law and resolves the live tracker
     * @param git the task-git port: its base refs for the law, its tenure record for the tracker
     * @param preconditions the daemon's startup verdict on the operator preconditions
     * @param errorConsole the console owner bound to {@code stderr}: each sentence carries a message
     *     from a tracker, a git remote or a check, so it goes out on the human path (FR5, FR6 of
     *     harden-untrusted-text-sinks)
     */
    ServeStartupGates(
            TrackerWiring trackerWiring, TaskGit git, ServePreconditions preconditions, ConsoleIO errorConsole) {
        this.trackerWiring = trackerWiring;
        this.git = git;
        this.preconditions = preconditions;
        this.errorConsole = errorConsole;
    }

    /**
     * FR13, D14/D15 of add-base-ref-resolution: the definition comes from the refreshed default
     * branch of origin, read from git objects — never from the clone's checkout. A default branch
     * that cannot be established or refreshed is startup failure of the same class as an
     * unreachable tracker.
     */
    TrustedTierStartup.StartupLaw bindStartupLaw(Path dir) throws IOException {
        try {
            return trackerWiring.bindStartupLaw(dir, git.baseRefs());
        } catch (DefaultBranchUnboundException unbound) {
            // throwable-not-subject: TrustedTierStartup logged the one ERROR of this failure; the
            //     console line is the operator's copy of its sentence.
            errorConsole.print("gnomish serve: startup failed: " + unbound.getMessage() + ConsoleIO.LINE_END);
            throw new ServeExitCodeException(1);
        }
    }

    /**
     * Design D17 of add-sandbox-base-image: the operator preconditions a slot would meet after its
     * claim, assessed once before the tracker is touched. A refusal is the startup's one coded line
     * (design D19, "One failure, one log": the checks themselves log nothing) and the check's own
     * message on the console. Assessed, the same checks are what the daemon's conditions probe with
     * (design D19), bound to the same clone and definition.
     *
     * @return the assessed checks, bound for the daemon's conditions; never null
     */
    PreconditionRecheck assessPreconditions(RegisteredClone clone, PipelineDefinition definition) {
        if (preconditions.assess(clone, definition)
                instanceof
                ServePreconditions.StartupAssessment.Refused(
                        RuntimeException refusal,
                        com.github.oinsio.gnomish.app.take.DaemonConditionKind kind)) {
            String message = String.valueOf(refusal.getMessage());
            // throwable-not-subject: the refusal is a precondition, not a defect; its message is the
            //     operator's line and the fix, and a stack would only name the check that met it.
            log.error(
                    OperatorEvent.SERVE_OPERATOR_PRECONDITION_REFUSED.head()
                            + "gnomish serve: startup refused: operator precondition {} unmet: {}",
                    kind,
                    message);
            errorConsole.print("gnomish serve: startup failed: " + message + ConsoleIO.LINE_END);
            throw new ServeExitCodeException(1);
        }
        return preconditions.rechecks(clone, definition);
    }

    /**
     * FR12, design D7: the startup label-provisioning smoke test — the same {@link
     * TrackerWiring#resolveTracker} funnel {@link TakeCommand} resolves through (FR4, design D2
     * of fix-claim-epoch-fence), so an unreachable repo or bad token surfaces here, before any task
     * is claimed, and every slot's claim lands in the same tenure record its writers stamp from.
     */
    Tracker provisionTracker(TrackerAdapterFactory factory, TrackerConfig trackerConfig, InstanceId instanceId) {
        try {
            return trackerWiring.resolveTracker(factory, trackerConfig, instanceId, git.epochs());
        } catch (RuntimeException startupFailure) {
            // The operator's console gets the plain sentence; the log file gets the same failure
            // with its stack and cause chain (FR2, FR7 of harden-logging-observability). Until now
            // a startup that died here left nothing at all in the log — the one record of why the
            // daemon never came up went to a terminal nobody keeps.
            log.error(
                    OperatorEvent.SERVE_TRACKER_PROVISION_FAILED.head()
                            + "gnomish serve: startup failed provisioning tracker {}",
                    bindingDescription(trackerConfig),
                    startupFailure);
            errorConsole.print("gnomish serve: startup failed provisioning tracker " + bindingDescription(trackerConfig)
                    + ": " + startupFailure.getMessage() + ConsoleIO.LINE_END);
            throw new ServeExitCodeException(1);
        }
    }

    /** Names the binding in the failure message: {@code type} plus {@code repo}, if configured. */
    private static String bindingDescription(TrackerConfig trackerConfig) {
        Object repo = trackerConfig.subsection().get("repo");
        return repo == null ? "'" + trackerConfig.type() + "'" : "'" + trackerConfig.type() + "' (" + repo + ")";
    }
}
