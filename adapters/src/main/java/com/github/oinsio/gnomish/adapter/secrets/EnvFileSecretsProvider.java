package com.github.oinsio.gnomish.adapter.secrets;

import com.github.oinsio.gnomish.app.UsageException;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.project.FactoryHome;
import com.github.oinsio.gnomish.app.project.ProjectLayout;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The env/file {@link SecretsProvider} adapter (design D12 of add-sandbox-core, D7 of
 * add-project-registry): resolves a named secret from the factory home's secrets folders, then
 * from the process environment, with a {@code <name>_FILE} indirection for reading the value out
 * of a local file instead (the Docker-secret convention), so a secret need not live in a
 * process's environment table.
 *
 * <p>Resolution order for a name {@code N} (first match wins):
 * <ol>
 *   <li>the file {@code N} in the resolved project's secrets folder
 *       ({@code <home>/projects/<name>/secrets/N}), when the command resolved a project;</li>
 *   <li>the file {@code N} in the host secrets folder ({@code <home>/secrets/N});</li>
 *   <li>if {@code N_FILE} is set to a non-blank path, that file — a referenced-but-unreadable
 *       file resolves to empty (fail-closed), never to the direct env fallback, so a
 *       misconfigured path fails loudly at the consumer rather than silently reading a
 *       different value;</li>
 *   <li>otherwise the value is {@code N} from the environment.</li>
 * </ol>
 * A file's whole content, stripped of surrounding whitespace, is the value. The file name is the
 * secret's exact variable name, so no mapping table exists and a plugin's credential needs
 * nothing; the file's path comes from {@link FactoryHome} and {@link ProjectLayout}, which refuse a
 * name that is not one path segment, so no name reaches a file outside the folders. A folder file readable or writable by group or others is refused, never read: {@link
 * #find} throws a {@link UsageException} naming the file and the {@code chmod} that fixes it
 * (NFR-S2). The check covers the folders only — the files the factory home owns; an {@code
 * N_FILE} target is the operator's own mount (a Docker secret is typically world-readable inside
 * its container) and keeps the add-sandbox-core contract.
 *
 * <p>A blank result at any step is treated as absent — the fail-closed contract of {@link
 * SecretsProvider#find(String)} (NFR-S1): the consumer turns empty into an error naming the
 * secret. Resolved values are never logged; only a file-read failure is logged, and its message
 * carries the file, never the secret's content.
 *
 * <p>Implements FR18, NFR-S1 of add-sandbox-core; FR8, NFR-S2 of add-project-registry.
 */
public final class EnvFileSecretsProvider implements SecretsProvider {

    private static final Logger log = LoggerFactory.getLogger(EnvFileSecretsProvider.class);

    /** The suffix that names the file-indirection variable for a secret {@code N}: {@code N_FILE}. */
    static final String FILE_SUFFIX = "_FILE";

    private static final Set<PosixFilePermission> LOOSE = Set.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE);

    private final List<Function<String, Path>> folders;
    private final Function<String, @Nullable String> env;

    /**
     * The production adapter: the two secrets folders of {@code home}, then the JVM's process
     * environment ({@code System.getenv}).
     *
     * @param home the factory home, whose host secrets folder is consulted second
     * @param project the resolved project, whose secrets folder is consulted first; {@code null}
     *     for a command that resolved none
     */
    public EnvFileSecretsProvider(FactoryHome home, @Nullable ProjectLayout project) {
        this(home, project, System::getenv);
    }

    /**
     * Testing seam: resolves environment lookups through {@code env} instead of
     * {@code System.getenv}, so a spec can drive the resolution order without
     * mutating the JVM's real process environment (not reliably possible on a
     * module-path JVM).
     *
     * @param env the environment-variable lookup: name to value, or {@code
     *     null} when the variable is unset; never null itself
     */
    EnvFileSecretsProvider(FactoryHome home, @Nullable ProjectLayout project, Function<String, @Nullable String> env) {
        this.folders = project == null ? List.of(home::hostSecret) : List.of(project::secret, home::hostSecret);
        this.env = env;
    }

    /**
     * {@inheritDoc}
     *
     * @throws UsageException if {@code name} is not one path segment, or a secrets-folder file
     *     named {@code name} is readable or writable by group or others (NFR-S2 of
     *     add-project-registry)
     */
    @Override
    public Optional<String> find(String name) {
        for (Function<String, Path> folder : folders) {
            Path file = fileIn(folder, name);
            if (Files.exists(file)) {
                return readFolderFile(file);
            }
        }
        String fileRef = env.apply(name + FILE_SUFFIX);
        if (fileRef != null && !fileRef.isBlank()) {
            return readFile(name + FILE_SUFFIX, fileRef.strip());
        }
        return present(env.apply(name));
    }

    /** The file of {@code name} in one secrets folder; a name that is not one segment is the operator's mistake. */
    private static Path fileIn(Function<String, Path> folder, String name) {
        try {
            return folder.apply(name);
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage() + " — a secret is named by its variable name");
        }
    }

    /** A secrets-folder file: refused when group or others may read or write it, else read. */
    private static Optional<String> readFolderFile(Path file) {
        try {
            if (isLoose(file)) {
                throw new UsageException("secret file " + file + " is readable or writable by group or others,"
                        + " so it was not read: run chmod 600 " + file);
            }
            return present(Files.readString(file).strip());
        } catch (IOException e) {
            warnUnreadable(file.toString(), e);
            return Optional.empty();
        }
    }

    /** Whether group or others may read or write {@code file}; a file system with no POSIX view has neither. */
    private static boolean isLoose(Path file) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        return view != null && !Collections.disjoint(view.readAttributes().permissions(), LOOSE);
    }

    /**
     * Reads the secret from {@code path}, returning its stripped contents or
     * empty when the file is absent, unreadable, or blank — the fail-closed
     * leg: a referenced file that cannot be read is an absent secret, never a
     * fall-through to the direct env value.
     *
     * @param variable the {@code *_FILE} variable that named {@code path}; the warning's subject,
     *     since "a secret would not resolve" is only actionable if the operator knows which one
     *     (FR5, NFR-S1 of harden-logging-observability — the variable, never the value)
     */
    private static Optional<String> readFile(String variable, String path) {
        try {
            return present(Files.readString(Path.of(path)).strip());
        } catch (IOException | InvalidPathException e) {
            warnUnreadable(variable, e);
            return Optional.empty();
        }
    }

    /**
     * The one warning of a read that failed; the caller returns the fail-closed empty itself, a
     * literal {@code Optional.empty()} that no return-value mutant can distinguish from its own.
     *
     * @param subject what named the file — the {@code *_FILE} variable, or the secrets-folder file
     *     itself
     */
    private static void warnUnreadable(String subject, Exception e) {
        // The message carries the path or the variable, never the file's content (a secret) — and
        // the throwable is passed as a throwable, not as a format argument, so the WARN keeps the
        // stack trace that says WHICH read failed and why. Neither an IOException from a file read
        // nor an InvalidPathException carries file content, so the stack leaks nothing the message
        // does not already say.
        log.warn(
                OperatorEvent.SECRET_FILE_UNREADABLE.head()
                        + "secret file named by {} could not be read; the secret resolves as absent",
                subject,
                e);
    }

    /** Present a value only when it is non-null and non-blank; the fail-closed "never a silent empty". */
    private static Optional<String> present(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
