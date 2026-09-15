/**
 * The factory's one untrusted-text owner: the character-class table naming what is hostile to a
 * terminal and to a log reader, and the primitives the two exits are built from — {@link
 * com.github.oinsio.gnomish.untrustedtext.TextSafety#strip} (escape sequences and neutralized
 * characters removed), {@link com.github.oinsio.gnomish.untrustedtext.TextSafety#capTail} (hostile
 * volume bounded to the tail), {@link com.github.oinsio.gnomish.untrustedtext.TextSafety#flatten}
 * (line separators rendered visibly), {@link
 * com.github.oinsio.gnomish.untrustedtext.TextSafety#forConsole} (the same table shown rather than
 * removed) and {@link com.github.oinsio.gnomish.untrustedtext.TextSafety#capRecord} (a whole
 * rendered record bounded to its head).
 *
 * <p><strong>Neutrality contract.</strong> This package imports the JDK and nothing else — no other
 * module of the factory, no Spring, no Jackson, no logging API. Unlike the leaves whose emptiness
 * exists to spare their consumers transitive coupling, this one's emptiness is a security property
 * (NFR-S1): a policy that can import nothing cannot reach a subprocess, a tracker, a log appender
 * or a configuration format, so the only thing attacker-influenced input can drive here is a walk
 * over code points. It is load-bearing twice over: {@code :domain} and the published
 * {@code :gnomish-plugin-api} both reach this leaf, so an edge added here lands in the domain and
 * in the contract's published POM alike. The Gradle layering gate states the same rule as data,
 * with an allowlist that is empty.
 *
 * <p><strong>What stays outside.</strong> These are primitives, not policy: they decide neither
 * which text is untrusted nor which plane it is bound for. The log plane's facade is
 * {@code logtext.LogText}, the findings funnel's is {@code app.findings.FindingsSanitizer}, and the
 * sink layer that neutralizes a rendered record lives with the appenders; the policy behind all
 * three is written down in {@code docs/adr/0004-logging-policy.md} and
 * {@code .claude/rules/logging.md}.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR1, NFR-S1 of split-logtext-leaves.
 */
@NullMarked
package com.github.oinsio.gnomish.untrustedtext;

import org.jspecify.annotations.NullMarked;
