package com.github.oinsio.gnomish.sandbox.environment

import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR6 of add-sandbox-base-image (design D2): the image contract is read from the one resource the
 * module's build copies from {@code sandbox-image/image-contract.properties}, once per process, and
 * a missing resource, a missing key or a malformed value fails loudly with the resource and the key
 * named — the contract is decided on, so it has no default. The resources here are fed through a
 * class loader that answers for {@link ImageContract#RESOURCE} alone.
 */
class ImageContractSpec extends Specification {

    static final String WELL_FORMED = 'contract-version=3\nguard-alias=egress-proxy\nguard-port=3128\n'

    def "FR6: the contract is read from the resource, values stripped and typed"() {
        when:
        def contract = ImageContract.readFrom(serving('# comment\ncontract-version = 3 \nguard-alias= egress-proxy\nguard-port=3128\n'))

        then:
        contract == new ImageContract(3, 'egress-proxy', 3128)
    }

    def "FR6: the running factory reads the copied resource once and keeps it"() {
        when:
        def first = ImageContract.current()

        then:
        first.is(ImageContract.current())
        first == ImageContract.readFrom(ImageContract.classLoader)
    }

    def "FR6: an absent resource is refused, naming the resource and its source file"() {
        when:
        ImageContract.readFrom(new ClassLoader(null) {})

        then:
        def e = thrown(IllegalStateException)
        e.message.contains(ImageContract.RESOURCE)
        e.message.contains('sandbox-image/image-contract.properties')
    }

    def "FR6: a resource that cannot be read is refused with the read failure as its cause"() {
        given:
        def failure = new IOException('disk gone')
        def loader = new ClassLoader(null) {
                    @Override
                    InputStream getResourceAsStream(String name) {
                        new InputStream() {
                                    @Override
                                    int read() throws IOException {
                                        throw failure
                                    }
                                }
                    }
                }

        when:
        ImageContract.readFrom(loader)

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains(ImageContract.RESOURCE)
        e.cause.is(failure)
    }

    def "FR6: a malformed resource is refused, naming the resource and the offending key (#why)"() {
        when:
        ImageContract.readFrom(serving(content))

        then:
        def e = thrown(IllegalStateException)
        e.message.contains(ImageContract.RESOURCE + ' is malformed')
        e.message.contains(fragment)
        e.cause instanceof IllegalArgumentException

        where:
        why | content || fragment
        'no version' | 'guard-alias=egress-proxy\nguard-port=3128\n' || 'carries no contract-version'
        'no alias' | 'contract-version=3\nguard-port=3128\n' || 'carries no guard-alias'
        'no port' | 'contract-version=3\nguard-alias=egress-proxy\n' || 'carries no guard-port'
        'version not a number' | WELL_FORMED.replace('=3\n', '=three\n') || "contract-version must be a whole number, was 'three'"
        'port not a number' | WELL_FORMED.replace('=3128', '=31.28') || "guard-port must be a whole number, was '31.28'"
        'version below one' | WELL_FORMED.replace('=3\n', '=0\n') || 'contract-version must be at least 1, was 0'
        'alias blank' | WELL_FORMED.replace('=egress-proxy', '=  ') || "guard-alias must be a non-blank name without whitespace, was ''"
        'alias with whitespace' | WELL_FORMED.replace('=egress-proxy', '=egress proxy') || "was 'egress proxy'"
        'port zero' | WELL_FORMED.replace('=3128', '=0') || 'guard-port must be within 1..65535, was 0'
        'port above the range' | WELL_FORMED.replace('=3128', '=65536') || 'guard-port must be within 1..65535, was 65536'
        'malformed escape' | WELL_FORMED + 'other=\\uZZZZ\n' || 'Malformed'
    }

    def "FR6: the edges of every range are accepted"() {
        expect:
        new ImageContract(1, 'a', 1).guardPort() == 1
        new ImageContract(1, 'a', 65_535).guardPort() == 65_535
    }

    private static ClassLoader serving(String content) {
        new ClassLoader(null) {
                    @Override
                    InputStream getResourceAsStream(String name) {
                        name == ImageContract.RESOURCE ? new ByteArrayInputStream(content.getBytes(StandardCharsets.ISO_8859_1)) : null
                    }
                }
    }
}
