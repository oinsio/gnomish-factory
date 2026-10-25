package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * Design D3 of add-sandbox-base-image (FR5, NFR-R1); FR1, NFR-R1 of fix-image-declared-volumes:
 * one {@code image inspect} of the image's {@code Config} yields its declared volumes and its
 * labels, and an answer of a shape the runtime does not write is refused rather than read as "the
 * image declares nothing". No daemon.
 */
class ImageFactsSpec extends Specification {

    static final String IMAGE = 'gnomish/img:1'

    private static RecordingDockerCli answering(String stdout) {
        def docker = new RecordingDockerCli()
        docker.onRun = { List<String> args -> DockerResult.of(0, stdout, '') }
        docker
    }

    private static ImageFacts parse(String answer) {
        ImageFacts.parse(IMAGE, UntrustedText.subprocess(answer))
    }

    def "FR5: read asks the runtime once, for the whole configuration, and parses both members from it"() {
        given:
        def docker = answering('{"Volumes":{"/cache":{}},"Labels":{"io.gnomish.image-contract":"2"}}')

        when:
        def facts = ImageFacts.read(docker, IMAGE)

        then: 'one inspect, of the Config'
        docker.runs == [
            [
                'image',
                'inspect',
                '-f',
                '{{json .Config}}',
                IMAGE
            ]
        ]

        and: 'the volumes and the labels both come from that one answer'
        facts.image() == IMAGE
        facts.declaredVolumes() == ['/cache']
        facts.labels() == ['io.gnomish.image-contract': UntrustedText.subprocess('2')]
    }

    def "FR5: #answer yields volumes #volumes and labels #labels, in the runtime's order"() {
        when:
        def facts = parse(answer)

        then:
        facts.declaredVolumes() == volumes
        facts.labels().keySet() as List == labels.keySet() as List
        facts.labels().collectEntries { k, v -> [k, v.forLog()] } == labels

        where:
        answer || volumes | labels
        'null' || [] | [:]
        '{}' || [] | [:]
        '{"Volumes":null,"Labels":null}' || [] | [:]
        '{"Volumes":{"/b":{},"/a":{}}}' || ['/b', '/a'] | [:]
        '{"Labels":{"z":"1","a":"2"}}' || [] | [z: '1', a: '2']
        '{"Volumes":{"/c":{}},"Labels":{"k":""}}' || ['/c'] | [k: '']
    }

    // Scenario: sandbox/execution-environment / Declared-volume read failure is an infrastructure failure
    def "NFR-R1: a refused inspect fails the caller and names the image and the runtime's answer"() {
        given:
        def docker = new RecordingDockerCli()
        docker.onRun = { List<String> args ->
            DockerResult.of(1, '', 'No such image: gnomish/img:1\n')
        }

        when:
        ImageFacts.read(docker, IMAGE)

        then:
        def e = thrown(DockerCommandFailedException)
        // The runtime's answer leaves the carrier through its log exit, so the trailing newline
        // it arrived with is inert notation rather than a second line (design D1, D5, D6).
        e.message == 'docker image inspect for gnomish/img:1 failed: No such image: gnomish/img:1\\n'
    }

    // Scenario: sandbox/execution-environment / Declared-volume read failure is an infrastructure failure
    def "NFR-R1: an answer of shape #answer is refused, never read as an image declaring nothing"() {
        when:
        ImageFacts.read(answering(answer), IMAGE)

        then:
        def e = thrown(DockerCommandFailedException)
        e.message.startsWith('docker image inspect for ' + IMAGE + ' failed: ' + prefix)
        e.message.contains(answer)
        (e.cause != null) == parseFailure

        where:
        answer || prefix | parseFailure
        '' || 'image configuration of an unexpected shape' | false
        'not json at all' || 'unparseable image configuration' | true
        '[]' || 'image configuration of an unexpected shape' | false
        '"/cache"' || 'image configuration of an unexpected shape' | false
        '7' || 'image configuration of an unexpected shape' | false
        '{"Volumes":[]}' || 'declared volumes of an unexpected shape' | false
        '{"Volumes":"/cache"}' || 'declared volumes of an unexpected shape' | false
        '{"Labels":[]}' || 'labels of an unexpected shape' | false
        '{"Labels":{"k":2}}' || 'labels of an unexpected shape' | false
        '{"Labels":{"k":null}}' || 'labels of an unexpected shape' | false
    }

    def "the volumes and labels are copied, so a caller cannot mutate the facts"() {
        given:
        def volumes = ['/cache']
        def labels = [k: UntrustedText.subprocess('v')]

        when:
        def facts = new ImageFacts(IMAGE, volumes, labels)
        volumes << '/var/lib/tool'
        labels.put('other', UntrustedText.subprocess('w'))

        then:
        facts.declaredVolumes() == ['/cache']
        facts.labels().keySet() == ['k'] as Set

        when:
        facts.labels().put('x', UntrustedText.subprocess('y'))

        then:
        thrown(UnsupportedOperationException)
    }
}
