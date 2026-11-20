package com.github.oinsio.gnomish.adapter.agent.claude;

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * What the Claude CLI needs from its environment, as the dialect's describe declares it (FR7, NFR-S1,
 * design D8 of extract-agent-round-runner): its credential variables, the host they reach, the
 * variable naming its configuration directory and the flags that disable its repo-local policy
 * sources.
 *
 * <p>The credentials are the provider variables that point the CLI at its model provider — {@code
 * ANTHROPIC_BASE_URL}, {@code ANTHROPIC_AUTH_TOKEN}, {@code ANTHROPIC_MODEL} (the three the Ollama
 * end-to-end path sets) — and the CLI's own two credentials, {@code CLAUDE_CODE_OAUTH_TOKEN} (a
 * subscription token from {@code claude setup-token}) and {@code ANTHROPIC_API_KEY}. Nothing is
 * inherited by a child, so a round receives these names only as resolved through the run's
 * child-environment allowlist — whose values are the secrets port, in production (finding F107 of
 * tighten-spec-contract) — and only those that resolve: a CLI logged in through {@code HOME} needs
 * none. They reach agent rounds and judge votes, never a {@code command} check (NFR-S3 of
 * fix-operator-blockers).
 *
 * <p>This class is the only production source that spells the credential names ({@code
 * AgentCredentialSeamBoundarySpec}): the agent adapters' former AI seam class was deleted and its
 * names moved here (NFR-S1 of extract-agent-round-runner).
 *
 * <p>Implements FR7, NFR-S1 of extract-agent-round-runner; FR9 of add-sandbox-core; FR5, NFR-S3 of
 * fix-operator-blockers; NFR-S3 of tighten-spec-contract; FR1, NFR-S1 of add-doctor-command.
 */
public final class ClaudeEnvironment {

    private static final String BASE_URL = "ANTHROPIC_BASE_URL";
    private static final String AUTH_TOKEN = "ANTHROPIC_AUTH_TOKEN";
    private static final String OAUTH_TOKEN = "CLAUDE_CODE_OAUTH_TOKEN";
    private static final String API_KEY = "ANTHROPIC_API_KEY";

    /** The credential variables the describe declares, in the order a round's environment holds them. */
    public static final List<String> CREDENTIALS =
            List.of(BASE_URL, AUTH_TOKEN, "ANTHROPIC_MODEL", OAUTH_TOKEN, API_KEY);

    /**
     * The credentials that carry a secret, in {@link #CREDENTIALS} order: the provider's auth token and
     * the CLI's own two. The base URL and the model point the CLI somewhere and authenticate nothing.
     */
    public static final List<String> SECRETS = List.of(AUTH_TOKEN, OAUTH_TOKEN, API_KEY);

    /** The variable naming the CLI's configuration directory. */
    static final String CONFIG_DIR_VARIABLE = "CLAUDE_CONFIG_DIR";

    /**
     * The flags that disable the CLI's repo-local policy sources: only the user's settings are loaded
     * (never the working copy's {@code .claude/settings*.json}), and only the MCP servers the argv
     * names (never the working copy's {@code .mcp.json}). Declared here; their enforcement is
     * {@code harden-verification-integrity}'s (NG4 of extract-agent-round-runner).
     */
    static final List<String> POLICY_DISABLING_FLAGS = List.of("--setting-sources=user", "--strict-mcp-config");

    /** The host the CLI reaches when no base URL is configured. */
    static final String DEFAULT_HOST = "api.anthropic.com";

    private ClaudeEnvironment() {}

    /**
     * The credential variables a round carries: the declared names that resolve through {@code
     * childEnv}, with their values, in {@link #CREDENTIALS} order; a name that does not resolve, or
     * that the allowlist withholds, is omitted.
     *
     * @param childEnv the run's child-environment allowlist; never null
     * @return the present credential variables; never null
     */
    public static Map<String, String> variables(ChildEnvAllowlist childEnv) {
        return childEnv.resolve(CREDENTIALS);
    }

    /**
     * The host the CLI reaches: the host of the base URL {@code childEnv} resolves, or {@link
     * #DEFAULT_HOST} when none resolves.
     *
     * @param childEnv the run's child-environment allowlist; never null
     * @return the egress host; never blank
     * @throws IllegalArgumentException if the configured base URL names no host
     */
    static String egressHost(ChildEnvAllowlist childEnv) {
        String baseUrl = childEnv.resolve(List.of(BASE_URL)).get(BASE_URL);
        if (baseUrl == null) {
            return DEFAULT_HOST;
        }
        String host;
        try {
            host = new URI(baseUrl.strip()).getHost();
        } catch (URISyntaxException e) {
            host = null;
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    BASE_URL + " names no host, so the Claude CLI's egress cannot be declared; set it to a URL"
                            + " such as https://" + DEFAULT_HOST);
        }
        return host;
    }
}
