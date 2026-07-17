package com.github.oinsio.gnomish.e2e.ollama

/**
 * Environment variables that point the real {@code claude} CLI at a local Ollama instance (task
 * 11.1, M1, D11): Ollama v0.14+ speaks the native Anthropic API, so no shim or proxy is needed —
 * {@code ANTHROPIC_BASE_URL} alone redirects the CLI's requests. {@link
 * com.github.oinsio.gnomish.FactoryProperties#agentCliEnvPassthrough()} is documentation-only
 * (design D7: the spawned process inherits the JVM's full environment already), so wiring these
 * three variables happens by setting them on the process that ultimately spawns {@code claude} —
 * here, the {@code java -jar} subprocess {@link com.github.oinsio.gnomish.e2e.E2eProcessHarness}
 * launches, via its {@code extraEnv} parameter — not through any allowlist code.
 *
 * <p>Each value is overridable by an already-set environment variable of the same name, so a
 * developer with a non-default Ollama port or a specific pulled model does not need to edit this
 * file.
 *
 * <p>Implements M1, D11 of add-agent-executor.
 */
final class OllamaEnv {

    /** Ollama's native Anthropic-compatible endpoint (v0.14+). */
    static final String BASE_URL_VAR = 'ANTHROPIC_BASE_URL'

    /**
     * The CLI requires a non-empty auth token env var to start; Ollama does not validate it, so
     * any non-blank placeholder value works (D11).
     */
    static final String AUTH_TOKEN_VAR = 'ANTHROPIC_AUTH_TOKEN'

    /** Selects which locally-pulled Ollama model {@code claude} targets. */
    static final String MODEL_VAR = 'ANTHROPIC_MODEL'

    private static final String DEFAULT_AUTH_TOKEN = 'ollama-local-placeholder'
    private static final String DEFAULT_MODEL = 'qwen2.5-coder:7b'

    private OllamaEnv() {}

    /**
     * @return the three env vars needed to point {@code claude -p} at a local Ollama instance,
     *     each defaulted and overridable via an already-set environment variable of the same name
     */
    static Map<String, String> forLocalOllama() {
        [
            (BASE_URL_VAR): System.getenv(BASE_URL_VAR) ?: OllamaAvailability.DEFAULT_BASE_URL,
            (AUTH_TOKEN_VAR): System.getenv(AUTH_TOKEN_VAR) ?: DEFAULT_AUTH_TOKEN,
            (MODEL_VAR): System.getenv(MODEL_VAR) ?: DEFAULT_MODEL,
        ]
    }
}
