package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish take}'s command-line flags into a {@link TakeArguments} (task 5.13): a
 * required-nothing {@code --dir} (defaults to {@code .}, like {@link RunArguments#dir()}), {@code
 * --interactive[=executor|judge]} (identical semantics to {@code gnomish run}'s, via {@link
 * InteractiveModeParser}), {@code --base} (explicit-mode fresh-claim only), {@code --discard-work},
 * and the positional {@code <ref>} — the first non-{@code --}, non-{@code take}-token source
 * argument, mirroring {@link StatusArgumentsParser}'s {@code firstPositionalAfterSubcommand} pattern.
 *
 * <p>Per the tracker-take spec's "Flag validation" scenario, {@code take} has its own, narrower
 * flag set than {@code gnomish run} (design D15): {@code --mode} (take is always git mode),
 * {@code --task}/{@code --task-file}/{@code --task-id} (no ad-hoc task source; task identity comes
 * from the tracker), {@code --resume} (the claim/branch protocol replaces it), and {@code
 * --from-stage} (design D4: a tracker task always starts at the pipeline's first stage) are all
 * rejected with {@link UsageException} before the tracker is ever touched. The bare form (no
 * {@code <ref>}) additionally rejects {@code --base} — a start modifier meaningful only for an
 * explicit-mode fresh claim.
 *
 * <p>Implements FR9 of add-tracker-port.
 */
final class TakeArgumentsParser {

    private static final String TAKE_TOKEN = "take";
    private static final String DIR = "dir";
    private static final String BASE = "base";
    private static final String DISCARD_WORK = "discard-work";

    /** Flags {@code take} never accepts (spec "Flag validation"), each with its own reason. */
    private static final List<String> REJECTED_FLAGS =
            List.of("mode", "task", "task-file", "task-id", "resume", "from-stage");

    /**
     * @param args the raw application arguments, including the leading {@code take} token
     * @return the validated flags
     * @throws UsageException if a rejected flag is present, {@code --base} is given on the bare
     *     form, or a shared flag ({@code --dir}, {@code --interactive}) fails its own format check
     */
    TakeArguments parse(ApplicationArguments args) {
        rejectRunOnlyFlags(args);
        Path dir = parseDir(args);
        String ref = firstPositionalAfterSubcommand(args);
        RunArguments.InteractiveMode interactiveMode = InteractiveModeParser.parse(args);
        String base = singleValue(args, BASE);
        boolean discardWork = args.containsOption(DISCARD_WORK);
        if (ref == null && base != null) {
            throw new UsageException(
                    "--base cannot be combined with bare 'take': it is a start modifier for 'take <ref>' only");
        }
        return new TakeArguments(dir, ref, interactiveMode, base, discardWork);
    }

    private void rejectRunOnlyFlags(ApplicationArguments args) {
        for (String flag : REJECTED_FLAGS) {
            if (args.containsOption(flag)) {
                throw new UsageException("--" + flag + " is not accepted by 'gnomish take': "
                        + "take has no ad-hoc task source, no --mode, no --resume, and no --from-stage"
                        + " (its own claim/branch protocol replaces them)");
            }
        }
    }

    private Path parseDir(ApplicationArguments args) {
        String value = singleValue(args, DIR);
        return value == null ? Path.of(".") : Path.of(value);
    }

    /**
     * The task ref: the first raw source argument that is neither a {@code --}-prefixed option
     * nor the leading {@code take} subcommand token itself; {@code null} for bare mode.
     */
    private @Nullable String firstPositionalAfterSubcommand(ApplicationArguments args) {
        boolean skippedSubcommand = false;
        for (String raw : args.getSourceArgs()) {
            if (raw.startsWith("--")) {
                continue;
            }
            if (!skippedSubcommand && raw.equals(TAKE_TOKEN)) {
                skippedSubcommand = true;
                continue;
            }
            return raw;
        }
        return null;
    }

    /**
     * Returns the single value of {@code name}, or {@code null} if the flag is absent. Multiple
     * occurrences are rejected, mirroring {@link RunArgumentsParser}'s own {@code singleValue}.
     */
    private @Nullable String singleValue(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) {
            return null;
        }
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            throw new UsageException("--" + name + " requires a value (e.g. --" + name + "=value)");
        }
        if (values.size() > 1) {
            throw new UsageException("--" + name + " may be given only once");
        }
        return values.getFirst();
    }
}
