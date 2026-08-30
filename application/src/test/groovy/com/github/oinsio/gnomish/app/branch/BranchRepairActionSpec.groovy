package com.github.oinsio.gnomish.app.branch

import com.github.oinsio.gnomish.domain.branch.BranchShape
import spock.lang.Specification

/**
 * NFR-O1 of harden-task-branch-contract: the {@code action} half of a repair line, rendered from the
 * shape's own disposition so the phrase and the routing decision cannot drift apart.
 */
class BranchRepairActionSpec extends Specification {

    // NFR-O1: each disposition reads as what its owner is about to do.
    def "renders the action from the shape's disposition"() {
        expect:
        BranchRepairAction.phrase(shape) == phrase

        where:
        shape || phrase
        new BranchShape.Parked() || 'resuming from the recorded position'
        new BranchShape.CompletedUncleaned() || 'resuming from the recorded position'
        new BranchShape.StaleEpoch() || 'reconciling the tip against origin, then classifying it again'
        new BranchShape.Corrupt('task.json: bad') || 'parking for a human on this first classification'
        new BranchShape.Unknown('state without task') || 'parking for a human on this first classification'
        new BranchShape.UnsupportedVersion('s.json', 7, 1) || 'parking for a human on this first classification'
        new BranchShape.Delivered() || 'finishing the delivered branch'
    }

    // FR2: the closed set stays total — every shape has a phrase, none of them blank.
    def "every shape of the closed set renders a phrase"() {
        expect:
        !BranchRepairAction.phrase(shape).isBlank()

        where:
        shape << [
            new BranchShape.Bare(),
            new BranchShape.Created(),
            new BranchShape.InProgress(),
            new BranchShape.Parked(),
            new BranchShape.Answered(),
            new BranchShape.CompletedUncleaned(),
            new BranchShape.Delivered(),
            new BranchShape.StaleEpoch(),
            new BranchShape.Corrupt('reason'),
            new BranchShape.Unknown('reason'),
            new BranchShape.UnsupportedVersion('state.json', 7, 1)
        ]
    }
}
