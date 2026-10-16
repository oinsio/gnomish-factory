package com.github.oinsio.gnomish.domain.branch

import spock.lang.Specification

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D13): the classifier reads the envelope at the
 * task's registered commit, and the tip contributes one fact — its relation to that commit. A tip
 * above the register is the registered commit's shape; a register that is not an ancestor of the
 * tip quarantines with a diagnosis, and a missing one under a tip that carries an envelope is
 * Unregistered (FR10 of fix-terminal-receipt-convergence, design D13).
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

    // FR10 of fix-terminal-receipt-convergence (design D13): a branch with no register is a
    // STARTED-less branch (Bare) only while its tip carries no envelope; any other is Unregistered —
    // a state of the clone, named with the register and the tip, never Unknown and never guessed
    // from content the factory never registered.
    // Scenario: lifecycle/task-branch-contract / A tip without its register is Unregistered, not Unknown
    // Scenario: lifecycle/task-branch-contract / A missing register is a state of the clone, not of the branch
    def "FR10: a branch with no register and #tip is #expected"() {
        when:
        def shapes = atRegister().collect {
            classifier.classify(withRelation(it, new TipRelation.NoRegister(REGISTER_NAME, TIP_ID, carries)))
        }

        then:
        shapes.every { it == expected }

        where:
        tip | carries || expected
        'an envelope at its tip' | true || new BranchShape.Unregistered(REGISTER_NAME, TIP_ID)
        'no envelope at its tip' | false || new BranchShape.Bare()
    }

    // FR10 (D13): once the touchpoint fetch lands the register, the same branch classifies by the
    // registered commit's content exactly as any registered branch does — and a reading with the
    // register present, repeated, changes nothing.
    // Scenario: lifecycle/task-branch-contract / A fetched register reclassifies an Unregistered branch by its content
    def "FR10: the register landing turns every Unregistered tip into the shape its content names"() {
        given:
        def tips = atRegister()

        expect:
        !tips.isEmpty()
        tips.every {
            def before = classifier.classify(withRelation(it, new TipRelation.NoRegister(REGISTER_NAME, TIP_ID, true)))
            def after = classifier.classify(it)
            before instanceof BranchShape.Unregistered && !(after instanceof BranchShape.Unregistered) &&
                    after == classifier.classify(it)
        }
    }
}
