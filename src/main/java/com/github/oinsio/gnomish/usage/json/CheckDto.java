package com.github.oinsio.gnomish.usage.json;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code gnomish usage --json} mini-contract's per-check shape carried under a row's {@code
 * checks} array — mirrors {@code state.json}'s {@code StateCheckDto} field-for-field (design D5),
 * as a distinct class in this package.
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 *
 * @param ref the label of the check that ran
 * @param verdict the lowerCamel verdict discriminator ({@code pass}/{@code fail}/{@code
 *     cannotVerify})
 * @param findings the check's structured findings; empty for {@code pass}/{@code cannotVerify}
 * @param durationMillis the wall time the check took, in milliseconds
 * @param reason the {@code cannotVerify} short cause, or {@code null} otherwise
 * @param details the {@code cannotVerify} free-text detail, or {@code null} otherwise
 */
public record CheckDto(
        String ref,
        String verdict,
        List<FindingDto> findings,
        long durationMillis,
        @Nullable String reason,
        @Nullable String details) {}
