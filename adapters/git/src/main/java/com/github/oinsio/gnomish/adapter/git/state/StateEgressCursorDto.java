package com.github.oinsio.gnomish.adapter.git.state;

/**
 * The {@code state.json} contract's egress-cursor shape (FR5 of
 * fix-denial-report-attachment): the read position in the environment's denial
 * source at the moment this state was committed, plus the identity of the source
 * it was read from.
 *
 * <p>Committed so a resuming instance can continue the denial delta where the
 * previous one stopped instead of replaying every denial the source still holds
 * onto the resumed round. Both values are opaque to this contract — only the
 * environment that minted them interprets them, and only after matching {@code
 * source} against its own live denial source (a resume on another machine, or
 * onto a recreated source, must not apply a foreign position).
 *
 * <p>Additive under contract v1: a state file written before the field existed
 * binds it to {@code null}, which reads as "no cursor to resume from".
 *
 * <p>Implements FR5 of fix-denial-report-attachment.
 *
 * @param source the identity of the denial source the position was read from
 * @param position the opaque read position within that source
 */
public record StateEgressCursorDto(String source, String position) {}
