package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.check.CheckProviderSeam;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The <em>container supports</em>: the installation's container-mode equipment — the check
 * providers, the factory and sandbox settings, the root's execution-mode selector, the process's
 * tenure record and the root's time equipment — and the constructions made from it (design D10 of
 * collapse-composition-roots). {@code gnomish run} and {@code take}/{@code serve} build the same
 * container support, differing only in the ownership label it stamps: {@link #manualSupport()}
 * and {@link #takeSupport()}. {@link #plan} resolves a manual run's execution mode through the same
 * selector {@code take}/{@code serve} ask.
 *
 * <p>One constructor (design D22 of supervise-daemon-loops-and-embed-dashboard): the runtime probe
 * reaches the selector from the root's own bean, so no shorter constructor supplies the Docker
 * adapter as a default and no spec needs a constructor of its own — a spec passes a selector over a
 * scripted probe. The time equipment stops at its leaf, the support factory's installation
 * constructor, which builds the box timing and the lifecycle pass from it.
 *
 * <p>The tenure record is read from {@link TaskGit#epochs()} and nowhere else (FR4 of
 * fix-claim-epoch-fence), so a container run stamps from the book its claim fills.
 *
 * <p>Implements FR1, FR2 of add-serve-sandbox-lifecycle; D10 of collapse-composition-roots; FR18,
 * FR22 of supervise-daemon-loops-and-embed-dashboard.
 */
@Component
public final class ContainerSupports {

    private final Map<String, CheckClientFactory> checkClientRegistry;
    private final FactoryProperties factoryProperties;
    private final SandboxProperties sandboxProperties;
    private final SandboxModeSelector modeSelector;
    private final ClaimEpochSource epochs;
    private final TimeEquipment time;

    /**
     * @param checkClientRegistry the discovered check providers; never null
     * @param factoryProperties the installation config; never null
     * @param sandboxProperties the operator sandbox config, read by the box equipment (the selector
     *     reads the same bound record for the image prerequisite: two readers, one record); never
     *     null
     * @param modeSelector the root's execution-mode selector; never null
     * @param git the task-git capability set, read only for its tenure record; never null
     * @param time the root's one time equipment, handed to the support factory; never null
     */
    ContainerSupports(
            Map<String, CheckClientFactory> checkClientRegistry,
            FactoryProperties factoryProperties,
            SandboxProperties sandboxProperties,
            SandboxModeSelector modeSelector,
            TaskGit git,
            TimeEquipment time) {
        this.checkClientRegistry = checkClientRegistry;
        this.factoryProperties = factoryProperties;
        this.sandboxProperties = sandboxProperties;
        this.modeSelector = modeSelector;
        this.epochs = git.epochs();
        this.time = time;
    }

    /** {@code gnomish run}'s container support, stamping {@code manual} (FR2 of add-serve-sandbox-lifecycle). */
    ContainerSupportFactory manualSupport() {
        return supportFactory(OwnershipMode.MANUAL);
    }

    /**
     * {@code take}/{@code serve}'s container dispatch bundle, whose support stamps {@code tracked}
     * — take/serve claim tasks through the tracker, run never does (FR1, FR2 of
     * add-serve-sandbox-lifecycle).
     */
    ContainerTakeSupport takeSupport() {
        return new ContainerTakeSupport(modeSelector, supportFactory(OwnershipMode.TRACKED));
    }

    /**
     * A manual run's execution plan: the bindings resolve fail-closed (FR14, D13 of
     * add-sandbox-core — container by default, never a silent host fallback) through the root's
     * one selector.
     */
    SandboxModeSelector.Plan plan(PipelineDefinition definition, RegisteredClone clone) {
        return modeSelector.plan(definition, clone);
    }

    /**
     * The support factory closing over {@code mode}, over the check providers' own credential
     * declarations (FR17, design D11 of add-plugin-architecture): a profile-resolved credential
     * name reaches the container's scrub set exactly as an inline one does (FR16, FR17, design
     * D8/D11), since the subsections are resolved against {@code factory.connections} before the
     * providers are asked what they name. The installation's box equipment and the sandbox
     * lifecycle pass are built here once for the mode, by the record's installation constructor
     * on the root's time equipment, and shared by every run of it (design D12 of
     * make-checkpoint-gate-durable).
     */
    private ContainerSupportFactory supportFactory(OwnershipMode mode) {
        List<String> checkCredentials = CheckProviderSeam.credentialEnvVars(
                CheckProviderSeam.resolve(
                        factoryProperties.check(), ConnectionProfiles.of(factoryProperties.connections())),
                checkClientRegistry);
        return new ContainerRunSupportFactory(
                checkCredentials, checkClientRegistry, mode, epochs, sandboxProperties, factoryProperties, time);
    }
}
