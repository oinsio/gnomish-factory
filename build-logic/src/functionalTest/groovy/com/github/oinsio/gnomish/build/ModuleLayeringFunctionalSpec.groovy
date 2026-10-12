package com.github.oinsio.gnomish.build

import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of {@code layering-conventions} (FR2, UX2 of split-into-modules): the
 * plugin is applied to a miniature build of two sibling "adapters" and {@code verifyModuleLayering}
 * is run, rather than its script read. Hermetic: project edges only, every run {@code --offline}.
 */
class ModuleLayeringFunctionalSpec extends Specification {

    @TempDir
    Path projectDir

    def setup() {
        write('settings.gradle', "rootProject.name = 'mini-layering'\ninclude 'tracker', 'git'\n")
        write('git/build.gradle', "plugins {\n    id 'java-library'\n}\n")
    }

    // Scenario: build/module-layering / Adapter importing a sibling adapter's internals fails the build
    def "UX2: a declared sibling edge outside the allowlist fails the gate, naming the edge"() {
        given: 'one adapter declares its sibling, which its layering allowlist does not admit'
        tracker("implementation project(':git')", '')

        when:
        BuildResult result = GradleRunnerSupport.runner(projectDir, ':tracker:verifyModuleLayering').buildAndFail()

        then:
        result.task(':tracker:verifyModuleLayering').outcome.name() == 'FAILED'
        result.output.contains(':tracker reaches :git on its compileClasspath')
    }

    def "FR2: the same edge passes once the allowlist states it"() {
        given:
        tracker("implementation project(':git')", "':git'")

        expect:
        GradleRunnerSupport.runner(projectDir, ':tracker:verifyModuleLayering').build()
                .task(':tracker:verifyModuleLayering').outcome.name() == 'SUCCESS'
    }

    private void tracker(String dependency, String allowed) {
        write('tracker/build.gradle', """\
plugins {
    id 'java-library'
    id 'layering-conventions'
}
dependencies {
    ${dependency}
}
layering {
    allowedProjects = [${allowed}]
}
""")
    }

    private void write(String relativePath, String content) {
        GradleRunnerSupport.writeFile(projectDir, relativePath, content)
    }
}
