package com.github.oinsio.gnomish.serveobservability.json;

/**
 * The JSON contract's {@code sweepTick} ledger line (NFR-O2 of add-serve-sandbox-lifecycle): one
 * per completed sweep tick, carrying that tick's per-category counts — including the untouched
 * categories, which are never itemized as their own lines.
 *
 * @param version the contract version; always {@code 1}
 * @param type the line-type discriminator; always {@code "sweepTick"}
 * @param instance the writing process's identity
 * @param at ISO-8601 UTC instant the tick completed
 * @param counts the tick's per-category verdict counts
 */
public record SweepTickLineDto(int version, String type, InstanceDto instance, String at, SweepCountsDto counts) {}
