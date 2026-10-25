package com.github.oinsio.gnomish.buildlogic

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
 *
 * <p>The plugin also computes the release's base tag (design D11 of add-sandbox-base-image), read
 * back here as the applying project's {@code baseTag} provider.
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
    def baseTag = project(':app').baseTag
    doLast {
        versions.each { name, version -> println "version.\${name}=\${version}" }
        println "baseTag.app=\${baseTag.getOrElse('<absent>')}"
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

    // Scenario: release/release-distribution / Local build is marked as not a release
    def "FR3: a build without a release version is a development build"() {
        expect:
        versions().contains('version.app=0.0.0-dev')
    }

    // Scenario: release/release-distribution / Plugin contract is untouched
    def "a sibling module keeping its own version is unchanged, with or without a release version"() {
        expect:
        versions(*arguments).contains('version.lib=4.5.6')

        where:
        arguments << [[], ['-PreleaseVersion=9.9.9']]
    }

    // FR9 of add-sandbox-base-image (design D11): X.Y of a final release, the whole pre-release
    def "FR9: the base tag of release version #version is #tag"() {
        expect:
        versions("-PreleaseVersion=${version}").contains("baseTag.app=${tag}\n")

        where:
        version | tag
        '9.9.9' | '9.9'
        '10.20.30' | '10.20'
        '0.4.1-rc.1' | '0.4.1-rc.1'
        '1.0.0-beta.2' | '1.0.0-beta.2'
    }

    def "FR9: a development build has no base tag"() {
        expect:
        versions().contains('baseTag.app=<absent>\n')
    }

    def "FR9: a release version with no leading MAJOR.MINOR. fails the build naming the expected shape: #version"() {
        when:
        def runner = GradleRunnerSupport.runner(projectDir, 'printVersions')
        def output = runner.withArguments(runner.arguments + ["-PreleaseVersion=${version}".toString()]).buildAndFail().output

        then:
        output.contains("releaseVersion '${version}' has no leading MAJOR.MINOR.")
        output.contains('expected MAJOR.MINOR.PATCH with an optional -suffix')

        where:
        version << ['nightly', '7', '1.2', '01.2.3', 'v1.2.3']
    }

    private String versions(String... arguments) {
        def runner = GradleRunnerSupport.runner(projectDir, 'printVersions')
        runner.withArguments(runner.arguments + arguments.toList()).build().output
    }

    private void write(String relativePath, String content) {
        GradleRunnerSupport.writeFile(projectDir, relativePath, content)
    }
}
