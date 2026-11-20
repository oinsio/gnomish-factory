package com.github.oinsio.gnomish.domain.egress;

/**
 * Where a URL the factory calls was written, which decides whether its host is granted or merely
 * requested (FR17, design D16 of migrate-verify-to-executor-contract).
 *
 * <p>Implements FR17 of migrate-verify-to-executor-contract.
 */
public enum EgressMedium {

    /**
     * A {@code factory.*} file of the operator: a {@code factory.check.<name>} subsection, a {@code
     * factory.connections.<name>} profile, the operator egress block. A host it names is granted.
     */
    OPERATOR,

    /**
     * The {@code .gnomish/} tree at the law commit, written by whoever commits to the target
     * repository: an inline {@code tracker.github.api-url}, a stage manifest's http target. A host it
     * names for an in-process call is a request, admitted only by an operator grant or by the vendor
     * default the adapter declares.
     */
    LAW
}
