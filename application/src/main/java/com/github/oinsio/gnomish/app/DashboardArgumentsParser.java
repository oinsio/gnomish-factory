package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish dashboard}'s command-line flags into a {@link DashboardArguments} (task
 * 4.1): {@code --dir} (defaults to {@code .}, mirroring {@link BoardArgumentsParser}), {@code
 * --out} (defaults to {@code null}, meaning the instance-directory default path — design D8), and
 * the {@code --watch} boolean flag (FR7). Reuses {@link ArgumentsParsingSupport#singleValue} for
 * the shared single-valued-flag idiom.
 *
 * <p>Implements FR1, FR7 of add-dashboard-page.
 */
final class DashboardArgumentsParser {

    private static final String DIR = "dir";
    private static final String OUT = "out";
    private static final String WATCH = "watch";

    /**
     * Parses {@code args} into a validated {@link DashboardArguments}.
     *
     * @param args the raw application arguments, including the leading {@code dashboard} token
     * @return the validated flags
     */
    DashboardArguments parse(ApplicationArguments args) {
        Path dir = parseDir(args);
        Path out = parseOut(args);
        boolean watch = args.containsOption(WATCH);
        return new DashboardArguments(dir, out, watch);
    }

    private Path parseDir(ApplicationArguments args) {
        String value = ArgumentsParsingSupport.singleValue(args, DIR);
        return value == null ? Path.of(".") : Path.of(value);
    }

    private @Nullable Path parseOut(ApplicationArguments args) {
        String value = ArgumentsParsingSupport.singleValue(args, OUT);
        return value == null ? null : Path.of(value);
    }
}
