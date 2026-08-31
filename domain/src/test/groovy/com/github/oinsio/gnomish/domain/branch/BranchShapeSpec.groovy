package com.github.oinsio.gnomish.domain.branch

import spock.lang.Specification

/**
 * FR1, FR2 of harden-task-branch-contract: the closed shape set declares, per shape, the one
 * component that converges it and whether that owner rolls the transition forward or discards.
 * The table asserted here is the one owned by {@code docs/adr/0003-crash-consistency.md}.
 */
class BranchShapeSpec extends Specification {

    // FR1: every shape names exactly one recovery owner and one disposition — the ADR's table.
    def "each shape declares its recovery owner and disposition"() {
        expect:
        shape.recoveryOwner() == owner
        shape.disposition() == disposition

        where:
        shape || owner | disposition
        new BranchShape.Bare() || RecoveryOwner.TAKE_ROUTING | RecoveryDisposition.ROLL_FORWARD
        new BranchShape.Created() || RecoveryOwner.STAGE_ENGINE | RecoveryDisposition.ROLL_FORWARD
        new BranchShape.InProgress() || RecoveryOwner.STAGE_ENGINE | RecoveryDisposition.ROLL_FORWARD
        new BranchShape.Parked() || RecoveryOwner.TERMINAL_TRANSITION | RecoveryDisposition.ROLL_FORWARD
        new BranchShape.Answered() || RecoveryOwner.STAGE_ENGINE | RecoveryDisposition.ROLL_FORWARD
        new BranchShape.CompletedUncleaned() || RecoveryOwner.COMPLETION_FINISH | RecoveryDisposition.ROLL_FORWARD
        new BranchShape.Delivered() || RecoveryOwner.NONE | RecoveryDisposition.TERMINAL
        new BranchShape.StaleEpoch() || RecoveryOwner.REPLICA_RECONCILER | RecoveryDisposition.DISCARD
        new BranchShape.UnsupportedVersion('state.json', 2, 1) || RecoveryOwner.RECOVERY_BUDGET | RecoveryDisposition.QUARANTINE
        new BranchShape.Corrupt('task.json: truncated') || RecoveryOwner.RECOVERY_BUDGET | RecoveryDisposition.QUARANTINE
        new BranchShape.Unknown('state without task') || RecoveryOwner.RECOVERY_BUDGET | RecoveryDisposition.QUARANTINE
    }

    // NFR-O1: "the clean expected one" is a property of the shape, so the repair log has one rule
    // to consult rather than a per-reader opinion.
    def "clean shapes are the ones a healthy progression passes through"() {
        expect:
        shape.isClean() == clean

        where:
        shape || clean
        new BranchShape.Created() || true
        new BranchShape.InProgress() || true
        new BranchShape.Answered() || true
        new BranchShape.Delivered() || true
        new BranchShape.Bare() || false
        new BranchShape.Parked() || false
        new BranchShape.CompletedUncleaned() || false
        new BranchShape.StaleEpoch() || false
        new BranchShape.UnsupportedVersion('task.json', 9, 1) || false
        new BranchShape.Corrupt('task.json: truncated') || false
        new BranchShape.Unknown('state without task') || false
    }

    // FR15: the version diagnosis names the version, which is why UnsupportedVersion is its own
    // shape rather than a flavour of Corrupt.
    def "the version shape carries the file and both versions"() {
        given:
        def shape = new BranchShape.UnsupportedVersion('state.json', 7, 1)

        expect:
        shape.fileName() == 'state.json'
        shape.observedVersion() == 7
        shape.supportedVersion() == 1
    }

    // FR16: whether a tip of this shape can be read into a report is the shape's own property —
    // the branch reader and the branch lister both ask it, so it has exactly one owner.
    def "tipCarriesState() separates the shapes an inspector can render from the ones it names"() {
        expect:
        shape.tipCarriesState() == carries

        where:
        shape || carries
        new BranchShape.Created() || true
        new BranchShape.InProgress() || true
        new BranchShape.Parked() || true
        new BranchShape.Answered() || true
        new BranchShape.CompletedUncleaned() || true
        new BranchShape.StaleEpoch() || true
        new BranchShape.Bare() || false
        new BranchShape.Delivered() || false
        new BranchShape.UnsupportedVersion('state.json', 2, 1) || false
        new BranchShape.Corrupt('task.json: truncated') || false
        new BranchShape.Unknown('state without task') || false
    }

    // FR16: the closed set names itself once — a table cell, a log line and a diagnosis all read
    // the same word for the same shape. Labels are load-bearing (the kill-point harness asserts on
    // them), so every shape's label is pinned as a literal, and the pin is checked against the
    // sealed set itself: a renamed shape fails its row, a twelfth shape fails the coverage check.
    def "label() pins every shape of the closed set"() {
        given: 'one instance of every shape, each with its pinned label'
        def pinned = [
            (new BranchShape.Bare()): 'Bare',
            (new BranchShape.Created()): 'Created',
            (new BranchShape.InProgress()): 'InProgress',
            (new BranchShape.Parked()): 'Parked',
            (new BranchShape.Answered()): 'Answered',
            (new BranchShape.CompletedUncleaned()): 'CompletedUncleaned',
            (new BranchShape.Delivered()): 'Delivered',
            (new BranchShape.StaleEpoch()): 'StaleEpoch',
            (new BranchShape.UnsupportedVersion('state.json', 2, 1)): 'UnsupportedVersion',
            (new BranchShape.Corrupt('task.json: truncated')): 'Corrupt',
            (new BranchShape.Unknown('state without task')): 'Unknown',
        ]

        expect: 'the pin covers the sealed set exactly, so a new shape cannot ship unlabeled'
        pinned.keySet().collect {
            it.getClass()
        } as Set == BranchShape.permittedSubclasses as Set

        and: 'each shape renders its pinned label'
        pinned.every { shape, label -> shape.label() == label }
    }
}
