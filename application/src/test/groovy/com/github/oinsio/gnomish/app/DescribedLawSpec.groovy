package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.domain.egress.AdmittedEndpoint
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR9, FR13 of define-executor-contract (design D8, task 7.3): a described law carries each
 * executor's describe answer — what the run's program dispatch builds the executor from — and the
 * engine's versions and credential names are derived from those same answers, so the two cannot
 * disagree.
 */
class DescribedLawSpec extends Specification {

    private static Describe describe(String version, List<String> credentials, List<Capability> capabilities = []) {
        new Describe(1, UntrustedText.subprocess(version), [Role.TRANSFORM], capabilities, JsonNodeFactory.instance.objectNode(),
        credentials, [], null, [], EnforcementLevel.PROMPT_ONLY, false, false)
    }

    // FR10 of extract-agent-round-runner (D11): the names whose describe declares poll are derived from
    //     the same answers, and only those
    def "FR10: the pollable names the engine receives are the described executors declaring poll"() {
        when:
        def law = new DescribedLaw(LawBinding.workingTree(Path.of('/tmp/law')),
                [remote: describe('1', [], [Capability.POLL]), lint: describe('7', [], [Capability.STREAMS_PROGRESS])])

        then:
        law.executors().pollable() == ['remote'] as Set
    }

    def "FR9: the answer of each described executor is the preflight's, and an undescribed name has none"() {
        given:
        def acme = describe('3', ['ACME_API_KEY'])
        def law = new DescribedLaw(LawBinding.workingTree(Path.of('/tmp/law')), [acme: acme])

        expect:
        law.answerOf('acme').get().is(acme)
        law.answerOf('lint').isEmpty()
    }

    def "FR13: the versions and credential names the engine receives are derived from the same answers"() {
        when:
        def law = new DescribedLaw(LawBinding.workingTree(Path.of('/tmp/law')),
                [acme: describe('3', ['ACME_API_KEY']), lint: describe('7', [])])

        then:
        law.executors().all().of('acme').get().forParsing() == '3'
        law.executors().all().of('lint').get().forParsing() == '7'
        law.executors().credentials().of('acme') == ['ACME_API_KEY']
        law.executors().credentials().of('lint') == []
    }

    // FR13 of migrate-verify-to-executor-contract (design D18): the connections run assembly admitted
    //     travel with the described law to the registry assembly, by built-in name
    def "FR13: an admitted connection is found by its built-in's name, and a law without one carries none"() {
        given:
        def connection = new AdmittedConnection(
                new AdmittedEndpoint(URI.create('https://api.github.com'), 'factory.check.scm-status.api-url', ['api.github.com']),
                [repo: 'acme/widgets'])
        def binding = LawBinding.workingTree(Path.of('/tmp/law'))

        expect:
        new DescribedLaw(binding, [:], ['scm-status': connection]).connectionOf('scm-status') == Optional.of(connection)
        new DescribedLaw(binding, [:], ['scm-status': connection]).connectionOf('github-actions').isEmpty()
        new DescribedLaw(binding, [:]).connectionOf('scm-status').isEmpty()
    }
}
