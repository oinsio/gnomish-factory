package com.github.oinsio.gnomish.untrustedtext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class allowed to read {@link UntrustedText#raw()} — the one way untrusted text leaves
 * the carrier without passing an exit (FR3, design D2 of type-untrusted-text). Two kinds of class
 * qualify, and no third:
 *
 * <ul>
 *   <li>the carrier itself, which renders the three exits from the text it holds;
 *   <li>a writer carrying the raw bytes to a <b>machine</b> medium, where the medium's own encoding
 *       bounds them and a neutralized value would corrupt the record — the task-branch
 *       {@code task.json}/{@code state.json} writers, the ledger and snapshot JSON writers, and the
 *       findings funnel's entry.
 * </ul>
 *
 * <p>The allowlist lives here, beside the type, rather than as a list of class names in build logic
 * that could drift from the code — the shape {@code @DoNotMutate} uses for the mutation gate.
 * {@code UntrustedTextGateSpec} in {@code :bootstrap} fails the build on any other caller of
 * {@code raw()}, and pins the annotated set itself, so the allowlist cannot grow unnoticed.
 *
 * <p>Runtime retention because the gate reads the annotation from bytecode, not from source.
 *
 * <p>Implements FR3 of type-untrusted-text.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UntrustedExit {}
