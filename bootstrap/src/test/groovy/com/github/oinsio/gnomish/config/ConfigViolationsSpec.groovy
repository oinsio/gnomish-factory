package com.github.oinsio.gnomish.config

import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link ConfigViolations#variable}: the line a {@code FACTORY_*} variable earns (FR7, UX1) — the
 * equivalent file line and command-line form, and still one line when the value is not printable.
 *
 * <p>Implements FR7, UX1 of add-project-registry.
 */
class ConfigViolationsSpec extends Specification {

    @TempDir
    Path tmp

    FactoryHome home
    ConfigViolations violations

    def setup() {
        home = FactoryHome.at(tmp.resolve('home'))
        violations = new ConfigViolations(
                ConfigLevels.application(), new ConfigPlaces(home, new ProjectName('widgets'), []))
    }

    // FR7: "Environment variable is refused with the equivalent line"
    def "FR7: a printable value is echoed in the equivalent line"() {
        when:
        violations.variable('FACTORY_SERVE_SLOTS', '4')

        then:
        violations.lines()[0].endsWith(
                " — set 'factory.serve.slots: 4' in ${projectFile()} or ${home.hostConfig()}" +
                ', or pass --factory.serve.slots=4')
    }

    // UX1: one line per violation — a control character in the value cannot split or restyle it
    def "UX1: a value holding #name is not echoed, the equivalent line names a placeholder"() {
        when:
        violations.variable('FACTORY_SERVE_SLOTS', value)

        then:
        violations.lines() == [
            'environment variable FACTORY_SERVE_SLOTS is not allowed — found in the environment' +
            ' — factory.* settings are not read from environment variables' +
            " — set 'factory.serve.slots: <value>' in ${projectFile()} or ${home.hostConfig()}" +
            ', or pass --factory.serve.slots=<value>'
        ]*.toString()

        where:
        name | value
        'a line feed' | '4\nrm -rf'
        'a carriage return' | '4\rx'
        'an escape' | '4\u001b[2J'
        'a DEL' | '4\u007f'
    }

    private Path projectFile() {
        home.project(new ProjectName('widgets')).config()
    }
}
