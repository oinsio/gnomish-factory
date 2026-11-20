package com.github.oinsio.gnomish.httpegress;

import java.io.Serial;

/**
 * The platform's answer outran one of the consumer's bounds (FR15, design D16 of
 * migrate-verify-to-executor-contract, the outcome classes): a chain of redirects longer than {@link
 * GuardedHttpCheckExchange#MAX_REDIRECTS}, a redirect back onto a URL the chain already visited, or
 * a body larger than the consumer's response bound. It is neither a refusal — no grant would change
 * it, so it never advises granting a host — nor a transport failure, so no transport retry, which
 * retries I/O failures only, repeats it: a verdict built-in reports it as {@code
 * failed/infrastructure} naming the limit, a tracker call fails as an unclassified failure.
 *
 * <p>Implements FR15, NFR-R5 of migrate-verify-to-executor-contract.
 */
public final class ExchangeLimitException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The bound the answer outran. */
    public enum Limit {

        /** The chain of redirects outran {@link GuardedHttpCheckExchange#MAX_REDIRECTS}. */
        REDIRECT_LIMIT("redirect limit"),

        /** A redirect pointed back at a URL the chain already visited. */
        REDIRECT_LOOP("redirect loop"),

        /** The body outgrew the consumer's response bound. */
        RESPONSE_SIZE("response size limit");

        private final String label;

        Limit(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Limit limit;

    /**
     * @param limit the bound outrun; never null
     * @param target the URL whose answer outran it; never null
     * @param detail the concrete fact; never null
     */
    public ExchangeLimitException(Limit limit, String target, String detail) {
        super("exchange with '%s' exceeded the %s: %s".formatted(target, limit.label(), detail));
        this.limit = limit;
    }

    public Limit limit() {
        return limit;
    }
}
