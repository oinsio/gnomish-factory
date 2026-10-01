/**
 * The operator-configuration level vocabulary: {@link
 * com.github.oinsio.gnomish.operatorconfig.ConfigLevel} on every {@code @ConfigurationProperties}
 * record component, naming the {@link com.github.oinsio.gnomish.operatorconfig.Level} of the
 * {@code factory.*} key it binds — host file, project file, either, or (for sandbox-boundary keys)
 * the project file alone.
 *
 * <p><strong>Neutrality contract.</strong> This package imports the JDK and nothing else — no other
 * module of the factory, no Spring. The annotated records live on both sides of the layering,
 * {@code :application} and {@code :sandbox:core}, and only a leaf both can reach serves both from
 * one declaration; an edge added here would land in each of them. The annotation is not placed in
 * {@code :domain}, whose engine has no business knowing operator files, nor in {@code
 * :sandbox:core}, which {@code :application} reaches but not the other way round (design D4 of
 * add-project-registry). The Gradle layering gate states the same rule as data, with an allowlist
 * that is empty.
 *
 * <p><strong>What stays outside.</strong> This is a declaration, not policy: deriving the key →
 * level table with Spring's relaxed names and checking each configuration source against it belong
 * to the composition root in {@code :bootstrap}.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR6 of add-project-registry.
 */
@NullMarked
package com.github.oinsio.gnomish.operatorconfig;

import org.jspecify.annotations.NullMarked;
