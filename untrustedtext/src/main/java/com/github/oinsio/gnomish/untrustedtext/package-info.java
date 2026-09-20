/**
 * The factory's one untrusted-text owner: the carrier attacker-influenced text travels in, the
 * character-class table naming what is hostile to a terminal and to a log reader, and the
 * primitives the three human planes — log, console and tracker comment — are rendered by.
 *
 * <p><strong>What lives here.</strong> {@link
 * com.github.oinsio.gnomish.untrustedtext.UntrustedText} is the carrier: minted at capture with a
 * {@link com.github.oinsio.gnomish.untrustedtext.Provenance} family, and left only through an
 * exit. {@link com.github.oinsio.gnomish.untrustedtext.UntrustedExit} and {@link
 * com.github.oinsio.gnomish.untrustedtext.UntrustedParser} are the two allowlists confining its
 * unrendered ways out — the raw bytes onto a machine medium, and the bytes taken to be turned into
 * a value. {@link com.github.oinsio.gnomish.untrustedtext.TextSafety} is the facade over the table
 * and over the primitives it composes those renderings from: {@link
 * com.github.oinsio.gnomish.untrustedtext.TextSafety#strip} (escape sequences and neutralized
 * characters removed), {@link com.github.oinsio.gnomish.untrustedtext.TextSafety#capTail} (hostile
 * volume bounded to the tail) and {@link
 * com.github.oinsio.gnomish.untrustedtext.TextSafety#flatten} (line separators rendered visibly)
 * for the log plane; {@link com.github.oinsio.gnomish.untrustedtext.TextSafety#forConsole} (the
 * same table shown rather than removed) for the operator's terminal; {@link
 * com.github.oinsio.gnomish.untrustedtext.TextSafety#forComment} and {@link
 * com.github.oinsio.gnomish.untrustedtext.TextSafety#forCommentInline} (mentions and issue
 * references broken, the block fenced) for the tracker; and {@link
 * com.github.oinsio.gnomish.untrustedtext.TextSafety#capRecord} (one rendered record component
 * bounded to its head) at the sink. Each mechanism is its own class beside that facade — the
 * character table, the line flattening, the console notation, the comment fencing, the
 * head-and-tail cap and the record cap — so that "which characters are hostile" and "how each
 * plane renders them" keep separate owners.
 *
 * <p><strong>Neutrality contract.</strong> This package imports the JDK and nothing else — no other
 * module of the factory, no Spring, no Jackson, no logging API. Unlike the leaves whose emptiness
 * exists to spare their consumers transitive coupling, this one's emptiness is a security property
 * (NFR-S1 of split-logtext-leaves): a policy that can import nothing cannot reach a subprocess, a
 * tracker, a log appender or a configuration format, so the only thing attacker-influenced input
 * can drive here is a walk over code points. It is load-bearing twice over: {@code :domain} and
 * the published {@code :gnomish-plugin-api} both reach this leaf, so an edge added here lands in
 * the domain and in the contract's published POM alike. The Gradle layering gate states the same
 * rule as data, with an allowlist that is empty.
 *
 * <p><strong>What stays outside.</strong> This leaf renders text and confines the ways out of the
 * carrier; it decides neither which text is untrusted nor which plane a given value is bound for —
 * both are the caller's. The facade for log-plane text from a family not yet typed is
 * {@code logtext.LogText}, the findings funnel's is {@code app.findings.FindingsSanitizer}, and the
 * sink layer that neutralizes a rendered record lives with the appenders; the policy behind all
 * three is written down in {@code docs/adr/0004-logging-policy.md} and
 * {@code .claude/rules/logging.md}.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR1, NFR-S1 of split-logtext-leaves and FR1, FR2, FR3, FR10, NFR-S2 of
 * type-untrusted-text.
 */
@NullMarked
package com.github.oinsio.gnomish.untrustedtext;

import org.jspecify.annotations.NullMarked;
