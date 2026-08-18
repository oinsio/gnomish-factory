package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.app.CheckParamsValidator;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Validates an {@code external} check's {@code params} when it selects the {@code github} provider
 * (FR5, FR6 of add-plugin-architecture). This provider takes its whole selector from the check's
 * engine-common {@code checkId} — the workflow name it looks up — and defines no params of its own,
 * so any declared key is a mistake reported as a located {@link ConfigError} naming the check and
 * the offending key rather than being silently ignored.
 *
 * <p>Reporting unknown keys rather than accepting them is what keeps a params block written for one
 * provider from passing quietly under another: a {@code pass_when} meant for the http provider,
 * left on a github check, is a load error instead of a param nobody reads.
 *
 * <p>Implements FR5, FR6 of add-plugin-architecture.
 */
public final class GithubCheckParamsValidator implements CheckParamsValidator {

    @Override
    public List<ConfigError> validate(String file, String where, Map<String, Object> params) {
        List<ConfigError> errors = new ArrayList<>();
        for (String key : new TreeSet<>(params.keySet())) {
            errors.add(new ConfigError(
                    file,
                    where + "." + key,
                    "unknown param '%s' for check provider 'github'; this provider takes its target from checkId"
                            .formatted(key)));
        }
        return List.copyOf(errors);
    }
}
