package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * The one place a dashboard output-path flag becomes a {@link Path}: {@code gnomish dashboard
 * --out} and {@code gnomish serve --dashboard-out} name the same page file, so they resolve their
 * value the same way (design D12 of supervise-daemon-loops-and-embed-dashboard). The value is taken
 * as given — relative to the working directory, not normalized — and {@code DashboardWatch} writes
 * the page there.
 *
 * <p>Implements FR7 of add-dashboard-page; FR8 of supervise-daemon-loops-and-embed-dashboard.
 */
final class DashboardOutputFlag {

    private DashboardOutputFlag() {}

    /**
     * @param args the raw application arguments
     * @param flag the flag's name without the leading {@code --}
     * @return the output path, or {@code null} when the flag is absent (the page's default path)
     * @throws UsageException if the flag is given with no value, or given more than once
     */
    static @Nullable Path parse(ApplicationArguments args, String flag) {
        String value = ArgumentsParsingSupport.singleValue(args, flag);
        return value == null ? null : Path.of(value);
    }
}
