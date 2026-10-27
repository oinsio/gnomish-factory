package com.github.oinsio.gnomish.app.doctor

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory
import com.github.oinsio.gnomish.app.DoctorFailedException
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.project.ProjectResolution
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.tomakehurst.wiremock.WireMockServer
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, NG5, NFR-R1 of add-doctor-command (design D7): {@code doctor} on a registered clone whose
 * law binds the shipped GitHub adapter, against a WireMock {@code acme/widgets} that lacks the
 * factory's labels. With {@code --online} the probe is exactly one {@code GET} of the label listing
 * with the configured credential and no {@code POST} — the adapter is never built, so nothing is
 * provisioned; without it no request reaches the stand-in. A refusal is a {@code fail} line with
 * the cause startup names, and on a 403 the permissions GitHub reports as accepted.
 */
class TrackerReadProbeGithubSpec extends Specification {

    private static final String LISTING = '/repos/acme/widgets/labels?per_page=100'

    @TempDir
    Path tempDir

    WireMockServer github = new WireMockServer(options().dynamicPort())
    ConsoleIO console = Mock()
    Path projectDir
    boolean failed

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

    private void listingAnswers(int status, String body, Map<String, String> headers = [:]) {
        def response = aResponse().withStatus(status).withBody(body)
        headers.each { k, v -> response = response.withHeader(k, v) }
        github.stubFor(get(urlEqualTo(LISTING)).willReturn(response))
    }

    /** The run's output lines, both renderings; {@link #failed} records whether it ended with exit code 1. */
    private List<String> doctor(String... options) {
        def printed = []
        console.print(_ as String) >> { String line -> printed << line }
        console.printMachine(_ as String) >> { String line -> printed << line }
        def clone = RegisteredCloneFixture.unregistered(tempDir.resolve('home'), projectDir)
        def beans = new DefaultListableBeanFactory()
        beans.registerSingleton('resolution', new ProjectResolution.Registered(clone))
        def readings = DoctorSpecSupport.readings([GNOMISH_GITHUB_TOKEN: 'tok-doctor'], [
            registry: [github: new GithubTrackerAdapterFactory()],
            source: TrackerValidatorStub.acceptingGithubSource()])
        def command = new DoctorCommand([
            new TrackerReadProbe(readings)
        ],
        new DoctorSurvey(beans.getBeanProvider(ProjectResolution), readings),
        new DefaultListableBeanFactory().getBeanProvider(RegisteredClone), console,
        DoctorCommandFixture.logFileEnvironment())
        try {
            command.run(new DefaultApplicationArguments((['doctor'] + options.toList()) as String[]))
            failed = false
        } catch (DoctorFailedException ignored) {
            failed = true
        }
        printed.join('').readLines()*.trim()
    }

    // Scenario: cli/operator-doctor / Online adds a read-only tracker probe
    def "FR3: --online on a repository without the labels makes one GET and no POST, and reports reading confirmed"() {
        given:
        listingAnswers(200, '[{"name":"gnomish:working"},{"name":"bug"}]')

        when:
        def lines = doctor('--online')

        then: 'one read-only request with the configured credential, and nothing else'
        github.verify(1, getRequestedFor(urlEqualTo(LISTING)).withHeader('Authorization', equalTo('Bearer tok-doctor')))
        github.verify(0, postRequestedFor(anyUrl()))
        github.verify(1, anyRequestedFor(anyUrl()))

        and: 'the line confirms reading, names the labels startup will create, and leaves write access open'
        lines[0] == 'ok  tracker read probe: reading confirmed; startup will create gnomish:ready, gnomish:needs-human, ' +
                'gnomish:delivered; write access is not verified'
        lines.size() == 2
        !failed
    }

    // Scenario: cli/operator-doctor / Offline by default
    // FR3, NFR-R1: without --online doctor opens no connection to the tracker
    def "FR3: without --online no request reaches the tracker and the probe has no line"() {
        given:
        listingAnswers(200, '[]')

        when:
        def lines = doctor(*arguments)

        then:
        github.verify(0, anyRequestedFor(anyUrl()))
        !lines.any {
            it.contains('tracker read probe') || it.contains('tracker-probe')
        }
        !failed

        where:
        arguments << [[], ['--json']]
    }

    // Scenario: cli/operator-doctor / Online probe refused
    def "FR3: a #status refusal is a fail line with the cause startup names, and the probe is not retried"() {
        given:
        listingAnswers(status, '{"message":"no"}', headers)

        when:
        def lines = doctor('--online')

        then: 'one request: a refusal, a bad credential included, is not retried'
        github.verify(1, anyRequestedFor(anyUrl()))
        github.verify(0, postRequestedFor(anyUrl()))

        and:
        lines.size() == 2
        lines[0] == "fail  tracker read probe: the tracker refused the probe with status ${status}: ${detail}; fix: ${TrackerReadProbe.REFUSED_FIX}".toString()
        failed

        where:
        status | headers || detail
        404 | [:] || 'repo not found or not visible to this token (likely cause: stale binding, e.g. a fork pointing at a repo the token cannot see)'
        403 | ['X-Accepted-GitHub-Permissions': 'issues=write; metadata=read'] || 'token lacks write access to this repo; the tracker accepts the permissions: issues=write; metadata=read'
        401 | [:] || 'unexpected HTTP 401'
    }
}
