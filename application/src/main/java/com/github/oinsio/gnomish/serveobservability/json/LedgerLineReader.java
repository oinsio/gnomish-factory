package com.github.oinsio.gnomish.serveobservability.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal sample reader for the ledger's crash-consistency contract
 * (design D5, D8): the daemon itself never reads the ledger back — this
 * class exists only to prove, and to document for external tool authors,
 * how a reader MUST tolerate a torn last line rather than crash (NFR-R2).
 *
 * <p>Reads a {@code .jsonl} file line by line and parses each non-blank line
 * as generic JSON via the same {@link ObjectMapper} configuration as {@link
 * LedgerJsonMapper}'s writer side ({@link LedgerJson#mapper()}). Per the
 * crash model, only the currently open tail line of a live append can be
 * torn — every earlier line was already flushed complete (write-only,
 * flush-per-line, no fsync). So a parse failure on the LAST line is treated
 * as a legal torn tail and silently skipped; a parse failure on any other
 * line is a genuine error and fails the read.
 *
 * <p>This is deliberately shallow: it validates JSON well-formedness only,
 * not the {@code taskOutcome}/{@code lifecycle}/{@code runSummary} schema —
 * schema mapping back to domain types is out of this contract's scope (the
 * daemon never needs it, D5).
 *
 * <p>Implements NFR-R2 of add-serve-observability.
 */
public final class LedgerLineReader {

    private final ObjectMapper mapper;

    /** Builds a reader backed by a fresh {@link LedgerJson#mapper()} instance. */
    public LedgerLineReader() {
        this.mapper = LedgerJson.mapper();
    }

    /**
     * Reads every complete JSON line in {@code file}. A malformed LAST line
     * is treated as a legal torn tail (NFR-R2) and silently omitted from the
     * result rather than raising an error; a malformed line anywhere else is
     * a genuine error.
     *
     * @param file the ledger file to read; never null
     * @return one parsed {@link JsonNode} per complete line, in file order
     * @throws IOException if {@code file} cannot be read, or a non-last line
     *     is not valid JSON
     */
    public List<JsonNode> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<JsonNode> records = new ArrayList<>();
        int lastIndex = lines.size() - 1;
        for (int i = 0; i <= lastIndex; i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                records.add(mapper.readTree(line));
            } catch (JsonProcessingException malformed) {
                if (i == lastIndex) {
                    // Torn tail of a live append (design D5, NFR-R2) — legal, skip.
                    continue;
                }
                throw new IOException(
                        "malformed ledger line " + (i + 1) + " is not the last line of " + file
                                + " — only the tail may be torn (NFR-R2)",
                        malformed);
            }
        }
        return records;
    }
}
