/**
 * The serve daemon's file-based observability contract (design D1 of
 * add-serve-observability): a {@code Snapshot} document answering "alive?
 * what are the slots doing?" from one atomically-overwritten local file, with
 * {@code version}/{@code writtenAt}/{@code intervalSeconds} self-description
 * (FR2) so staleness is computable without daemon config access.
 *
 * <p>This package holds only the document model — sections {@code instance},
 * {@code lifecycle}, {@code feed}, {@code slots}, {@code vitals}, {@code
 * tracker} (FR3) — and nothing that wires it to a real writer thread or state
 * source; those are later task groups. JSON serialization lives in the
 * {@code json} subpackage, mirroring the {@code status}/{@code status.json}
 * split.
 *
 * <p>Implements FR2, FR3 of add-serve-observability.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.serveobservability;

import org.jspecify.annotations.NullMarked;
