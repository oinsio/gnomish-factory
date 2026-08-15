/**
 * A third-party adapter written against nothing but {@code gnomish-plugin-api} (UX3, FR4 of
 * split-into-modules). It exists to be compiled, not to be run: its module declares exactly one
 * dependency, so if the contract surface ever stops being self-sufficient — a port typed in a
 * `:domain` type the api forgets to expose, an SPI that needs an `application` internal — this
 * source set stops compiling and `check` goes red.
 *
 * <p>Null-marked (JSpecify), like the rest of the build.
 */
@NullMarked
package com.github.oinsio.gnomish.sample;

import org.jspecify.annotations.NullMarked;
