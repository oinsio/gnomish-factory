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
 * <p>The logic arrives here from {@code app.findings.TrackerFence}, the {@code String} facade
 * that has since been retired: one owner for the rendering, reachable from the leaf every carrier
 * already reaches, and these are the cases that facade's own spec used to assert.
 */
class TextSafetyCommentSpec extends Specification {

    private static final String ZWSP = AdversarialCorpus.ch(0x200B)

    private static final String ESC = AdversarialCorpus.ch(0x1B)

    /**
     * The comment plane's own hostile predicate, restated as data: unlike {@link HostileCodePoints},
     * it excludes the zero-width, bidi and tag ranges, because the mention-breaking layer
     * deliberately inserts a zero-width space into the output — a full-table check would fail on the
     * very defense the spec is asserting.
     */
    private static boolean commentHostile(int cp) {
        cp == 0x1B || (cp >= 0x7F && cp <= 0x9F) || (cp < 0x20 && cp != 0x0A && cp != 0x09)
    }

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

    def "an indented tilde run cannot close the fence early either — #label"() {
        when: 'the content carries a blank-but-not-empty line and an indented tilde run'
        def fenced = TextSafety.forComment('  \n' + indent + '~~~~~~')

        then: 'the fence still outruns it — a closing fence may be indented, so the run counts'
        fenced.readLines()[1] == '~~~~~~~'
        fenced.readLines().last() == '~~~~~~~'

        where:
        label | indent
        'three spaces' | '   '
        'one space' | ' '
        'a tab' | '\t'
    }

    def "the fence is sized after stripping, not before — #label"() {
        when: 'a tilde run the content splits with a character the strip layer removes'
        def fenced = TextSafety.forComment('~~' + hidden + '~~~')

        then: 'the joined run of five is what the fence must outrun, not the two halves of it'
        fenced.readLines()[2] == '~~~~~'
        fenced.readLines()[1] == '~~~~~~'
        fenced.readLines().last() == '~~~~~~'

        where:
        label | hidden
        'a zero-width space' | ZWSP
        'an escape sequence' | ESC + '[0m'
        'a bidi override' | AdversarialCorpus.ch(0x202E)
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

    def "the inline shape neutralizes without labeling or fencing"() {
        expect: 'a field quoted inside the factory\'s own line carries no label and no fence'
        TextSafety.forCommentInline('tests failed') == 'tests failed'
    }

    def "the inline shape breaks mentions and issue references too"() {
        when:
        def inline = TextSafety.forCommentInline('@team see #123')

        then:
        inline == '@' + ZWSP + 'team see #' + ZWSP + '123'
    }

    def "the inline shape strips escape sequences and keeps line structure"() {
        expect:
        TextSafety.forCommentInline("${ESC}[31mred${ESC}[0m\nsecond") == 'red\nsecond'
    }

    def "the fenced block is the inline shape inside a label and a fence"() {
        given: 'text whose own tilde run forces a longer fence'
        def text = '~~~~~\n@team'

        expect: 'the fence composes the inline rendering rather than repeating its neutralization'
        TextSafety.forComment(text) ==
                'Untrusted machine output:\n~~~~~~\n' + TextSafety.forCommentInline(text) + '\n~~~~~~'
    }

    def "nothing the table neutralizes survives the inline comment shape — #label"() {
        given:
        def input = AdversarialCorpus.ENTRIES[label]

        expect:
        !TextSafety.forCommentInline(input).codePoints().anyMatch { int cp ->
            commentHostile(cp)
        }

        where:
        label << AdversarialCorpus.ENTRIES.keySet()
    }

    def "nothing the table neutralizes survives the comment exit — #label"() {
        given:
        def input = AdversarialCorpus.ENTRIES[label]

        expect: 'the fenced block carries no ESC and no C1 control'
        !TextSafety.forComment(input).codePoints().anyMatch { int cp ->
            commentHostile(cp)
        }

        where:
        label << AdversarialCorpus.ENTRIES.keySet()
    }
}
