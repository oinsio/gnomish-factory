package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.check.http.HttpCheckParamsValidator;
import com.github.oinsio.gnomish.adapter.pipeline.CheckSettingsValidator;
import com.github.oinsio.gnomish.adapter.scm.actions.GithubCheckParamsValidator;
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The load-time params validators of the built-in verdict executors that grade a check's settings
 * (FR6, FR13 of add-plugin-architecture; task 5.2 of migrate-verify-to-executor-contract): the
 * {@code http} built-in's and the {@code github-actions} built-in's, keyed by the executor name a
 * check names. They were read off the discovered check providers until the check SPI left; a
 * built-in is compiled into and named by core (ADR 0020), so the composition root names them here,
 * and the loader's check seam grades each check's settings with its executor's validator. The
 * {@code github-actions} validator lives in {@code :adapters:scm}, which does not reach the loader's
 * module, so it is bound by a method reference (task 6.1 of migrate-verify-to-executor-contract).
 *
 * <p>Implements FR6, FR13 of add-plugin-architecture; FR1, FR13 of migrate-verify-to-executor-contract.
 */
@Configuration
public class BuiltInCheckConfiguration {

    /**
     * The built-ins' params validators keyed by executor name, the loader's check seam input.
     *
     * @return the validators; never null
     */
    @Bean
    public Map<String, CheckSettingsValidator> checkParamsValidatorRegistry() {
        return Map.of(
                BuiltInExecutors.HTTP,
                new HttpCheckParamsValidator(),
                BuiltInExecutors.GITHUB_ACTIONS,
                new GithubCheckParamsValidator()::validate);
    }
}
