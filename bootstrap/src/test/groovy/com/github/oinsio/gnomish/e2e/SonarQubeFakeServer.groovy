package com.github.oinsio.gnomish.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile

/**
 * The fake SonarQube server of the M3 end-to-end spec (task 7.1 of
 * migrate-verify-to-executor-contract): the committed {@code fake-sonarqube/server.py}, run in a
 * container on Docker's default bridge — the network the egress guard's second leg joins — so a box
 * reaches it through its guard by address, the one address the law grants the program. The image is
 * the guard's own, already a prerequisite of the container layer, for its Python; nothing else is
 * pulled. The spec reads what the server was asked on the port Docker publishes, outside every guard.
 */
final class SonarQubeFakeServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper()

    private final GenericContainer<?> container

    private SonarQubeFakeServer(GenericContainer<?> container) {
        this.container = container
    }

    /** Starts a server that accepts only {@code token}. */
    static SonarQubeFakeServer start(String token) {
        def container = new GenericContainer<>(GuardImageAvailability.IMAGE)
                .withCopyFileToContainer(MountableFile.forClasspathResource('/fake-sonarqube/server.py'), '/srv/server.py')
                .withEnv('SONAR_TOKEN_EXPECTED', token)
                .withCreateContainerCmdModifier { it.withEntrypoint('python3') }
                .withCommand('/srv/server.py')
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp('/__requests').forPort(9000).withStartupTimeout(Duration.ofSeconds(60)))
        container.start()
        new SonarQubeFakeServer(container)
    }

    /** The server's address on the default bridge: what a box's guard reaches it by. */
    String address() {
        container.containerInfo.networkSettings.networks['bridge'].ipAddress
    }

    /**
     * Every Web API call the server answered, in order: {@code method}, {@code path}, {@code query}
     * (each parameter's first value) and {@code authorized} — whether it carried the expected token.
     */
    List<Map<String, Object>> requests() {
        def response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://${container.host}:${container.getMappedPort(9000)}/__requests")).build(),
                HttpResponse.BodyHandlers.ofString())
        assert response.statusCode() == 200
        JSON.readValue(response.body(), List)
    }

    @Override
    void close() {
        container.stop()
    }
}
