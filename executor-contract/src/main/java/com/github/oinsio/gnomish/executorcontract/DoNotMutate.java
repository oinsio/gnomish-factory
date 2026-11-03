package com.github.oinsio.gnomish.executorcontract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Module-local copy of the project's PIT "do not mutate this method" marker, kept inside {@code
 * executor-contract} so the module reaches the JDK and Jackson only; the factory-root marker in
 * {@code :domain} would be an edge into a factory layer. PIT's {@code
 * ExcludedAnnotationInterceptorFactory} (feature {@code FANN}, on by default) matches it by simple
 * name, provided it survives into bytecode ({@link RetentionPolicy#CLASS}).
 *
 * <p>Every use carries a comment naming which of the accepted reasons of {@code
 * .claude/rules/testing.md} ("Per-method exemptions") applies.
 *
 * <p>Implements FR1 of define-executor-contract.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
@interface DoNotMutate {}
