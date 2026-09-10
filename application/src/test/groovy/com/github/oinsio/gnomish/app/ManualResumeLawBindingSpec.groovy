package com.github.oinsio.gnomish.app

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR7, FR12, D13 of add-base-ref-resolution: the one rule that decides a manual {@code gnomish run
 * --resume}'s law source — the local tip of the ref the task branch is pinned to, never the
 * operator clone's own checkout. The tracker-driven twin of this rule, with its narrow fetch and
 * its park/release arms, is proven in {@code ResumeLawBindingSpec}.
 */
class ManualResumeLawBindingSpec extends Specification {

    private static final Path CLONE = Path.of('/clones/widgets')

    // FR12, D13: the pinned ref names the law, so a clone checked out at main still runs a
    //     release/1.18 task under release/1.18's law.
    def "FR12: a pinned task binds law at its pinned ref"() {
        expect:
        ManualResumeLawBinding.of(CLONE, 'release/1.18', 'c0ffee') == new LawBinding.AtRevision(CLONE, 'release/1.18')
    }

    // FR7: a legacy branch predating the durable pin carries only the base commit, itself a valid
    //     revision — the same fallback the tracker-driven resume takes.
    def "FR7: a legacy branch with no pin binds law at its recorded base commit"() {
        expect:
        ManualResumeLawBinding.of(CLONE, null, 'c0ffee') == new LawBinding.AtRevision(CLONE, 'c0ffee')
    }

    // FR8, UX3: a manual run without --base pinned the literal HEAD ref, so its resume binds the
    //     clone's checkout exactly as it did before the pin existed.
    def "FR8: a task started by a manual run without --base still binds the clone's checkout"() {
        expect:
        ManualResumeLawBinding.of(CLONE, 'HEAD', 'c0ffee') == LawBinding.atCheckout(CLONE)
    }
}
