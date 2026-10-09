package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.app.take.AbortFuse;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Builds the {@link SlotWiring} of a tracker-driven command once its tracker is provisioned: the
 * equipment {@code take} and {@code serve} share, held as fields, and the one construction both
 * used to spell by hand (design D9 of collapse-composition-roots). An abstract factory — fixed
 * fields, one method, one return type — and named as one; it has no accessor, so a command holds
 * it only to build a wiring, never to read a member back out.
 *
 * <p>Each command passes what is its own: {@code take} its bound tracker and git, {@code serve}
 * its bound tracker derived over the health-wrapped tracker and its outage-decorated git. The
 * abort handler is built over {@link BoundTracker#tracker()}, so it writes to the same tracker the
 * slot claims through by construction, and over the clock of the slot's own assembly, so the abort
 * stamp, the terminal-write retry {@link SlotWiring#terminalWriteRetry()} derives and every resume
 * stamp read one time source: the factory takes no time of its own, so no caller can hand it a
 * second one (task 3.9 of supervise-daemon-loops-and-embed-dashboard).
 *
 * <p>Implements FR1 of collapse-composition-roots; FR1 of introduce-slot-wiring; FR9 of
 * add-project-registry.
 */
final class SlotWiringFactory {

    private final RunAssembly assembly;
    private final ObjectProvider<RegisteredClone> resolvedClone;
    private final String taskIdMdcKey;
    private final ContainerTakeSupport containerTakeSupport;
    private final PipelineSource pipelineSource;

    /**
     * @param assembly the plain run assembly the wiring's copy is derived from; its time equipment
     *     is the slot's one time (the abort stamp, the terminal-write retry, every resume stamp)
     * @param resolvedClone the registered clone the slots work in, read when a wiring is built:
     *     its bean exists only once the configuration loader resolved a project (D9 of
     *     add-project-registry)
     * @param taskIdMdcKey the MDC key the task id is bound under while a slot works a task
     * @param containerTakeSupport the container-mode seam of the take chain
     * @param pipelineSource the source the startup definition came from, which every fresh claim
     *     reads its task tier through (FR13 of add-base-ref-resolution)
     */
    SlotWiringFactory(
            RunAssembly assembly,
            ObjectProvider<RegisteredClone> resolvedClone,
            String taskIdMdcKey,
            ContainerTakeSupport containerTakeSupport,
            PipelineSource pipelineSource) {
        this.assembly = assembly;
        this.resolvedClone = resolvedClone;
        this.taskIdMdcKey = taskIdMdcKey;
        this.containerTakeSupport = containerTakeSupport;
        this.pipelineSource = pipelineSource;
    }

    /**
     * The slot wiring over {@code bound}, {@code git} and {@code heartbeat}.
     *
     * <p><b>Metz test.</b> The two commands call this with substituted collaborators, never with
     * a flag. The moment a boolean, a mode or an {@code if (serve)} appears in here, the
     * abstraction is wrong: split it into a take and a serve construction, or inline it back into
     * both callers.
     */
    SlotWiring slotWiring(BoundTracker bound, TaskGit git, TakeHeartbeat heartbeat) {
        RunAssembly slotAssembly =
                assembly.withExtraListener(heartbeat.progress()).withPipelineSource(pipelineSource);
        return new SlotWiring(
                slotAssembly,
                git,
                resolvedClone.getObject(),
                taskIdMdcKey,
                new AbortFuse(
                        new AbortHandler(
                                bound.tracker(), slotAssembly.timeEquipment().clock()),
                        bound.trackerConfig().abortThreshold()),
                bound.credentialEnvVars(),
                containerTakeSupport,
                heartbeat.tenure(),
                bound.trustedBase());
    }
}
