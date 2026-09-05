package com.github.oinsio.gnomish.adapter.git.state;

/**
 * The identity a denial source assigned one denial event, as both task-branch envelopes carry it
 * (FR7 of fix-denial-attribution-durability): the source's own event timestamp, plus the identity
 * of the source that stamped it.
 *
 * <p>What it is for: attaching denials to a record merges by this identity against the denials
 * already recorded at the branch tip, so a read that lost its position and fell back to the
 * source's whole tail recovers exactly the unrecorded events instead of duplicating the rest.
 * Both values are opaque to this contract — only the source that minted them interprets them.
 *
 * <p>Environment bookkeeping, not report content: it rides {@code state.json} and {@code
 * task.json} and deliberately never reaches {@code status.json} or the text render, the same
 * stance the egress cursor takes.
 *
 * <p>Additive under contract v1: a denial entry written before the field existed binds it to
 * {@code null}, which reads as "unknown, keep" — such a denial matches nothing and is attached
 * rather than merged away, the duplicate-over-silence stance of design D3.
 *
 * <p>Implements FR7 of fix-denial-attribution-durability.
 *
 * @param source the identity of the denial source that recorded the event
 * @param at the source-assigned event timestamp, opaque
 */
public record DenialIdentityDto(String source, String at) {}
