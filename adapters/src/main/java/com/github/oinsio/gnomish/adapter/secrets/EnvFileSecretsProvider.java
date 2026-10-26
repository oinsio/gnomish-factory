package com.github.oinsio.gnomish.adapter.secrets;

import com.github.oinsio.gnomish.app.SecretLocation;
import com.github.oinsio.gnomish.app.SecretLocator;
import com.github.oinsio.gnomish.app.UsageException;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.project.FactoryHome;
import com.github.oinsio.gnomish.app.project.ProjectLayout;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

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
 * <p>It is also the factory's {@link SecretLocator}: {@link #locate} runs the one search {@link
 * #find} runs ({@link EnvFileSecretSearch}) and answers where, never what (FR1, NFR-S1 of
 * add-doctor-command).
 *
 * <p>Implements FR18, NFR-S1 of add-sandbox-core; FR8, NFR-S2 of add-project-registry; FR1, NFR-S1
 * of add-doctor-command.
 */
public final class EnvFileSecretsProvider implements SecretsProvider, SecretLocator {

    private final EnvFileSecretSearch search;

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
        this.search = new EnvFileSecretSearch(
                project == null ? List.of(home::hostSecret) : List.of(project::secret, home::hostSecret), env);
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
        EnvFileSecretSearch.Hit hit = search.search(name);
        return hit == null ? Optional.empty() : Optional.of(hit.value());
    }

    /**
     * Where {@link #find} finds {@code name}, by the same one search, or every place it looks: the
     * place, never the value (FR1, NFR-S1 of add-doctor-command).
     *
     * @throws UsageException as {@link #find} does
     */
    @Override
    public SecretLocation locate(String name) {
        EnvFileSecretSearch.Hit hit = search.search(name);
        return hit == null ? new SecretLocation.Absent(search.places(name)) : new SecretLocation.Found(hit.place());
    }
}
