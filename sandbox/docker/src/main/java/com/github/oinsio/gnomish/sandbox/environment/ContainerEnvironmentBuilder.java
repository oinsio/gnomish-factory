package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The per-role assembly behind {@link ContainerEnvironments}'s {@code
 * roundEnvironment}/{@code judgeEnvironment}/{@code verificationEnvironment} (FR3, FR8, FR13 of
 * add-sandbox-core): wires a {@link ContainerTaskExecutionEnvironment} with its {@link
 * EgressGuard} and mandatory {@link EnvironmentSelfCheck} into a {@link SelfCheckedEnvironment}
 * for one role key.
 *
 * <p>An instance constructed once per task with the equipment every role shares (design D11 of
 * add-parameter-count-gate — the fields-not-parameters shape of {@code process-invariants.md});
 * {@link #build} takes only the per-call job, the role key. It is a relay for the installation's
 * {@link BoxEquipment} (design D12 of tighten-spec-contract): the value is taken whole and handed
 * whole to the {@link ContainerTaskExecutionEnvironment}, and its members to the leaves that use
 * exactly them ({@link EgressGuard}, {@link EnvironmentSelfCheck}). It is not an assembly object
 * in the sense of {@code testing.md}: {@link #scrubsCredential} computes the allowlist probe
 * {@link ContainerEnvironments} delegates to, so the class stays in the mutation scope, its
 * mutants killed by {@code ContainerEnvironmentsSeamSpec}.
 *
 * <p>The guard admits the operator's {@code factory.egress.sandbox} and the hosts the task's law
 * grants its {@code program} executors (design D10 of define-executor-contract; design D16 of
 * migrate-verify-to-executor-contract); a {@code program} verdict check's fresh box admits the
 * operator's list and that one executor's grant ({@link #buildGranted}, design D6 of that change).
 *
 * <p>Implements FR6 of add-parameter-count-gate; FR13 of tighten-spec-contract; FR10 of
 * define-executor-contract; NFR-S1 of migrate-verify-to-executor-contract.
 */
final class ContainerEnvironmentBuilder {

    private final BoxEquipment equipment;
    private final BoxGitLink link;
    private final ChildEnvAllowlist allowlist;
    private final String projectId;
    private final List<String> guardAllowlist;
    private final List<String> operatorEgress;

    /**
     * @param equipment the installation's box equipment every role is built with; never null
     * @param link the factory clone the boxes are seeded from and the fetch back into it; never null
     * @param allowlist the run's layered child-env allowlist (D6, FR9 of add-sandbox-core); never
     *     null
     * @param projectId the project identity stamped beside the ownership mode on every object this
     *     task creates (FR8 of add-serve-sandbox-lifecycle); never blank
     * @param executorEgress the hosts the law's {@code program} executors are granted (design D10 of
     *     define-executor-contract), admitted by the guard beside the operator's
     *     {@code factory.egress.sandbox}; never null, may be empty
     */
    ContainerEnvironmentBuilder(
            BoxEquipment equipment,
            BoxGitLink link,
            ChildEnvAllowlist allowlist,
            String projectId,
            List<String> executorEgress) {
        this.equipment = equipment;
        this.link = link;
        this.allowlist = allowlist;
        this.projectId = projectId;
        this.operatorEgress = List.copyOf(equipment.egress().entries());
        this.guardAllowlist = withOperator(executorEgress);
    }

    /** Builds the self-checked environment for the given role key ({@code <key>}, {@code <key>-j}, or {@code <key>-v}). */
    SelfCheckedEnvironment build(String key) {
        return build(key, guardAllowlist);
    }

    /**
     * Builds the self-checked environment for {@code key} whose guard admits the operator's {@code
     * factory.egress.sandbox} and {@code grant} alone — the fresh box of one executor's verdict check
     * (design D6 of migrate-verify-to-executor-contract), never the other executors' grants.
     */
    SelfCheckedEnvironment buildGranted(String key, List<String> grant) {
        return build(key, withOperator(grant));
    }

    private List<String> withOperator(List<String> hosts) {
        List<String> union = new ArrayList<>(operatorEgress);
        union.addAll(hosts);
        return List.copyOf(union);
    }

    private SelfCheckedEnvironment build(String key, List<String> admitted) {
        var docker = equipment.docker();
        var sandbox = equipment.sandbox();
        var environment = new ContainerTaskExecutionEnvironment(equipment, key, link, allowlist, projectId);
        var guard = new EgressGuard(
                docker,
                key,
                sandbox.guardImage(),
                admitted,
                equipment.guardConfig(key),
                equipment.ownership(projectId));
        var selfCheck = new EnvironmentSelfCheck(
                environment,
                guard,
                docker,
                key,
                sandbox.runtime(),
                // The operator's own list: the self-check's allowed-destination probe dials one of
                // its hosts, which must not be an executor's API the box may not reach at startup.
                operatorEgress,
                equipment.timing().equipment().sleeper());
        return new SelfCheckedEnvironment(environment, selfCheck, guard);
    }

    /** Whether the composed allowlist scrubs {@code credentialEnvVar}; see {@link ContainerEnvironments#scrubsCredential}. */
    boolean scrubsCredential(String credentialEnvVar) {
        return allowlist.compose(List.of(), Map.of(credentialEnvVar, "probe")).isEmpty();
    }
}
