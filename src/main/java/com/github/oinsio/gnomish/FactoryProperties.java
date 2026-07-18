package com.github.oinsio.gnomish;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Immutable typed configuration of a factory instance, bound from the {@code factory.*}
 * external properties via constructor binding (design D4). Validation is plain Java
 * triggered by the compact constructor — directly spec-able and mutation-testable,
 * no Bean Validation.
 *
 * <p>Implements FR3 of add-project-skeleton.
 *
 * <p>{@code agentCliBinary} and {@code agentCliEnvPassthrough} are installation-level
 * agent-executor configuration: the CLI binary path and the environment variable names
 * passed through to the spawned CLI process (e.g. the Ollama seam). These never live in
 * the stage manifest (design D7). Implements FR11 of add-agent-executor.
 *
 * @param instanceId unique identifier of this factory instance
 *     ({@code factory.instance-id}); never null or blank
 * @param agentCliBinary path or name of the agent CLI binary
 *     ({@code factory.agent-cli-binary}); defaults to {@code "claude"} (resolved from
 *     {@code PATH}) when unset
 * @param agentCliEnvPassthrough names of environment variables passed through to the
 *     spawned CLI process ({@code factory.agent-cli-env-passthrough}); defaults to an
 *     empty list when unset
 */
@ConfigurationProperties("factory")
public record FactoryProperties(String instanceId, String agentCliBinary, List<String> agentCliEnvPassthrough) {

    private static final String DEFAULT_AGENT_CLI_BINARY = "claude";

    public FactoryProperties {
        instanceId = requireValidInstanceId(instanceId);
        agentCliBinary = defaultAgentCliBinary(agentCliBinary);
        agentCliEnvPassthrough = defaultEnvPassthrough(agentCliEnvPassthrough);
    }

    /**
     * Fails fast on a missing or blank instance id. Kept as an explicit method rather
     * than inline in the compact constructor: PIT's record filter suppresses all
     * mutations inside a record's canonical constructor, which would silently exempt
     * the validation logic from the 100% mutation gate (FR6). The parameter is
     * {@code @Nullable} because framework property binding constructs the record
     * reflectively and can pass null despite this package's {@code @NullMarked} default.
     */
    private static String requireValidInstanceId(@Nullable String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("factory.instance-id must not be null or blank");
        }
        return instanceId;
    }

    /**
     * Resolves the unset case to the {@code claude}-on-PATH default (design D7). Kept as
     * an explicit method for the same PIT record-constructor reason as {@link
     * #requireValidInstanceId}.
     */
    private static String defaultAgentCliBinary(@Nullable String agentCliBinary) {
        return agentCliBinary == null ? DEFAULT_AGENT_CLI_BINARY : agentCliBinary;
    }

    /**
     * Resolves the unset case to an empty passthrough list (design D7). Kept as an
     * explicit method for the same PIT record-constructor reason as {@link
     * #requireValidInstanceId}.
     */
    private static List<String> defaultEnvPassthrough(@Nullable List<String> agentCliEnvPassthrough) {
        return agentCliEnvPassthrough == null ? List.of() : agentCliEnvPassthrough;
    }
}
