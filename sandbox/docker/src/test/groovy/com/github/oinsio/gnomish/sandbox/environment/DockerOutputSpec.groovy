package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * FR11, NFR-R2 of add-sandbox-core: docker list output parses into non-blank,
 * stripped lines — the trailing-newline blank and any stray blank line are
 * dropped, and surrounding whitespace is trimmed — the shared parsing the orphan
 * sweep and the aged reaper both depend on.
 */
class DockerOutputSpec extends Specification {

    def "parses non-blank, stripped lines and drops blanks"() {
        expect:
        DockerOutput.lines('a\n\n b \nc\n') == ['a', 'b', 'c']
    }

    def "empty or whitespace-only output yields no lines"() {
        expect:
        DockerOutput.lines('') == []
        DockerOutput.lines('\n') == []
        DockerOutput.lines('   \n  ') == []
    }
}
