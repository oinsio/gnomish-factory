package com.github.oinsio.gnomish.doctor.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Serializes the {@code gnomish doctor --json} document (design D5 of add-doctor-command) — the
 * sibling of {@code BoardJsonMapper} and {@code StatusReportJsonMapper}, beside them in this
 * module, which owns the project's JSON writing. It lives here rather than in the doctor package
 * of {@code :bootstrap} so that package names no JSON library at all (M2: its boundary spec bans a
 * YAML or JSON parser there); the doctor package hands over plain {@link DoctorCheckDto} rows.
 *
 * <p>Writes only: nothing here reads a document. Not {@code @UntrustedExit}: every field is a
 * string the doctor check composed, so no {@code raw()} text passes through (logging rule, "Carry
 * untrusted text in {@code UntrustedText}"). Null fields render as JSON {@code null}, so a result
 * without a fix still carries the {@code fix} key.
 *
 * <p>Implements FR5, NFR-O1 of add-doctor-command.
 */
@NullMarked
public final class DoctorReportJsonMapper {

    /** The contract version the document carries. */
    public static final int VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The pretty-printed document of {@code checks}, without a trailing line end.
     *
     * @param checks every result of the run, in report order
     */
    public String serialize(List<DoctorCheckDto> checks) {
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new DoctorReportDto(VERSION, List.copyOf(checks)));
        } catch (JsonProcessingException e) {
            // Plain records of strings: unreachable in practice, as for the board and status mappers.
            throw new IllegalStateException("failed to serialize the doctor report", e);
        }
    }
}
