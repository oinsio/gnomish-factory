package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The process's one {@link ServePreconditions} (design D17 of add-sandbox-base-image), built once
 * over the ports a slot reaches the operator preconditions by, so the {@code serve} startup
 * assessment and {@code doctor}'s per-kind verdicts are asked of the same object (design D1 of
 * add-doctor-command). It reads no clone at construction: a clone is handed to each assessment, so
 * the bean exists in a {@code doctor} run outside any project too.
 *
 * <p>Implements FR13 of add-sandbox-base-image; FR1, FR4 of add-doctor-command.
 */
@Configuration
public class OperatorPreconditionsConfiguration {

    /**
     * The preconditions owner, over the ports a slot reaches the preconditions by.
     *
     * @param git the clone bundle, whose branch operations run the ref-format check
     * @param sandboxModeSelector the process's one execution-mode selector
     * @param sandboxImageReadiness the image contract and box git floor checks' port
     * @param sandboxProperties the operator sandbox config, read for the configured image
     * @return the preconditions owner
     */
    @Bean
    ServePreconditions servePreconditions(
            TaskGit git,
            SandboxModeSelector sandboxModeSelector,
            SandboxImageReadiness sandboxImageReadiness,
            SandboxProperties sandboxProperties) {
        return new ServePreconditions(git.branches(), sandboxModeSelector, sandboxImageReadiness, sandboxProperties);
    }
}
