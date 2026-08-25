package com.github.oinsio.gnomish;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The defaulting and validation companion of {@link FactoryProperties}: one static resolver per
 * record component, each turning "unset" into the documented default and an invalid explicit value
 * into a startup error naming the external property. Kept outside the record deliberately — PIT's
 * record filter suppresses all mutations inside a record's canonical constructor, which would
 * silently exempt this validation logic from the 100% mutation gate (FR6 of add-project-skeleton);
 * a plain class keeps every branch in the mutation scope.
 *
 * <p>Every parameter is {@code @Nullable} because Spring's reflective constructor binding can pass
 * null for any component despite this package's {@code @NullMarked} default.
 *
 * <p>Implements FR3 of add-project-skeleton; FR11 of add-agent-executor; FR7 of
 * fix-round-stdout-drain; FR5 of bound-subprocess-commands.
 */
final class FactoryPropertyDefaults {

    static final String DEFAULT_INSTANCE_NAME = "gnomish-factory";
    static final String DEFAULT_AGENT_CLI_BINARY = "claude";
    static final Duration DEFAULT_AGENT_CLI_TAIL_DRAIN_GRACE = Duration.ofSeconds(5);
    // The three subprocess deadlines of bound-subprocess-commands (design D8). Each mirrors the
    // adapter-side constant that documents the same value for the no-argument construction path
    // (GitProcessRunner.DEFAULT_NETWORK_TIMEOUT, DockerCli.DEFAULT_COMMAND_TIMEOUT,
    // CommandProcessRunner.DEFAULT_CHECK_TIMEOUT); those live in other modules that this root
    // package may not reference (process-invariants module-boundary rule), so the pairs are kept
    // in sync by hand, exactly as the tracker backoff defaults are.
    static final Duration DEFAULT_GIT_NETWORK_TIMEOUT = Duration.ofMinutes(5);
    static final Duration DEFAULT_DOCKER_COMMAND_TIMEOUT = Duration.ofMinutes(5);
    static final Duration DEFAULT_CHECK_COMMAND_TIMEOUT = Duration.ofMinutes(30);

    private FactoryPropertyDefaults() {}

    /**
     * Resolves the unset case to the neutral {@code gnomish-factory} default (design D5, D6); a
     * value explicitly set to blank (e.g. {@code factory.instance-name=""}) is still rejected —
     * that is a configuration mistake, not "unset".
     */
    static String instanceName(@Nullable String instanceName) {
        if (instanceName == null) {
            return DEFAULT_INSTANCE_NAME;
        }
        if (instanceName.isBlank()) {
            throw new IllegalArgumentException("factory.instance-name must not be blank");
        }
        return instanceName;
    }

    /** Resolves the unset case to the {@code claude}-on-PATH default (design D7). */
    static String agentCliBinary(@Nullable String agentCliBinary) {
        return agentCliBinary == null ? DEFAULT_AGENT_CLI_BINARY : agentCliBinary;
    }

    /**
     * Resolves the unset case to the 5-second default and rejects a non-positive value — a grace
     * of zero or less could never absorb a piped tail, so it is a configuration mistake caught at
     * startup, before any dialog (FR7, design D2 of fix-round-stdout-drain). A malformed value
     * ({@code "banana"}) never reaches here: Spring's Duration conversion fails the bind first,
     * which is the same startup error one layer up.
     */
    static Duration tailDrainGrace(@Nullable Duration agentCliTailDrainGrace) {
        if (agentCliTailDrainGrace == null) {
            return DEFAULT_AGENT_CLI_TAIL_DRAIN_GRACE;
        }
        if (agentCliTailDrainGrace.isZero() || agentCliTailDrainGrace.isNegative()) {
            throw new IllegalArgumentException("factory.agent-cli-tail-drain-grace must be positive");
        }
        return agentCliTailDrainGrace;
    }

    /**
     * Resolves the unset case to {@code fallback} and rejects a non-positive value for any of the
     * three subprocess deadlines (FR5, design D8 of bound-subprocess-commands). A deadline of zero
     * or less would kill every command before it could start, so it is a configuration mistake
     * caught at startup rather than a per-command mystery; a malformed value ({@code "banana"})
     * never reaches here, since Spring's Duration conversion fails the bind one layer up. One
     * shared validator over three copies: the three differ only in default and property name, and
     * {@code propertyName} keeps the startup error pointing at the knob the operator set.
     */
    static Duration positiveTimeout(@Nullable Duration configured, Duration fallback, String propertyName) {
        if (configured == null) {
            return fallback;
        }
        if (configured.isZero() || configured.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        return configured;
    }

    /** Resolves the unset case to an empty passthrough list (design D7). */
    static List<String> envPassthrough(@Nullable List<String> agentCliEnvPassthrough) {
        return agentCliEnvPassthrough == null ? List.of() : agentCliEnvPassthrough;
    }

    /** Resolves the unset case to {@link FactoryProperties.Tracker}'s own defaults (design D5, D10). */
    static FactoryProperties.Tracker tracker(FactoryProperties.@Nullable Tracker tracker) {
        return tracker == null ? new FactoryProperties.Tracker(null, null) : tracker;
    }

    /**
     * Resolves the unset case to an empty map (no check provider configured, no connection profile
     * defined) and defends the map's immutability. No key or value is interpreted here: both {@code
     * factory.check} and {@code factory.connections} are open-ended sets of named subsections whose
     * content only the provider's own {@code CheckSubsectionValidator} may grade (FR4, FR5, FR16,
     * design D12 of add-plugin-architecture) — which is why the vendor-shaped {@code Check.Github}
     * record with its both-or-neither constructor is gone from core.
     */
    static Map<String, Map<String, Object>> subsections(@Nullable Map<String, Map<String, Object>> sections) {
        if (sections == null) {
            return Map.of();
        }
        var copy = new LinkedHashMap<String, Map<String, Object>>();
        sections.forEach((name, content) -> copy.put(name, content == null ? Map.of() : Map.copyOf(content)));
        return Map.copyOf(copy);
    }
}
