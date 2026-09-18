/**
 * Seeded subjects for the untrusted-text gates (design D2 of type-untrusted-text). A gate that
 * only ever runs over a clean tree reports the same green as a gate whose detector is broken, so
 * each rule is also pointed at classes written to break it: {@link
 * com.github.oinsio.gnomish.architecture.seeded.RawReadingSeed} for the raw-access rule, paired
 * with {@link com.github.oinsio.gnomish.architecture.seeded.AnnotatedExitSeed} to prove the
 * {@code @UntrustedExit} exemption itself still fires rather than the rule being silently
 * disabled; {@link com.github.oinsio.gnomish.architecture.seeded.CarrierAccessorSeed} for the
 * accessor-return-type rule and the carrier-accessor derivation the sink scan keys on; {@link
 * com.github.oinsio.gnomish.architecture.seeded.ForParsingSeed} for the parsing-access rule,
 * paired with {@link com.github.oinsio.gnomish.architecture.seeded.AnnotatedParserSeed} to prove
 * the {@code @UntrustedParser} exemption itself still fires; and {@link
 * com.github.oinsio.gnomish.architecture.seeded.PassThroughParserSeed} for the one half of the
 * parser membership criterion a scan can decide — a parser that hands the text back unchanged.
 *
 * <p>They are Java, not Groovy, deliberately: the raw-access rule reads call targets from
 * bytecode, and Groovy's dynamic dispatch emits none. They sit in the groovy test source
 * directory so the module's joint compilation picks them up without a source set of its own.
 */
package com.github.oinsio.gnomish.architecture.seeded;
