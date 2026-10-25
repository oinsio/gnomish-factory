package com.github.oinsio.gnomish.distribution

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The release's registry check, {@code scripts/image-tag-refusal.sh} (FR7, NFR-S3 of
 * add-sandbox-base-image; design D6, D15), driven through its three outcomes over the committed
 * {@code docker} presets of the stand-in library ({@code tables/docker-registry.params}), whose
 * answers are docker's own texts. On GitHub the check meets a real registry once per release, so a
 * regression — a failure read as an absent tag, which would overwrite a release tag with a green
 * build — would stay unseen until it published over a release.
 *
 * <p>The stand-in reaches the script as {@code docker} on the {@code PATH}, through a per-run link
 * that records each invocation, so every feature also reads which reference was asked.
 */
class ImageTagRefusalScriptSpec extends Specification {

    private static final Path SCRIPT = RepoSourceTree.repoRoot().resolve('scripts/image-tag-refusal.sh')

    private static final String IMAGE = 'ghcr.io/oinsio/gnomish-sandbox-base'

    @TempDir
    Path tmp

    Path docker

    // Scenario: sandbox/sandbox-base-image / An existing release tag is not overwritten
    def "FR7: an existing tag #tag is refused with exit 1, naming the tag and how to attest it"() {
        when:
        def run = refusal('docker-manifest-present', tag)

        then: 'the push must not run, and the message names the tag and the recovery'
        run.exit == 1
        run.output.startsWith("::error title=Image tag exists::${IMAGE}:${tag} already exists in the registry")
        run.output.contains('a release tag is never overwritten')
        run.output.contains('do not delete it: attest it by its digest in a separate run')
        run.output.contains("docker buildx imagetools inspect ${IMAGE}:${tag}")
        run.output.contains('push-to-registry')
        asked() == [
            "manifest inspect ${IMAGE}:${tag}".toString()
        ]

        where:
        tag << ['0.2.0', '0.3.0-rc.1']
    }

    // FR7 (design D15): the registry's answer that no such manifest exists frees the tag
    def "FR7: tag #tag is free when docker answers '#answer'"() {
        when:
        def run = refusal(preset, tag)

        then:
        run.exit == 0
        run.output == "${IMAGE}:${tag} does not exist yet; the release may push it\n"
        asked() == [
            "manifest inspect ${IMAGE}:${tag}".toString()
        ]

        where:
        preset | tag | answer
        'docker-manifest-unknown' | '0.2.0' | StandIn.data('stderr-registry#manifest-unknown').trim()
        'docker-manifest-unknown' | '0.3.0-rc.1' | StandIn.data('stderr-registry#manifest-unknown').trim()
        'docker-manifest-no-such' | '0.2.0' | StandIn.data('stderr-registry#no-such-manifest-final').trim()
        'docker-manifest-no-such' | '0.3.0-rc.1' | StandIn.data('stderr-registry#no-such-manifest-pre').trim()
    }

    // Scenario: sandbox/sandbox-base-image / An unanswered registry check publishes nothing
    def "FR7: docker failing with #failure is refused with exit 2, naming the tag, the error and the re-run"() {
        when:
        def run = refusal(preset, '0.2.0')

        then: 'nothing is pushed: the job stops on the exit, and the message carries the captured error'
        run.exit == 2
        run.output == "::error title=Image tag check::could not tell whether ${IMAGE}:0.2.0 exists, so nothing was pushed: " +
                "docker manifest inspect failed with: ${error}; re-run once the registry answers\n"
        asked() == [
            "manifest inspect ${IMAGE}:0.2.0".toString()
        ]

        where:
        failure | preset | error
        'denied access' | 'docker-manifest-denied' | StandIn.data('stderr-registry#denied').trim()
        'an unreachable registry' | 'docker-manifest-no-host' | StandIn.data('stderr-registry#no-such-host').trim()
        'an absent text followed by another line' | 'docker-manifest-two-lines' |
                StandIn.data('stderr-registry#manifest-unknown').trim() + '%0A' + StandIn.data('stderr-registry#no-such-host').trim()
    }

    // FR7: the job passes the arguments; a wrong count fails without asking the registry
    def "FR7: a wrong argument count fails with exit 2 and asks docker nothing"() {
        given:
        docker = StandIn.link(tmp.resolve('docker'), 'docker-manifest-unknown')

        when:
        def run = script([IMAGE])

        then:
        run.exit == 2
        run.output.contains("usage: ${SCRIPT} <image> <tag>")
        !Files.exists(StandIn.log(docker))
    }

    private Map refusal(String preset, String tag) {
        docker = StandIn.link(tmp.resolve('docker'), preset)
        script([IMAGE, tag])
    }

    /** Each recorded invocation's arguments, in order. */
    private List<String> asked() {
        StandIn.log(docker).toFile().readLines().findAll {
            it.startsWith('argv=')
        }.collect {
            it - 'argv='
        }
    }

    private Map script(List<String> args) {
        def builder = new ProcessBuilder(['sh', SCRIPT.toString()] + args).redirectErrorStream(true)
        def env = TestChildEnvironment.cleared(builder)
        env.PATH = "${tmp}${File.pathSeparator}${env.PATH}".toString()
        def process = builder.start()
        def output = process.inputStream.text
        [exit: process.waitFor(), output: output]
    }
}
