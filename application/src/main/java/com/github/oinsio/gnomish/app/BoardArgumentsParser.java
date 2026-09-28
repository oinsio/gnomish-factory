package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish board}'s command-line flags into a {@link BoardArguments} (task 3.2):
 * {@code --dir} (defaults to {@code .}, mirroring {@link ServeArgumentsParser}), {@code --json},
 * and a positive-only {@code --limit} (defaults to 50, design D4 of add-board-command — the
 * {@code listReady} window size). Reuses {@link ArgumentsParsingSupport#singleValue} for the
 * shared single-valued-flag idiom.
 *
 * <p>Implements FR1 of add-board-command.
 */
final class BoardArgumentsParser {

    private static final String DIR = "dir";
    private static final String JSON = "json";
    private static final String LIMIT = "limit";
    private static final int DEFAULT_LIMIT = 50;
    private static final String BOARD_TOKEN = "board";

    /** Every option {@code board} accepts (FR8 of fix-operator-blockers). */
    private static final List<String> ACCEPTED = List.of(DIR, JSON, LIMIT);

    /**
     * @param args the raw application arguments, including the leading {@code board} token
     * @return the validated flags
     * @throws UsageException if {@code --limit} is given but is not a positive integer
     */
    BoardArguments parse(ApplicationArguments args) {
        ArgumentsParsingSupport.rejectUnknownOptions(args, BOARD_TOKEN, ACCEPTED, Map.of());
        Path dir = ArgumentsParsingSupport.projectDir(args);
        boolean json = args.containsOption(JSON);
        int limit = parseLimit(args);
        return new BoardArguments(dir, json, limit);
    }

    private int parseLimit(ApplicationArguments args) {
        String value = ArgumentsParsingSupport.singleValue(args, LIMIT);
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        int limit;
        try {
            limit = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new UsageException("--limit must be a positive integer, got '" + value + "'");
        }
        if (limit <= 0) {
            throw new UsageException("--limit must be positive, got " + limit);
        }
        return limit;
    }
}
