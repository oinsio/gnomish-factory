package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.DoNotMutate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AI base-url/auth-token seam of the agent adapters (design D6, FR9 of
 * add-sandbox-core): the factory-set protocol variables that point the agent CLI
 * at its model provider — {@code ANTHROPIC_BASE_URL}, {@code
 * ANTHROPIC_AUTH_TOKEN}, {@code ANTHROPIC_MODEL} (the same three the Ollama E2E
 * path uses, D11 of add-agent-executor). With the layered child-environment
 * allowlist nothing is inherited implicitly, so these names are read live from
 * the factory environment and set explicitly on every agent round and judge
 * vote; a name unset in the factory environment is simply omitted (a logged-in
 * CLI resolves credentials from {@code HOME}). The change-B virtual-key gateway
 * plugs into this exact seam with no code change here.
 *
 * <p>Implements FR9 of add-sandbox-core.
 */
final class AgentAiSeam {

    /** The seam variable names, in the order they are applied. */
    static final List<String> NAMES = List.of("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_MODEL");

    private AgentAiSeam() {}

    /**
     * PIT M4 documented exception (build.gradle has the full rationale): {@code
     * @DoNotMutate} because this is a one-line delegation to the JDK platform API
     * {@code System.getenv()} with no seam to inject a fake environment into it
     * (no precedent for that in this codebase's other env-touching adapters, and
     * reflecting into {@code ProcessEnvironment} is blocked by module access on
     * this JDK without a build-wide {@code --add-opens} that nothing else needs).
     * The mutant that replaces this method's body with {@code emptyMap()} is
     * indistinguishable from the real behavior whenever none of {@link #NAMES}
     * happen to be set in the test process's environment — the common case,
     * including CI. All of this method's actual logic is exhaustively covered
     * via {@link #fromEnvironment(Map)}, which {@code AgentAiSeamSpec} drives
     * directly with both present and absent variables.
     */
    @DoNotMutate
    static Map<String, String> fromFactoryEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /** Testing seam: the same selection over a caller-supplied environment map. */
    static Map<String, String> fromEnvironment(Map<String, String> factoryEnvironment) {
        Map<String, String> seam = new LinkedHashMap<>();
        for (String name : NAMES) {
            String value = factoryEnvironment.get(name);
            if (value != null) {
                seam.put(name, value);
            }
        }
        return seam;
    }
}
