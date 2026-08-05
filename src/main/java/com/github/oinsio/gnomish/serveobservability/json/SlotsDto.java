package com.github.oinsio.gnomish.serveobservability.json;

import java.util.List;

/**
 * The JSON contract's {@code slots} section (FR6): total capacity plus one
 * entry per occupied slot; free slots have no entry.
 *
 * @param capacity the total number of slots the daemon runs
 * @param entries one entry per occupied slot; possibly empty
 */
public record SlotsDto(int capacity, List<SlotEntryDto> entries) {}
