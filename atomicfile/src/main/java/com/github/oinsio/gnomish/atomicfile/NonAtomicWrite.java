package com.github.oinsio.gnomish.atomicfile;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that writes a file <em>without</em> going through {@link AtomicFileWriter}, and
 * says why that is correct for the file it writes (FR5, design D10 of harden-task-branch-contract).
 *
 * <p>The atomic-write boundary gate is a negative rule: no production class may reach a {@code
 * java.nio.file.Files} write method unless it lives in this package or carries this marker. A
 * negative rule is what catches the writer nobody thought to add to a list — the failure mode a
 * whitelist of named writers cannot have, because a new writer is exactly the thing missing from
 * it.
 *
 * <p>The escape is deliberately self-declaring rather than a central allowlist, the same shape
 * {@code @DoNotMutate} uses for the mutation gate: the justification lives beside the code it
 * excuses, so a reviewer reading the writer reads the reason, and a file that stops needing the
 * exemption drops it in the same diff.
 *
 * <p>Two kinds of file legitimately qualify, and no others:
 *
 * <ul>
 *   <li><b>Append-only records</b>, where replace-by-rename is not the operation being performed —
 *       the whole point is to add to what is there, and a rename would drop it.
 *   <li><b>Files no factory instance reads back as durable state</b> — scratch and configuration
 *       handed to a subprocess or a container, where a torn read has no reader to mislead.
 * </ul>
 *
 * <p>A factory-owned file under {@code .gnomish-task/}, or anything another instance classifies a
 * task from, never qualifies: those are what FR5 exists for.
 *
 * @see AtomicFileWriter
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface NonAtomicWrite {

    /**
     * Why this class's write does not need the atomic-rename discipline, in one sentence a
     * reviewer can check against the two accepted categories above.
     *
     * @return the justification; never blank
     */
    String value();
}
