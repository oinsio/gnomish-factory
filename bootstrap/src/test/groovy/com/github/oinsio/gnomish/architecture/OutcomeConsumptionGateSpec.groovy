package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D7 of make-checkpoint-gate-durable: a consumed outcome is cleared only by one of the
 * three lifecycle writers — {@code TaskRepository.appendDecision}, {@code approveCheckpoint},
 * {@code resumeFrom} — each landing the cleared outcome and the state the continuation runs from
 * in one commit. The attempts reset is that state: {@code TaskState.resetAttempts()} computed and
 * run in memory with no write is the defect the resumed write closed (a kill after the run started
 * granted the budget again on the next pickup). The reset compiles anywhere and passes any
 * component spec, so this scan keeps it where the hand-off to a writer is visible.
 *
 * <p>Required, by file, each asserted reached: the declaration ({@code TaskState}) and the two
 * call sites that hand the reset state to a writer — {@code EscalationResume.decide}, whose result
 * the two {@code run} continuations land through {@code EscalationResume.land} ({@code
 * appendDecision} or {@code resumeFrom}), and {@code TakeDecisionResume}, whose three resets go to
 * {@code mechanics.resumeFrom} or {@code mechanics.appendDecision}.
 *
 * <p>Permitted but not required: the two {@code ResumeMechanics} implementations. Task 5.2 names
 * them as owners of the hand-off, and a reset placed there would sit beside the writer it feeds,
 * but today they receive the reset state from {@code TakeDecisionResume} and compute none — so
 * they cannot be asserted reached, and requiring them would demand a call nobody needs.
 *
 * <p>Detection is the bare identifier {@code resetAttempts} in code, comments stripped. That one
 * token covers every spelling that reaches the method — a call ({@code .resetAttempts()}), a
 * method reference ({@code TaskState::resetAttempts}, which hands the reset to a stream or a
 * callback and would otherwise bypass a call-shaped pattern), and the declaration. A longer name
 * that merely starts with it ({@code resetAttemptsFor}) is a different identifier and is not
 * matched.
 *
 * <p>FR7 of make-checkpoint-gate-durable.
 */
class OutcomeConsumptionGateSpec extends Specification {

    /** Files that must name {@code resetAttempts}, each with why. */
    private static final Map<String, String> REQUIRED = [
        ('domain/src/main/java/com/github/oinsio/gnomish/domain/engine/TaskState.java'):
        'the declaration',
        ('application/src/main/java/com/github/oinsio/gnomish/app/EscalationResume.java'):
        'run: decide resets, land writes through appendDecision or resumeFrom',
        ('application/src/main/java/com/github/oinsio/gnomish/app/TakeDecisionResume.java'):
        'take: every reset goes to mechanics.resumeFrom or mechanics.appendDecision',
    ]

    /** Files that may name it without being required to: owners of the hand-off with no reset today. */
    private static final Map<String, String> PERMITTED = [
        ('application/src/main/java/com/github/oinsio/gnomish/app/HostResumeMechanics.java'):
        'host ResumeMechanics: wraps resumeFrom/appendDecision; receives the reset state today',
        ('application/src/main/java/com/github/oinsio/gnomish/app/ContainerResumeMechanics.java'):
        'container ResumeMechanics: wraps resumeFrom/appendDecision; receives the reset state today',
    ]

    // FR7: the attempts reset lives only where its hand-off to a lifecycle writer is visible
    def "FR7: resetAttempts is named in production only in the allowlisted files, the required ones all reached"() {
        given: 'every production source, comments stripped'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when:
        def naming = sources.findAll { namesReset(RepoSourceTree.code(it)) }
        .collect { RepoSourceTree.relative(it) } as Set

        then: 'no file outside the allowlist names the reset'
        (naming - REQUIRED.keySet() - PERMITTED.keySet()).sort() == []

        and: 'every required file is reached and still names it'
        (REQUIRED.keySet() - naming).sort() == []
    }

    // The detector is the gate — every spelling that reaches the method is found, a mention is not
    def "the detector: #shape"() {
        expect:
        namesReset(source.readLines().collect {
            RepoSourceTree.codeOnly(it)
        }.join('\n')) == detected

        where:
        shape | source || detected
        'a call' | 'var reset = finalState.resetAttempts();' || true
        'a spaced call' | 'var reset = finalState . resetAttempts ( );' || true
        'a method reference' | 'states.map(TaskState::resetAttempts)' || true
        'a spaced method reference' | 'states.map(TaskState :: resetAttempts)' || true
        'an instance method reference' | 'Supplier<TaskState> s = finalState::resetAttempts;' || true
        'the declaration' | 'public TaskState resetAttempts() {' || true
        'a longer identifier' | 'var r = finalState.resetAttemptsFor(stage);' || false
        'a javadoc mention' | ' * the state {@link TaskState#resetAttempts()} returns' || false
        'a trailing comment' | 'var r = finalState; // was finalState.resetAttempts()' || false
        'a line comment' | '// finalState.resetAttempts()' || false
    }

    private static boolean namesReset(String code) {
        code =~ /(?<![\w$])resetAttempts(?![\w$])/
    }
}
