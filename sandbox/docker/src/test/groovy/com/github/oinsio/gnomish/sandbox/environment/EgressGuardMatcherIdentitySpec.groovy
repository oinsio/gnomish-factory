package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.httpegress.EgressAllowlist
import com.github.oinsio.gnomish.httpegress.EgressEntries
import com.github.oinsio.gnomish.httpegress.HostResolver
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The identity spec of the declared pair {@code EgressEntries} ↔ the box guard's addon (FR17,
 * design D8, D16 of migrate-verify-to-executor-contract; {@code manual-sync-pairs.md}, the
 * no-shared-classpath table): the addon's own matcher — the {@code _allowed} of {@link
 * EgressGuardConfig#SCRIPT}, unmodified, over the allowlist {@link EgressGuardConfig#allowlistJson}
 * renders — answers for every name of one table exactly as {@link EgressEntries#namesHost} does
 * over the same entries. The addon runs in the guard container, in Python, so no shared code can
 * hold the two together; this spec does.
 *
 * <p>The addon is run by the committed harness {@code egress-guard/matcher-harness.py}, which
 * stands in for the mitmproxy import and the config mount and calls the addon's own function.
 */
class EgressGuardMatcherIdentitySpec extends Specification {

    /** The sandbox-subset entries every name is judged against — each shape of the subset. */
    static final List<String> ENTRIES = [
        'api.example.com',
        '*.blob.core.windows.net',
        'Upper.Example.ORG',
        '10.0.0.1',
        '::1'
    ]

    /** The names both matchers judge, with the answer the subset promises. */
    static final Map<String, Boolean> NAMES = [
        'api.example.com' : true,
        'API.EXAMPLE.COM' : true,
        'x.api.example.com' : false,
        'example.com' : false,
        'abc.blob.core.windows.net': true,
        'a.b.blob.core.windows.net': true,
        'blob.core.windows.net' : false,
        'evilblob.core.windows.net': false,
        'upper.example.org' : true,
        '10.0.0.1' : true,
        '10.0.0.2' : false,
        '::1' : true,
        'other.example' : false,
    ]

    @Shared
    @TempDir
    Path tmp

    // FR17, D8: one table of names, both ends of the declared pair, the same answers
    def "FR17: the guard's addon and EgressEntries answer alike for every name of the table"() {
        when:
        def java = NAMES.keySet().collect { name ->
            ENTRIES.any {
                EgressEntries.namesHost(it, name)
            }
        }
        def python = addonAnswers(EgressGuardConfig.allowlistJson(ENTRIES), NAMES.keySet().toList())

        then: 'the Java owner answers as the subset promises'
        java == NAMES.values().toList()

        and: 'the addon answers exactly as the owner does'
        python == java
    }

    // FR17, D16: a sandbox-scope wildcard reads the same in the box guard and in the in-process guard
    // Scenario: sandbox/sandbox-egress / The sandbox scope is read by the one grammar
    def "FR17: '*.example.com' admits api.example.com and denies example.com in the box guard and the in-process guard alike"() {
        given:
        def entries = ['*.example.com']
        def inProcess = new EgressAllowlist(entries, { host ->
            [
                InetAddress.getByAddress(host, InetAddress.ofLiteral('93.184.216.34').address)
            ]
        } as HostResolver, EgressAllowlist.IN_PROCESS_KEY, false)

        expect: 'the box guard admits the subdomain and denies the apex'
        addonAnswers(EgressGuardConfig.allowlistJson(entries), [
            'api.example.com',
            'example.com'
        ]) == [true, false]

        and: 'the in-process guard reads the same entry the same way'
        inProcess.refuse(URI.create('https://api.example.com/x')) == null
        inProcess.refuse(URI.create('https://example.com/x')).reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
    }

    /** The addon's own {@code _allowed} over {@code names}, run by the committed harness. */
    private List<Boolean> addonAnswers(String allowlistJson, List<String> names) {
        def harness = Paths.get(getClass().getResource('/egress-guard/matcher-harness.py').toURI())
        def out = tmp.resolve('answers.json')
        def err = tmp.resolve('harness.err')
        def builder = new ProcessBuilder([
            'python3',
            harness.toString(),
            EgressGuardConfig.SCRIPT,
            allowlistJson
        ] + names)
        .redirectInput(ProcessBuilder.Redirect.from(new File('/dev/null')))
        .redirectOutput(out.toFile())
        .redirectError(err.toFile())
        TestChildEnvironment.cleared(builder)
        def process = builder.start()
        assert process.waitFor(60, TimeUnit.SECONDS): 'the matcher harness did not finish'
        assert process.exitValue() == 0: Files.readString(err)
        Files.readAllLines(out).collect {
            it == 'true'
        }
    }
}
