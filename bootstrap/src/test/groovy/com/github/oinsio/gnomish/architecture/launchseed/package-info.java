/**
 * Seeded subjects for {@code GitLaunchBoundarySpec} (FR7, design D7 of own-git-invocation-policy).
 * Over a clean tree the gate's detectors find only the classified spawn sites and two launchers
 * that call the owner, which is also what a broken detector would report; each seed here is
 * written to break one of them: {@link
 * com.github.oinsio.gnomish.architecture.launchseed.ExtraSpawnSeed} is a spawn site no allowlist
 * entry classifies, its builder held by a nested class so the top-level mapping is exercised too,
 * and {@link com.github.oinsio.gnomish.architecture.launchseed.OwnerSkippingLauncherSeed} is a git
 * launcher that spells its own command line instead of taking it from {@code
 * GitInvocation.commandLine}.
 *
 * <p>They are Java, not Groovy, because the detectors read dependencies and calls from bytecode,
 * and Groovy's dynamic dispatch emits none. Their package is a sibling of {@code
 * architecture.seeded}, not a subpackage: the untrusted-text gates import that package with its
 * subpackages and count what they find there.
 */
package com.github.oinsio.gnomish.architecture.launchseed;
