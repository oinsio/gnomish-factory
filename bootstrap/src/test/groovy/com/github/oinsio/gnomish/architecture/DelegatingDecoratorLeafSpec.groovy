package com.github.oinsio.gnomish.architecture

import static com.github.oinsio.gnomish.architecture.DelegatingDecoratorRule.unforwardedDefaults

import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import java.nio.file.Path
import spock.lang.Specification

/**
 * The delegating-decorator rule's other half (FR9, design D7 of fix-denial-attribution-durability):
 * it constrains only a class that holds a delegate of the interface it implements. A leaf that
 * inherits an interface default is telling the truth about itself, so the rule leaves it alone —
 * while the same class body holding a delegate is reported. {@link DelegatingDecoratorCompletenessSpec}
 * owns the gate over the production tree.
 */
class DelegatingDecoratorLeafSpec extends Specification {

    // Scenario: build/quality-gates / A leaf implementer is untouched
    def "FR9: a class implementing an interface with a default, but holding no delegate of it, is not constrained"() {
        expect: 'the leaf inherits the three-argument default and is not reported'
        unforwardedDefaults([LeafPass], []) == []

        and: 'the same inheritance with a delegate held beside it is reported'
        unforwardedDefaults([HoldingPass], []).size() == 1
    }

    /** A leaf: answers for itself and inherits the default that extends the call. */
    static class LeafPass implements SandboxLifecyclePass {

        @Override
        String run(Path cloneDir, LivenessVerdict liveness) {
            'leaf'
        }
    }

    /** The leaf's body, holding a delegate it never forwards the default to. */
    static class HoldingPass implements SandboxLifecyclePass {

        private final SandboxLifecyclePass delegate

        HoldingPass(SandboxLifecyclePass delegate) {
            this.delegate = delegate
        }

        @Override
        String run(Path cloneDir, LivenessVerdict liveness) {
            delegate.run(cloneDir, liveness)
        }
    }
}
