package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Path
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The identity of the declared pair of design D11 of add-sandbox-base-image (FR7, FR8, FR9): the
 * base tag {@code product-version-conventions} computes for a release version and the one
 * {@code scripts/release-preflight.sh --base-tag} prints for its tag are one value, so the base the
 * release workflow pushes is the base the factory's refusal and shipped recipe name.
 *
 * <p>Both ends run for real: the REAL script (handed over as {@code gnomish.releasePreflightScript},
 * an input of {@code functionalTest}) and a TestKit build applying the plugin with
 * {@code -PreleaseVersion=<tag without v>}. The table holds tags the script's {@code TAG_PATTERN}
 * accepts — final and pre-release, multi-digit components, dotted suffixes; a tag the pattern
 * refuses gets no base tag from the script, and the build refuses its version on its own
 * ({@code ReleaseVersionSpec}).
 */
class BaseTagIdentityFunctionalSpec extends Specification {

    @Shared
    @TempDir
    Path projectDir

    def setupSpec() {
        GradleRunnerSupport.writeFile(projectDir, 'settings.gradle', "rootProject.name = 'mini-base-tag'\n")
        GradleRunnerSupport.writeFile(projectDir, 'build.gradle', """\
plugins {
    id 'product-version-conventions'
}

tasks.register('printBaseTag') {
    def tag = baseTag
    doLast {
        println "base_tag=\${tag.getOrElse('<absent>')}"
    }
}
""")
    }

    def "FR9: the script's --base-tag for #tag equals the base tag the build computes for its version"() {
        when:
        def script = scriptBaseTag(tag)
        def build = buildBaseTag(tag.substring(1))

        then:
        script == build
        script == expected

        where:
        tag | expected
        'v0.4.1' | '0.4'
        'v1.0.0' | '1.0'
        'v10.20.30' | '10.20'
        'v0.4.1-rc.1' | '0.4.1-rc.1'
        'v1.0.0-beta.2' | '1.0.0-beta.2'
        'v12.0.7-alpha' | '12.0.7-alpha'
        'v2.3.4-rc.1.x-2' | '2.3.4-rc.1.x-2'
    }

    private static String scriptBaseTag(String tag) {
        def builder = new ProcessBuilder('bash', GradleRunnerSupport.requiredProperty('gnomish.releasePreflightScript'),
                '--base-tag', tag).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new File('/dev/null')))
        // the pure mode needs nothing from the parent (no PATH: a git or gh call fails the run), and
        // a CI step's GITHUB_OUTPUT must not receive the spec's lines
        builder.environment().clear()
        def process = builder.start()
        def output = process.inputStream.text
        assert process.waitFor() == 0: "release-preflight.sh --base-tag ${tag} failed: ${output}"
        baseTagLine(output)
    }

    private String buildBaseTag(String version) {
        def runner = GradleRunnerSupport.runner(projectDir, 'printBaseTag', '-q')
        baseTagLine(runner.withArguments(runner.arguments + ["-PreleaseVersion=${version}".toString()]).build().output)
    }

    private static String baseTagLine(String output) {
        def lines = output.readLines().findAll { it.startsWith('base_tag=') }
        assert lines.size() == 1: "expected one base_tag= line in: ${output}"
        lines[0].substring('base_tag='.length())
    }
}
