package com.github.oinsio.gnomish.adapter.git.state;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code state.json} contract's per-check shape carried under an attempt's
 * {@code checks} array: {@code ref}, {@code verdict}, {@code findings}, {@code
 * durationMillis} — mirrors {@code status.json}'s {@code CheckDto} flattening
 * field-for-field (design D5), as a distinct class in this package. {@code
 * verdict} renders as a bare lowerCamel string discriminator ({@code "pass"} /
 * {@code "fail"} / {@code "cannotVerify"}) with the domain {@code Verdict}'s
 * payload flattened onto this same record rather than nested:
 *
 * <ul>
 *   <li>{@code Verdict.Pass} — {@code verdict = "pass"}, {@code findings = []},
 *       {@code reason}/{@code details} both {@code null}, {@code runUrl} carrying
 *       the platform run link when the verdict has one
 *   <li>{@code Verdict.Fail} — {@code verdict = "fail"}, {@code findings}
 *       populated, {@code reason}/{@code details}/{@code runUrl} all {@code null}
 *   <li>{@code Verdict.CannotVerify} — {@code verdict = "cannotVerify"}, {@code
 *       findings = []}, {@code reason}/{@code details} populated from the verdict,
 *       {@code runUrl} {@code null}
 * </ul>
 *
 * <p>Implements FR3, FR4 of add-git-workflow; NFR-O2 of add-sandbox-core.
 *
 * @param ref the label of the check that ran
 * @param verdict the lowerCamel verdict discriminator
 * @param findings the check's structured findings; empty for {@code pass}/{@code
 *     cannotVerify}
 * @param durationMillis the wall time the check took, in milliseconds
 * @param reason the {@code cannotVerify} short cause, or {@code null} otherwise
 * @param details the {@code cannotVerify} free-text detail, or {@code null}
 *     otherwise
 * @param runUrl the passing external check's platform run URL, or {@code null}
 *     when the verdict carries none
 */
public record StateCheckDto(
        String ref,
        String verdict,
        List<StateFindingDto> findings,
        long durationMillis,
        @Nullable String reason,
        @Nullable String details,
        @Nullable String runUrl) {}
