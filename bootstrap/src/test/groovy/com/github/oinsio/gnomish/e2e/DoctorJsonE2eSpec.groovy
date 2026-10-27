package com.github.oinsio.gnomish.e2e

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.app.doctor.DoctorCheckId
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.TempDir

/**
 * {@code gnomish doctor --json} through the packaged jar (FR5, FR6 of add-doctor-command): the
 * whole of stdout is one JSON document — no Spring Boot banner and no log line ahead of it or after
 * it — carrying the document version and every offline check, and the exit code is the one the text
 * form of the same run returns. In-process specs switch the banner off themselves, so only the
 * real process shows what an automation reading stdout gets.
 *
 * <p>The clone is registered with a GitHub tracker and no token, so the run has a {@code fail} and
 * both forms exit 1 whatever the machine's Docker state.
 */
class DoctorJsonE2eSpec extends AbstractE2eProcessSpec implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private Path registeredClone(FactoryHome home) {
        Path dir = initWorkingRepo(tempDir, 'widgets')
        Files.createDirectories(dir.resolve('.gnomish'))
        Files.writeString(dir.resolve('.gnomish/config.yaml'), '''\
schemaVersion: "1"
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        commitAll(dir)
        ProjectRegistry.scan(home).add(new ProjectName('widgets'), dir)
        dir
    }

    // FR5, FR6: stdout is one document an automation can parse, with the text form's exit code
    // Scenario: cli/operator-doctor / JSON for automation
    def "doctor --json from the packaged jar prints exactly one JSON document and exits as the text form does"() {
        given:
        FactoryHome home = FactoryHome.at(Files.createDirectories(tempDir.resolve('home')))
        Path dir = registeredClone(home)
        Map<String, String> env = [(FactoryHome.HOME_VARIABLE): home.root().toString()]

        when:
        def json = harness.execute('doctor', dir, ['--dir=' + dir, '--json'], [], false, env)
        def text = harness.execute('doctor', dir, ['--dir=' + dir], [], false, env)
        JsonParser parser = new ObjectMapper().createParser(json.stdout())
        JsonNode document = parser.readValueAsTree()

        then: 'the document is the whole of stdout: it opens it, and nothing follows it'
        json.stdout().startsWith('{')
        parser.nextToken() == null

        and: 'it carries the version and every offline check by its stable id'
        document.get('version').asInt() == 1
        List<String> ids = document.get('checks').collect {
            it.get('id').asText()
        }
        DoctorCheckId.values().findAll {
            !it.online()
        }.every {
            ids.contains(it.id())
        }
        document.get('checks').every { check ->
            [
                'id',
                'status',
                'detail',
                'fix'
            ].every { check.has(it) }
        }

        and: 'the missing token is a fail, and both forms exit 1'
        document.get('checks').any { it.get('status').asText() == 'fail' }
        json.exitCode() == 1
        text.exitCode() == json.exitCode()
    }
}
