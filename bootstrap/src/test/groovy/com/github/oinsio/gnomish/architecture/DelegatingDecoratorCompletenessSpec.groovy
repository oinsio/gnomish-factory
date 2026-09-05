package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.file.Path
import java.util.function.Supplier
import spock.lang.Shared
import spock.lang.Specification

/**
 * Delegating-decorator completeness gate (FR9, M5, design D7 of
 * fix-denial-attribution-durability): a production class that implements an interface AND holds a
 * delegate of that same interface must override every default method the interface declares.
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
 * constructor parameter, or a {@link Supplier} of the interface.
 *
 * <p>Exemptions are named in {@link #EXEMPT}, each with its reason — never a blanket pattern.
 */
class DelegatingDecoratorCompletenessSpec extends Specification {

    /**
     * Justified exemptions, one entry per class AND method, each with its reason — never a
     * pattern, and never a whole class: a delegator exempted for one default stays gated on
     * every other default its interfaces declare.
     */
    static final List<Map<String, String>> EXEMPT = [
        [
            type: 'com.github.oinsio.gnomish.adapter.tracker.EpochRecordingTrackerFactory',
            method: 'create',
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
        def classes = productionClasses
                .findAll {
                    !it.isInterface() && !it.name.contains('$$') && !it.name.contains('$_')
                }
                .collect { it.reflect() }
                .findAll { !it.synthetic && !it.anonymousClass }

        expect: 'no delegator inherits a default that would answer for its delegate'
        unforwardedDefaults(classes) == []
    }

    // M5: the rule's own teeth — a seeded delegator that forgets one default is reported, and the
    //     complete twin beside it is not, so a rule that reported nothing could not pass this.
    def "a seeded delegator that forgets a default fails the rule"() {
        expect:
        unforwardedDefaults([ForgetfulDelegator]).size() == 1
        unforwardedDefaults([ForgetfulDelegator])[0].contains('SandboxLifecyclePass.run')

        and: 'the same shape with the default forwarded is clean'
        unforwardedDefaults([CompleteDelegator]) == []

        and: 'a supplier-held delegate counts as a delegate too'
        unforwardedDefaults([SupplierDelegator]).size() == 1

        and: 'a leaf that merely implements the interface is untouched — the default is truthful there'
        unforwardedDefaults([Leaf]) == []
    }

    // FR9: the allowlist is a list of names with reasons, not a pattern — and every entry still
    //     names a class that exists, so a rename cannot silently widen the exemption.
    def "every exemption names a class that exists and states its reason"() {
        expect:
        EXEMPT.every { entry ->
            def type = productionClasses.find { it.name == entry.type }
            type != null && type.reflect().interfaces.any { iface ->
                iface.methods.any { it.isDefault() && it.name == entry.method }
            } && !entry.reason.isBlank()
        }
    }

    /**
     * The rule itself: for each class, each interface it implements that it also holds a delegate
     * of, every default method of that interface must be declared by the class (or a superclass).
     */
    private static List<String> unforwardedDefaults(Collection<Class<?>> classes) {
        classes.collectMany { Class<?> type ->
            allInterfaces(type).collectMany { Class<?> iface ->
                if (!holdsDelegateOf(type, iface)) {
                    return []
                }
                iface.methods
                        .findAll {
                            it.isDefault() && !declares(type, it) && !exempt(type, it)
                        }
                        .collect {
                            "${type.name} does not forward ${iface.simpleName}.${it.name}" as String
                        }
            }
        }
        .sort()
    }

    private static boolean exempt(Class<?> type, Method method) {
        EXEMPT.any { it.type == type.name && it.method == method.name }
    }

    /**
     * The interfaces the rule judges: the project's own seams. The Groovy runtime's {@code
     * GroovyObject} is excluded — every Groovy class implements it, its defaults are the
     * metaclass plumbing the compiler generates, and no author ever "forgot" to forward them.
     */
    private static Set<Class<?>> allInterfaces(Class<?> type) {
        Set<Class<?>> found = [] as Set<Class<?>>
        for (Class<?> c = type; c != null && c != Object; c = c.superclass) {
            c.interfaces.each { collectInterfaces(it, found) }
        }
        found.findAll {
            !it.name.startsWith('groovy.') && !it.name.startsWith('org.codehaus.groovy.')
        } as Set<Class<?>>
    }

    private static void collectInterfaces(Class<?> iface, Set<Class<?>> found) {
        if (found.add(iface)) {
            iface.interfaces.each { collectInterfaces(it, found) }
        }
    }

    /** A field, record component, constructor parameter, or Supplier of the interface. */
    private static boolean holdsDelegateOf(Class<?> type, Class<?> iface) {
        List<List<Object>> held = (type.declaredFields.findAll {
            !Modifier.isStatic(it.modifiers)
        }
        .collect { [it.type, it.genericType] } as List<List<Object>>) +
        (type.declaredConstructors.collectMany { ctor ->
            [
                ctor.parameterTypes.toList(),
                ctor.genericParameterTypes.toList()
            ].transpose()
        } as List<List<Object>>)
        held.any { raw, generic ->
            isDelegate(raw as Class<?>, generic as Type, iface)
        }
    }

    private static boolean isDelegate(Class<?> raw, Type generic, Class<?> iface) {
        if (iface.isAssignableFrom(raw)) {
            return true
        }
        Supplier.isAssignableFrom(raw) && generic instanceof ParameterizedType &&
                (generic as ParameterizedType).actualTypeArguments.any {
                    it instanceof Class && iface.isAssignableFrom(it as Class)
                }
    }

    private static boolean declares(Class<?> type, Method method) {
        for (Class<?> c = type; c != null && c != Object; c = c.superclass) {
            if (c.declaredMethods.any {
                        it.name == method.name && it.parameterTypes == method.parameterTypes
                    }) {
                return true
            }
        }
        false
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

    /** Not a delegator: for a leaf the default is a truthful "I do not have this". */
    static class Leaf implements SandboxLifecyclePass {

        @Override
        String run(Path cloneDir, LivenessVerdict liveness) {
            'mine'
        }
    }
}
