package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.project.RegisteredClone;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish take}'s command-line flags into a {@link TakeArguments} (task 5.13): a
 * required-nothing {@code --dir} (the registered clone, defaulting to {@code .}), {@code
 * --interactive[=executor|judge]} (identical semantics to {@code gnomish run}'s, via {@link
 * InteractiveModeParser}), {@code --base} (explicit-mode fresh-claim only), {@code --discard-work},
 * and the positional refs — every non-{@code --}, non-{@code take}-token source argument, via
 * {@link ArgumentsParsingSupport#allPositionalsAfterSubcommand}: zero for bare mode, one for
 * explicit mode, two or more for batch mode (FR2 of add-factory-serve).
 *
 * <p>Per the tracker-take spec's "Flag validation" scenario, {@code take} has its own, narrower
 * flag set than {@code gnomish run} (design D15): {@code --mode} (take is always git mode),
 * {@code --task}/{@code --task-file}/{@code --task-id} (no ad-hoc task source; task identity comes
 * from the tracker), {@code --resume} (the claim/branch protocol replaces it), and {@code
 * --from-stage} (design D4: a tracker task always starts at the pipeline's first stage) are all
 * rejected with {@link UsageException} before the tracker is ever touched. The bare form (no
 * {@code <ref>}) additionally rejects {@code --base} — a start modifier meaningful only for an
 * explicit-mode fresh claim — and {@code --takeover}, the explicit-mode-only headless takeover
 * authorization (task 6.2 of add-claim-heartbeat, FR6). The batch form ({@code take <ref> <ref>
 * ...}, two or more positional refs) additionally rejects {@code --interactive} and {@code --base}
 * (FR2, FR3 of add-factory-serve; spec "take subcommand surface").
 *
 * <p>Implements FR9 of add-tracker-port; FR6 of add-claim-heartbeat; FR2, FR3 of add-factory-serve;
 * FR3 of add-project-registry.
 */
final class TakeArgumentsParser {

    private static final String TAKE_TOKEN = "take";
    private static final String DIR = "dir";
    private static final String BASE = "base";
    private static final String DISCARD_WORK = "discard-work";
    private static final String TAKEOVER = "takeover";

    /** Flags {@code take} never accepts (spec "Flag validation"), each with its own reason. */
    private static final List<String> REJECTED_FLAGS =
            List.of("mode", "task", "task-file", "task-id", "resume", "from-stage");

    /**
     * Every option {@code take} knows (FR8 of fix-operator-blockers): its own flags plus {@link
     * #REJECTED_FLAGS}, which stay known so their specific refusal wins over the generic one.
     */
    private static final List<String> ACCEPTED = Stream.concat(
                    Stream.of(DIR, "interactive", BASE, DISCARD_WORK, TAKEOVER), REJECTED_FLAGS.stream())
            .toList();

    /**
     * @param args the raw application arguments, including the leading {@code take} token
     * @param clone the registered clone the configuration loader resolved from {@code --dir}; the
     *     {@code dir} component is its path (FR3, design D9 of add-project-registry)
     * @return the validated flags
     * @throws UsageException if a rejected flag is present, {@code --base} is given on the bare
     *     form, {@code --interactive} or {@code --base} is given on the batch form (2+ refs), or a
     *     shared flag ({@code --interactive}) fails its own format check
     */
    TakeArguments parse(ApplicationArguments args, RegisteredClone clone) {
        ArgumentsParsingSupport.rejectUnknownOptions(args, TAKE_TOKEN, ACCEPTED, Map.of());
        rejectRunOnlyFlags(args);
        Path dir = clone.clonePath();
        List<String> refs = ArgumentsParsingSupport.allPositionalsAfterSubcommand(args, TAKE_TOKEN);
        RunArguments.InteractiveMode interactiveMode = InteractiveModeParser.parse(args);
        String base = ArgumentsParsingSupport.singleValue(args, BASE);
        boolean discardWork = args.containsOption(DISCARD_WORK);
        boolean takeover = args.containsOption(TAKEOVER);
        if (refs.isEmpty() && base != null) {
            throw new UsageException(
                    "--base cannot be combined with bare 'take': it is a start modifier for 'take <ref>' only");
        }
        if (refs.isEmpty() && takeover) {
            throw new UsageException(
                    "--takeover cannot be combined with bare 'take': it authorizes an explicit 'take <ref>' takeover only");
        }
        if (refs.size() >= 2) {
            rejectBatchOnlyFlags(interactiveMode, base);
        }
        return new TakeArguments(dir, refs, interactiveMode, base, discardWork, takeover);
    }

    /**
     * FR3 of add-factory-serve: batch mode ({@code take <ref> <ref> ...}) rejects {@code
     * --interactive} — no single console session makes sense across multiple concurrently worked
     * refs — and {@code --base}, a start modifier meaningful only for one fresh explicit-mode claim.
     */
    private void rejectBatchOnlyFlags(RunArguments.InteractiveMode interactiveMode, @Nullable String base) {
        if (interactiveMode != RunArguments.InteractiveMode.NONE) {
            throw new UsageException(
                    "--interactive cannot be combined with batch 'take <ref> <ref> ...': it names a single "
                            + "console session, not one per ref");
        }
        if (base != null) {
            throw new UsageException(
                    "--base cannot be combined with batch 'take <ref> <ref> ...': it is a start modifier for "
                            + "a single fresh explicit-mode claim only");
        }
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
}
