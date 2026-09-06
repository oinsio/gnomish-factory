package com.github.oinsio.gnomish.architecture

import static com.github.oinsio.gnomish.architecture.DelegatingDecoratorRule.params
import static com.github.oinsio.gnomish.architecture.DelegatingDecoratorRule.unforwardedDefaults

import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.nio.file.Path
import java.util.function.Supplier
import spock.lang.Shared
import spock.lang.Specification

/**
 * Delegating-decorator completeness gate (FR9, M5, design D7 of
 * fix-denial-attribution-durability): a production class that implements an interface AND holds a
 * delegate of that same interface must override every default method the interface declares.
 * {@link DelegatingDecoratorRule} holds the rule; this spec owns the allowlist and the assertions.
 *
 * <p>For a leaf implementation a constant default is a truthful "I do not have this". For a
 * delegating one it is a lie about someone else's capability: the delegate may well answer, but
 * the caller never reaches it. That is exactly how the predecessor's denial-cursor feature shipped
 * inert — {@code LeasedEnvironment} forwarded six methods and inherited three empty constants, so
 * production committed no cursor and restored none while both halves of its specs stayed green
 * over doubles that implemented the methods themselves. A repo-wide sweep found one sibling
 * ({@code ObservedSandboxLifecyclePass} dropping the caller's extra verdict sink) and this rule is
 * what keeps the third from being written.
 *
 * <p>"Holds a delegate" is read broadly, since the shape varies: a field, a record component, a
 * constructor parameter, or any container naming the interface as a type argument — a {@link
 * Supplier}, a {@code List}, an {@code Optional}.
 *
 * <p>Exemptions are named in {@link #EXEMPT}, each with its reason — never a blanket pattern.
 */
class DelegatingDecoratorCompletenessSpec extends Specification {

    /**
     * Justified exemptions, one entry per class AND signature, each with its reason — never a
     * pattern, and never a whole class: a delegator exempted for one default stays gated on
     * every other default its interfaces declare, its same-named overloads included.
     */
    static final List<Map<String, String>> EXEMPT = [
        [
            type: 'com.github.oinsio.gnomish.adapter.tracker.EpochRecordingTrackerFactory',
            method: 'create',
            params: 'SecretsProvider,TrackerConfig,String,ClaimEpochSource',
            reason: 'the unforwarded 4-parameter create default self-delegates into the 3-parameter' +
            ' form this class does override, so forwarding it would bypass the epoch recording' +
            ' the decorator exists to do'
        ]
    ]

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    // FR9, M5: the rule over the whole production tree — every delegating implementer is complete.
    def "no production delegating implementer leaves an interface default unforwarded"() {
        given: 'every named production class ArchUnit can reflect on'
        // Named classes only, and the exclusion is a limitation worth stating: a generated body
        // ($$ for a CGLIB-style proxy, $_ for a Groovy closure), a synthetic class and an
        // anonymous one have no name an EXEMPT entry could hold and no declaration a reader could
        // open, so a finding against one would be unactionable. A decorator meant to be gated is
        // therefore a named class — an anonymous delegator is outside this gate's reach.
        def classes = productionClasses
                .findAll {
                    !it.isInterface() && !it.name.contains('$$') && !it.name.contains('$_')
                }
                .collect { it.reflect() }
                .findAll { !it.synthetic && !it.anonymousClass }

        expect: 'no delegator inherits a default that would answer for its delegate'
        unforwardedDefaults(classes, EXEMPT) == []
    }

    // M5: the rule's own teeth — a seeded delegator that forgets one default is reported, and the
    //     complete twin beside it is not, so a rule that reported nothing could not pass this.
    def "a seeded delegator that forgets a default fails the rule"() {
        expect:
        unforwardedDefaults([ForgetfulDelegator], EXEMPT).size() == 1
        unforwardedDefaults([ForgetfulDelegator], EXEMPT)[0]
        .endsWith('SandboxLifecyclePass.run(Path,LivenessVerdict,SweepVerdictListener)')

        and: 'the same shape with the default forwarded is clean'
        unforwardedDefaults([CompleteDelegator], EXEMPT) == []

        and: 'a leaf that merely implements the interface is untouched — the default is truthful there'
        unforwardedDefaults([Leaf], EXEMPT) == []
    }

    // FR9, M5: the delegate shapes the rule recognises — held directly, or named as a type
    //     argument of whatever holds it. A container hides a delegate no less than a field does.
    def "a delegate held in a #shape counts as a delegate"() {
        expect:
        unforwardedDefaults([delegator], EXEMPT).size() == 1

        where:
        shape | delegator
        'supplier' | SupplierDelegator
        'list' | ListDelegator
        'optional' | OptionalDelegator
    }

