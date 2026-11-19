package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import java.time.Duration
import spock.lang.Specification

/**
 * FR13, FR17, NFR-S2 of migrate-verify-to-executor-contract (design D18, "Where a refusal at run
 * assembly goes"): {@link BuiltInConnections} admits, once per run, the operator connection of every
 * SCM built-in the law's checks name, and turns every way that connection cannot be used — absent,
 * refused by the guard, naming no repository, its token unresolved — into one located error naming
 * the check, the executor and the operator key, never an exception. A law that names no SCM built-in
 * admits nothing and needs no subsection.
 */
class BuiltInConnectionsSpec extends Specification {

    private static final String MANIFEST = 'stages/build/stage.yaml'
    private static final Map STATUS_OK = [('api-url'): 'http://127.0.0.1:3000', repo: 'acme/widgets']

    private static VerifyCheck.Executor scm(String executor, String checkId = 'ci') {
        new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, executor, checkId, [:], Duration.ofSeconds(1),
        Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX,
        Threshold.ERROR, ['build'])
    }

    private static PipelineDefinition law(List<VerifyCheck> checks) {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
            'i.md', checks, new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
    }

    private static BuiltInConnections connections(Map subsections, Map<String, String> secrets = [
                GNOMISH_SCM_STATUS_TOKEN: 't', GNOMISH_GITHUB_ACTIONS_TOKEN: 't']) {
        new BuiltInConnections(subsections,
                new ScmConnectionReader(ConnectionProfiles.none(), new OperatorEgress(['loopback'])), { String name ->
                    Optional.ofNullable(secrets[name])
                } as SecretsProvider)
    }

    def "FR13: a law that names no SCM built-in admits nothing, with no subsection configured"() {
        when:
        def admission = connections([:], [:]).admit(law([
            VerifyChecks.builtin('files', [files: ['a']]),
            VerifyChecks.command('true'),
            VerifyChecks.external('probe', 'http', [url: 'http://127.0.0.1/'], Duration.ofSeconds(1), Duration.ofMinutes(1),
            VerifyCheck.TimeoutClass.QUALITY, [])
        ]))

        then:
        admission == new BuiltInConnections.Admission.Admitted([:])
    }

    def "FR13, NFR-S2: a check naming scm-status #what is refused at verify[1], naming the check, scm-status and #key"() {
        when:
        def admission = connections(subsections, secrets).admit(law([
            VerifyChecks.builtin('files', [files: ['a']]),
            scm('scm-status', 'ci-status')
        ]))

        then:
        admission instanceof BuiltInConnections.Admission.Refused
        def errors = (admission as BuiltInConnections.Admission.Refused).errors()
        errors.size() == 1
        errors[0].file() == MANIFEST
        errors[0].where() == 'verify[1]'
        errors[0].message().startsWith("check 'ci-status' names executor 'scm-status', whose operator connection ${key} cannot be used: ")
        errors[0].message().contains(why)

        where:
        what | subsections | secrets || key | why
        'with no connection configured' | [:] | [:] || 'factory.check.scm-status' | 'factory.check.scm-status.api-url is required'
        'whose endpoint the guard refuses' | ['scm-status': [('api-url'): 'http://10.20.30.40:3000', repo: 'acme/widgets']] | [:] || 'factory.check.scm-status' | 'factory.check.scm-status.api-url'
        'whose connection names no repository' | ['scm-status': [('api-url'): 'http://127.0.0.1:3000']] | [:] || 'factory.check.scm-status' | 'factory.check.scm-status.repo is required'
        'whose token does not resolve' | ['scm-status': STATUS_OK] | [:] || 'factory.check.scm-status.credential' | 'its token GNOMISH_SCM_STATUS_TOKEN is missing or blank'
    }

    def "FR13: two checks naming one unconnected built-in carry one error, at the first"() {
        when:
        def admission = connections([:], [:]).admit(law([
            scm('scm-status', 'one'),
            scm('scm-status', 'two')
        ]))

        then:
        (admission as BuiltInConnections.Admission.Refused).errors()*.where() == ['verify[0]']
    }

    def "FR13, NFR-S2: each SCM built-in's admitted connection travels by its name, read from its own factory.check key"() {
        when:
        def admission = connections([
            'scm-status': STATUS_OK,
            'github-actions': [('api-url'): 'http://127.0.0.1:4000', repo: 'acme/tools']
        ]).admit(law([
            scm('scm-status'),
            VerifyChecks.external('ci.yml', 'github-actions', Duration.ofSeconds(1), Duration.ofMinutes(1),
            VerifyCheck.TimeoutClass.QUALITY)
        ]))

        then:
        admission instanceof BuiltInConnections.Admission.Admitted
        def admitted = (admission as BuiltInConnections.Admission.Admitted).connections()
        admitted.keySet() == [
            'scm-status',
            'github-actions'
        ] as Set
        admitted['scm-status'].endpoint().url() == URI.create('http://127.0.0.1:3000')
        admitted['scm-status'].settings().repo == 'acme/widgets'
        admitted['github-actions'].endpoint().url() == URI.create('http://127.0.0.1:4000')
        admitted['github-actions'].endpoint().key() == 'factory.check.github-actions.api-url'
    }

    // D18 ("Where a refusal at run assembly goes"): the one owner of the case — no load rule refuses
    //     it first, and the former provider key `factory.check.github` connects nothing.
    // Scenario: verification/verdict-executors / Unconnected credentialed built-in is a located error
    def "FR13, NFR-S2: a check naming github-actions #what is a located error naming the check and github-actions"() {
        when:
        def admission = connections(subsections, [:]).admit(law([
            VerifyChecks.external('ci.yml', 'github-actions', Duration.ofSeconds(1), Duration.ofMinutes(1),
            VerifyCheck.TimeoutClass.QUALITY)
        ]))

        then:
        def errors = (admission as BuiltInConnections.Admission.Refused).errors()
        errors.size() == 1
        errors[0].file() == MANIFEST
        errors[0].where() == 'verify[0]'
        errors[0].message().startsWith("check 'ci.yml' names executor 'github-actions', whose operator connection factory.check.github-actions")
        errors[0].message().contains('factory.check.github-actions.api-url is required')

        where:
        what | subsections
        'and no factory.check.github-actions subsection' | [:]
        'connected under the former provider key only' | [github: [('api-url'): 'http://127.0.0.1:4000', repo: 'acme/tools']]
    }

    // FR26 of add-sandbox-core; NFR-S2: with no profile renaming it, the token is read under the
    //     default name the env/file secrets adapter backs, and a missing one refuses the task naming it.
    // Scenario: verification/github-external-check / Missing token fails closed at wiring time
    // Scenario: verification/github-external-check / The default token name is the env/file secret
    def "FR13, NFR-S2: a github-actions connection whose GNOMISH_GITHUB_ACTIONS_TOKEN does not resolve is refused naming it"() {
        when:
        def admission = connections(['github-actions': [('api-url'): 'http://127.0.0.1:4000', repo: 'acme/tools']], secrets)
        .admit(law([
            VerifyChecks.external('ci.yml', 'github-actions', Duration.ofSeconds(1), Duration.ofMinutes(1),
            VerifyCheck.TimeoutClass.QUALITY)
        ]))

        then:
        (admission instanceof BuiltInConnections.Admission.Admitted) == resolves
        resolves || (admission as BuiltInConnections.Admission.Refused).errors()[0].message()
                .endsWith('factory.check.github-actions.credential cannot be used: its token GNOMISH_GITHUB_ACTIONS_TOKEN is missing or blank')

        where:
        secrets | resolves
        [GNOMISH_GITHUB_ACTIONS_TOKEN: 't'] | true
        [:] | false
    }
}
