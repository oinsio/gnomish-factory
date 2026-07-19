package com.github.oinsio.gnomish.e2e.gitea

import org.testcontainers.DockerClientFactory

/**
 * Docker-prerequisite detection for the Gitea E2E layer (task 6.5, FR11, G2): a reachable Docker
 * daemon is required to start the {@code gitea/gitea} container. {@code .claude/rules/testing.md}
 * treats Docker as a dev/CI prerequisite for this layer specifically (unlike the Ollama E2E layer,
 * which deliberately avoids Testcontainers — see {@code OllamaAvailability}'s doc comment), so
 * specs built on {@link GiteaContainerFixture} skip cleanly instead of failing when Docker is
 * absent, mirroring the {@code OllamaAvailability}/{@code @IgnoreIf} convention already
 * established by the Ollama E2E layer.
 *
 * <p>Implements FR11 of add-git-workflow (G2 infra).
 */
final class GiteaAvailability {

    private GiteaAvailability() {}

    /**
     * @return {@code true} when a Docker daemon is reachable via the environment Testcontainers
     *     itself would use (same probe {@code DockerClientFactory} performs internally before
     *     starting any container); never throws — any detection failure reads as "not available"
     */
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable()
        } catch (RuntimeException | Error ignored) {
            return false
        }
    }
}
