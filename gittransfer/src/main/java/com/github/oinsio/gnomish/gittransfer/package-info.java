/**
 * The factory's one git-transfer owner: which argument list and which environment every factory
 * {@code git fetch} and {@code git clone} runs with. Inputs are plain values — a transfer source of
 * a closed set of kinds and one refspec. The output is a value too: the argv from the leading
 * {@code -c} pairs through the refspec, and the environment entries to set and to unset. Nothing
 * here launches a process or touches the filesystem; the medium that runs the value — the git
 * runner in the git adapter, or the seed helper's script in the docker backend — is the caller's.
 *
 * <p><strong>Neutrality contract.</strong> This package imports the JDK and nothing else — no other
 * module of the factory, no Spring, no Jackson, no logging API. Its emptiness is a security
 * property (NFR-S1, NFR-S2): a policy that can import nothing cannot reach a tracker, a container,
 * a remote, or a configuration format, so the only things a caller can vary are the source kind
 * and the refspec. It is also what lets two modules that share no other edge — the git adapter
 * above the docker backend — build one transfer from one owner. The Gradle layering gate states
 * the same rule as data, with an allowlist that is empty.
 *
 * <p><strong>What stays outside.</strong> Running the argv, bounding it, and scrubbing its stderr
 * live in the git adapter; rendering the clone into the seed helper's constant script lives in the
 * docker backend; classifying a refused transfer lives beside the runner.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR1, FR2 of own-git-transfer-argv.
 */
@NullMarked
package com.github.oinsio.gnomish.gittransfer;

import org.jspecify.annotations.NullMarked;
