package com.github.oinsio.gnomish.sandbox.environment;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All docker subprocess I/O and output parsing for {@link SandboxLifecycleSweep} — listing
 * factory objects with their labels, and reading one object's timing fields — kept separate from
 * the decision tree so the evaluator's action logic stays free of argv/output plumbing (mirrors
 * {@code DockerCommands}/{@code DockerOutput}'s existing split).
 */
final class SandboxLifecycleObjectReader {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleObjectReader.class);

    private final DockerCli docker;

    SandboxLifecycleObjectReader(DockerCli docker) {
        this.docker = docker;
    }

    /**
     * Every factory object of one kind, with its labels — fail-closed: a listing that exited
     * non-zero is "cannot enumerate", never "there is nothing" (NFR-R1 of
     * add-serve-sandbox-lifecycle). Reading a failed listing's empty stdout as an empty world would
     * make every surviving object look container-less, so a live session's volume could be disposed
     * out from under it. Thrown as the runtime-outage signal the sweep already skips its whole pass
     * on (NFR-R3).
     *
     * @throws DockerUnavailableException if the listing command exited non-zero
     */
    List<ListedDockerObject> list(ObjectKind kind, List<String> listArgv) {
        DockerResult result = docker.run(listArgv);
        if (!result.ok()) {
            throw new DockerUnavailableException(
                    "docker could not list factory " + kind + " objects: "
                            + result.stderr().strip(),
                    null);
        }
        List<ListedDockerObject> objects = new ArrayList<>();
        for (String line : DockerOutput.lines(result.stdout())) {
            int tab = line.indexOf('\t');
            String name = tab == -1 ? line : line.substring(0, tab);
            String rawLabels = tab == -1 ? "" : line.substring(tab + 1);
            objects.add(new ListedDockerObject(name, kind, DockerLabelFormat.parse(rawLabels)));
        }
        return objects;
    }

    /** The container's four timing fields in one inspect call; empty when unreadable (racy removal). */
    Optional<ObjectTiming> containerTiming(String name) {
        DockerResult result = docker.run(DockerLifecycleCommands.inspectContainerTiming(name));
        if (!result.ok()) {
            return Optional.empty();
        }
        String[] parts = result.stdout().strip().split("\\s+");
        if (parts.length != 4) {
            return Optional.empty();
        }
        boolean running = Boolean.parseBoolean(parts[0]);
        Instant createdAt = parseOrNull(name, parts[2]);
        if (createdAt == null) {
            return Optional.empty();
        }
        return Optional.of(new ObjectTiming(
                running,
                createdAt,
                sinceCreation(createdAt, parseOrNull(name, parts[3])),
                running ? null : sinceCreation(createdAt, parseOrNull(name, parts[1]))));
    }

    /**
     * Docker renders "this never happened" as Go's zero time, {@code 0001-01-01T00:00:00Z} — a
     * perfectly parseable instant that a created-but-never-started container carries in both
     * {@code StartedAt} and {@code FinishedAt}. Read literally it makes such a container two
     * millennia old, so the aged reaper (FR5 of add-serve-sandbox-lifecycle) would dispose box,
     * volume and network the moment the minimum-age guard lets go, instead of keeping them for the
     * reap threshold. Any value preceding the container's own creation is that sentinel rather than
     * a time, and is reported absent so the decision falls back to {@code createdAt}.
     */
    private static @Nullable Instant sinceCreation(Instant createdAt, @Nullable Instant value) {
        return value != null && value.isBefore(createdAt) ? null : value;
    }

    /** A non-container object's creation instant only (no running state, no finished-at). */
    Optional<Instant> createdAt(String name, List<String> inspectCreatedAtArgv) {
        DockerResult result = docker.run(inspectCreatedAtArgv);
        return result.ok()
                ? Optional.ofNullable(parseOrNull(name, result.stdout().strip()))
                : Optional.empty();
    }

    /**
     * An unparseable timestamp costs the object its verdict for this pass, so it is logged rather
     * than swallowed: a silently skipped object is never reaped and never reported, the exact
     * stall {@code SKIPPED_NO_VERDICT} exists to make visible.
     *
     * <p>DEBUG, not WARN (FR12 of harden-logging-observability): the sweep now emits that object's
     * {@code SKIPPED_NO_VERDICT} verdict, and the verdict sink is the one owner of the operator
     * line for it. This one keeps the raw value the parse choked on, which the verdict does not
     * carry.
     */
    private static @Nullable Instant parseOrNull(String name, String value) {
        try {
            return Instant.parse(unquote(value));
        } catch (DateTimeParseException e) {
            log.debug("sandbox lifecycle sweep skipped {}: unparseable docker timestamp '{}'", name, value);
            return null;
        }
    }

    /** Strips the surrounding quotes of a {@code {{json .Field}}}-rendered string. */
    private static String unquote(String value) {
        String head = value.startsWith("\"") ? value.substring(1) : value;
        return head.endsWith("\"") ? head.substring(0, head.length() - 1) : head;
    }
}
