package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The one mapping between the open round's domain {@link PendingPollRecord} list ({@code
 * TaskState.pendingPolls()}) and {@code state.json}'s root array {@code pendingPolls} ({@link
 * StatePendingPollDto}), both ways (design D20 as amended 2026-11-12, D21 of
 * migrate-verify-to-executor-contract). An empty list is not written, and an absent array reads as
 * an empty list, so every state file without a pending poll — every one written before the array
 * existed included — reads and writes unchanged.
 *
 * <p>The key is rebuilt through the {@code :pendingkey} leaf's constructor, so the state file's cap
 * on it is {@link PendingKey#MAX_LENGTH} with no second constant; the token goes back through the
 * one parse {@link RoundToken#of}. A key the leaf refuses, a token that is not a commit id, a
 * missing {@code check}, {@code check.index}, {@code check.ref}, {@code attempt}, {@code key} or
 * {@code since}, a negative index or attempt, a blank label and a malformed instant each make the
 * file a {@link MalformedStateFileException} naming the element's field and the rule — never the
 * value, which is branch text and may be 4096 characters long.
 *
 * <p>An {@link UntrustedExit} for the same reason as {@link StateJsonMapper}: it writes the check's
 * label verbatim into the branch document, and re-mints it as branch text on the way back (design
 * D3 of type-untrusted-text).
 *
 * <p>Implements FR2, FR20, NFR-R1 of migrate-verify-to-executor-contract.
 */
@UntrustedExit
final class StatePendingPollMapper {

    private StatePendingPollMapper() {}

    /** The array to write: {@code null}, so the field is omitted, when the round has no pending poll. */
    static @Nullable List<StatePendingPollDto> toDtos(List<PendingPollRecord> records) {
        if (records.isEmpty()) {
            return null;
        }
        return records.stream().map(StatePendingPollMapper::toDto).toList();
    }

    /**
     * Rebuilds the open round's list, empty when the document carries no array.
     *
     * @throws MalformedStateFileException if an element misses a field or breaks its rule
     */
    static List<PendingPollRecord> fromDtos(@Nullable List<StatePendingPollDto> dtos) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream().map(StatePendingPollMapper::fromDto).toList();
    }

    private static StatePendingPollDto toDto(PendingPollRecord record) {
        CheckRef check = record.check();
        RoundToken token = record.token();
        return new StatePendingPollDto(
                new StatePendingPollDto.Check(check.index(), check.label().raw()),
                record.attempt(),
                token == null ? null : token.commit(),
                record.key().value(),
                record.since().toString());
    }

    private static PendingPollRecord fromDto(StatePendingPollDto dto) {
        StatePendingPollDto.Check check = present(dto.check(), "check");
        return new PendingPollRecord(
                checkRef(present(check.index(), "check.index"), present(check.ref(), "check.ref")),
                attempt(present(dto.attempt(), "attempt")),
                token(dto.token()),
                key(present(dto.key(), "key")),
                since(present(dto.since(), "since")));
    }

    private static CheckRef checkRef(int index, String ref) {
        if (index < 0) {
            throw malformed("check.index must not be negative");
        }
        if (ref.isBlank()) {
            throw malformed("check.ref must not be blank");
        }
        return new CheckRef(index, UntrustedText.branchDocument(ref));
    }

    private static int attempt(int value) {
        if (value < 0) {
            throw malformed("attempt must not be negative");
        }
        return value;
    }

    private static @Nullable RoundToken token(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return RoundToken.of(value);
        } catch (IllegalArgumentException e) {
            throw malformed("token is not a round token: " + e.getMessage());
        }
    }

    private static PendingKey key(String value) {
        if (value.isBlank()) {
            throw malformed("key must not be blank");
        }
        String unusable = PendingKey.unusable(value);
        if (unusable != null) {
            throw malformed("key is unusable: " + unusable);
        }
        return new PendingKey(value);
    }

    private static Instant since(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw malformed("since is not an ISO-8601 instant");
        }
    }

    private static <T> T present(@Nullable T value, String field) {
        if (value == null) {
            throw malformed(field + " is missing");
        }
        return value;
    }

    private static MalformedStateFileException malformed(String detail) {
        return new MalformedStateFileException(EnvelopePaths.STATE_FILE, "pendingPolls[]." + detail);
    }
}
