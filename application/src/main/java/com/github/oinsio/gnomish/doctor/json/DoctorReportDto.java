package com.github.oinsio.gnomish.doctor.json;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * The {@code gnomish doctor --json} document (v1, design D5 of add-doctor-command): the contract
 * version and every result in report order — sibling of {@code BoardReportDto} and {@code
 * StatusReportDto}.
 *
 * <p>Implements FR5 of add-doctor-command.
 *
 * @param version the contract version; always {@link DoctorReportJsonMapper#VERSION}
 * @param checks every result of the run, in report order
 */
@NullMarked
public record DoctorReportDto(int version, List<DoctorCheckDto> checks) {}
