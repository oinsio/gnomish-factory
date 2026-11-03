package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import spock.lang.Specification

/**
 * FR12, M4 of define-executor-contract (design D18, D19): the declared pair {@link KitCase} ↔
 * {@code steps/executor-run.sh} of the stand-in library ({@code manual-sync-pairs.md}, "Declared
 * pairs with no shared classpath"). The script names the cases twice — the header the fake's reader
 * learns them from, and the switch that accepts a {@code BROKEN} case — and each list equals the
 * enum's words, in the kit's order where the header lists them.
 */
class KitCaseScriptIdentitySpec extends Specification {

    private static final List<String> KIT = KitCase.values()*.word()

    private final List<String> script = StandIn.library().resolve('steps/executor-run.sh').toFile().readLines()

    // FR12, M4: the header lists exactly the kit's twelve cases, in its order
    def "FR12, M4: the script's header lists the kit's cases in the kit's order"() {
        given: 'the indented case lines between the list head and the line naming any other name'
        int head = script.findIndexOf { it.contains('KitCase.java') }
        int end = script.findIndexOf { it.startsWith('# Any other name') }
        List<String> listed = script.subList(head + 1, end)
                .findAll { it ==~ /^#   [a-z-]+ .*/ }
                .collect { it.substring(4).tokenize(' ').first() }

        expect:
        head> 0 && end> head
        KIT.size() == 12
        listed == KIT
    }

    // FR12: the switch that accepts a broken case accepts exactly the kit's cases
    def "FR12: the BROKEN switch accepts exactly the kit's cases"() {
        given: 'the alternation of the case statement over $broken, joined across its continuation lines'
        int start = script.findIndexOf { it.trim() == 'case "$broken" in' }
        List<String> following = script.subList(start + 1, script.size())
        String alternation = following.subList(0, following.findIndexOf {
            it.contains(')')
        } + 1)
        .join(' ')
        .replace('\\', ' ')
        List<String> accepted = alternation.substring(0, alternation.indexOf(')'))
                .tokenize('| ')

        expect:
        start> 0
        accepted.toSet() == KIT.toSet()
        accepted.size() == KIT.size()
    }
}
