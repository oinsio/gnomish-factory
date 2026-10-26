package com.github.oinsio.gnomish.app.doctor;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One doctor result: which check, its status, the detail and — for {@code warn} and {@code fail} —
 * the fix (FR1, FR5 of add-doctor-command). Every text field is console-ready: a capture left its
 * carrier through {@code forConsole()} before it reached here (NFR-O1), and no field ever holds a
 * secret value (NFR-S1). A check may report several results under one id, one per finding (a
 * configuration violation, a declared secret).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR1, FR5, NFR-O1, NFR-S1 of add-doctor-command.
 *
 * @param check the check that produced it
 * @param status its status
 * @param detail what the check found
 * @param fix what fixes it; {@code null} for {@code ok} and {@code skipped}, and where the detail is
 *     the owner's own refusal, which already names its fix
 */
@NullMarked
public record DoctorVerdict(
        DoctorCheckId check,
        DoctorStatus status,
        String detail,
        @Nullable String fix) {}
