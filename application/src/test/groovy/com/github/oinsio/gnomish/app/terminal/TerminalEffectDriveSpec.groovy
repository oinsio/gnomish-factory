package com.github.oinsio.gnomish.app.terminal

import spock.lang.Specification

/**
 * FR10 of harden-task-branch-contract: one shared intent→effect→receipt protocol — durable intent
 * before the effect, receipt after it, the target probed before a re-drive, and the destructive step
 * after every constructive receipt.
 */
class TerminalEffectDriveSpec extends Specification {

    /** Records the order its steps ran in, so the protocol's ordering is asserted, not inferred. */
    static class RecordingEffect implements TerminalEffect {

        final List<String> steps = []
        EffectObservation observation = EffectObservation.ABSENT
        boolean delivers = true

        @Override
        void recordIntent() {
            steps << 'intent'
        }

        @Override
        EffectObservation observeAtTarget() {
            steps << 'probe'
            return observation
        }

        @Override
        boolean deliver() {
            steps << 'effect'
            return delivers
        }

        @Override
        void recordReceipt() {
            steps << 'receipt'
        }

        @Override
        void runDestructiveStep() {
            steps << 'destructive'
        }
    }

    def effect = new RecordingEffect()

    // FR10: intent is durable before the effect, the receipt follows it, the destructive step is last.
    def "a fresh transition runs intent, effect, receipt, then the destructive step"() {
        when:
        def delivery = TerminalEffectDrive.deliverFresh(effect)

        then:
        effect.steps == [
            'intent',
            'effect',
            'receipt',
            'destructive'
        ]
        delivery == EffectDelivery.CONFIRMED
        delivery.settled()
    }

    // FR10: the fresh path never probes — an ordinary transition pays no extra read.
    def "a fresh transition does not probe the target"() {
        when:
        TerminalEffectDrive.deliverFresh(effect)

        then:
        !effect.steps.contains('probe')
    }

    // FR10: an unconfirmed effect records no receipt, so the durable intent stays outstanding.
    def "an unconfirmed effect records no receipt and removes nothing"() {
        given:
        effect.delivers = false

        when:
        def delivery = TerminalEffectDrive.deliverFresh(effect)

        then:
        effect.steps == ['intent', 'effect']
        delivery == EffectDelivery.UNCONFIRMED
        !delivery.settled()
    }

    // FR10: recovery verifies the effect at the target before re-driving it.
    def "a recovered intent whose effect already landed records only the owed receipt"() {
        given:
        effect.observation = EffectObservation.LANDED

        when:
        def delivery = TerminalEffectDrive.redeliver(effect)

        then:
        effect.steps == [
            'probe',
            'receipt',
            'destructive'
        ]
        delivery == EffectDelivery.ALREADY_LANDED
        delivery.settled()
    }

    // FR10: recovery never re-records the intent — it is already durable on the branch.
    def "a recovered intent whose effect is absent is re-driven without re-recording the intent"() {
        given:
        effect.observation = observation

        when:
        def delivery = TerminalEffectDrive.redeliver(effect)

        then:
        effect.steps == [
            'probe',
            'effect',
            'receipt',
            'destructive'
        ]
        delivery == EffectDelivery.CONFIRMED

        where:
        // An unaskable target reads as "not there": every tracker write is a find-then-upsert (FR11),
        // so a redundant re-drive updates in place while a skipped one would lose the transition.
        observation << [
            EffectObservation.ABSENT,
            EffectObservation.UNDETERMINED
        ]
    }

    // FR10, NFR-R1: running a recovery on an already-recovered state changes nothing beyond the receipt.
    def "re-driving twice equals re-driving once"() {
        given:
        effect.observation = EffectObservation.LANDED

        when:
        TerminalEffectDrive.redeliver(effect)
        def second = TerminalEffectDrive.redeliver(effect)

        then:
        effect.steps == [
            'probe',
            'receipt',
            'destructive',
            'probe',
            'receipt',
            'destructive'
        ]
        second == EffectDelivery.ALREADY_LANDED
    }

    // FR10: a flow with nothing to remove keeps the default no-op destructive step.
    def "a flow with no destructive step drives to a confirmed delivery"() {
        given:
        def plain = new TerminalEffect() {
                    final List<String> steps = []

                    @Override
                    void recordIntent() {
                        steps << 'intent'
                    }

                    @Override
                    EffectObservation observeAtTarget() {
                        return EffectObservation.ABSENT
                    }

                    @Override
                    boolean deliver() {
                        steps << 'effect'
                        return true
                    }

                    @Override
                    void recordReceipt() {
                        steps << 'receipt'
                    }
                }

        when:
        def delivery = TerminalEffectDrive.deliverFresh(plain)

        then:
        plain.steps == ['intent', 'effect', 'receipt']
        delivery == EffectDelivery.CONFIRMED
    }
}
