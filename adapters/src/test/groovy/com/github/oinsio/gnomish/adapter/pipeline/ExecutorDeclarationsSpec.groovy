package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import spock.lang.Specification

/**
 * The structural tier and the mapping of the {@code config.yaml} {@code executors:} section (FR9,
 * FR10, UX1 of define-executor-contract; design D5 phase a): every problem is a located error naming
 * the declaration and the field, judged by the grammar's one owner.
 */
class ExecutorDeclarationsSpec extends Specification {

    static ExecutorDeclarationDto program(List<String> argv) {
        new ExecutorDeclarationDto('program', argv, null, null, null)
    }

    static ConfigError error(String where, String message) {
        new ConfigError('config.yaml', where, message)
    }

    // FR9: an absent section, and a sound declaration, are clean
    def "a sound declaration yields no error"() {
        expect:
        ExecutorDeclarations.check(null) == []
        ExecutorDeclarations.check([
            acme: new ExecutorDeclarationDto('program', ['acme', '--json'], [maxTurns: 20], ['ACME_API_KEY', '_X1'],
            [
                'api.acme.example',
                '*.acme.example',
                '10.0.0.1',
                ' API.Acme.Example '
            ])
        ]) == []
    }

    // FR9, UX1: the declaration and the field are named
    // Scenario: pipeline/pipeline-config / Structural errors are caught at law load
    def "#problem is a located error naming the declaration and the field"() {
        expect:
        ExecutorDeclarations.check([acme: dto]) == [error(where, message)]

        where:
        problem | dto || where | message
        'no type' | new ExecutorDeclarationDto(null, ['a'], null, null, null) || 'executors.acme.type' | "missing required field 'type'"
        'unknown type' | new ExecutorDeclarationDto('wasm', ['a'], null, null, null) || 'executors.acme.type' | "unknown executor type 'wasm'; known executor types are program"
        'no argv' | program(null) || 'executors.acme.program' | "missing required field 'program'; a program declaration names its argv, the program first"
        'empty argv' | program([]) || 'executors.acme.program' | "missing required field 'program'; a program declaration names its argv, the program first"
        'a blank word' | program(['acme', ' ']) || 'executors.acme.program[1]' | 'blank argv entry; every argv entry is a word'
        'a null word' | program([null]) || 'executors.acme.program[0]' | 'blank argv entry; every argv entry is a word'
    }

    // FR9, NFR-S1: credentials are environment variable names, egress entries hosts
    def "a malformed #field entry is located by its index"() {
        given:
        def dto = field == 'credentials'
                ? new ExecutorDeclarationDto('program', ['a'], null, ['OK', value], null)
                : new ExecutorDeclarationDto('program', ['a'], null, null, ['ok.example', value])

        expect:
        ExecutorDeclarations.check([acme: dto]) == [
            error("executors.acme.${field}[1]" as String, message)
        ]

        where:
        field | value || message
        'credentials' | 'acme_key' || "invalid credential name 'acme_key'; a credential is an environment variable name matching [A-Z_][A-Z0-9_]*"
        'credentials' | null || "invalid credential name ''; a credential is an environment variable name matching [A-Z_][A-Z0-9_]*"
        'egress' | 'https://api.acme.com' || "invalid egress host 'https://api.acme.com'; an egress entry is a host name, an IP literal or a *.suffix wildcard"
        'egress' | null || "invalid egress host ''; an egress entry is a host name, an IP literal or a *.suffix wildcard"
    }

    // FR9: the name follows the one executor-name grammar
    def "a name outside the executor-name grammar is a located error"() {
        expect:
        ExecutorDeclarations.check([Acme: program(['a'])]) == [
            error('executors.Acme', "invalid executor name 'Acme'; a name matches [a-z][a-z0-9_-]{0,63}")
        ]
    }

    // FR9, UX1: every problem of every declaration in one pass, in declaration order; an empty body
    //     is a missing type
    def "every declaration is checked in one pass"() {
        given:
        def section = new LinkedHashMap<String, ExecutorDeclarationDto>()
        section.put('b', null)
        section.put('a', new ExecutorDeclarationDto('program', null, null, ['bad'], ['bad host']))

        expect:
        ExecutorDeclarations.check(section)*.where() == [
            'executors.b.type',
            'executors.a.program',
            'executors.a.credentials[0]',
            'executors.a.egress[0]'
        ]
    }

    // FR9: the mapping is total and order-preserving; absent fields map to empty
    def "the section maps to domain declarations in declaration order"() {
        given:
        def section = new LinkedHashMap<String, ExecutorDeclarationDto>()
        section.put('lint', null)
        section.put('acme', new ExecutorDeclarationDto('program', ['acme', null], [maxTurns: 20], ['K'], ['h.example']))

        expect:
        ExecutorDeclarations.map(null, []) == []
        ExecutorDeclarations.map(section, []) == [
            new ExecutorDeclaration('lint', ExecutorDeclaration.Type.PROGRAM, [], [:], [], []),
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, ['acme', ''], [maxTurns: 20], ['K'], ['h.example'])
        ]
    }

    // Scenario: pipeline/pipeline-config / Settings key with no value is rejected
    // UX2 of add-command-executor (design D10): a settings key with no value is a located error, left
    //     out of the declaration
    def "a declaration's settings key with no value is a located error, not an exception"() {
        given:
        def settings = new LinkedHashMap<String, Object>()
        settings.put('maxTurns', null)
        settings.put('mode', 'fast')
        def errors = []

        when:
        def mapped = ExecutorDeclarations.map([acme: new ExecutorDeclarationDto('program', ['acme'], settings, null, null)], errors)

        then:
        errors == [
            new ConfigError('config.yaml', 'executors.acme.settings.maxTurns', "missing value for 'maxTurns'")
        ]
        mapped[0].settings() == [mode: 'fast']
    }
}
