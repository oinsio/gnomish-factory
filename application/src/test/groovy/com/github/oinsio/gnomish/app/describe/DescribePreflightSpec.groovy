package com.github.oinsio.gnomish.app.describe

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The describe preflight over a real host environment and the executor stand-in presets of design
 * D18 (FR4, FR13, UX1 of define-executor-contract): each outcome — the versions obtained, a located
 * law error, an operator precondition refusal — and the run-held cache.
 */
class DescribePreflightSpec extends DescribePreflightSpecBase {

    def "FR4, FR13: a program executor answering describe yields its version under its law name"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': StandIn.executor('reference')], stage('build', 'acme')))

        then:
        outcome instanceof DescribeOutcome.Described
        with(((DescribeOutcome.Described) outcome).executors().all()) {
            byName().keySet() == ['acme'] as Set
            of('acme').get().raw() == '1'
        }
    }

    def "FR13: a law declaring no executor describes nothing"() {
        expect:
        preflight().describe(environment, law([:], stage('build', null))) ==
        new DescribeOutcome.Described([:])
    }

    def "FR4, UX1: a protocol version the factory does not speak is a located law error naming the executor and both versions"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs('protocol-2')], stage('build', 'acme')))

        then:
        outcome == new DescribeOutcome.Invalid([
            new ConfigError('config.yaml', 'executors.acme',
            "executor 'acme' speaks executor protocol version 2; this factory speaks version 1")
        ])
    }

    def "FR4, UX1: a stage whose executor does not serve transform is a located law error naming the executor, the stage and the role"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs('verdict-only')], stage('build', 'acme')))

        then:
        outcome == new DescribeOutcome.Invalid([
            new ConfigError('stages/build/stage.yaml', 'executor.name',
            "executor 'acme' does not serve role 'transform', which stage 'build' needs; its describe lists verdict")
        ])
    }

    // Scenario: executor/wire-contract / Effect role is refused by the engine in this version
    def "FR1, NG4: a stage whose executor's describe lists only effect is a located law error naming effect as reserved"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs('effect-only')], stage('build', 'acme')))

        then:
        outcome == new DescribeOutcome.Invalid([
            new ConfigError('stages/build/stage.yaml', 'executor.name',
            "executor 'acme' lists only role 'effect', which is reserved and not executed in this version; stage 'build' needs role 'transform'")
        ])
    }

    def "FR4, NG4: a describe listing no role, or effect beside another role, is refused as an unserved role, not as reserved"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs(answer)], stage('build', 'acme')))

        then:
        outcome == new DescribeOutcome.Invalid([
            new ConfigError('stages/build/stage.yaml', 'executor.name',
            "executor 'acme' does not serve role 'transform', which stage 'build' needs; its describe lists ${listed}")
        ])

        where:
        answer || listed
        'no-role' || 'no role'
        'effect-and-verdict' || 'verdict, effect'
    }

    def "FR4, UX1: a program that answers describe wrongly is a located configuration error naming the executor and the failure"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': program], stage('build', 'acme')))

        then:
        def error = only(outcome)
        error.file() == 'config.yaml'
        error.where() == 'executors.acme.program'
        error.message().startsWith("executor 'acme' cannot be described (configuration error): ${failure}")
        error.message().endsWith('; fix the program or its declaration, then return the task to work')

        where:
        program || failure
        StandIn.executor('describe-failing') || 'describe exited 3 (executor: describe failed: no configuration found'
        StandIn.executor('describe-silent') || 'describe wrote no answer to GNOMISH_RESULT_FILE'
        StandIn.executor('describe-oversized') || 'the describe answer exceeds 65536 bytes'
    }

    def "NFR-S2: a malformed answer or a control character in a text field is a configuration error, its detail neutralized"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs(answer)], stage('build', 'acme')))

        then:
        def error = only(outcome)
        error.message().startsWith("executor 'acme' cannot be described (configuration error): ${failure}")
        !error.render().forLog().chars().anyMatch { Character.isISOControl(it) }

        where:
        answer || failure
        'malformed' || 'the describe answer is malformed ('
        'control-chars' || 'the describe answer carries a control character in a text field'
    }

    // The PT0.2S describe bound is waited out on the wall clock: the subject is the kill of a real
    // process at the bound. The 'stall' preset never exits on its own, so the outcome needs no order.
    def "FR4, UX1: a program the environment cannot start, or a describe killed at the bound, is a located external failure"() {
        when:
        def bounds = new ProgramBounds(Duration.ofMillis(200), Duration.ofMinutes(5), Duration.ofMinutes(30))
        def outcome = new DescribePreflight(new VirtualClock(), bounds, ChildEnvAllowlist.none().programGrant())
                .describe(environment, law(['acme': program], stage('build', 'acme')))

        then:
        def error = only(outcome)
        error.where() == 'executors.acme.program'
        error.message().startsWith("executor 'acme' could not be described (external failure): ${failure}")
        error.message().endsWith('; return the task to work once the environment can run it')

        where:
        program || failure
        Path.of('/nonexistent/gnomish-describe-absent') || 'the environment refused to start the program'
        StandIn.process('stall') || 'describe did not exit within PT0.2S and was killed'
    }

    def "UX1: every executor's failure is reported at once — a program that cannot answer beside one that disagrees"() {
        when:
        def outcome = preflight().describe(environment,
                law(['acme': StandIn.executor('describe-failing'), 'lint': describeAs('protocol-2')], stage('build', 'acme')))

        then:
        outcome instanceof DescribeOutcome.Invalid
        ((DescribeOutcome.Invalid) outcome).errors()*.where() == [
            'executors.acme.program',
            'executors.lint'
        ]
    }

    def "FR13: the second executor of one law over the same program is a cache hit — one describe, both versions"() {
        given:
        Path link = StandIn.recording(tempDir, 'reference-recorded')
        def preflight = preflight()
        def definition = law(['acme': link, 'acme-fast': link], stage('build', 'acme'), stage('review', 'acme-fast'))

        when:
        def first = preflight.describe(environment, definition)
        def again = preflight.describe(environment, definition)

        then: 'both executors are described under their own names'
        first instanceof DescribeOutcome.Described
        ((DescribeOutcome.Described) first).executors().all().byName().keySet() == ['acme', 'acme-fast'] as Set
        again == first

        and: 'by one describe process for the run'
        StandIn.log(link).toFile().text.count('argv=describe') == 1
    }

    def "FR13: two executors over different programs are described each by its own process"() {
        given:
        Path acme = StandIn.recording(Files.createDirectories(tempDir.resolve('acme')), 'reference-recorded')
        Path lint = StandIn.recording(Files.createDirectories(tempDir.resolve('lint')), 'reference-recorded')

        when:
        preflight().describe(environment, law(['acme': acme, 'lint': lint], stage('build', 'acme')))

        then:
        StandIn.log(acme).toFile().text.count('argv=describe') == 1
        StandIn.log(lint).toFile().text.count('argv=describe') == 1
    }
}
