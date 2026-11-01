package com.github.oinsio.gnomish.serveobservability.json;

import com.github.oinsio.gnomish.serveobservability.RoundOutcomeLine;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * Maps a {@link RoundOutcomeLine} to its JSON-contract DTO (NFR-O2, UX3 of
 * define-executor-contract), the one ledger mapping that reads a carrier: the executor's describe
 * version is the executor's own text, written verbatim to the ledger's machine medium, so this class
 * is an {@link UntrustedExit} and {@link LedgerJsonMapper}, whose every other field the factory
 * minted, stays outside the allowlist. JSON escaping keeps the line one line whatever the version
 * holds.
 *
 * <p>Implements NFR-O2, UX3 of define-executor-contract.
 */
@UntrustedExit
final class RoundOutcomeLineMapper {

    private RoundOutcomeLineMapper() {}

    /** The DTO of {@code line}; {@code executor} is {@code <name>@<version>}, or the name alone. */
    static RoundOutcomeLineDto toDto(RoundOutcomeLine line) {
        UntrustedText version = line.executorVersion();
        return new RoundOutcomeLineDto(
                1,
                "roundOutcome",
                InstanceDto.from(line.instance()),
                line.at().toString(),
                line.taskId(),
                line.stage(),
                line.attempt(),
                version == null ? line.executorName() : line.executorName() + "@" + version.raw(),
                line.role(),
                line.status());
    }
}