    // FR9: the allowlist is a list of signatures with reasons, not a pattern — and every entry
    //     still names a default that exists, so a rename or a changed parameter list cannot
    //     silently widen the exemption; it turns the build red instead.
    def "every exemption names a default that exists and states its reason"() {
        expect:
        EXEMPT.every { entry ->
            def type = productionClasses.find { it.name == entry.type }
            type != null && type.reflect().interfaces.any { iface ->
                iface.methods.any {
                    it.isDefault() && it.name == entry.method && params(it) == entry.params
                }
            } && !entry.reason.isBlank()
        }
    }

    // FR9, M5: the exemption is matched by signature, not by bare name. The near-miss below is
    //     the real hazard: SandboxLifecyclePass already declares a two-parameter run beside the
    //     three-parameter default, so a bare-name match would hand one method's exemption to the
    //     other — and to every same-named default either seam gains later.
    def "an exemption is matched by signature, not by the method name alone"() {
        given: 'an exemption naming the right class and method name but a sibling overload\'s shape'
        def nearMiss = [
            [
                type: ForgetfulDelegator.name,
                method: 'run',
                params: 'Path,LivenessVerdict',
                reason: 'seeded: names the abstract sibling, not the default'
            ]
        ]

        expect: 'the default it does not name stays gated'
        unforwardedDefaults([ForgetfulDelegator], nearMiss).size() == 1

        and: 'the same entry carrying the default\'s own signature exempts it'
        unforwardedDefaults([ForgetfulDelegator], [
            nearMiss[0] + [params: 'Path,LivenessVerdict,SweepVerdictListener']
        ]) == []
    }

    /**
     * The seed is a real seam, not an invented one: {@link SandboxLifecyclePass} declares the
     * sink-taking {@code run} as a default that drops its extra sink, which is precisely the
     * sibling defect this rule was written after.
     */
    static class ForgetfulDelegator implements SandboxLifecyclePass {

        private final SandboxLifecyclePass delegate

        ForgetfulDelegator(SandboxLifecyclePass delegate) {
            this.delegate = delegate
        }

        @Override
        String run(Path cloneDir, LivenessVerdict liveness) {
            delegate.run(cloneDir, liveness)
        }
    }

    /** The same shape, complete. */
    static class CompleteDelegator implements SandboxLifecyclePass {

        private final SandboxLifecyclePass delegate

        CompleteDelegator(SandboxLifecyclePass delegate) {
            this.delegate = delegate
        }

        @Override
        String run(Path cloneDir, LivenessVerdict liveness) {
            delegate.run(cloneDir, liveness)
        }

        @Override
        String run(Path cloneDir, LivenessVerdict liveness, SweepVerdictListener extraSink) {
            delegate.run(cloneDir, liveness, extraSink)
        }
    }

    /** The LeasedEnvironment shape: the delegate arrives through a supplier. */
    static class SupplierDelegator implements SandboxLifecyclePass {

        private final Supplier<SandboxLifecyclePass> current

        SupplierDelegator(Supplier<SandboxLifecyclePass> current) {
            this.current = current
        }

        @Override
        String run(Path cloneDir, LivenessVerdict liveness) {
            current.get().run(cloneDir, liveness)
        }
    }

    /** A fan-out: the unforwarded default drops the capability for every element at once. */
    static class ListDelegator implements SandboxLifecyclePass {

        private final List<SandboxLifecyclePass> passes

        ListDelegator(List<SandboxLifecyclePass> passes) {
            this.passes = passes
        }

        @Override
        String run(Path cloneDir, LivenessVerdict liveness) {
            passes.collect { it.run(cloneDir, liveness) }.last()
        }
    }

    /** An absent delegate is still a delegate: the default answers for it while it is present. */
    static class OptionalDelegator implements SandboxLifecyclePass {

        private final Optional<SandboxLifecyclePass> delegate

        OptionalDelegator(Optional<SandboxLifecyclePass> delegate) {
            this.delegate = delegate
        }

        @Override
        String run(Path cloneDir, LivenessVerdict liveness) {
            delegate.map { it.run(cloneDir, liveness) }.orElse('none')
        }
    }

    /** Not a delegator: for a leaf the default is a truthful "I do not have this". */
    static class Leaf implements SandboxLifecyclePass {

        @Override
        String run(Path cloneDir, LivenessVerdict liveness) {
            'mine'
        }
    }
}
