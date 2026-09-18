package com.github.oinsio.gnomish.untrustedtext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class allowed to read {@link UntrustedText#forParsing()} — the captured bytes, taken not
 * to show a reader but to turn into a value (FR10, design D11 of type-untrusted-text). Captured
 * standard output is a carrier like standard error, but it is read for the opposite reason: a
 * commit id from {@code rev-parse}, a ref list from {@code branch --list}, a container's state from
 * {@code docker inspect}. A parse needs the bytes uncapped and unflattened, which is exactly what
 * an exit rendering takes away.
 *
 * <p>It is a second annotation rather than a widening of {@link UntrustedExit} because the two
 * lists answer different questions and are read for different reasons: the exit set answers "who
 * may put untrusted bytes in front of a reader", this one "who converts untrusted bytes into a
 * value". Folding parsers into the exit set would take that first list from seven classes to
 * thirty-three and leave it meaning nothing.
 *
 * <p><b>Membership is by return type, not by intent.</b> A method here turns the text into
 * something that is no longer untrusted text: a typed value ({@code Path}, a boolean, an enum, an
 * {@code Optional} of a commit id), a {@code String} that passed a <b>named</b> syntax gate
 * ({@code RefNameSyntax}, {@code ModelIdSyntax}) or whose fixed shape the method checks, or a
 * carrier re-minted with another provenance. Handing the text back unchanged as a {@code String} is
 * not parsing — it is the escape hatch this change exists to close, and the gate seeds exactly that
 * offender. Where the converted value is itself a {@code String}, the method's own javadoc names
 * what makes it inert, a review obligation on the precedent of {@code lock-scope.md}.
 *
 * <p>A reader that yields a document's content rather than an answer about it is not a parser
 * either: it carries the carrier onward to the machine writer that consumes it.
 *
 * <p>Runtime retention because the gate reads the annotation from bytecode, not from source.
 * {@code UntrustedTextGateSpec} in {@code :bootstrap} fails the build on any other caller of
 * {@code forParsing()}, and pins the annotated set as a map from class to what it converts the text
 * into, so a reviewer reads the warrant beside the name.
 *
 * <p>Implements FR10 of type-untrusted-text.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UntrustedParser {}
