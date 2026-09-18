package com.github.oinsio.gnomish.untrustedtext

import spock.lang.Specification

/**
 * FR2, NFR-S2 of type-untrusted-text, design D7: the comment exit — the third rendering this
 * module owns, beside the log line and the operator's console. Its reader is a tracker comment,
 * which is both a markdown renderer and the next LLM's input, so it needs three things the other
 * two do not: a fence the content cannot close early, a label saying the block is machine output
 * rather than instructions, and mentions and issue references broken so published text cannot
 * ping a team or cross-link an issue.
 *
 * <p>The logic arrives here from {@code app.findings.TrackerFence}, which becomes a delegate: one
 * owner for the rendering, reachable from the leaf every carrier already reaches.
 */
class TextSafetyCommentSpec extends Specification {

    private static final String ZWSP = '​'

    private static final String ESC = AdversarialCorpus.ch(0x1B)

    def "the block is labeled and fenced"() {
        expect:
        TextSafety.forComment('tests failed') == 'Untrusted machine output:\n~~~~\ntests failed\n~~~~'
    }

    def "a mention cannot ping anyone"() {
        when:
        def fenced = TextSafety.forComment('@team please approve this')

        then:
        fenced.contains('@' + ZWSP + 'team')
        !fenced.contains('@team')
    }

    def "an issue reference cannot cross-link"() {
        when:
        def fenced = TextSafety.forComment('see #123 for the failing build')

        then:
        fenced.contains('#' + ZWSP + '123')
        !fenced.contains('#123')
    }

    def "the fence is longer than the longest run the content opens a line with"() {
        when:
        def fenced = TextSafety.forComment('~~~~\ntext\n~~~~~~~\nmore')

        then:
        fenced.readLines()[1] == '~~~~~~~~'
        fenced.readLines().last() == '~~~~~~~~'
    }

    def "escape sequences are stripped before publication"() {
        when:
        def fenced = TextSafety.forComment("${ESC}[31mred${ESC}[0m")

        then:
        fenced.contains('red')
        !fenced.contains(ESC)
    }

    def "line structure is kept — a comment reader is a person, not grep"() {
        when:
        def fenced = TextSafety.forComment('first\nsecond\nthird')

        then: 'the label, the two fences and the three content lines'
        fenced.readLines().size() == 6
        fenced.readLines()[2..4] == ['first', 'second', 'third']
    }

    def "empty text still renders a well-formed block"() {
        expect:
        TextSafety.forComment('') == 'Untrusted machine output:\n~~~~\n\n~~~~'
    }

    def "nothing the table neutralizes survives the comment exit — #label"() {
        given:
        def input = AdversarialCorpus.ENTRIES[label]

        expect: 'the fenced block carries no ESC and no C1 control'
        !TextSafety.forComment(input).codePoints().anyMatch { int cp ->
            cp == 0x1B || (cp >= 0x7F && cp <= 0x9F) || (cp < 0x20 && cp != 0x0A && cp != 0x09)
        }

        where:
        label << AdversarialCorpus.ENTRIES.keySet()
    }
}
