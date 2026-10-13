package com.github.oinsio.gnomish.app

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.any
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.tomakehurst.wiremock.WireMockServer
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The board's read-only promise on the wire (NG3, NFR-S1 of add-board-command): {@code gnomish
 * board} runs through the shipped GitHub adapter — {@link GithubTrackerAdapterFactory}, resolved by
 * {@link TrackerWiring} exactly as the composition root resolves it — against an in-JVM WireMock
 * stand-in for the GitHub API, and every request the stand-in recorded is a read.
 *
 * <p>{@code BoardCommandSpec} (application) proves the same promise one level up, on the port: a
 * read-only tracker fake that fails on any write method. This spec is the adapter-level half — what
 * actually leaves the process. The repository's labels already exist, as on any repository a
 * factory has served: on one that lacks them, the adapter's startup provisioning creates them.
 *
 * <p>Implements NG3, NFR-S1 of add-board-command.
 */
class BoardGithubReadOnlySpec extends Specification implements ApplicationArgumentsFixture, StdoutCaptureFixture {

    private static final String READY_FEED =
    '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'
    private static final String LABELS = '''[
        {"name":"gnomish:ready","color":"2ea44f"},
        {"name":"gnomish:working","color":"1f6feb"},
        {"name":"gnomish:needs-human","color":"d73a4a"},
        {"name":"gnomish:delivered","color":"8250df"}
    ]'''

    @TempDir
    Path tempDir

    WireMockServer github = new WireMockServer(options().dynamicPort())
    Path projectDir

    def setup() {
        github.start()
        projectDir = tempDir.resolve('project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'),
                'purpose: build it\nexecutor:\n  type: agent-cli\n  model: model-x\n' +
                'instructions: stages/build/instructions.md\nadvancement: auto\n')
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'),
                'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\ntracker:\n  type: github\n' +
                "  github:\n    api-url: ${github.baseUrl()}\n    repo: acme/widgets\n")
    }

    def cleanup() {
        github.stop()
    }

    private BoardCommand boardCommand() {
        new BoardCommand(
                new VirtualClock(),
                new FactoryProperties('board-instance', null, null, null),
                RegisteredCloneFixture.scope(
                        RegisteredCloneFixture.unregistered(tempDir.resolve('home'), projectDir), 'board-instance'),
                new TrackerWiring(
                        [github: new GithubTrackerAdapterFactory()],
                        new MapSecretsProvider([GNOMISH_GITHUB_TOKEN: 'token-for-the-stub']),
                        TrackerValidatorStub.acceptingGithubSource(), VirtualTimeEquipment.create()),
                liveConsole())
    }

    // F92: scenario text aligned to what this test asserts
    // Scenario: observability/tracker-board / No write operations reach the tracker
    def "NG3: a board run through the GitHub adapter issues only GET requests"() {
        given: 'a provisioned repository with one ready issue; every other request, a write included, is answered'
        github.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(200).withBody('[]')))
        github.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100')).atPriority(1)
                .willReturn(aResponse().withStatus(200).withBody(LABELS)))
        github.stubFor(get(urlEqualTo(READY_FEED)).atPriority(1)
                .willReturn(aResponse().withStatus(200).withBody('[{"number":7,"title":"Add widgets"}]')))

        when:
        def output = captureStdout {
            boardCommand().run(args('board', "--dir=${projectDir}".toString()))
        }

        then: 'the board really read the tracker'
        output.contains('Add widgets')

        and: 'every recorded request is a read'
        def requests = github.allServeEvents*.request
        !requests.isEmpty()
        requests*.method*.value().unique() == ['GET']
    }
}
