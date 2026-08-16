package com.github.oinsio.gnomish.adapter.check.github;

/**
 * Thrown by {@link GithubWorkflowRunQuery} and its helpers when the workflow
 * runs listing cannot be parsed (FR1 of add-external-check-github-actions).
 *
 * <p>Implements FR1 of add-external-check-github-actions.
 */
public final class GithubWorkflowRunQueryException extends RuntimeException {

    GithubWorkflowRunQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
