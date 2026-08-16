package com.github.oinsio.gnomish.usage.json;

import java.util.List;

/**
 * The {@code gnomish usage --json} mini-contract's envelope (FR14, NFR-C1 of add-git-workflow): a
 * dedicated {@code "version": 1} contract, separate from the status-report v1 contract (design
 * D5) — persistence/status and usage evolve independently. Same JSON conventions as status-report
 * v1: camelCase, ISO-8601 UTC instants, millisecond durations, {@code null} fields render as JSON
 * {@code null} rather than being omitted.
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 *
 * @param version the mini-contract version; always {@code 1} today
 * @param taskId the tracker's original taskId
 * @param rows every reconstructed round, oldest to newest; possibly empty
 * @param totals the cumulative usage across {@code rows}
 */
public record UsageReportDto(int version, String taskId, List<UsageRowDto> rows, ExecutorUsageDto totals) {}
