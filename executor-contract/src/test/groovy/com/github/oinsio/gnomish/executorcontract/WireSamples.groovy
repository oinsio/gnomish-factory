package com.github.oinsio.gnomish.executorcontract

import static com.github.oinsio.gnomish.executorcontract.WireJson.t

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * FR1 of define-executor-contract: one DTO sample per wire variant the contract names — every role
 * of the request, every policy kind, every status and failure class of the result, every answer kind
 * with and without its optional fields, both describe shapes — built through the DTOs' own constructors. {@code WireSchemaRoundTripSpec} validates each
 * against its schema file.
 */
final class WireSamples {

    private WireSamples() {}

    static ObjectNode object(String json) {
        (ObjectNode) WireJson.readTree(json)
    }

    static ExecutorRequest request(Role role, RoleInputs inputs, Policy policy = new Policy.ReadOnly()) {
        new ExecutorRequest(role, new Workspace('/workspace'), policy, inputs, object('{"model":"m"}'),
                Bounds.none(), [], null)
    }

    static List<ExecutorRequest> requests() {
        [
            request(Role.TRANSFORM, RoleInputs.transform('Implement it.'), new Policy.WorkspaceWrite()),
            request(Role.VERDICT, RoleInputs.verdict('Every method has a javadoc.')),
            request(Role.DECISION, RoleInputs.decision('Which base?', ['main', 'release/1.x'])),
            request(Role.EFFECT, RoleInputs.none(), new Policy.WriteExactly('out/report.json')),
            new ExecutorRequest(Role.TRANSFORM, new Workspace('/w'), new Policy.WorkspaceWrite(),
            RoleInputs.transform('Again.'), object('{}'),
            new Bounds(40L, 100000L, new BigDecimal('2.5'), 30000L, 600000L, 3600000L),
            [
                new Finding('test failed', 'FooSpec.groovy:12', 'expected 1'),
                new Finding('lint', null, null)
            ],
            new Continuation('k-7f3a', 'main'))
        ]
    }

    static List<ExecutorResult> results() {
        def reported = new Usage(UsageProvenance.REPORTED, 184000L,
                ['m': new ModelUsage(1200, 3400, 0, 56000, new BigDecimal('0.42')), 'n': new ModelUsage(1, 2, 3, 4, null)],
                [
                    new ToolUsage('Edit', 7, 950L),
                    new ToolUsage('Bash', 0, null)
                ])
        def answer = WireJson.readTree('{"kind":"select","chosen":"main"}')
        [
            new ExecutorResult(Status.COMPLETED, null, null, null, null, reported,
            [
                new Denial(t('egress refused'), t('example.com:443'), t('CONNECT')),
                new Denial(t('policy'), null, null)
            ]),
            new ExecutorResult(Status.COMPLETED, null, null, t('chose'), answer, Usage.absent(), []),
            new ExecutorResult(Status.FAILED, FailureClass.QUALITY, null, t('tests red'), null, Usage.absent(), []),
            new ExecutorResult(Status.FAILED, FailureClass.INFRASTRUCTURE, null, null, null, Usage.absent(), []),
            new ExecutorResult(Status.FAILED, FailureClass.LIMIT, null, t('turns'), null, Usage.absent(), []),
            new ExecutorResult(Status.PENDING, null, new PendingKey('build-8812'), null, null, Usage.absent(), [])
        ]
    }

    static List<Answer> answers() {
        [
            new Answer.Verdict([
                new SarifRun(t('judge'), [
                    new SarifResult(t('javadoc'), SarifLevel.WARNING, t('Foo has no javadoc'), t('src/Foo.java'), 12),
                    new SarifResult(null, SarifLevel.ERROR, t('tests red'), null, null),
                    new SarifResult(null, SarifLevel.NOTE, t('only a file'), t('build.gradle'), null),
                    new SarifResult(null, SarifLevel.NONE, t('only a line'), null, 3)
                ]),
                new SarifRun(t('lint'), [])
            ]),
            new Answer.Verdict([]),
            new Answer.Select(t('main'), [], null),
            new Answer.Select(t('main'), [t('main'), t('release/1.x')], t('The task names no release line.')),
            new Answer.DecisionRequest(t('Keep it?'), [t('keep'), t('remove')], null),
            new Answer.DecisionRequest(t('Keep it?'), [
                t('keep'),
                t('remove'),
                t('deprecate')
            ], 'k-7f3a')
        ]
    }

    static List<Describe> describes() {
        [
            new Describe(1, t('0.4.2'), Role.values() as List, Capability.values() as List,
            object('{"type":"object"}'), ['ACME_API_KEY'], ['api.acme.example'], 'ACME_CONFIG_DIR',
            ['--no-project-config'], EnforcementLevel.TOOL_GATE, true),
            new Describe(2, t('1.0.0'), [Role.VERDICT], [], object('{}'), [], [], null, [],
            EnforcementLevel.PROMPT_ONLY, false)
        ] + EnforcementLevel.values().collect {
            new Describe(1, t('1'), [Role.TRANSFORM], [], object('{}'), [], [], null, [], it, false)
        }
    }
}
