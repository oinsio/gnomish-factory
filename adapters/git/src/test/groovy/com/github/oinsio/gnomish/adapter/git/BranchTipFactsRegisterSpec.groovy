package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.branch.EnvelopeStatus
import com.github.oinsio.gnomish.domain.branch.TipRelation
import spock.lang.Specification

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D13): the wire format is read at the registered
 * commit, and only while the tip stands at or above it. A branch whose register is missing or not
 * an ancestor of the tip is classified by that relation alone — its envelope is never read, so no
 * content the factory never registered becomes a fact.
 */
class BranchTipFactsRegisterSpec extends Specification {

    private static final String TASK = '{"version":1,"taskId":"PROJ-1","title":"T","body":"B",' +
    '"createdAt":"2026-07-18T09:00:00Z","baseCommit":"abc","decisions":[],"outcome":{"type":"completed"}}'

    private static BranchTipFactsReaderSpec.FixedTip source(TipRelation relation) {
        new BranchTipFactsReaderSpec.FixedTip(files: ['.gnomish-task/task.json': TASK], relation: relation)
    }

    // FR13: a tip off the register reads nothing — its facts carry the relation and no envelope.
    def "FR13: a #relation.class.simpleName relation reads no envelope at all"() {
        given:
        def tip = source(relation)

        when:
        def facts = new BranchTipFactsReader().read(tip)

        then:
        tip.reads.isEmpty()
        facts.taskEnvelope() == new EnvelopeStatus.Absent()
        facts.stateEnvelope() == new EnvelopeStatus.Absent()
        facts.tipRelation() == relation

        where:
        relation << [
            new TipRelation.Diverged('a' * 40, 'b' * 40),
            new TipRelation.NoRegister('refs/gnomish-factory/PROJ-1', 'b' * 40, true)
        ]
    }

    // FR13: at or above the register, the registered commit's envelope is the one read.
    def "FR13: a #relation.class.simpleName relation reads the registered envelope"() {
        given:
        def tip = source(relation)

        when:
        def facts = new BranchTipFactsReader().read(tip)

        then:
        tip.reads == [
            '.gnomish-task/task.json',
            '.gnomish-task/state.json'
        ]
        facts.taskEnvelope() == new EnvelopeStatus.Parsed()
        facts.tipRelation() == relation

        where:
        relation << [
            new TipRelation.AtRegister(),
            new TipRelation.AboveRegister()
        ]
    }

    // FR13: the status and listing reader stops at the same relation: a quarantined branch is a
    //     shape with nothing to render, read from nothing.
    def "FR13: the envelope reader answers a diverged register with its Unknown shape and reads nothing"() {
        given:
        def tip = source(new TipRelation.Diverged('a' * 40, 'b' * 40))

        when:
        def read = new TipEnvelopeReader().read(tip)

        then:
        read instanceof TipEnvelopeRead.NoState
        (read as TipEnvelopeRead.NoState).shape() instanceof BranchShape.Unknown
        tip.reads.isEmpty()
    }
}
