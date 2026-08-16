package com.github.oinsio.gnomish.gitobjects;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Library-local copy of the project's PIT "do not mutate this method" marker, kept inside {@code
 * gitobjects} so the package depends only on the JDK and SLF4J and stays extraction-ready by a
 * folder move (design D19 of add-sandbox-core, pinned by {@code GitObjectsBoundarySpec}). The
 * factory-root {@link com.github.oinsio.gnomish.DoNotMutate} would be a cross-package dependency
 * back into the factory — exactly the edge the boundary forbids.
 *
 * <p>Recognized by PIT's built-in {@code ExcludedAnnotationInterceptorFactory} (feature {@code
 * FANN}, on by default), which matches by <em>simple</em> name — any annotation named {@code
 * Generated}, {@code DoNotMutate}, or {@code CoverageIgnore}, regardless of package — so this copy
 * excludes just like the root one with no Gradle-side wiring, provided it survives into bytecode
 * ({@link RetentionPolicy#CLASS}).
 *
 * <p>Every use must carry a comment at the call site explaining exactly why the mutant cannot be
 * killed by a reasonable unit test ({@code .claude/rules/testing.md}: "each exception must be
 * explicitly justified").
 *
 * <p>Implements FR25, D19 of add-sandbox-core.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface DoNotMutate {}
