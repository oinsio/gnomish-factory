package com.github.oinsio.gnomish.e2e.ollama

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Native-prerequisite detection for the Ollama E2E layer (task 11.1, M1, D11): a reachable local
 * Ollama instance and a {@code claude} CLI binary on {@code PATH}. Dockerized Ollama has no Metal
 * access on macOS and is unusably slow (D11), so this layer runs against a real local Ollama
 * install rather than Testcontainers — this class is the "is that prerequisite actually there"
 * check the {@code ollamaE2eTest} Gradle task's specs use to skip cleanly instead of failing when
 * a developer's machine does not have it.
 *
 * <p>Implements M1, D11 of add-agent-executor.
 */
final class OllamaAvailability {

    /** Ollama's default local HTTP endpoint (also the default {@code ANTHROPIC_BASE_URL} target). */
    static final String DEFAULT_BASE_URL = 'http://localhost:11434'

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2)

    private OllamaAvailability() {}

    /**
     * @param baseUrl the Ollama endpoint to probe; defaults to {@link #DEFAULT_BASE_URL}
     * @return {@code true} when an HTTP GET against the endpoint root succeeds (any response —
     *     Ollama answers 200 on {@code /} — proves a listener is up); {@code false} on any
     *     connection failure or timeout, never throws
     */
    static boolean ollamaReachable(String baseUrl = DEFAULT_BASE_URL) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build()
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(PROBE_TIMEOUT)
                .GET()
                .build()
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding())
            return response.statusCode() < 500
        } catch (IOException | InterruptedException ignored) {
            return false
        }
    }

    /**
     * @param binary the CLI binary name or path to resolve; defaults to {@code claude}
     * @return {@code true} when the binary resolves on {@code PATH} (or is an executable file at
     *     an explicit path); a login/auth check is out of scope — Ollama does not validate the
     *     auth token anyway (D11), so any resolvable binary is enough for this layer
     */
    static boolean claudeCliAvailable(String binary = 'claude') {
        java.nio.file.Path explicit = pathIfExplicit(binary)
        if (explicit != null) {
            return java.nio.file.Files.isExecutable(explicit)
        }
        String pathEnv = System.getenv('PATH')
        if (pathEnv == null) {
            return false
        }
        return pathEnv.split(java.io.File.pathSeparator).any { dir ->
            java.nio.file.Files.isExecutable(java.nio.file.Path.of(dir, binary))
        }
    }

    private static java.nio.file.Path pathIfExplicit(String binary) {
        binary.contains(java.io.File.separator) ? java.nio.file.Path.of(binary) : null
    }

    /** @return {@code true} only when both the Ollama endpoint and the {@code claude} CLI are available */
    static boolean harnessReady(String baseUrl = DEFAULT_BASE_URL, String binary = 'claude') {
        ollamaReachable(baseUrl) && claudeCliAvailable(binary)
    }
}
