package com.github.oinsio.gnomish.adapter.secrets

import com.github.oinsio.gnomish.app.SecretLocation
import com.github.oinsio.gnomish.app.UsageException
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectLayout
import com.github.oinsio.gnomish.app.project.ProjectName
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.function.Function
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR4, NFR-S1 of add-doctor-command: the secrets adapter answers where it finds a secret by
 * the one search its {@code find} runs — the project's folder, the host's folder, the file {@code
 * N_FILE} names, the variable {@code N} — or every place it looked, and never the value; a place it
 * refuses is refused here too.
 */
class EnvFileSecretLocatorSpec extends Specification {

    static final String NAME = 'GNOMISH_GITHUB_TOKEN'

    @TempDir
    Path tempDir

    FactoryHome home
    ProjectLayout project

    def setup() {
        home = FactoryHome.at(tempDir.resolve('home'))
        project = home.project(new ProjectName('widgets'))
    }

    def "FR1: a secret in #place is found there, and find reads the same value"() {
        given:
        def env = [:]
        Path file = null
        switch (place) {
            case 'the project folder': file = secretFile(project.secret(NAME)); break
            case 'the host folder': file = secretFile(home.hostSecret(NAME)); break
            case 'the file N_FILE names':
                file = secretFile(tempDir.resolve('mounted'))
                env[NAME + '_FILE'] = file.toString()
                break
            default: env[NAME] = 'value'
        }
        def provider = provider(env)

        when:
        def location = provider.locate(NAME)

        then:
        location instanceof SecretLocation.Found
        (location as SecretLocation.Found).place() == expected(place, file)
        !location.toString().contains('value')
        provider.find(NAME) == Optional.of('value')

        where:
        place << [
            'the project folder',
            'the host folder',
            'the file N_FILE names',
            'the environment'
        ]
    }

    def "FR1: an absent secret names every place searched, in the search's order"() {
        expect:
        provider([:]).locate(NAME) == new SecretLocation.Absent([
            project.secret(NAME).toString(),
            home.hostSecret(NAME).toString(),
            "the file the environment variable ${NAME}_FILE names".toString(),
            "the environment variable ${NAME}".toString(),
        ])
        provider([:], null).locate(NAME) == new SecretLocation.Absent([
            home.hostSecret(NAME).toString(),
            "the file the environment variable ${NAME}_FILE names".toString(),
            "the environment variable ${NAME}".toString(),
        ])
    }

    def "NFR-S1: a blank value is absent, as find has it"() {
        given:
        def provider = provider([(NAME): '  '])

        expect:
        provider.locate(NAME) instanceof SecretLocation.Absent
        provider.find(NAME) == Optional.empty()
    }

    def "FR1: a folder file others may read is refused by locate as by find"() {
        given:
        def file = secretFile(home.hostSecret(NAME))
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString('rw-r--r--'))

        when:
        provider([:]).locate(NAME)

        then:
        def refusal = thrown(UsageException)
        refusal.message.contains('chmod 600 ' + file)
    }

    private EnvFileSecretsProvider provider(Map<String, String> env, ProjectLayout layout = project) {
        new EnvFileSecretsProvider(home, layout, { String name ->
            env.get(name)
        } as Function)
    }

    private static Path secretFile(Path file) {
        Files.createDirectories(file.parent)
        Files.writeString(file, 'value\n')
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString('rw-------'))
        file
    }

    private String expected(String place, Path file) {
        switch (place) {
            case 'the file N_FILE names': return "the file ${file} (named by ${NAME}_FILE)".toString()
            case 'the environment': return "the environment variable ${NAME}".toString()
            default: return file.toString()
        }
    }
}
