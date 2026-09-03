package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.app.port.check.ExternalCheckPinContributor;
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.gitobjects.BlobTooLargeException;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.MissingObjectException;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pin-check guard (FR16, design D10): wraps any {@link ExternalCheckClient} and, on
 * every poll — the check's point of use — verifies that the check's definition files are
 * byte-identical to the base branch before the delegate is contacted. The pin set is the
 * union of the stage law's declared {@link VerifyCheck.External#pinPaths()} and the
 * {@link ExternalCheckPinContributor}'s adapter contribution; comparison reads both sides
 * as bare git objects in the factory clone ({@link GitObjects}, D11) — the base branch tip
 * versus the harvested attempt commit carried by the {@link RecordedAttemptCommitWorkspace}.
 *
 * <p>Outcomes: an empty union passes vacuously and the poll goes straight through (the
 * interactive client with nothing declared); any difference — changed bytes, a path added
 * on the gnome branch, or a path removed from it — is a quality {@link PollStatus.Fail}
 * with one finding per differing path, and the delegate is never invoked. Comparing
 * against the base branch, not the previous round, catches a substitution made at any
 * earlier stage right here. Fail-closed degradations are {@link PollStatus.CannotVerify}:
 * a non-empty pin set with a workspace that carries no attempt commit, an unresolvable
 * base ref, or a pinned blob too large to compare — the pin can then not be evaluated,
 * so no adapter contact happens either.
 *
 * <p>Implements FR16 of add-sandbox-core.
 *
 * @param delegate the real external-check client, contacted only after the pin passes
 * @param contributor the delegate's pin-path contribution seam
 * @param gitObjects the factory clone's bare-object reader the comparison runs over
 * @param baseRef the base branch ref the pin compares against (the law source branch)
 */
public record PinCheckedExternalCheckClient(
        ExternalCheckClient delegate, ExternalCheckPinContributor contributor, GitObjects gitObjects, String baseRef)
        implements ExternalCheckClient {

    /**
     * The read cap for one pinned definition file on either side of the comparison:
     * workflow files and analyzer configs are kilobytes, so a megabyte bounds hostile
     * volume (NFR-C1) without ever refusing a legitimate pin.
     */
    static final long PIN_READ_CAP_BYTES = 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(PinCheckedExternalCheckClient.class);

    @Override
    public PollStatus poll(VerifyCheck.External check, Workspace workspace) {
        var pins = new TreeSet<>(check.pinPaths());
        pins.addAll(contributor.pinPaths(check));
        if (pins.isEmpty()) {
            return delegate.poll(check, workspace);
        }

        if (!(workspace instanceof RecordedAttemptCommitWorkspace attemptWorkspace)) {
            return new PollStatus.CannotVerify(
                    "pin-check requires the attempt-commit workspace",
                    "external check '" + check.checkId() + "' declares pin paths " + pins
                            + " but the workspace is " + workspace.getClass().getName()
                            + ", which carries no attempt commit to compare against");
        }
        Optional<ObjectId> base = gitObjects.resolveRef(baseRef);
        if (base.isEmpty()) {
            return new PollStatus.CannotVerify(
                    "pin-check cannot resolve the base branch",
                    "base ref '" + baseRef + "' does not resolve in the factory clone");
        }
        ObjectId attempt = ObjectId.of(attemptWorkspace.attemptCommitSha());

        List<Finding> diffs = new ArrayList<>();
        for (String path : pins) {
            PinnedBlob baseBlob = read(base.get(), path);
            PinnedBlob attemptBlob = read(attempt, path);
            if (baseBlob.tooLarge() || attemptBlob.tooLarge()) {
                return new PollStatus.CannotVerify(
                        "pin-check cannot compare an oversized pinned file",
                        "pinned definition file '" + path + "' exceeds " + PIN_READ_CAP_BYTES
                                + " bytes and cannot be byte-compared");
            }
            diff(path, baseBlob, attemptBlob).ifPresent(diffs::add);
        }
        if (!diffs.isEmpty()) {
            log.warn(
                    OperatorEvent.EXTERNAL_CHECK_PIN_MISMATCH.head()
                            + "pin-check failed for external check '{}': {} pinned definition file(s) differ from '{}';"
                            + " the adapter is not invoked",
                    check.checkId(),
                    diffs.size(),
                    baseRef);
            return new PollStatus.Fail(diffs);
        }
        return delegate.poll(check, workspace);
    }

    /** One finding per differing path, naming how the attempt side diverged from the base branch (FR16). */
    private Optional<Finding> diff(String path, PinnedBlob base, PinnedBlob attempt) {
        if (base.present() && !attempt.present()) {
            return Optional.of(finding(path, "was removed relative to the base branch"));
        }
        if (!base.present() && attempt.present()) {
            return Optional.of(finding(path, "is absent from the base branch"));
        }
        if (base.present() && !Arrays.equals(base.bytes(), attempt.bytes())) {
            return Optional.of(finding(path, "differs from the base branch"));
        }
        return Optional.empty();
    }

    private static Finding finding(String path, String how) {
        return new Finding("pinned definition file '" + path + "' " + how, path, null);
    }

    private PinnedBlob read(ObjectId commit, String path) {
        try {
            return new PinnedBlob(gitObjects.readBlob(commit, path, PIN_READ_CAP_BYTES), false);
        } catch (MissingObjectException e) {
            return new PinnedBlob(null, false);
        } catch (BlobTooLargeException e) {
            return new PinnedBlob(null, true);
        }
    }

    /**
     * One side of a pinned-path comparison: absent, present with bytes, or over the read cap.
     *
     * <p>A plain class, not a record: a record's auto-generated {@code equals}/{@code hashCode}
     * would compare the {@code bytes} array component by reference rather than by content
     * (Error Prone {@code ArrayRecordComponent}), which is not this type's intended semantics.
     */
    private static final class PinnedBlob {

        private final byte @Nullable [] bytes;
        private final boolean tooLarge;

        private PinnedBlob(byte @Nullable [] bytes, boolean tooLarge) {
            this.bytes = bytes;
            this.tooLarge = tooLarge;
        }

        byte @Nullable [] bytes() {
            return bytes;
        }

        boolean tooLarge() {
            return tooLarge;
        }

        boolean present() {
            return bytes != null;
        }
    }
}
