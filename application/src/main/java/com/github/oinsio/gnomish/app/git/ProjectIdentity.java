package com.github.oinsio.gnomish.app.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the project identity a sandbox sweep scopes its listings to (design D5 of
 * add-serve-sandbox-lifecycle): a stable digest of the target project's {@code origin} remote
 * URL, or the operator's explicit {@code factory.sandbox.project-id} override when set. Without
 * this label, a second project sharing the same Docker host would be invisible to its own sweep
 * and visible to another project's (proposal FR8).
 *
 * <p>The override always wins, independent of whether {@code origin} is even configured, so a
 * host-only or bare-local clone can still be scoped explicitly. The digest is truncated
 * hexadecimal SHA-256 — long enough to make an accidental collision between two projects
 * negligible, short enough to read in a label or a log line — never the raw URL, which may carry
 * a credential-bearing form (e.g. an HTTPS URL with an embedded token).
 *
 * <p>A clone with neither an override nor an {@code origin} falls back to the digest of its own
 * canonical absolute path, never to a shared constant: a constant would put every origin-less
 * project on a host into one sweep scope, which is precisely the "project A stops project B's
 * live boxes" failure the project label exists to prevent (design D5). Per-clone scoping errs the
 * safe way — two checkouts of the same origin-less repo simply ignore each other's objects.
 *
 * <p>Implements FR8 of add-serve-sandbox-lifecycle.
 */
public final class ProjectIdentity {

    private static final int DIGEST_HEX_LENGTH = 12;

    /**
     * The alphabet a resolved identity may use. Narrower than Docker's own label grammar on
     * purpose: the identity is stamped into a label whose raw {@code k1=v1,k2=v2} rendering is
     * split on bare commas and equals signs when read back ({@code DockerLabelFormat}), so a
     * comma or an equals sign in the value would let one label value forge a second label pair —
     * an override reading {@code acme,com.github.oinsio.gnomish.mode=manual} would parse back as a
     * {@code manual}-mode object and lose its claim-oracle protection. Whitespace and {@code /}
     * are excluded for the same "must survive a filter and a log line verbatim" reason. The
     * derived digest is hexadecimal and satisfies this by construction; only the override needs
     * checking.
     */
    private static final Pattern LABEL_SAFE = Pattern.compile("[A-Za-z0-9._-]+");

    private ProjectIdentity() {}

    /**
     * Resolves the project identity: the override verbatim when set, otherwise a digest of {@code
     * originUrl}, otherwise a digest of {@code cloneDir}.
     *
     * @param configuredProjectId the operator override ({@code factory.sandbox.project-id});
     *     {@code null} when unset — rejected if blank or outside {@link #LABEL_SAFE}
     * @param originUrl the clone's {@code origin} remote URL, when configured; empty for a
     *     bare-local clone
     * @param cloneDir the target project's clone directory — the fallback identity source for a
     *     clone with no {@code origin}
     * @return the resolved project identity: the override verbatim when set, otherwise a
     *     truncated digest of {@code originUrl}, otherwise a truncated digest of {@code cloneDir}
     */
    public static String resolve(@Nullable String configuredProjectId, Optional<String> originUrl, Path cloneDir) {
        if (configuredProjectId != null) {
            return validated(configuredProjectId);
        }
        return originUrl
                .map(ProjectIdentity::digest)
                .orElseGet(() -> digest(cloneDir.toAbsolutePath().normalize().toString()));
    }

    /**
     * Rejects an override the label machinery cannot carry verbatim, naming the property so the
     * operator can find it. The offending value is deliberately not echoed: an override is
     * operator-supplied free text that may have been pasted from a credential-bearing string, and
     * this message reaches logs.
     */
    private static String validated(String configuredProjectId) {
        if (configuredProjectId.isBlank()) {
            throw new IllegalArgumentException("factory.sandbox.project-id must not be blank");
        }
        if (!LABEL_SAFE.matcher(configuredProjectId).matches()) {
            throw new IllegalArgumentException("factory.sandbox.project-id must match [A-Za-z0-9._-]+");
        }
        return configuredProjectId;
    }

    private static String digest(String originUrl) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
        byte[] hash = sha256.digest(originUrl.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash, 0, DIGEST_HEX_LENGTH / 2);
    }
}
