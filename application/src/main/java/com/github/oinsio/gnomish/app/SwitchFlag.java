package com.github.oinsio.gnomish.app;

import java.util.List;
import org.springframework.boot.ApplicationArguments;

/**
 * The one way a subcommand reads a switch — a flag whose presence is the whole answer ({@code
 * --drain}, {@code --dashboard}, {@code --json}, {@code --watch}, {@code --discard-work}, {@code
 * --takeover}). Spring's {@link ApplicationArguments#containsOption} answers {@code true} for
 * {@code --dashboard=false} as well, so a switch read through it alone inverts what the operator
 * wrote; a switch given a value is therefore a usage error, never a silent "on".
 *
 * <p>Implements FR8 of supervise-daemon-loops-and-embed-dashboard.
 */
final class SwitchFlag {

    private SwitchFlag() {}

    /**
     * @param args the raw application arguments
     * @param name the switch's name without the leading {@code --}
     * @return whether the switch is present
     * @throws UsageException if the switch is given with a value
     */
    static boolean isOn(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) {
            return false;
        }
        List<String> values = args.getOptionValues(name);
        if (values != null && !values.isEmpty()) {
            throw new UsageException("--" + name + " is a switch and takes no value: pass --" + name
                    + " to turn it on, omit it to leave it off");
        }
        return true;
    }
}
