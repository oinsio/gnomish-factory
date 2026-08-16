/**
 * The {@code StatusReport} model: a single pure report shared by every render (text,
 * JSON) and every consumer (the interactive runner live, and — via the same contract —
 * a future external CLI reading a persisted state file with no live process at all)
 * (design D7 of add-manual-run).
 *
 * <p>Fields are partitioned by derivability: state-derivable fields are computable from
 * {@code TaskContext} + {@code TaskState} alone and are required; live-only fields
 * (current activity, the last escalation report) exist only while a process is running
 * and are nullable — absent when a report is built from a persisted state file alone.
 *
 * <p>Implements FR11 of add-manual-run.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default;
 * nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.status;

import org.jspecify.annotations.NullMarked;
