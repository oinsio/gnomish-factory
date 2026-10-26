package com.github.oinsio.gnomish.adapter.secrets;

import com.github.oinsio.gnomish.app.UsageException;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The resolution order of {@link EnvFileSecretsProvider} — the secrets folders, then the {@code
 * N_FILE} indirection, then the environment — run once per name, answering both the value and the
 * place it came from, so the port's {@code find} and the doctor's {@code locate} are one search
 * and cannot disagree on where a secret is (FR1, FR4, NFR-S1 of add-doctor-command). The order,
 * the fail-closed rules and the folder-file permission refusal are the provider's, documented on
 * it; this class only holds them.
 *
 * <p>Its warning is logged under the provider's logger, the component an operator knows.
 *
 * <p>Implements FR18, NFR-S1 of add-sandbox-core; FR8, NFR-S2 of add-project-registry; FR1, NFR-S1
 * of add-doctor-command.
 */
final class EnvFileSecretSearch {

    private static final Logger log = LoggerFactory.getLogger(EnvFileSecretsProvider.class);

    /** The suffix that names the file-indirection variable for a secret {@code N}: {@code N_FILE}. */
    static final String FILE_SUFFIX = "_FILE";

    private static final Set<PosixFilePermission> LOOSE = Set.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE);

    /**
     * A resolved secret and where it was read; the value never leaves the adapter but through the
     * port's {@code find}. A class rather than a record, so no generated {@code toString} can print
     * the value (NFR-S1).
     */
    static final class Hit {

        private final String value;
        private final String place;

        Hit(String value, String place) {
            this.value = value;
            this.place = place;
        }

        String value() {
            return value;
        }

        String place() {
            return place;
        }
    }

    private final List<Function<String, Path>> folders;
    private final Function<String, @Nullable String> env;

    /**
     * @param folders the secrets folders in the order they are consulted, each mapping a name to its file
     * @param env the environment-variable lookup: name to value, or {@code null} when unset
     */
    EnvFileSecretSearch(List<Function<String, Path>> folders, Function<String, @Nullable String> env) {
        this.folders = List.copyOf(folders);
        this.env = env;
    }

    /**
     * Resolves {@code name}; the first place that holds it decides, a blank or unreadable value
     * included.
     *
     * @return the value and its place, or {@code null} when the name resolves to nothing
     * @throws UsageException if {@code name} is not one path segment, or a folder file holding it
     *     is readable or writable by group or others
     */
    @Nullable
    Hit search(String name) {
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
        return present(env.apply(name), "the environment variable " + name);
    }

    /**
     * Every place {@link #search} consults for {@code name}, in its order — the folder files, the
     * file {@code N_FILE} names, the variable {@code N}.
     *
     * @throws UsageException if {@code name} is not one path segment
     */
    List<String> places(String name) {
        List<String> places = new ArrayList<>();
        for (Function<String, Path> folder : folders) {
            places.add(fileIn(folder, name).toString());
        }
        places.add("the file the environment variable " + name + FILE_SUFFIX + " names");
        places.add("the environment variable " + name);
        return places;
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
    private static @Nullable Hit readFolderFile(Path file) {
        try {
            if (isLoose(file)) {
                throw new UsageException("secret file " + file + " is readable or writable by group or others,"
                        + " so it was not read: run chmod 600 " + file);
            }
            return present(Files.readString(file).strip(), file.toString());
        } catch (IOException e) {
            warnUnreadable(file.toString(), e);
            return null;
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
    private static @Nullable Hit readFile(String variable, String path) {
        try {
            return present(
                    Files.readString(Path.of(path)).strip(), "the file " + path + " (named by " + variable + ")");
        } catch (IOException | InvalidPathException e) {
            warnUnreadable(variable, e);
            return null;
        }
    }

    /**
     * The one warning of a read that failed; the caller returns the fail-closed {@code null}
     * itself, a literal no return-value mutant can distinguish from its own.
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

    /** A hit only when the value is non-null and non-blank; the fail-closed "never a silent empty". */
    private static @Nullable Hit present(@Nullable String value, String place) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new Hit(value, place);
    }
}
