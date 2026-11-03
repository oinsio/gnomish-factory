package com.github.oinsio.gnomish.usage.json;

import com.github.oinsio.gnomish.executorcontract.usagewire.ParticipantUsageDto;
import java.util.List;

/**
 * The {@code gnomish usage --json} mini-contract's per-round shape: one reconstructed round from
 * {@link com.github.oinsio.gnomish.app.port.git.UsageRow}, carried at full granularity — every
 * participant entry of the round in the one usage wire vocabulary (FR15, FR16 of
 * define-executor-contract), individual checks, none summed (that summing is text-mode-only, {@code UsageTextRenderer}'s job).
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 *
 * @param stage the stage name this round's attempt was recorded under
 * @param round the round's sequence number within the stage
 * @param result the lowerCamel result classification ({@code passed}/{@code
 *     qualityFailure}/{@code cannotVerify}/{@code decisionNeeded})
 * @param startedAt ISO-8601 UTC instant the round began
 * @param checks the verify results produced this round; possibly empty
 * @param usage the round's participant entries
 */
public record UsageRowDto(
        String stage,
        int round,
        String result,
        String startedAt,
        List<CheckDto> checks,
        List<ParticipantUsageDto> usage) {}
