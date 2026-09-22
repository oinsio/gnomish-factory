package com.github.oinsio.gnomish.domain.branch

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
    def "the classifier's diagnosis labels are the owner's file names"() {
        expect:
        BranchShapeClassifier.TASK_FILE.is(EnvelopePaths.TASK_FILE)
        BranchShapeClassifier.STATE_FILE.is(EnvelopePaths.STATE_FILE)
    }
}
