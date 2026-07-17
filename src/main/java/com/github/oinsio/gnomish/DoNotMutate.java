package com.github.oinsio.gnomish;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method PIT must not mutate: recognized by PIT's built-in {@code
 * ExcludedAnnotationInterceptorFactory} (feature {@code FANN}, on by default),
 * which matches any annotation whose simple name is {@code Generated}, {@code
 * DoNotMutate}, or {@code CoverageIgnore} — no Gradle-side configuration needed
 * beyond this type existing with {@link RetentionPolicy#CLASS} (or {@code
 * RUNTIME}) so it survives into the bytecode PIT scans.
 *
 * <p>PIT's {@code excludedMethods}/{@code excludedClasses} are unqualified glob
 * filters with no class-scoping syntax (confirmed against PIT core: {@code
 * excludedMethods} matches the bare method name in every class in scope). This
 * annotation is the project's only precise, per-method exclusion mechanism —
 * reach for it instead of a method-name glob whenever the method name is not
 * provably unique across the codebase (a name collision would silently exempt
 * unrelated methods from the 100% mutation gate).
 *
 * <p>Every use must carry a comment at the call site explaining exactly why the
 * mutant cannot be killed by a reasonable unit test ({@code .claude/rules/testing.md}:
 * "each exception must be explicitly justified").
 *
 * <p>M4 of add-manual-run.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface DoNotMutate {}
