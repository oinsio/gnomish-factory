package com.github.oinsio.gnomish.testsupport

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses

/**
 * Which throwable types declare that their detail <em>is</em> untrusted text, read from bytecode
 * (FR5, FR7, design D2, D5 of type-untrusted-text).
 *
 * <p>Rule (c) keeps a carrier out of a sink so the exit is visible at the site. A {@code Throwable}
 * whose constructor takes {@link UntrustedText} is the one shape where that reasoning inverts:
 * design D5 makes the parameter type itself the exit contract — the constructor composes the
 * message through the carrier's log exit, and the signature is what stops a raw {@code String}
 * being handed over instead ({@code implementation.md}, item 3, "the escape hatch is gone"). A rule
 * that flagged those sites would ask every one of them to write {@code .forLog()} by hand at the
 * call, which is exactly the honest-debt pattern D5 was written to replace.
 *
 * <p>So the scan skips a carrier argument at {@code new SomeException(...)} when {@code
 * SomeException} declares such a constructor, and flags it everywhere else — a legacy throwable
 * still taking a {@code String} detail is caught as before.
 *
 * <p>Two consumers read this one predicate: {@code UntrustedTextSinkGateSpec} derives rule (c)'s
 * exemption set from it, and {@code TypedExceptionMessageSpec} pins its rendering table against it,
 * so the two sets are the same set by construction rather than kept in step by hand
 * ({@code manual-sync-pairs.md}).
 */
class CarrierConstructors {

    /**
     * Simple names of every throwable in {@code classes} that declares a carrier detail. The
     * throwable filter is part of the derivation rather than each caller's own step, so both gates
     * read the same set instead of one of them widening it to any carrier-constructed type.
     */
    static Set<String> throwableTypeNamesIn(JavaClasses classes) {
        classes.findAll {
            it.isAssignableTo(Throwable) && declaresCarrierConstructor(it)
        }
        .collect { it.simpleName }
        .toSet()
    }

    /**
     * Whether {@code candidate} declares the D5 exit contract in a constructor signature — the one
     * reading of the bytecode, kept private so no caller can re-derive the set around it with a
     * filter of its own ({@link #throwableTypeNamesIn} is the whole answer both gates take).
     */
    private static boolean declaresCarrierConstructor(JavaClass candidate) {
        candidate.constructors.any {
            it.rawParameterTypes*.name.contains(UntrustedText.name)
        }
    }
}
