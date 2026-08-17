package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.List;
import java.util.Map;

/**
 * The provider-owned validation hook for a {@code factory.check.<provider>} operator subsection —
 * symmetric to {@link TrackerSubsectionValidator}, which grades the repo-side {@code
 * tracker.<type>} subsection (design D12 of add-plugin-architecture). Criterion (c) of FR4 asks
 * each plugin-ready port for a config subsection plus an SPI validator; for the check port the
 * manifest-side half is {@link CheckParamsValidator} and this is the operator-side half.
 *
 * <p>What a provider grades here is its connection form: the endpoint, repository or credential
 * keys it requires, and (once named connection profiles exist) the rule that exactly one of the
 * inline keys or a {@code connection: <name>} reference is declared. The core never interprets
 * those keys — it only locates the seam around the delegation.
 *
 * <p>Problems are returned as located {@link ConfigError} data, never thrown, so every configured
 * provider's complaints aggregate into one startup report rather than the first one aborting
 * before the rest are seen (NFR-R1).
 *
 * <p>Implements FR4, FR5 of add-plugin-architecture.
 */
@FunctionalInterface
public interface CheckSubsectionValidator {

    /**
     * Validates one provider-owned {@code factory.check.<provider>} subsection's content.
     *
     * <p>Implements FR4, FR5 of add-plugin-architecture.
     *
     * @param file the offending file (the operator configuration the subsection was read from)
     * @param where the located field prefix for this subsection (e.g. {@code factory.check.github});
     *     implementations that check nested keys should extend it (e.g. {@code
     *     factory.check.github.repo})
     * @param subsection the raw, untyped subsection content as bound from operator configuration
     *     (maps/lists/scalars only)
     * @return every located problem found in the subsection; empty when valid
     */
    List<ConfigError> validate(String file, String where, Map<String, Object> subsection);

    /**
     * The connection-aware form the operator-config seam calls (FR16, design D8/D12) — the check
     * port's mirror of {@link TrackerSubsectionValidator#validate(String, String, Map,
     * ConnectionProfiles)}, with the same contract: the default resolves a {@code connection: <name>}
     * reference against {@code profiles} and delegates, so one set of key rules grades an inline and
     * a referencing subsection alike, while the reference itself is graded by the seam.
     *
     * <p>Implements FR16 of add-plugin-architecture.
     *
     * @param file the offending operator configuration file
     * @param where the located field prefix for this subsection (e.g. {@code factory.check.github})
     * @param subsection the raw, untyped subsection content, before profile resolution
     * @param profiles the operator-declared connection profiles; never null, possibly none
     * @return every located problem found in the resolved subsection; empty when valid
     */
    default List<ConfigError> validate(
            String file, String where, Map<String, Object> subsection, ConnectionProfiles profiles) {
        return validate(file, where, profiles.resolve(subsection));
    }
}
