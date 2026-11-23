package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.PendingSubject;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;

/**
 * The one mapping between a pending poll's {@link PendingSubject} and its {@code state.json} wire
 * form (design D11 of extract-agent-round-runner): the token {@code subject} — {@value #CHECK} or
 * {@value #ROUND} — and, read back, the check a {@code check} object names. Both directions are
 * exhaustive over the sealed subject, so a new subject fails to compile here until it has a token;
 * the {@code check} object is written by {@link StatePendingPollMapper}, the exit that renders the
 * label raw.
 *
 * <p>An element with no {@code subject} was written before the round subject existed and reads as a
 * check's, so every state file of {@code migrate-verify-to-executor-contract} reads unchanged. An
 * unknown token is a malformed state file, never a check and never skipped: an entry this build
 * cannot name is never polled, and the refusal names the field, not the value. A round's element
 * carrying a {@code check} object is refused the same way, since it names two subjects at once.
 *
 * <p>Implements FR10, NFR-R3 of extract-agent-round-runner.
 */
final class StatePendingSubjectMapper {

    /** The wire token of a check's pending poll. */
    static final String CHECK = "check";

    /** The wire token of a {@code transform} round's pending poll. */
    static final String ROUND = "round";

    private StatePendingSubjectMapper() {}

    /** The wire token {@code subject} names. */
    static String token(PendingSubject subject) {
        return switch (subject) {
            case PendingSubject.Check _ -> CHECK;
            case PendingSubject.Round _ -> ROUND;
        };
    }

    /**
     * Rebuilds the subject an element names.
     *
     * @param dto the element as read
     * @return the subject; never null
     * @throws MalformedStateFileException if the token is unknown, a check's element misses its
     *     check, or a round's element carries one
     */
    static PendingSubject fromDto(StatePendingPollDto dto) {
        String written = dto.subject();
        return switch (written == null ? CHECK : written) {
            case CHECK -> new PendingSubject.Check(checkRef(present(dto.check(), "check")));
            case ROUND -> round(dto.check());
            default -> throw StatePendingPollMapper.malformed("subject is not a known pending subject");
        };
    }

    private static PendingSubject round(StatePendingPollDto.@Nullable Check check) {
        if (check != null) {
            throw StatePendingPollMapper.malformed("check must be absent for the round subject");
        }
        return PendingSubject.ROUND;
    }

    private static CheckRef checkRef(StatePendingPollDto.Check check) {
        int index = present(check.index(), "check.index");
        String ref = present(check.ref(), "check.ref");
        if (index < 0) {
            throw StatePendingPollMapper.malformed("check.index must not be negative");
        }
        if (ref.isBlank()) {
            throw StatePendingPollMapper.malformed("check.ref must not be blank");
        }
        return new CheckRef(index, UntrustedText.branchDocument(ref));
    }

    private static <T> T present(@Nullable T value, String field) {
        return StatePendingPollMapper.present(value, field);
    }
}
