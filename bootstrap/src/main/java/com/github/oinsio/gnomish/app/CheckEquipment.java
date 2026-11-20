package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.check.http.HttpCheckProbe;
import com.github.oinsio.gnomish.adapter.check.http.HttpRunContext;
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader;
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.httpegress.OperatorEgress;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The check equipment one installation runs every stage's checks with (design D11 and the {@code
 * CheckEquipment} row of collapse-composition-roots): the two in-process check runners, the
 * operator's {@code factory.check.<built-in>} subsections ({@link BuiltInCheckSubsections}) and the
 * credential seam. The run's assembly asks it for the built-ins' registry collaborators and
 * connections, and the child-environment allowlist; the check-provider registry it once held left
 * with task 5.2 of migrate-verify-to-executor-contract.
 *
 * <p>The {@link SecretsProvider} is a member with no accessor (NFR-S1): a holder can have a provider,
 * a built-in or a child environment resolve a credential (NFR-S3 of tighten-spec-contract), but
 * cannot obtain the seam. The {@link FactoryProperties} are read for the check subsections, the
 * connection profiles and the {@code in-process} egress only.
 *
 * <p>Implements FR16, FR26 of add-sandbox-core; FR5, FR6, FR16, FR17 of add-plugin-architecture;
 * FR1, FR4, NFR-S1 of collapse-composition-roots; FR23 of supervise-daemon-loops-and-embed-dashboard;
 * NFR-S3 of tighten-spec-contract; FR13, NFR-S2 of migrate-verify-to-executor-contract.
 */
@NullMarked
public final class CheckEquipment {

    private final FilesExistCheckRunner filesExistCheckRunner;
    private final ShellCommandCheckRunner shellCommandCheckRunner;
    private final SecretsProvider secretsProvider;
    private final FactoryProperties factoryProperties;
    private final BuiltInCheckSubsections subsections;

    CheckEquipment(
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            SecretsProvider secretsProvider,
            FactoryProperties factoryProperties) {
        this.filesExistCheckRunner = filesExistCheckRunner;
        this.shellCommandCheckRunner = shellCommandCheckRunner;
        this.secretsProvider = secretsProvider;
        this.factoryProperties = factoryProperties;
        this.subsections = new BuiltInCheckSubsections(
                factoryProperties.check(), ConnectionProfiles.of(factoryProperties.connections()));
    }

    /**
     * The operator's {@code factory.check.<built-in>} subsections this equipment reads: graded once
     * at startup by the composition root, and the source of the built-ins' credential names.
     */
    BuiltInCheckSubsections subsections() {
        return subsections;
    }

    /**
     * The builtin-check runner for one run: reading the attempt commit through the sandbox pieces
     * in container mode, the working tree on the host.
     */
    FilesExistCheckRunner builtinRunner(@Nullable SandboxRunPieces sandbox) {
        return sandbox == null
                ? filesExistCheckRunner
                : filesExistCheckRunner.withAttemptReader(sandbox.attemptReader());
    }

    /**
     * The command-check runner for one run: composing child environments from {@code childEnv},
     * and running each check in the sandbox pieces' check environments in container mode.
     */
    ShellCommandCheckRunner commandRunner(ChildEnvAllowlist childEnv, @Nullable SandboxRunPieces sandbox) {
        var runner = shellCommandCheckRunner.withChildEnv(childEnv);
        return sandbox == null ? runner : runner.withEnvironments(sandbox.checkEnvironments());
    }

    /**
     * The run's child-environment allowlist (D6, FR9 of add-sandbox-core): the operator's {@code
     * passthroughNames}, the declared credential names the allowlist refuses in passthrough and
     * scrubs from every composed child environment (FR17, design D11 of add-plugin-architecture) —
     * the active tracker adapter's, unioned here with the built-ins' from their one owner, {@link
     * BuiltInCheckSubsections#credentialNames(PipelineDefinition)} (each SCM built-in's token name and
     * each {@code http} check's own {@code auth} credential, FR11) — and the secrets port as the source of
     * every value it admits — base set, passthrough and the agent's AI seam variables alike — so no
     * child environment reads the process environment around the port (NFR-S3, finding F107 of
     * tighten-spec-contract). The port is handed into the allowlist as its value source and is not
     * readable back out of it.
     *
     * @throws IllegalArgumentException if a passthrough name is a declared credential
     */
    ChildEnvAllowlist childEnv(
            List<String> passthroughNames, PipelineDefinition definition, List<String> credentialEnvVarsToScrub) {
        var names = new ArrayList<>(credentialEnvVarsToScrub);
        names.addAll(subsections.credentialNames(definition));
        return ChildEnvAllowlist.of(passthroughNames, names, secretsProvider::find);
    }

    /**
     * The installation's child-environment allowlist for container runs: the operator's {@code
     * passthroughNames}, the SCM built-ins' credential names ({@link
     * BuiltInCheckSubsections#credentialNames()}, the installation half of the run's names),
     * and the secrets port as its value source (NFR-S3 of tighten-spec-contract). A container run
     * widens it with its own tracker and pipeline credential names ({@link
     * ChildEnvAllowlist#declaring}).
     *
     * @throws IllegalArgumentException if a passthrough name is a built-in's credential
     */
    ChildEnvAllowlist installationChildEnv(List<String> passthroughNames) {
        return ChildEnvAllowlist.of(passthroughNames, subsections.credentialNames(), secretsProvider::find);
    }

    /**
     * The run's built-in registry entries' collaborators (design D18 of
     * migrate-verify-to-executor-contract): the run's two in-process runners, the {@code http}
     * judgement guarded by the operator's {@code in-process} scope over the run's variables, and the
     * secrets port the SCM tokens resolve through, never readable back out.
     */
    BuiltInEntries builtIns(
            ChildEnvAllowlist childEnv,
            @Nullable SandboxRunPieces sandbox,
            HttpRunContext runContext,
            InstantSource clock) {
        var http = HttpCheckProbe.guarded(operatorEgress(), secretsProvider, runContext);
        return new BuiltInEntries(
                builtinRunner(sandbox), commandRunner(childEnv, sandbox), http, secretsProvider, clock);
    }

    /** The run-assembly admission of the SCM built-ins' operator connections (design D18). */
    BuiltInConnections connections() {
        var reader = new ScmConnectionReader(ConnectionProfiles.of(factoryProperties.connections()), operatorEgress());
        return new BuiltInConnections(factoryProperties.check(), reader, secretsProvider);
    }

    private OperatorEgress operatorEgress() {
        return new OperatorEgress(factoryProperties.egress().inProcess());
    }
}
