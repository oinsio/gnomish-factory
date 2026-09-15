package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * Single-owner table row 3 of harden-untrusted-text-sinks: "which characters are hostile" has one
 * owner, {@code CharacterTable} in {@code :logtext}, and the sink converters are consumers of it.
 * A converter that grew a control-character literal or a control-class regex of its own would be a
 * second table — the drift the declared-pair rule exists to stop, in a place no equivalence spec
 * is watching, because the pair's two declared ends are {@code LogText} and the findings sanitizer.
 *
 * <p>The gate is a source scan rather than a type rule for the reason every gate in this package
 * is: what a class writes in a string literal is invisible to the compiler and to ArchUnit alike.
 * Line separators and the tab of the continuation marker are deliberately not in scope — they are
 * the record's structure, not the hostile vocabulary.
 *
 * <p>FR1, FR3, FR4 of harden-untrusted-text-sinks.
 */
class SinkConverterVocabularySpec extends Specification {

    /** The sink converters and the neutralization they share: one package, scanned whole. */
    private static final String CONVERTER_PACKAGE = 'bootstrap/src/main/java/com/github/oinsio/gnomish/logging/'

    /** The classes this gate must have reached — an empty scan would pass silently otherwise. */
    private static final Set<String> KNOWN_CONVERTER_SOURCES = [
        'SafeMessageConverter.java',
        'SafeThrowableConverter.java',
        'SafeMdcConverter.java',
        'SinkNeutralizer.java',
    ] as Set

    /**
     * A control character named as itself — the {@code \\u001B} / {@code \\u009B} escapes and the
     * raw byte — or a regex class that stands for the control range. Either is a second character
     * table in the making.
     */
    private static final Pattern OWN_VOCABULARY = Pattern.compile(
    '\\\\u00(?:[01][0-9A-Fa-f]|7[Ff]|[89][0-9A-Fa-f])' +
    '|\\\\[pP]\\{(?:Cc|Cntrl|C|Cf)\\}' +
    '|\\\\(?:0[0-3][0-7]|x[0-9A-Fa-f]{2})' +
    '|[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F-\\x9F]')

    // Single-owner table row 3: the converters consume the one table and declare none of their own
    def "the sink converter sources hold no character vocabulary of their own"() {
        given: 'every source of the converter package, with its comments removed'
        def sources = RepoSourceTree.productionSources {
            it.startsWith(CONVERTER_PACKAGE)
        }

        expect: 'the scan really reached the converters — an empty file set would pass silently'
        sources.collect { it.name } as Set == KNOWN_CONVERTER_SOURCES

        when:
        def violations = sources.findAll {
            declaresOwnVocabulary(code(it))
        }.collect {
            it.name
        }

        then: 'the gate names every offending source'
        violations == []
    }

    // The detector is the gate — a seeded violation must fail it, or a green run means nothing
    def "a seeded #shape is detected"() {
        expect:
        declaresOwnVocabulary(seeded)

        where:
        shape | seeded
        'ESC escape literal' | 'private static final String ESC = "\\u001B";'
        'C1 CSI escape literal' | 'if (c == \'\\u009B\') { return true; }'
        'control-class regex' | 'Pattern.compile("\\\\p{Cc}+")'
        'octal escape' | 'out.append("\\033[2J");'
        'hex escape' | 'out.append("\\x1b");'
    }

    // The structure the converters legitimately write is not vocabulary, and must not be flagged
    def "the line structure a converter writes is not flagged: #shape"() {
        expect:
        !declaresOwnVocabulary(allowed)

        where:
        shape | allowed
        'continuation marker' | 'private static final String CONTINUATION = "\\t| ";'
        'line-break class' | 'Pattern.compile("\\\\R")'
        'trace-line shape' | 'Pattern.compile("^\\\\t+at .*")'
    }

    private static boolean declaresOwnVocabulary(String code) {
        OWN_VOCABULARY.matcher(code).find()
    }

    /** The source with both comment forms removed: a control character named in prose is prose. */
    private static String code(File file) {
        RepoSourceTree.code(file).replaceAll(/(?s)\/\*.*?\*\//, '')
    }
}
