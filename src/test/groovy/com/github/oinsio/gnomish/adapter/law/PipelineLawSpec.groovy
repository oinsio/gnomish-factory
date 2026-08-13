package com.github.oinsio.gnomish.adapter.law

import spock.lang.Specification

/**
 * FR19, D14 of add-sandbox-core: {@link PipelineLaw} is the immutable frozen-law
 * value; {@link PipelineLaw#ofContent} builds one from readable content and
 * {@link PipelineLaw#controlFile} looks it up by ref.
 */
class PipelineLawSpec extends Specification {

    def "ofContent yields the exact frozen content for a known ref"() {
        given:
        def law = PipelineLaw.ofContent(['stages/implement/instructions.md': 'Do the thing.'])

        expect:
        law.controlFile('stages/implement/instructions.md') == 'Do the thing.'
    }

    def "controlFile on a ref not part of the frozen law fails as unreadable, naming the ref"() {
        given:
        def law = PipelineLaw.ofContent([:])

        when:
        law.controlFile('unknown.md')

        then:
        def e = thrown(UnreadableLawFileException)
        e.message.contains('unknown.md')
    }

    def "the returned law is immutable against later mutation of the source map"() {
        given:
        def source = ['a.md': 'alpha'] as LinkedHashMap
        def law = PipelineLaw.ofContent(source)

        when:
        source['a.md'] = 'tampered'
        source['b.md'] = 'added'

        then:
        law.controlFile('a.md') == 'alpha'

        when:
        law.controlFile('b.md')

        then:
        thrown(UnreadableLawFileException)
    }
}
