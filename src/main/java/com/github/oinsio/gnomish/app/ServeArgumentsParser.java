package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish serve}'s command-line flags into a {@link ServeArguments} (task 5.1 of
 * add-factory-serve): a required-nothing {@code --dir} (defaults to {@code .}, like {@link
 * TakeArgumentsParser}), a positive-only {@code --slots} override of {@code
 * ServeProperties#slots()} (design D3), and the {@code --drain} flag.
 *
 * <p>{@code serve} has no ad-hoc task source and no {@code <ref>} — it works the whole ready
 * queue, not one task — so every flag meaningful only to a single-task {@code take} invocation is
 * rejected up front, before the tracker is ever touched, mirroring {@link
 * TakeArgumentsParser}'s "Flag validation" refusal: {@code --mode}, {@code --task}, {@code
 * --task-file}, {@code --task-id}, {@code --from-stage}, {@code --resume}, {@code --base}, {@code
 * --discard-work}, {@code --takeover}. {@code --interactive} is rejected too — serve is
 * unconditionally non-interactive (FR4) — rather than merely left unparsed, so a caller who
 * mistakenly passes it gets a clear refusal instead of silent acceptance.
 *
 * <p>Implements FR2, FR4, D3 of add-factory-serve.
 */
final class ServeArgumentsParser {

    private static final String DIR = "dir";
    private static final String SLOTS = "slots";
    private static final String DRAIN = "drain";

    /** Flags {@code serve} never accepts: {@code take}'s single-task flag set (see class doc). */
    private static final List<String> REJECTED_FLAGS = List.of(
            "mode",
            "task",
            "task-file",
            "task-id",
            "from-stage",
            "resume",
            "base",
            "discard-work",
            "takeover",
            "interactive");

    /**
     * @param args the raw application arguments, including the leading {@code serve} token
     * @return the validated flags
     * @throws UsageException if a rejected flag is present, or {@code --slots} is given but is
     *     not a positive integer
     */
    ServeArguments parse(ApplicationArguments args) {
        rejectInapplicableFlags(args);
        Path dir = parseDir(args);
        Integer slots = parseSlots(args);
        boolean drain = args.containsOption(DRAIN);
        return new ServeArguments(dir, slots, drain);
    }

    private void rejectInapplicableFlags(ApplicationArguments args) {
        for (String flag : REJECTED_FLAGS) {
            if (args.containsOption(flag)) {
                throw new UsageException("--" + flag + " is not accepted by 'gnomish serve': serve has no"
                        + " single-task flags, no --mode/--resume, and is unconditionally non-interactive");
            }
        }
    }

    private Path parseDir(ApplicationArguments args) {
        String value = ArgumentsParsingSupport.singleValue(args, DIR);
        return value == null ? Path.of(".") : Path.of(value);
    }

    private @Nullable Integer parseSlots(ApplicationArguments args) {
        String value = ArgumentsParsingSupport.singleValue(args, SLOTS);
        if (value == null) {
            return null;
        }
        int slots;
        try {
            slots = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new UsageException("--slots must be a positive integer, got '" + value + "'");
        }
        if (slots <= 0) {
            throw new UsageException("--slots must be positive, got " + slots);
        }
        return slots;
    }
}
