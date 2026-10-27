package com.github.oinsio.gnomish.doctor.json;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One result of the {@code gnomish doctor --json} document (v1): the check's stable id, its status
 * token, the detail and the fix. Every field is a string the doctor check composed — text a tool
 * printed left its carrier through the console exit inside the check (NFR-O1), and no field ever
 * holds a secret value (NFR-S1) — so the document reads no raw capture.
 *
 * <p>Implements FR5, NFR-O1, NFR-S1 of add-doctor-command.
 *
 * @param id the check's stable id ({@code git-floor}, {@code sandbox-image}, …)
 * @param status {@code ok}, {@code warn}, {@code fail} or {@code skipped}
 * @param detail what the check found
 * @param fix what fixes it, or {@code null} — rendered as JSON {@code null}, never omitted
 */
@NullMarked
public record DoctorCheckDto(
        String id, String status, String detail, @Nullable String fix) {}
