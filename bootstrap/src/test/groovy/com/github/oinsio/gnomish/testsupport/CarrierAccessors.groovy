package com.github.oinsio.gnomish.testsupport

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaParameterizedType

/**
 * Which methods yield untrusted text, read from bytecode — the one answer both untrusted-text
 * gates are built on (design D2 of type-untrusted-text).
 *
 * <p>{@code UntrustedTextGateSpec}'s rule (b) asks it of the capture vocabulary's accessors, one
 * by one: a family whose accessor still returns a {@code String} has no mint. {@code
 * UntrustedTextSinkGateSpec}'s rule (c) asks it of the whole tree, to learn the accessor names a
 * carrier can arrive at a sink under. Deriving that set rather than listing it is what keeps the
 * source scan honest across the migration: a family typed in a later task joins the scan the
 * moment its accessors change, and a name that never becomes a carrier never enters it.
 */
class CarrierAccessors {

    /**
     * True when the method yields untrusted text: the carrier itself, or a {@code List} of it —
     * the shape {@code options} and every other multi-valued capture takes. The list case reads
     * the generic signature, because every {@code List} has the same raw return type.
     */
    static boolean carrierTyped(JavaMethod method) {
        if (method.rawReturnType.name == UntrustedText.name) {
            return true
        }
        def returnType = method.returnType
        method.rawReturnType.name == List.name &&
                returnType instanceof JavaParameterizedType &&
                returnType.actualTypeArguments*.name == [UntrustedText.name]
    }

    /**
     * Every no-argument accessor name in {@code classes} whose result is a carrier, minus the
     * names that are <b>ambiguous</b> — declared elsewhere in the same tree by a no-argument
     * method returning a plain {@code String}.
     *
     * <p>The source scan keys on the name of the call an argument expression ends with, because
     * that is all a source has; it cannot ask what type the receiver is. A name that means "a
     * carrier" on one record and "a string" on another is therefore no evidence either way, and
     * counting it would fail the build on {@code repeat.reason()} — a suppressor's own text — for
     * looking like {@code outcome.reason()}. Dropping it loses nothing a source scan could have
     * decided, and the loss repairs itself: when a family's twin is typed, the name stops being
     * ambiguous and re-enters the scan by itself.
     */
    static Set<String> namesIn(JavaClasses classes) {
        carrierNames(classes) - ambiguousNamesIn(classes)
    }

    /**
     * Carrier accessor names a plain-{@code String} method of the same name also claims —
     * <b>whatever its parameters</b>. The scan reads an argument expression and can see the name
     * the call ends with; it cannot tell {@code error.render()} from {@code briefing.render(req)},
     * so a name that any {@code String}-returning method in the tree answers to is no evidence
     * either way. Requiring the twin to be no-argument was what made {@code render} — a carrier on
     * {@code ConfigError} since task 5.2, a plain renderer on four other classes — flag every
     * {@code render(…)} call in the tree as a raw carrier at a sink.
     */
    static Set<String> ambiguousNamesIn(JavaClasses classes) {
        def strings = classes.collectMany { it.methods }
        .findAll { it.rawReturnType.name == String.name }
        .collect { it.name }
        .toSet()
        carrierNames(classes).intersect(strings)
    }

    private static Set<String> carrierNames(JavaClasses classes) {
        classes.collectMany { it.methods }
        .findAll { it.rawParameterTypes.isEmpty() && carrierTyped(it) }
        .collect { it.name }
        .toSet()
    }
}
