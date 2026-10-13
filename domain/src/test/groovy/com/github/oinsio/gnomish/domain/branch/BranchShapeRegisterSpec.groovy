package com.github.oinsio.gnomish.domain.branch

import com.github.oinsio.gnomish.domain.engine.Position
import spock.lang.Specification

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D13): the classifier reads the envelope at the
 * task's registered commit, and the tip contributes one fact — its relation to that commit. A tip
 * above the register is the registered commit's shape; a register that is not an ancestor of the
 * tip, or a missing one under a tip that carries an envelope, quarantines with a diagnosis.
 */
class BranchShapeRegisterSpec extends Specification {

    private static final String REGISTER_ID = 'a' * 40
    private static final String TIP_ID = 'b' * 40
    private static final String REGISTER_NAME = 'refs/gnomish-factory/PROJ-1'

    def classifier = new BranchShapeClassifier()

    private static BranchTipFacts withRelation(BranchTipFacts facts, TipRelation relation) {
        new BranchTipFacts(facts.taskEnvelope(), facts.stateEnvelope(), facts.recordedOutcome(),
                facts.recordedPosition(), facts.roundsRecorded(), facts.decisionsRecorded(),
                facts.registeredCleanup(), relation)
    }

    private static List<BranchTipFacts> atRegister() {
        BranchShapeClassifierPropertySpec.everyTip().findAll {
            it.tipRelation() instanceof TipRelation.AtRegister
        }
    }

    // FR13: the commits above the register are the open round's gnome range — never classified.
    // Scenario: lifecycle/task-branch-contract / A tip above the register is an open gnome range
    def "FR13: a tip above the register classifies exactly as the registered commit does"() {
        given:
        def tips = atRegister()

        expect:
        !tips.isEmpty()
        tips.every {
            classifier.classify(withRelation(it, new TipRelation.AboveRegister())) == classifier.classify(it)
        }
    }

    // F135: the facts come from the registered commit, so a Completed the gnome wrote above it is
    // simply not among them — the shape is the registered commit's own.
    // Scenario: lifecycle/task-branch-contract / A gnome-written Completed outcome is not a pending cleanup
    // Scenario: lifecycle/task-branch-contract / A gnome-written gate position opens no gate
    def "F135: a registered commit in progress under a tip above it is InProgress, not #forged"() {
        given: 'the facts the registered commit carries: rounds recorded, no outcome, at a stage'
        def registered = new BranchTipFacts(new EnvelopeStatus.Parsed(), new EnvelopeStatus.Parsed(),
                RecordedTerminal.NONE, Optional.of(new Position.AtStage('implement')), true, false, false,
                new TipRelation.AboveRegister())

        expect:
        classifier.classify(registered) == new BranchShape.InProgress()

        where:
        forged << [
            'CompletedUncleaned',
            'AwaitingApproval',
            'Delivered'
        ]
    }

    // D19: a register that is not an ancestor of the tip names both commits, whatever the
    // registered commit carries — a delivered register included.
    // Scenario: lifecycle/task-branch-contract / A register that is not an ancestor of the tip quarantines
    def "D19: a diverged register is Unknown naming the register and the tip, whatever the content"() {
        when:
        def shapes = atRegister().collect {
            classifier.classify(withRelation(it, new TipRelation.Diverged(REGISTER_ID, TIP_ID)))
        }

        then:
        shapes.every { it instanceof BranchShape.Unknown }
        shapes.every {
            def reason = (it as BranchShape.Unknown).reason()
            reason.contains(REGISTER_ID) && reason.contains(TIP_ID)
        }
    }

    // A branch with no register is a STARTED-less branch (Bare) only while its tip carries no
    // envelope; any other is quarantined, never guessed from content the factory never registered.
    // Scenario: lifecycle/task-branch-contract / A register that is not an ancestor of the tip quarantines
    // Scenario: git/git-task-persistence / The touchpoint fetch carries the register
    def "a branch with no register and #tip is #expected"() {
        when:
        def shapes = atRegister().collect {
            classifier.classify(withRelation(it, new TipRelation.NoRegister(REGISTER_NAME, TIP_ID, carries)))
        }

        then:
        shapes.every { it.class == expected }
        !carries || shapes.every {
            def reason = (it as BranchShape.Unknown).reason()
            reason.contains(REGISTER_NAME) && reason.contains(TIP_ID)
        }

        where:
        tip | carries || expected
        'an envelope at its tip' | true || BranchShape.Unknown
        'no envelope at its tip' | false || BranchShape.Bare
    }
}
