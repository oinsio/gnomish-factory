package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * {@link VerifyCheckMapper} (FR9 of add-external-check-github-actions): an unrecognized
 * {@code timeout-class} both appends a located error AND still maps the check's {@code
 * timeoutClass} to the safe {@code QUALITY} fallback (mirroring {@code DurationConfig#parse}'s
 * convention) — a fact {@link PipelineMapperDurationSpec}'s error-path scenario cannot observe directly,
 * since {@link PipelineMapper} discards the whole definition once any error is present. It also
 * pins the token-to-executor mapping (FR1, UX2 of migrate-verify-to-executor-contract): {@code
 * builtin} → {@code files-exist}, {@code command} → {@code shell}, {@code external} → its provider
 * (absent or {@code github} → {@code github-actions}), and the explicit {@code executor:} form.
 *
 * <p>Implements FR9 of add-external-check-github-actions; FR1, UX2 of
 * migrate-verify-to-executor-contract.
 */
class VerifyCheckMapperSpec extends Specification {

    /** No law executor is granted credentials. */
    private static final Set<String> NONE = [] as Set

    def "an unrecognized timeout-class still maps the check to the QUALITY fallback alongside the located error"() {
        given:
        def errors = []
        def dto = new VerifyCheckDto.External('ci', '30s', '15m', 'urgent')

        when:
        def checks = VerifyCheckMapper.mapAll('stages/build/stage.yaml', [dto], NONE, errors)

        then:
        errors.size() == 1
        (checks[0] as VerifyCheck.Executor).timeoutClass() == VerifyCheck.TimeoutClass.QUALITY
    }

    // FR13 (add-sandbox-core): an unrecognized verify-in still maps the command
    // to the SAME_BOX fallback alongside the located error — a fact
    // PipelineMapperSandboxSpec cannot observe, since the definition is discarded once
    // any error is present
    def "an unrecognized verify-in still maps the command to the SAME_BOX fallback alongside the located error"() {
        given:
        def errors = []
        def dto = new VerifyCheckDto.Command('./gradlew test', 'elsewhere')

        when:
        def checks = VerifyCheckMapper.mapAll('stages/build/stage.yaml', [dto], NONE, errors)

        then:
        errors.size() == 1
        (checks[0] as VerifyCheck.Executor).verifyIn() == VerifyCheck.VerifyIn.SAME_BOX
    }

    // FR16 (add-sandbox-core): external pin paths are copied verbatim; a null
    // (absent) pin-path list maps to empty, and both cases parse with no error
    def "external pin paths are copied verbatim, null mapping to empty"() {
        given:
        def errors = []

        when:
        def withPins = VerifyCheckMapper.mapAll('m',
                [
                    new VerifyCheckDto.External('ci', null, null, '30s', '5m', null, ['a.yml', 'b.yml'])
                ], NONE, errors)
        def without = VerifyCheckMapper.mapAll('m', [
            new VerifyCheckDto.External('ci', '30s', '5m', null)
        ], NONE, errors)

        then:
        errors.isEmpty()
        (withPins[0] as VerifyCheck.Executor).pinPaths() == ['a.yml', 'b.yml']
        (without[0] as VerifyCheck.Executor).pinPaths() == []
    }

    // FR13 (add-plugin-architecture), M4; FR1 of migrate-verify-to-executor-contract: a manifest
    // written before providers existed declares no `provider`, and still resolves to the
    // github-actions built-in — the default is recorded in the model, so a defaulted selection is
    // visible rather than guessed later at dispatch
    // Scenario: pipeline/pipeline-config / Default external provider maps
    def "an absent provider is recorded as the defaulted github-actions executor"() {
        given:
        def errors = []

        when:
        def checks = VerifyCheckMapper.mapAll('stages/build/stage.yaml',
                [
                    new VerifyCheckDto.External('ci/build', '30s', '5m', null)
                ], NONE, errors)

        then:
        errors.isEmpty()
        (checks[0] as VerifyCheck.Executor).executorName() == 'github-actions'
        (checks[0] as VerifyCheck.Executor).settings() == [:]
    }

    // FR7 (add-plugin-architecture); FR1 of migrate-verify-to-executor-contract: a declared
    // provider is carried verbatim as the executor name — including one no registry serves,
    // which the load seam reports — together with its opaque params as the executor's settings,
    // beside the engine-common fields, and no verify-check variant of its own
    // Scenario: pipeline/pipeline-config / External check carries provider and opaque params
    def "a declared provider and its params are carried verbatim as the executor name and settings"() {
        given:
        def errors = []

        when:
        def checks = VerifyCheckMapper.mapAll('stages/build/stage.yaml',
                [
                    new VerifyCheckDto.External('gate', 'unserved-provider', [url: 'https://sonar/api'],
                    '30s', '5m', 'infrastructure', ['sonar-project.properties'])
                ], NONE, errors)

        then: 'the mapper never grades the selection; it only records it'
        errors.isEmpty()
        def check = checks[0] as VerifyCheck.Executor
        check.token() == VerifyCheck.Token.EXTERNAL
        check.executorName() == 'unserved-provider'
        check.settings() == [url: 'https://sonar/api']

        and: 'the engine-common fields ride beside them'
        check.checkId() == 'gate'
        check.interval() == java.time.Duration.ofSeconds(30)
        check.timeout() == java.time.Duration.ofMinutes(5)
        check.timeoutClass() == VerifyCheck.TimeoutClass.INFRASTRUCTURE
        check.pinPaths() == ['sonar-project.properties']

        and: 'no variant beside the executor check and the judge exists to carry it'
        VerifyCheck.permittedSubclasses*.name.toSorted() == [
            VerifyCheck.Executor.name,
            VerifyCheck.Judge.name
        ].toSorted()
    }

    // FR1, UX2 of migrate-verify-to-executor-contract: the three tokens are sugar for executor
    // names, and the token is kept on the check
    def "a #token check maps to the #executor executor, keeping its token and its check id"() {
        given:
        def errors = []

        when:
        def check = VerifyCheckMapper.mapAll('m', [dto], NONE, errors)[0] as VerifyCheck.Executor

        then:
        errors.isEmpty()
        check.token() == token
        check.executorName() == executor
        check.checkId() == checkId

        where:
        dto | token | executor | checkId
        new VerifyCheckDto.Builtin('files_exist', [paths: ['a']]) | VerifyCheck.Token.BUILTIN | 'files-exist' | 'files_exist'
        new VerifyCheckDto.Command('./gradlew check') | VerifyCheck.Token.COMMAND | 'shell' | './gradlew check'
        new VerifyCheckDto.External('ci', '30s', '5m', null) | VerifyCheck.Token.EXTERNAL | 'github-actions' | 'ci'
        new VerifyCheckDto.External('ci', 'github', null, '30s', '5m', null, null) | VerifyCheck.Token.EXTERNAL | 'github-actions' | 'ci'
        new VerifyCheckDto.External('ci', 'http', null, '30s', '5m', null, null) | VerifyCheck.Token.EXTERNAL | 'http' | 'ci'
    }

    // FR1, UX2: the builtin's params become the files-exist settings; the in-process built-ins
    // poll nothing, so their timing is zero and their knobs are the defaults
    def "a builtin's params become its settings and the in-process checks carry the default knobs"() {
        given:
        def errors = []

        when:
        def checks = VerifyCheckMapper.mapAll('m', [
            new VerifyCheckDto.Builtin('files_exist', [paths: ['a']]),
            new VerifyCheckDto.Command('make', 'fresh-box')
        ], NONE, errors).collect { it as VerifyCheck.Executor }

        then:
        errors.isEmpty()
        checks[0].settings() == [paths: ['a']]
        checks[1].settings() == [:]
        checks*.interval() == [java.time.Duration.ZERO] * 2
        checks*.timeout() == [java.time.Duration.ZERO] * 2
        checks*.timeoutClass() == [
            VerifyCheck.TimeoutClass.QUALITY
        ] * 2
        checks*.pinPaths() == [[], []]
        checks*.verifyIn() == [
            VerifyCheck.VerifyIn.SAME_BOX,
            VerifyCheck.VerifyIn.FRESH_BOX
        ]
        checks*.threshold() == [Threshold.ERROR] * 2
        checks*.expected() == [[], []]
    }

    // FR1 of migrate-verify-to-executor-contract: the explicit `executor:` form carries its name
    // verbatim and every field it declares, each parsed by the same knob parsers as the tokens
    def "an explicit executor form carries its name and every declared field"() {
        given:
        def errors = []

        when:
        def check = VerifyCheckMapper.mapAll('m', [
            new VerifyCheckDto.Executor('sonarqube', 'gate', [project: 'acme'], '10s', '2m', 'infrastructure',
            ['sonar-project.properties'], 'fresh-box', 'note', ['sonar/gate'])
        ], NONE, errors)[0] as VerifyCheck.Executor

        then:
        errors.isEmpty()
        check == new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'sonarqube', 'gate', [project: 'acme'],
        java.time.Duration.ofSeconds(10), java.time.Duration.ofMinutes(2), VerifyCheck.TimeoutClass.INFRASTRUCTURE,
        ['sonar-project.properties'], VerifyCheck.VerifyIn.FRESH_BOX, Threshold.NOTE, ['sonar/gate'])
    }

    // FR1: an explicit executor form declaring nothing but its name takes every default; a
    // missing name maps to blank, which StageSanityRule reports
    def "an explicit executor form with only a name takes the defaults, and a missing name maps to blank"() {
        given:
        def errors = []

        when:
        def checks = VerifyCheckMapper.mapAll('m', [
            new VerifyCheckDto.Executor('acme', null, null, null, null, null, null, null, null, null),
            new VerifyCheckDto.Executor(null, null, null, null, null, null, null, null, null, null)
        ], NONE, errors).collect { it as VerifyCheck.Executor }

        then:
        errors.isEmpty()
        checks[0] == new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'acme', '', [:], java.time.Duration.ZERO,
        java.time.Duration.ZERO, VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.SAME_BOX,
        Threshold.ERROR, [])
        checks[1].executorName() == ''
    }

    // FR7 of migrate-verify-to-executor-contract: an unrecognized threshold is a located error,
    // the check still mapped with the ERROR fallback
    def "an unrecognized threshold is a located error with the ERROR fallback"() {
        given:
        def errors = []

        when:
        def check = VerifyCheckMapper.mapAll('stages/build/stage.yaml', [
            new VerifyCheckDto.Executor('acme', 'id', null, null, null, null, null, null, 'fatal', null)
        ], NONE, errors)[0] as VerifyCheck.Executor

        then:
        errors*.where() == ['verify[0].threshold']
        errors[0].file() == 'stages/build/stage.yaml'
        errors[0].message().contains("'fatal'")
        check.threshold() == Threshold.ERROR
    }

    // FR7: each threshold token maps to its constant
    def "threshold #raw maps to #expected"() {
        given:
        def errors = []

        expect:
        (VerifyCheckMapper.mapAll('m', [
            new VerifyCheckDto.Executor('acme', 'id', null, null, null, null, null, null, raw, null)
        ], NONE, errors)[0] as VerifyCheck.Executor).threshold() == expected
        errors.isEmpty()

        where:
        raw | expected
        'error' | Threshold.ERROR
        'warning' | Threshold.WARNING
        'note' | Threshold.NOTE
    }

    // FR1: a malformed duration or settings entry of the explicit form is a located error, like
    // the external form's
    def "a malformed interval and timeout of the explicit form are located errors"() {
        given:
        def errors = []

        when:
        VerifyCheckMapper.mapAll('m', [
            new VerifyCheckDto.Executor('acme', 'id', null, 'soon', 'later', 'never', null, 'nowhere', null, null)
        ], NONE, errors)

        then:
        errors*.where() == [
            'verify[0].interval',
            'verify[0].timeout',
            'verify[0].timeout-class',
            'verify[0].verifyIn'
        ]
    }

    // NFR-S1 of migrate-verify-to-executor-contract (design D6): a check naming a law executor granted
    // credentials runs in a fresh box unless it declares otherwise, in either form that can name one
    def "a #form check naming a credentialed executor and declaring no verify-in defaults to fresh-box"() {
        given:
        def errors = []

        expect:
        (VerifyCheckMapper.mapAll('m', [dto], ['sonarqube'] as Set, errors)[0] as VerifyCheck.Executor).verifyIn() ==
        VerifyCheck.VerifyIn.FRESH_BOX
        errors.isEmpty()

        where:
        form | dto
        'executor:' | new VerifyCheckDto.Executor('sonarqube', 'gate', null, '10s', '2m', null, null, null, null, null)
        'external' | new VerifyCheckDto.External('gate', 'sonarqube', null, '10s', '2m', null, null)
    }

    // NFR-S1: the credentialed default never overrides a declaration, binds only the credentialed
    // names, and leaves the built-in tokens, which a law never declares, in the round box
    def "#what maps to #expected"() {
        given:
        def errors = []

        expect:
        (VerifyCheckMapper.mapAll('m', [dto], ['sonarqube', 'shell'] as Set, errors)[0] as VerifyCheck.Executor).verifyIn() ==
        expected
        errors.isEmpty()

        where:
        what | dto || expected
        'a declared same-box on a credentialed executor' | new VerifyCheckDto.Executor('sonarqube', 'gate', null, '10s', '2m', null, null, 'same-box', null, null) || VerifyCheck.VerifyIn.SAME_BOX
        'an executor granted no credential' | new VerifyCheckDto.Executor('acme', 'gate', null, '10s', '2m', null, null, null, null, null) || VerifyCheck.VerifyIn.SAME_BOX
        'an external check on a built-in' | new VerifyCheckDto.External('ci', '30s', '5m', null) || VerifyCheck.VerifyIn.SAME_BOX
        'a command check under a credentialed name' | new VerifyCheckDto.Command('make') || VerifyCheck.VerifyIn.SAME_BOX
    }
}
