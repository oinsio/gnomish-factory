package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Path
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import spock.lang.Specification

/**
 * FR3, FR10 of add-project-registry: {@link ProjectScope} hands every project-scoped command the
 * registered clone the configuration loader resolved, and is the one place the project and
 * instance names are joined into an instance id.
 */
class ProjectScopeSpec extends Specification {

    private static final RegisteredClone CLONE =
    RegisteredCloneFixture.unregistered(Path.of('/tmp/gnomish-home'), Path.of('/tmp/widgets'), 'widgets')

    // FR3, D9: the clone is the loader's bean, read when the command runs
    def "FR3: the registered clone is the one the loader registered"() {
        expect:
        RegisteredCloneFixture.scope(CLONE).registeredClone().is(CLONE)
    }

    // FR3, D9: a command running with no resolved project is a wiring defect — refused loudly, never
    // a fallback to a directory of the command's own
    def "FR3: a process with no registered clone refuses instead of falling back"() {
        given:
        def noClone = new DefaultListableBeanFactory().getBeanProvider(RegisteredClone)
        def scope = new ProjectScope(noClone, new FactoryProperties('default', null, null, null))

        when:
        scope.registeredClone()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('no registered clone')
    }

    // FR10 of add-project-registry (serve-observability, "begin with the project name"): the id is
    // <project>-<instance>-<suffix>, so a tracker comment names the project
    def "FR10 of add-project-registry: the minted instance id begins with <project>-<instance>-"() {
        when:
        def id = RegisteredCloneFixture.scope(CLONE, 'laptop').mintInstanceId()

        then:
        id.name() == 'widgets-laptop'
        id.value().startsWith('widgets-laptop-')
        id.value() ==~ /widgets-laptop-[0-9a-z]{6}/
    }
}
