package com.github.oinsio.gnomish.app.branch

import com.github.oinsio.gnomish.domain.branch.BranchShape
import spock.lang.Specification

/**
 * FR15, UX2 of harden-task-branch-contract: the one renderer of "what was found on the branch" is
 * total over the closed shape set, and says which shapes explain themselves.
 *
 * <p>Written as fix-claim-epoch-fence removed the epoch-comparison shape (FR1): the ordinary
 * shapes' arm had been reached only through that shape's quarantine, so nothing held the renderer
 * to being total once it was gone.
 */
class BranchShapeDiagnosisSpec extends Specification {

    private static final List<BranchShape> EVERY_SHAPE = [
        new BranchShape.Bare(),
        new BranchShape.Created(),
        new BranchShape.InProgress(),
        new BranchShape.Parked(),
        new BranchShape.Answered(),
        new BranchShape.CompletedUncleaned(),
        new BranchShape.Delivered(),
        new BranchShape.Corrupt('task.json: truncated'),
        new BranchShape.Unknown('state.json without task.json'),
        new BranchShape.UnsupportedVersion('state.json', 7, 1)
    ]

    // FR2: every shape of the closed set renders a phrase, none of them blank.
    def "every shape of the closed set renders a phrase"() {
        expect:
        !BranchShapeDiagnosis.phrase(shape).isBlank()

        where:
        shape << EVERY_SHAPE
    }

    // FR15: a shape whose owner converges it needs no explanation — its name IS the diagnosis, and
    // reaching the renderer with one at all is a routing defect rather than a branch state.
    def "a shape with a recovery owner renders as its own name"() {
        expect:
        BranchShapeDiagnosis.phrase(shape) == shape.label()

        where:
        shape << [
            new BranchShape.Created(),
            new BranchShape.InProgress(),
            new BranchShape.Parked(),
            new BranchShape.Answered(),
            new BranchShape.CompletedUncleaned(),
            new BranchShape.Delivered()
        ]
    }

    // UX2: only the three quarantining shapes and Bare carry a diagnosis beside their name.
    def "only the quarantining shapes and Bare carry a diagnosis"() {
        expect:
        (BranchShapeDiagnosis.diagnosisFor(shape) != null) == explains

        where:
        shape || explains
        new BranchShape.Corrupt('task.json: truncated') || true
        new BranchShape.Unknown('state without task') || true
        new BranchShape.UnsupportedVersion('state.json', 7, 1) || true
        new BranchShape.Bare() || true
        new BranchShape.Created() || false
        new BranchShape.InProgress() || false
        new BranchShape.Parked() || false
        new BranchShape.Answered() || false
        new BranchShape.CompletedUncleaned() || false
        new BranchShape.Delivered() || false
    }
}
