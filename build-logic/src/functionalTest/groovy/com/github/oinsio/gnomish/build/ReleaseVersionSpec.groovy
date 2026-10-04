package com.github.oinsio.gnomish.build

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of {@code product-version-conventions} (FR3, design D1 of
 * add-release-pipeline): the plugin is applied to a miniature two-module build and the versions
 * Gradle actually configured are read back, rather than the script's text.
 *
 * <p>The sibling module keeps its own {@code version} assignment, as every module but
 * {@code :bootstrap} does in the real build — the plugin must not reach past the project that
 * applies it. Hermetic: the plugin needs nothing resolved, so every run is {@code --offline}.
 */
class ReleaseVersionSpec extends Specification {

    @TempDir
    Path projectDir

    def setup() {
        write('settings.gradle', """\
rootProject.name = 'mini-version'
include 'app', 'lib'
""")
        write('app/build.gradle', """\
plugins {
    id 'product-version-conventions'
}
""")
        write('lib/build.gradle', "version = '4.5.6'\n")
        write('build.gradle', """\
evaluationDependsOnChildren()

tasks.register('printVersions') {
    def versions = subprojects.collectEntries { [(it.name): it.version.toString()] }
    doLast {
        versions.each { name, version -> println "version.\${name}=\${version}" }
    }
}
""")
    }

    def "FR3: the release version handed to the build becomes the product version"() {
        when:
        def output = versions('-PreleaseVersion=9.9.9')

        then:
        output.contains('version.app=9.9.9')
    }

    def "FR3: a build without a release version is a development build"() {
        expect:
        versions().contains('version.app=0.0.0-dev')
    }

    def "a sibling module keeping its own version is unchanged, with or without a release version"() {
        expect:
        versions(*arguments).contains('version.lib=4.5.6')

        where:
        arguments << [[], ['-PreleaseVersion=9.9.9']]
    }

    private String versions(String... arguments) {
        def runner = GradleRunnerSupport.runner(projectDir, 'printVersions')
        runner.withArguments(runner.arguments + arguments.toList()).build().output
    }

    private void write(String relativePath, String content) {
        GradleRunnerSupport.writeFile(projectDir, relativePath, content)
    }
}
