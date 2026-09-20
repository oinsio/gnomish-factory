package com.github.oinsio.gnomish.untrustedtext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class allowed to read {@link UntrustedText#raw()} — one of the two ways untrusted text
 * leaves the carrier unrendered, this one onto a <b>machine</b> medium ({@link UntrustedParser}
 * gates the other, onto a parse) (FR3, design D2 of type-untrusted-text). Two kinds of class
 * qualify, and no third:
 *
 * <ul>
 *   <li>the carrier itself, which renders every human-plane exit from the text it holds;
 *   <li>a writer carrying the raw bytes to a machine medium, where the medium's own encoding
 *       bounds them and a neutralized value would corrupt the record — the task-branch
 *       {@code task.json}/{@code state.json} writers, the {@code --json} report mappers, and the
 *       findings funnel's entry.
 * </ul>
 *
 * <p><b>Membership is the {@code raw()} call, not the family.</b> A machine writer whose every
 * field is a string the factory minted itself reads no raw text, so marking it would widen the
 * allowlist for nothing — which is why the ledger and snapshot JSON writers are deliberately not
 * members, whatever family their fields once belonged to.
 *
 * <p>The allowlist lives here, beside the type, rather than as a list of class names in build logic
 * that could drift from the code — the shape {@code @DoNotMutate} uses for the mutation gate.
 * {@code UntrustedTextGateSpec} in {@code :bootstrap} fails the build on any other caller of
 * {@code raw()}, and pins the annotated set itself, so the allowlist cannot grow unnoticed.
 *
 * <p><b>The marker needs a warrant.</b> The same gate fails the build on an annotated class that
 * never reads {@code raw()} — the check {@link UntrustedParser} is held to as well, written once
 * so the two sets cannot be graded differently. The carrier itself is the one member exempt: it
 * declares the method.
 *
 * <p>Runtime retention because the gate reads the annotation from bytecode, not from source.
 *
 * <p>Implements FR3 of type-untrusted-text.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UntrustedExit {}
