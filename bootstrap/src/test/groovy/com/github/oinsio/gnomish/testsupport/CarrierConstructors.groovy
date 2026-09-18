package com.github.oinsio.gnomish.testsupport

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
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
 * that flagged those sites would ask every one of the 38 to write {@code .forLog()} by hand at the
 * call, which is exactly the honest-debt pattern D5 was written to replace.
 *
 * <p>So the scan skips a carrier argument at {@code new SomeException(...)} when {@code
 * SomeException} declares such a constructor, and flags it everywhere else — a legacy throwable
 * still taking a {@code String} detail is caught as before.
 */
class CarrierConstructors {

    /** Simple names of every class in {@code classes} with an {@code UntrustedText} constructor parameter. */
    static Set<String> throwableTypeNamesIn(JavaClasses classes) {
        classes.findAll { candidate ->
            candidate.constructors.any {
                it.rawParameterTypes*.name.contains(UntrustedText.name)
            }
        }
        .collect { it.simpleName }
        .toSet()
    }
}
