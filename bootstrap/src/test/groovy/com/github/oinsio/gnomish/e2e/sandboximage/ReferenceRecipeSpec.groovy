package com.github.oinsio.gnomish.e2e.sandboximage

import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.sandbox.environment.ImageContract
import com.github.oinsio.gnomish.sandbox.environment.SandboxBaseImage
import com.github.oinsio.gnomish.sandbox.environment.TestDockerCommand
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR9, UX1 of add-sandbox-base-image: the reference recipe, {@code docs/examples/sandbox-image/},
 * builds {@code FROM} the sandbox base image, adds only a toolchain and the agent CLI, and ends with
 * the base's two helpers — so every contract item, the egress plumbing included, is inherited.
 *
 * <p>The recipe names the published base, {@code ghcr.io/oinsio/gnomish-sandbox-base:${GNOMISH_BASE_TAG}}.
 * The spec builds it against the base the E2E fixture built from this checkout: that base is tagged,
 * locally and never pushed, under the published name with a tag no release uses, and the build
 * overrides {@code GNOMISH_BASE_TAG} with it; every other build argument keeps its default. The
 * build downloads the JDK image, Node and the agent CLI, so it needs the network the operator's
 * build needs.
 */
class ReferenceRecipeSpec extends Specification {

    private static final String PUBLISHED_BASE = 'ghcr.io/oinsio/gnomish-sandbox-base'
    private static final String LOCAL_BASE_TAG = 'e2e-reference-recipe'
    private static final String RECIPE_TAG = 'gnomish-reference-recipe:e2e'

    /** What the base's egress plumbing is made of; the recipe spells none of it. */
    private static final List<String> PLUMBING = [
        'HTTP_PROXY',
        'HTTPS_PROXY',
        'http_proxy',
        'https_proxy',
        'JAVA_TOOL_OPTIONS',
        'GRADLE_OPTS',
        'MAVEN_ARGS',
        'settings.xml',
        'keytool',
        'update-ca-certificates',
    ].asImmutable()

    @Shared
    Path recipe = SandboxBaseImage.repoRoot().resolve('docs/examples/sandbox-image')

    @Shared
    TestDockerCommand.Result build

    def cleanupSpec() {
        if (build != null) {
            TestDockerCommand.run([
                'image',
                'rm',
                '-f',
                RECIPE_TAG,
                "${PUBLISHED_BASE}:${LOCAL_BASE_TAG}".toString()
            ])
        }
    }

    // UX1: the recipe's own text — instructions only, comments aside — restates no plumbing.
    // Scenario: sandbox/sandbox-egress / The reference recipe inherits the plumbing
    def "FR9: the recipe builds FROM the published base and spells none of the egress plumbing"() {
        given:
        def instructions = Files.readAllLines(recipe.resolve('Dockerfile')).findAll {
            !it.strip().startsWith('#')
        }

        expect:
        instructions.findAll {
            it.startsWith('FROM ')
        }.last() == "FROM ${PUBLISHED_BASE}:\${GNOMISH_BASE_TAG}"
        instructions.findAll { line ->
            PLUMBING.any {
                line.contains(it)
            }
        } == []
    }

    // UX1: the README shows the complete operator Dockerfile — the recipe's instructions, line for
    //     line — and it fits in about 25 lines.
    def "UX1: the README's operator Dockerfile is the recipe without its comments, in at most 26 lines"() {
        given:
        def readme = Files.readString(recipe.resolve('README.md'))
        def shown = (readme =~ /(?s)```dockerfile\n(.*?)```/).with {
            it.find() ? it.group(1).readLines() : []
        }
        def instructions = Files.readAllLines(recipe.resolve('Dockerfile')).findAll {
            it.strip() && !it.strip().startsWith('#')
        }

        expect:
        shown == instructions
        instructions.size() <= 26
    }

    // FR9, M1: the build succeeds — its last step is gnomish-trust-ca && gnomish-verify-image — and
    //     the image verifies again, runs the toolchain and the agent CLI as gnome, and carries the
    //     base's contract label.
    // Scenario: sandbox/sandbox-base-image / Recipe builds and verifies
    @IgnoreIf(value = {
        !GiteaAvailability.dockerAvailable()
    }, reason = 'Docker daemon unavailable')
    def "FR9, M1: the recipe builds on the base, verifies and carries the base's contract label"() {
        when:
        def result = recipeBuild()

        then:
        result.exitCode == 0
        TestDockerCommand.shell(RECIPE_TAG, 'gnome', 'gnomish-verify-image').exitCode == 0
        label(RECIPE_TAG) == label(SandboxBaseImage.TAG)
        label(RECIPE_TAG) == ImageContract.current().contractVersion().toString()
        TestDockerCommand.shell(RECIPE_TAG, 'gnome',
                'id -un; for t in java node claude git; do command -v $t; done').success().readLines() == [
                    'gnome',
                    '/opt/java/openjdk/bin/java',
                    '/opt/node/bin/node',
                    '/opt/node/bin/claude',
                    '/opt/git/bin/git'
                ]
    }

    // FR9: the built recipe's proxy environment is the base's, value for value.
    // Scenario: sandbox/sandbox-egress / The reference recipe inherits the plumbing
    @IgnoreIf(value = {
        !GiteaAvailability.dockerAvailable()
    }, reason = 'Docker daemon unavailable')
    def "FR9: the built recipe carries the base's egress environment unchanged"() {
        given:
        recipeBuild().success()

        expect:
        def inherited = environment(RECIPE_TAG).subMap(PLUMBING)
        inherited.keySet() == [
            'HTTP_PROXY',
            'HTTPS_PROXY',
            'http_proxy',
            'https_proxy',
            'JAVA_TOOL_OPTIONS',
            'GRADLE_OPTS',
            'MAVEN_ARGS'
        ] as Set
        inherited == environment(SandboxBaseImage.TAG).subMap(PLUMBING)
    }

    /** Builds the recipe once per spec run over the fixture's base. */
    private TestDockerCommand.Result recipeBuild() {
        if (build == null) {
            String local = "${PUBLISHED_BASE}:${LOCAL_BASE_TAG}"
            TestDockerCommand.succeed([
                'tag',
                SandboxBaseImage.ensureBuilt(),
                local
            ])
            build = TestDockerCommand.run([
                'build',
                '-t',
                RECIPE_TAG,
                '--build-arg',
                "GNOMISH_BASE_TAG=${LOCAL_BASE_TAG}".toString(),
                recipe.toString()
            ])
        }
        build
    }

    private static String label(String image) {
        TestDockerCommand.succeed([
            'image',
            'inspect',
            '-f',
            '{{index .Config.Labels "io.gnomish.image-contract"}}',
            image
        ]).strip()
    }

    private static Map<String, String> environment(String image) {
        TestDockerCommand.succeed([
            'image',
            'inspect',
            '-f',
            '{{range .Config.Env}}{{println .}}{{end}}',
            image
        ]).readLines().findAll {
            it.contains('=')
        }.collectEntries { String entry ->
            int split = entry.indexOf('=')
            [(entry.substring(0, split)): entry.substring(split + 1)]
        }
    }
}
