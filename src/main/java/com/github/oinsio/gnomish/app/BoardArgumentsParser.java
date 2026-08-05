package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
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

    /**
     * @param args the raw application arguments, including the leading {@code board} token
     * @return the validated flags
     * @throws UsageException if {@code --limit} is given but is not a positive integer
     */
    BoardArguments parse(ApplicationArguments args) {
        Path dir = parseDir(args);
        boolean json = args.containsOption(JSON);
        int limit = parseLimit(args);
        return new BoardArguments(dir, json, limit);
    }

    private Path parseDir(ApplicationArguments args) {
        String value = ArgumentsParsingSupport.singleValue(args, DIR);
        return value == null ? Path.of(".") : Path.of(value);
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
