package com.github.oinsio.gnomish;

import com.github.oinsio.gnomish.operatorconfig.ConfigLevel;
import com.github.oinsio.gnomish.operatorconfig.Level;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The operator egress block, {@code factory.egress} (FR17, design D16 of
 * migrate-verify-to-executor-contract): one block with two scopes, each a list in the one egress
 * entry grammar ({@code EgressEntries} in {@code :httpegress}). It replaces the two lists that
 * carried the same question before — the box's allowlist under {@code factory.sandbox} and the http
 * check's under {@code factory.check.http} — which are removed keys now, reported at startup with
 * the scope that replaces each.
 *
 * <p>A component of {@link FactoryProperties} rather than a {@code @ConfigurationProperties} record
 * of its own: the keys bind under {@code factory} like every other block, and the level table reads
 * each scope's level off its component here. The values are judged at startup, located at the file
 * and line that set them: the {@code sandbox} scope against the subset the box guard can apply, the
 * {@code in-process} scope against the full grammar.
 *
 * <p>Implements FR17 of migrate-verify-to-executor-contract.
 *
 * @param sandbox what every box may reach ({@code factory.egress.sandbox}); a sandbox-boundary key,
 *     read from the project file only, since it widens the box; defaults to empty (default-deny)
 * @param inProcess what the factory process may reach beyond the hosts its connections grant
 *     ({@code factory.egress.in-process}); a project key; defaults to empty
 */
public record EgressProperties(
        @ConfigLevel(Level.SANDBOX_BOUNDARY) List<String> sandbox,
        @ConfigLevel(Level.PROJECT) List<String> inProcess) {

    // Spring's reflective constructor binding can pass null for an unset list despite the
    // @NullMarked contract, so each list is defaulted here rather than left null.
    public EgressProperties(@Nullable List<String> sandbox, @Nullable List<String> inProcess) {
        this.sandbox = sandbox == null ? List.of() : List.copyOf(sandbox);
        this.inProcess = inProcess == null ? List.of() : List.copyOf(inProcess);
    }

    /** The empty block: both scopes default-deny. */
    public static EgressProperties none() {
        return new EgressProperties(List.of(), List.of());
    }
}
