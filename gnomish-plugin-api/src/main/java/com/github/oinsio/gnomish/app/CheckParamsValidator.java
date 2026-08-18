package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.List;
import java.util.Map;

/**
 * The provider-owned validation hook for an {@code external} check's manifest {@code params} — the
 * check-port mirror of {@link TrackerSubsectionValidator} (design D3 of add-plugin-architecture).
 * The core loader never interprets a provider's params itself: it locates the seam around the
 * delegation (unknown provider, missing selection) and hands the raw params to the validator the
 * selected provider exposes through {@link CheckClientFactory#paramsValidator()}.
 *
 * <p>Problems are returned as located {@link ConfigError} data, never thrown, so a provider's own
 * complaints aggregate with the loader's in one pass (NFR-R1).
 *
 * <p>Check providers are open-ended, not a fixed compile-time enum, so this is a runtime-registered
 * functional hook rather than a Jackson discriminator sealed family: a new provider arrives as a
 * jar and its params are graded without the core loader changing.
 *
 * <p>Implements FR5, FR6 of add-plugin-architecture.
 */
@FunctionalInterface
public interface CheckParamsValidator {

    /**
     * Validates one {@code external} check's provider-owned {@code params}.
     *
     * <p>Implements FR5, FR6 of add-plugin-architecture.
     *
     * @param file the offending file (the stage manifest the check was declared in)
     * @param where the located field prefix for this check's params (e.g. {@code
     *     stages[1].verify[0].params}); implementations that check nested keys should extend it
     * @param params the raw, untyped params as parsed by Jackson (maps/lists/scalars only)
     * @return every located problem found in the params; empty when valid
     */
    List<ConfigError> validate(String file, String where, Map<String, Object> params);

    /**
     * The validator of a provider that grades no per-check params: it accepts everything. The load
     * seam keys its registry by <em>every</em> discovered provider, so a provider exposing no
     * validator still needs an entry — otherwise a missing key would mean two different things, "no
     * such provider" and "nothing to grade" (FR6).
     *
     * <p>Implements FR5, FR6 of add-plugin-architecture.
     *
     * @return a validator that reports no problem for any params; never null
     */
    static CheckParamsValidator none() {
        return (file, where, params) -> List.of();
    }
}
