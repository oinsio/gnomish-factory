package com.github.oinsio.gnomish.domain.branch

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.RoundToken
import spock.lang.Specification

/**
 * FR9 of fix-envelope-medium: the envelope's path names have one owner, so every module addresses
 * the state directory and the two files beneath it by the same spelling. These are the values the
 * on-disk contract of every existing task branch rests on — a rename here renames the medium.
 */
class EnvelopePathsSpec extends Specification {

    // FR9: the spelling itself is the contract; a branch written by an earlier release carries
    // exactly these names.
    def "pins the spelling of #constant"() {
        expect:
        value == expected

        where:
        constant | value || expected
        'DIR_NAME' | EnvelopePaths.DIR_NAME || '.gnomish-task'
        'DIR' | EnvelopePaths.DIR || '.gnomish-task/'
        'TASK_FILE' | EnvelopePaths.TASK_FILE || 'task.json'
        'STATE_FILE' | EnvelopePaths.STATE_FILE || 'state.json'
        'TASK_JSON' | EnvelopePaths.TASK_JSON_PATH || '.gnomish-task/task.json'
        'STATE_JSON' | EnvelopePaths.STATE_JSON_PATH || '.gnomish-task/state.json'
        'DECISIONS' | EnvelopePaths.DECISIONS_DIR || '.gnomish-task/decisions'
    }

    // FR9: the two shapes of the directory differ by exactly the trailing separator, and every
    // path beneath it is derived from the prefix shape — mixing them is the defect the two
    // constants exist to prevent.
    def "derives every path from the directory, and the two directory shapes differ only by the separator"() {
        expect:
        EnvelopePaths.DIR == EnvelopePaths.DIR_NAME + '/'
        EnvelopePaths.TASK_JSON_PATH == EnvelopePaths.DIR + EnvelopePaths.TASK_FILE
        EnvelopePaths.STATE_JSON_PATH == EnvelopePaths.DIR + EnvelopePaths.STATE_FILE
        EnvelopePaths.DECISIONS_DIR.startsWith(EnvelopePaths.DIR)
        !EnvelopePaths.DIR_NAME.endsWith('/')
        !EnvelopePaths.DECISIONS_DIR.endsWith('/')
    }

    // FR9, design D8: the domain's diagnosis labels are references to the owner, not a third
    // spelling — the pair that used to be kept in step by hand.
    // Scenario: git/git-task-persistence / Every medium addresses the envelope by one spelling
    def "the classifier's diagnosis labels are the owner's file names"() {
        expect:
        BranchShapeClassifier.TASK_FILE.is(EnvelopePaths.TASK_FILE)
        BranchShapeClassifier.STATE_FILE.is(EnvelopePaths.STATE_FILE)
    }

    // FR9 of extract-agent-round-runner (D3): the answer file spells the stage, the attempt and the
    //     round token under the gnome-writable directory — the path every existing pending request
    //     on a task branch was written at, so the spelling is the medium's contract.
    def "FR9: answerFile spells the stage, the attempt and the round token"() {
        expect:
        EnvelopePaths.answerFile(new AttemptKey('T-1', 'implement', 2), RoundToken.of('abc123')) ==
                new EnvelopePath('.gnomish-task/decisions/implement-a2-abc123.json')
    }

    // FR9 of extract-agent-round-runner: an envelope path cannot name a file outside the state
    //     directory, so no value of the type is a path a caller joined by hand elsewhere.
    def "FR9: an envelope path outside the state directory is refused: #relative"() {
        when:
        new EnvelopePath(relative)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains(EnvelopePaths.DIR)

        where:
        relative << [
            'decisions/x.json',
            '.gnomish-task/../x.json',
            '.gnomish-taskx/y.json'
        ]
    }

    def "FR9: a path beneath the state directory is accepted as spelled"() {
        expect:
        new EnvelopePath('.gnomish-task/decisions/x.json').relative() == '.gnomish-task/decisions/x.json'
    }
}
