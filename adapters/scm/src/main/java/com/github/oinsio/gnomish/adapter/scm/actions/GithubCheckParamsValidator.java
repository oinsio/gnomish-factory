package com.github.oinsio.gnomish.adapter.scm.actions;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Validates the settings of a check naming the {@code github-actions} built-in — an {@code external}
 * check's {@code params} (FR5, FR6 of add-plugin-architecture; FR6 of
 * migrate-verify-to-executor-contract). The built-in takes its whole selector from the check's
 * engine-common {@code checkId} — the workflow name it looks up — and defines no params of its own,
 * so any declared key is a mistake reported as a located {@link ConfigError} naming the check and
 * the offending key rather than being silently ignored.
 *
 * <p>Reporting unknown keys rather than accepting them is what keeps a params block written for one
 * provider from passing quietly under another: a {@code pass_when} meant for the http
 * left on a github check, is a load error instead of a param nobody reads.
 *
 * <p>Implements FR5, FR6 of add-plugin-architecture.
 */
public final class GithubCheckParamsValidator {

    /**
     * Grades one check's settings: every key is an error, since the built-in takes none. Bound as a
     * method reference by the composition root, which keys it under {@code github-actions}.
     *
     * @param file the stage manifest the check was declared in
     * @param where the located field prefix for this check's settings
     * @param params the raw settings
     * @return one located problem per declared key; empty when none is declared
     */
    public List<ConfigError> validate(String file, String where, Map<String, Object> params) {
        List<ConfigError> errors = new ArrayList<>();
        for (String key : new TreeSet<>(params.keySet())) {
            errors.add(new ConfigError(
                    file,
                    where + "." + key,
                    "unknown param '%s' for executor 'github-actions'; it takes its target from checkId"
                            .formatted(key)));
        }
        return List.copyOf(errors);
    }
}
