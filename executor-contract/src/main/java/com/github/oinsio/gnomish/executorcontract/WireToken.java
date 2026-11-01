package com.github.oinsio.gnomish.executorcontract;

import java.util.Optional;

/**
 * A wire enum of the executor contract: a constant that names itself on the wire by an explicit
 * token rather than by its Java name. The lookup is the one way a token becomes a constant, so
 * every enum's {@code fromWire} reads tokens the same way.
 *
 * <p>Implements FR1, NFR-R3 of define-executor-contract.
 */
interface WireToken {

    /** The token this constant is written as. */
    String wire();

    /** The constant whose token equals {@code token} exactly, or empty when none does. */
    static <E extends Enum<E> & WireToken> Optional<E> lookup(E[] constants, String token) {
        for (E constant : constants) {
            if (constant.wire().equals(token)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }
}
