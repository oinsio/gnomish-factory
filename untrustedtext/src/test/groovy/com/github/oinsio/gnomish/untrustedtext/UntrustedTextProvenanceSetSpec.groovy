package com.github.oinsio.gnomish.untrustedtext

import java.lang.reflect.Modifier
import spock.lang.Specification

/**
 * FR1, FR4 of type-untrusted-text: the provenance families are a closed set — the six from outside
 * the trust boundary, the operator's own argument and the factory's own prose — and the carrier can
 * be made only through one public mint per family. Text that belongs to none of them has no way in:
 * the class is final, its one constructor private, and no public factory takes a provenance as an
 * argument a caller could fill with something new.
 */
class UntrustedTextProvenanceSetSpec extends Specification {

    /** Mint name → the family it stamps, one per family. */
    private static final Map<String, Provenance> MINTS = [
        subprocess: Provenance.SUBPROCESS,
        container: Provenance.CONTAINER,
        agent: Provenance.AGENT,
        tracker: Provenance.TRACKER,
        manifest: Provenance.MANIFEST,
        branchDocument: Provenance.BRANCH_DOCUMENT,
        operator: Provenance.OPERATOR,
        factory: Provenance.FACTORY,
    ]

    // Scenario: core/untrusted-text / The provenance set is closed
    def "FR1: the families are closed and each has exactly one mint, the only way a carrier is made"() {
        expect: 'the eight families, no more'
        Provenance.values() as Set == MINTS.values() as Set

        and: 'the carrier cannot be extended or constructed from outside'
        Modifier.isFinal(UntrustedText.modifiers)
        UntrustedText.declaredConstructors.every {
            Modifier.isPrivate(it.modifiers)
        }

        and: 'the public static methods yielding a carrier from a string are exactly the mints'
        UntrustedText.methods.findAll {
            Modifier.isStatic(it.modifiers) && it.returnType == UntrustedText
        }*.name as Set == MINTS.keySet()

        and: 'no mint takes a provenance, so a caller cannot name a family of its own'
        UntrustedText.methods.every { method ->
            !(Provenance in method.parameterTypes)
        }

        and: 'each mint stamps its own family'
        MINTS.every { name, family ->
            UntrustedText."$name"('text').provenance() == family
        }
    }
}
