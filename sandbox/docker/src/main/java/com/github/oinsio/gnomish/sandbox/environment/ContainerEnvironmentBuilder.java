package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.nio.file.Path;

/**
 * The per-role assembly behind {@link ContainerEnvironments}'s {@code
 * roundEnvironment}/{@code judgeEnvironment}/{@code verificationEnvironment} (FR3, FR8, FR13 of
 * add-sandbox-core): wires a {@link ContainerTaskExecutionEnvironment} with its {@link
 * EgressGuard} and mandatory {@link EnvironmentSelfCheck} into a {@link SelfCheckedEnvironment}
 * for one role key. Extracted from {@link ContainerEnvironments} for file size; the behavior is
 * unchanged.
 */
final class ContainerEnvironmentBuilder {

    private ContainerEnvironmentBuilder() {}

    /** Builds the self-checked environment for the given role key ({@code <key>}, {@code <key>-j}, or {@code <key>-v}). */
    static SelfCheckedEnvironment build(
            DockerCli docker,
            String key,
            Path sourceClone,
            ContainerHarvest harvester,
            SandboxProperties sandbox,
            Clock clock,
            ChildEnvAllowlist allowlist,
            Sleeper sleeper,
            Path guardConfigRoot) {
        var environment = new ContainerTaskExecutionEnvironment(
                docker,
                key,
                sourceClone,
                harvester,
                sandbox.image(),
                sandbox.runtime(),
                sandbox.limits(),
                sandbox.enforceDiskQuota(),
                clock,
                allowlist);
        var guard = new EgressGuard(
                docker, key, sandbox.guardImage(), sandbox.egressAllowlist(), guardConfigRoot.resolve(key));
        var selfCheck = new EnvironmentSelfCheck(
                environment, guard, docker, key, sandbox.runtime(), sandbox.egressAllowlist(), sleeper);
        return new SelfCheckedEnvironment(environment, selfCheck, guard);
    }
}
