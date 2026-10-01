package com.github.oinsio.gnomish.operatorconfig;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the {@link Level} of the {@code factory.*} key a {@code @ConfigurationProperties} record
 * component binds (FR6 of add-project-registry, design D4). The level lives beside the key's
 * definition and nowhere else: the key → level table the configuration loader checks against is
 * derived from these annotations by reflection, never written by hand, so a new key cannot exist
 * without a level (M2) and no separate table can drift from the records.
 *
 * <p>Every component of every {@code @ConfigurationProperties} record carries one, nested records
 * included — with one shape excepted: a component whose type is itself a nested configuration
 * record ({@code factory.tracker}, {@code factory.sandbox.limits}) names no key of its own, so it
 * carries none, and its components carry theirs. A {@code Map}-valued component whose keys are contributed by plugins ({@code
 * factory.check.<provider>}, {@code factory.connections.<name>}) is levelled at its root, and the
 * whole subtree takes that level.
 *
 * <p>Runtime-retained and targeted at record components only, so it stays on the component where
 * {@link java.lang.reflect.RecordComponent#getAnnotation} reads it, rather than being propagated
 * to the accessor, field or constructor parameter.
 *
 * <p>Implements FR6 of add-project-registry.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface ConfigLevel {

    /**
     * The places the component's key may be set.
     *
     * @return the key's level
     */
    Level value();
}
