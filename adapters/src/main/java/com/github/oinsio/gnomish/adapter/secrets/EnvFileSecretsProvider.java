package com.github.oinsio.gnomish.adapter.secrets;

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The env/file {@link SecretsProvider} adapter (design D12): resolves a named
 * secret from the process environment, with a {@code <name>_FILE} indirection
 * for reading the value out of a local file instead (the Docker-secret
 * convention), so a secret need not live in a process's environment table.
 *
 * <p>Resolution order for a name {@code N} (first match wins):
 * <ol>
 *   <li>if {@code N_FILE} is set to a non-blank path, the value is that file's
 *       stripped contents — a referenced-but-unreadable file resolves to empty
 *       (fail-closed), never to the direct env fallback, so a misconfigured
 *       path fails loudly at the consumer rather than silently reading a
 *       different value;</li>
 *   <li>otherwise the value is {@code N} from the environment.</li>
 * </ol>
 * A blank result at either step is treated as absent — the fail-closed contract
 * of {@link SecretsProvider#find(String)} (NFR-S1): the consumer turns empty
 * into an error naming the secret. Resolved values are never logged; only a
 * file-read failure is logged, and its message carries the path, never the
 * secret's content.
 *
 * <p>Implements FR18, NFR-S1 of add-sandbox-core.
 */
public final class EnvFileSecretsProvider implements SecretsProvider {

    private static final Logger log = LoggerFactory.getLogger(EnvFileSecretsProvider.class);

    /** The suffix that names the file-indirection variable for a secret {@code N}: {@code N_FILE}. */
    static final String FILE_SUFFIX = "_FILE";

    private final Function<String, @Nullable String> env;

    /** The production adapter, backed by the JVM's process environment ({@code System.getenv}). */
    public EnvFileSecretsProvider() {
        this(System::getenv);
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
    EnvFileSecretsProvider(Function<String, @Nullable String> env) {
        this.env = env;
    }

    @Override
    public Optional<String> find(String name) {
        String fileRef = env.apply(name + FILE_SUFFIX);
        if (fileRef != null && !fileRef.isBlank()) {
            return readFile(name + FILE_SUFFIX, fileRef.strip());
        }
        return present(env.apply(name));
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
            // The message carries the path, never the file's content (a secret) — and the throwable
            // is passed as a throwable, not as a format argument, so the WARN keeps the stack trace
            // that says WHICH read failed and why. Neither an IOException from a file read nor an
            // InvalidPathException carries file content, so the stack leaks nothing the message
            // does not already say.
            log.warn(
                    OperatorEvent.SECRET_FILE_UNREADABLE.head()
                            + "secret file named by {} could not be read; the secret resolves as absent",
                    variable,
                    e);
            return Optional.empty();
        }
    }

    /** Present a value only when it is non-null and non-blank; the fail-closed "never a silent empty". */
    private static Optional<String> present(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
