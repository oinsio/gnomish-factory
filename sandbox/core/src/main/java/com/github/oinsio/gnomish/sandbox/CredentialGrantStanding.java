package com.github.oinsio.gnomish.sandbox;

/**
 * Whether one credential name can be granted to a {@code program} executor's child as its own
 * resolved layer ({@link ProgramCredentialGrant}; NFR-S1 of define-executor-contract, design D10).
 * Only {@link #GRANTABLE} reaches exactly that child: a name every child already receives, or one
 * every child is denied, cannot be granted to one child alone, and a name with no value would let
 * the executor run without its declared input.
 *
 * <p>Implements NFR-S1 of define-executor-contract.
 */
public enum CredentialGrantStanding {

    /** The name has a value and belongs to no shared layer: granted, it reaches that child alone. */
    GRANTABLE,

    /** The operator passes the name through to every child ({@code factory.sandbox.env-passthrough}). */
    PASSED_THROUGH,

    /** The run declares the name as a factory credential (tracker, check provider): every child is denied it. */
    WITHHELD,

    /** The value source resolves no value for the name. */
    NO_VALUE
}
