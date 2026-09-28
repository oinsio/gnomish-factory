package com.github.oinsio.buildchecks.parametercount;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Exempts one constructor or method from the seven-parameter limit, with the reason written on
 * the declaration it excuses.
 *
 * <p>The only exemption token the parameter-count check honours: it ignores
 * {@code @SuppressWarnings}, so no form exists that silences a whole class or file. The target
 * set refuses the annotation on a type for the same reason, and the check reports a blank
 * {@link #reason()}, so "annotated" and "justified" cannot come apart. A grep for this name is
 * therefore the complete list of the gate's exceptions.
 *
 * <p>Implements FR4 of add-parameter-count-gate (design D5).
 */
@Retention(SOURCE)
@Target({METHOD, CONSTRUCTOR})
public @interface ParameterLimitExemption {

    /** Why this signature cannot take a parameter object; must not be blank. */
    String reason();
}
