package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.MechanismFieldErrors
import spock.lang.Specification

/**
 * The wire check of a Mechanism field declared blank where the declaration itself is the fault
 * (FR2, UX2 of add-command-executor): the mapping erases a blank {@code model} into {@code ""} and
 * a blank {@code command} into no command, so the domain rule could not see that the field was
 * declared at all. Everything the domain rule does see is left to it — one fault, one error.
 */
class BlankMechanismFieldsSpec extends Specification {

    static final String FILE = 'stages/gen/stage.yaml'

    List<ConfigError> check(ExecutorDto executor) {
        List<ConfigError> errors = []
        BlankMechanismFields.check(errors, FILE, 'executor', executor)
        errors
    }

    static ExecutorDto exec(String type, String model, String command) {
        new ExecutorDto(type, model, null, null, null, command)
    }

    // FR2: a model declared blank on a command stage is still a declared model
    def "a command stage declaring a blank model (#model) is the forbidden-model error"() {
        expect:
        check(exec('command', model, 'make gen')) == [
            MechanismFieldErrors.modelOnCommandStage(FILE, 'executor')
        ]

        where:
        model << ['', '   ', '\t']
    }

    // FR2: a command declared blank on another type is still a declared command
    def "a #type stage declaring a blank command (#command) is the forbidden-command error"() {
        expect:
        check(exec(type, 'm', command)) == [
            MechanismFieldErrors.commandOnStage(FILE, 'executor', type)
        ]

        where:
        [type, command] << [
            ['api', 'agent-cli', 'program'],
            ['', '  ']
        ].combinations()
    }

    // FR13 of extract-agent-round-runner: a judge check's block is located at the check
    def "a judge block's blank field is located under the block's own location"() {
        given:
        List<ConfigError> errors = []

        when:
        BlankMechanismFields.check(errors, FILE, 'verify[2].executor', exec('agent-cli', 'm', ' '))

        then:
        errors == [
            MechanismFieldErrors.commandOnStage(FILE, 'verify[2].executor', 'agent-cli')
        ]
        errors[0].where() == 'verify[2].executor.command'
    }

    // FR2: what the domain rule sees is the domain rule's to report — never twice
    def "#label contributes nothing at the wire"() {
        expect:
        check(executor).isEmpty()

        where:
        label | executor
        'a command stage with no model' | exec('command', null, 'make gen')
        'a command stage with a non-blank model' | exec('command', 'x', 'make gen')
        'a command stage with a blank command' | exec('command', null, '  ')
        'a command stage with no command' | exec('command', null, null)
        'an agent stage with a non-blank command' | exec('agent-cli', 'm', 'make gen')
        'an agent stage with a blank model' | exec('agent-cli', '', null)
        'an unknown type with blank fields' | exec('shell', '', '')
        'a missing type with blank fields' | exec(null, '', '')
    }
}
