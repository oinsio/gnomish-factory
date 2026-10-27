package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.DoctorProjectReadings;
import com.github.oinsio.gnomish.app.UsageException;
import com.github.oinsio.gnomish.app.port.tracker.TrackerReadVerdict;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The one read-only tracker probe {@code doctor --online} adds (FR3, NG5 of add-doctor-command;
 * design D3, D7): the tracker adapter's own probe — for GitHub the label listing startup's
 * provisioning makes first, classified by the code startup refuses with — made with the configured
 * credential through the tracker wiring's relay. It never builds an adapter: no {@code create}, so
 * nothing is provisioned, and no read-only tracker resolution. Reading is all it confirms; the line
 * says that write access is not verified and names what startup will create. Its id is {@linkplain
 * DoctorCheckId#online() online}, so without {@code --online} it is not run and nothing reaches the
 * network. An unregistered tracker type is the tracker wiring's refusal in its own words, rendered
 * for the console; a failure to obtain any answer is thrown on to {@link DoctorCommand}. Skipped
 * when no project resolves, the law did not load, or the law has no tracker.
 *
 * <p>Implements FR3, FR4, NFR-O1, NG5, UX1 of add-doctor-command.
 */
@NullMarked
@Component
final class TrackerReadProbe implements DoctorCheck {

    /** What a confirmed read leaves open (NG5 of add-doctor-command). */
    static final String WRITE_NOT_VERIFIED = "write access is not verified";

    /** The fix of a refused probe. */
    static final String REFUSED_FIX =
            "give the tracker credential the access the cause names, or correct the tracker binding";

    /** The fix of an answer that is not the tracker's own: something else answered at its address. */
    static final String MALFORMED_FIX = "point the pipeline law's tracker section at the tracker's own API, past any"
            + " proxy that answers in its place, then run gnomish doctor --online again";

    private final DoctorProjectReadings readings;

    /** @param readings the owners the probe is relayed through */
    TrackerReadProbe(DoctorProjectReadings readings) {
        this.readings = readings;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.TRACKER_PROBE;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return context.onDefinition(id(), (clone, definition) -> List.of(probe(definition)));
    }

    private DoctorVerdict probe(PipelineDefinition definition) {
        TrackerConfig tracker = definition.tracker();
        if (tracker == null) {
            return verdict(DoctorStatus.SKIPPED, "the pipeline law declares no tracker", null);
        }
        TrackerReadVerdict answer;
        try {
            answer = readings.probeTracker(tracker);
        } catch (UsageException e) {
            return verdict(DoctorStatus.FAIL, DoctorText.words(e), DoctorText.TRACKER_TYPE_FIX);
        }
        return switch (answer) {
            case TrackerReadVerdict.NotApplicable notApplicable ->
                verdict(
                        DoctorStatus.SKIPPED,
                        "tracker type '" + tracker.type() + "' has no remote binding to probe; no call was made",
                        null);
            case TrackerReadVerdict.ReadConfirmed confirmed ->
                verdict(DoctorStatus.OK, confirmedDetail(confirmed.toProvision()), null);
            case TrackerReadVerdict.Refused refused -> verdict(DoctorStatus.FAIL, refusedDetail(refused), REFUSED_FIX);
            case TrackerReadVerdict.Malformed malformed ->
                verdict(DoctorStatus.FAIL, "the tracker answered the probe, but " + malformed.cause(), MALFORMED_FIX);
        };
    }

    private static String confirmedDetail(List<String> toProvision) {
        String provision = toProvision.isEmpty()
                ? "startup will create nothing"
                : "startup will create " + String.join(", ", toProvision);
        return "reading confirmed; " + provision + "; " + WRITE_NOT_VERIFIED;
    }

    private static String refusedDetail(TrackerReadVerdict.Refused refused) {
        String head = "the tracker refused the probe with status " + refused.statusCode() + ": " + refused.cause();
        return refused.acceptedPermissions()
                .map(UntrustedText::forConsole)
                .map(permissions -> head + "; the tracker accepts the permissions: " + permissions)
                .orElse(head);
    }

    private DoctorVerdict verdict(DoctorStatus status, String detail, @Nullable String fix) {
        return new DoctorVerdict(id(), status, detail, fix);
    }
}
