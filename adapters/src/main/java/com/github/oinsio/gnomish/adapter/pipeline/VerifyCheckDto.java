package com.github.oinsio.gnomish.adapter.pipeline;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A {@code stage.yaml} {@code verify} entry (D2, D5): one of four variants
 * selected by the explicit {@code type} discriminator — {@code builtin},
 * {@code command}, {@code external}, {@code judge}. Jackson maps the
 * discriminator to the matching record; the adapter later maps these to the
 * sealed domain {@code VerifyCheck} (task 5.3). An unknown {@code type} is a
 * structural error the loader reports (task 5.2, FR5).
 *
 * <p>Opaque {@code params}/{@code settings} are typed {@code Map<String, Object>}
 * so they bind to plain JDK types, never a Jackson {@code JsonNode} (D5a) —
 * keeping the domain Jackson-free. Local-sanity ranges (external timing, judge
 * votes) are validator concerns (task 4.4), not the DTO's; timing is carried as
 * raw strings and parsed to {@code Duration} by the mapper (task 5.3).
 *
 * <p>Implements FR2 (DTO shapes), D2, D5, D5a of load-pipeline-config.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = VerifyCheckDto.Builtin.class, name = "builtin"),
    @JsonSubTypes.Type(value = VerifyCheckDto.Command.class, name = "command"),
    @JsonSubTypes.Type(value = VerifyCheckDto.External.class, name = "external"),
    @JsonSubTypes.Type(value = VerifyCheckDto.Judge.class, name = "judge")
})
public sealed interface VerifyCheckDto {

    /**
     * A {@code builtin} engine check: a check {@code name} plus opaque
     * declarative {@code params} (plain JDK map, D5a).
     *
     * @param name the engine check identifier, or {@code null} when omitted
     * @param params the declarative parameters; {@code null}/absent means none
     */
    record Builtin(@Nullable String name, @Nullable Map<String, Object> params) implements VerifyCheckDto {}

    /**
     * A {@code command} check: the executable command line plus the optional
     * {@code verifyIn} freshness knob ({@code same-box}/{@code fresh-box}, FR13
     * of add-sandbox-core). {@code verifyIn} is a raw string so an unknown value
     * becomes a located error the mapper reports (task 2.1); absent defaults to
     * same-box.
     *
     * @param command the command line, or {@code null} when omitted
     * @param verifyIn the raw freshness knob ({@code same-box}/{@code
     *     fresh-box}), or {@code null} when omitted (defaults to same-box, FR13
     *     of add-sandbox-core); parsing and the unknown-value error are the
     *     mapper's concern
     */
    record Command(@Nullable String command, @Nullable String verifyIn) implements VerifyCheckDto {

        /** Convenience for construction outside Jackson binding (e.g. tests): no {@code verifyIn}. */
        Command(@Nullable String command) {
            this(command, null);
        }
    }

    /**
     * An {@code external} check: an identifier plus raw polling-timing strings
     * (e.g. {@code 30s}, {@code 15m}); parsing to {@code Duration} and timing
     * sanity are the mapper/validator concern (tasks 5.3, 4.4).
     *
     * @param checkId the external check identifier, or {@code null} when omitted
     * @param provider the check-provider discriminator, or {@code null} when
     *     omitted — the mapper defaults it to {@code github} and records that
     *     choice explicitly in the domain model (FR13 of add-plugin-architecture)
     * @param params the provider-owned per-check selectors; {@code null}/absent
     *     means none. Opaque here (plain JDK map, D5a): the selected provider's
     *     own validator grades them at the load seam (FR6 of
     *     add-plugin-architecture)
     * @param interval the raw poll-interval string, or {@code null} when omitted
     * @param timeout the raw poll-timeout string, or {@code null} when omitted
     * @param timeoutClass the raw timeout-class string ({@code quality} or
     *     {@code infrastructure}), or {@code null} when omitted (defaults to
     *     {@code quality}, FR9); parsing and the unknown-value error are the
     *     mapper's concern (task 6.1)
     * @param pinPaths the law-declared pin paths — repo paths whose content
     *     defines the check (FR16 of add-sandbox-core); {@code null}/absent
     *     means none declared (the adapter's own contributed paths still apply).
     *     Repo-relative data the loader never reads; lexical form is validated
     *     by {@code StageSanityRule}
     */
    record External(
            @Nullable String checkId,
            @Nullable String provider,
            @Nullable Map<String, Object> params,
            @Nullable String interval,
            @Nullable String timeout,
            @Nullable String timeoutClass,
            @Nullable List<String> pinPaths)
            implements VerifyCheckDto {

        /**
         * Convenience for construction outside Jackson binding (e.g. tests): no
         * {@code provider}, {@code params} or {@code pinPaths} — the pre-provider
         * manifest shape.
         */
        External(
                @Nullable String checkId,
                @Nullable String interval,
                @Nullable String timeout,
                @Nullable String timeoutClass) {
            this(checkId, null, null, interval, timeout, timeoutClass, null);
        }
    }

    /**
     * A {@code judge} check: acceptance-criteria file, pinned model, opaque
     * {@code settings} (plain JDK map, D5a) and a vote count.
     *
     * @param criteriaFile the acceptance-criteria file path, or {@code null} when
     *     omitted
     * @param model the pinned judge model, or {@code null} when omitted
     * @param settings the opaque model settings; {@code null}/absent means none
     * @param votes the vote count, or {@code null} when omitted (a validator
     *     concern, task 4.4)
     */
    record Judge(
            @Nullable String criteriaFile,
            @Nullable String model,
            @Nullable Map<String, Object> settings,
            @Nullable Integer votes)
            implements VerifyCheckDto {}
}
