package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import java.util.List;
import java.util.Map;

/**
 * The AI base-url/auth-token seam of the agent adapters (design D6, FR9 of
 * add-sandbox-core): the factory-set protocol variables that point the agent CLI
 * at its model provider — {@code ANTHROPIC_BASE_URL}, {@code
 * ANTHROPIC_AUTH_TOKEN}, {@code ANTHROPIC_MODEL} (the same three the Ollama E2E
 * path uses, D11 of add-agent-executor). With the layered child-environment
 * allowlist nothing is inherited implicitly, so these names are resolved live
 * through the round's {@link ChildEnvAllowlist} — the secrets port, in
 * production — and set explicitly on every agent round and judge vote; a name
 * that does not resolve is simply omitted (a logged-in CLI resolves credentials
 * from {@code HOME}). The change-B virtual-key gateway
 * plugs into this exact seam with no code change here.
 *
 * <p>The seam also carries the agent CLI's own credentials, {@code CLAUDE_CODE_OAUTH_TOKEN} (a
 * subscription token minted with {@code claude setup-token}) and {@code ANTHROPIC_API_KEY}
 * (design D3 of fix-operator-blockers). A box has no logged-in {@code HOME}, so without them a
 * container-bound agent round cannot authenticate; routing them here rather than through
 * {@code factory.sandbox.env-passthrough} keeps them on agent rounds and judge votes only — the
 * two callers of this class — and off every {@code command} check (NFR-S3). This class is the
 * only production source that names the two variables ({@code AgentCredentialSeamBoundarySpec}).
 *
 * <p>Implements FR9 of add-sandbox-core; FR5, NFR-S3 of fix-operator-blockers; NFR-S3 of
 * tighten-spec-contract.
 */
final class AgentAiSeam {

    /** The seam variable names, in the order they are applied. */
    static final List<String> NAMES = List.of(
            "ANTHROPIC_BASE_URL",
            "ANTHROPIC_AUTH_TOKEN",
            "ANTHROPIC_MODEL",
            "CLAUDE_CODE_OAUTH_TOKEN",
            "ANTHROPIC_API_KEY");

    private AgentAiSeam() {}

    /**
     * The seam variables present for one round or vote, resolved live through {@code childEnv} —
     * whose values the composition root binds to the secrets port, so an agent credential comes
     * from the same source as every other secret the factory holds (NFR-S3, finding F107 of
     * tighten-spec-contract): a token in the host secrets folder reaches the agent CLI exactly as
     * it reaches the tracker. A name that does not resolve is omitted.
     *
     * @param childEnv the round's child-environment allowlist; never null
     * @return the present seam variables, in {@link #NAMES} order; never null
     */
    static Map<String, String> variables(ChildEnvAllowlist childEnv) {
        return childEnv.resolve(NAMES);
    }
}
