package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.TrackerReadVerdict;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Optional;

/**
 * The verdict of {@link GithubLabelProvisioner#assess}: what one label listing of the configured
 * repository answered, classified by the code startup refuses with (design D1 applied to the
 * provisioner, D7 of add-doctor-command). Exactly one of three:
 *
 * <ul>
 *   <li>{@link Listed} — the listing succeeded; the configured labels it lacks are named, so a
 *       reader can say which ones startup will create;
 *   <li>{@link Refused} — the listing came back with a non-2xx status: the status, the cause
 *       provisioning names for it, and, on a 403, GitHub's own {@code X-Accepted-GitHub-Permissions}
 *       diagnostic verbatim, as an {@link UntrustedText} of the tracker family since the header is
 *       response text;
 *   <li>{@link Malformed} — a 2xx whose body is not a label listing.
 * </ul>
 *
 * <p>{@link #failureMessage} is the sentence {@code provision} refuses with, so the refusal and
 * the verdict are one classification and cannot disagree; {@link #toReadVerdict} is the same
 * verdict in the plugin api's words, the answer of {@code probeRead} (design D7).
 *
 * <p>Implements FR3 of add-doctor-command (design D7); FR5, NFR-R4 of add-tracker-port.
 */
public sealed interface GithubLabelAssessment {

    /**
     * The message of the {@link GithubLabelProvisioningException} startup throws for this verdict,
     * or empty when the verdict does not refuse.
     */
    Optional<String> failureMessage(String repoName);

    /** This verdict as the plugin api's {@link TrackerReadVerdict}, nothing added and nothing lost. */
    TrackerReadVerdict toReadVerdict();

    /** The listing succeeded; {@code missingLabelNames} are the configured labels it lacks. */
    record Listed(List<String> missingLabelNames) implements GithubLabelAssessment {

        public Listed {
            missingLabelNames = List.copyOf(missingLabelNames);
        }

        @Override
        public Optional<String> failureMessage(String repoName) {
            return Optional.empty();
        }

        @Override
        public TrackerReadVerdict toReadVerdict() {
            return new TrackerReadVerdict.ReadConfirmed(missingLabelNames);
        }
    }

    /**
     * The listing was refused with {@code statusCode}; {@code cause} is the factory's wording of
     * that status, and {@code acceptedPermissions} is GitHub's {@code X-Accepted-GitHub-Permissions}
     * header on a 403 that carried one.
     */
    record Refused(int statusCode, String cause, Optional<UntrustedText> acceptedPermissions)
            implements GithubLabelAssessment {

        @Override
        public Optional<String> failureMessage(String repoName) {
            return Optional.of(GithubLabelProvisioner.failureMessage(repoName, "list existing labels", statusCode));
        }

        @Override
        public TrackerReadVerdict toReadVerdict() {
            return new TrackerReadVerdict.Refused(statusCode, cause, acceptedPermissions);
        }
    }

    /** The listing answered 2xx with a body that is not a label listing. */
    record Malformed() implements GithubLabelAssessment {

        /** What could not be read, worded once for the refusal and the api verdict. */
        static final String CAUSE = "could not parse existing-labels response";

        @Override
        public Optional<String> failureMessage(String repoName) {
            return Optional.of("Label provisioning failed for %s: %s".formatted(repoName, CAUSE));
        }

        @Override
        public TrackerReadVerdict toReadVerdict() {
            return new TrackerReadVerdict.Malformed(CAUSE);
        }
    }
}
