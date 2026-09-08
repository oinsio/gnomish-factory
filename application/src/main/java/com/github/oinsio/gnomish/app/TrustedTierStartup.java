package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome;
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery;
import com.github.oinsio.gnomish.app.port.pipeline.BoundConfiguration;
import com.github.oinsio.gnomish.app.port.pipeline.ConfiguredDesignatorKinds;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.baseref.BaseDefinition;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The startup read of {@code serve} and {@code take} (FR13, design D14, D15 of
 * add-base-ref-resolution): discover the repository default branch from {@code origin}, refresh it
 * with a narrow fetch, and load the whole definition from git objects at the refreshed tip — the
 * fail-fast validation of the common case, the source of the trusted configuration tier held for
 * the process lifetime, and the definition {@code board}/{@code dashboard} display. The clone's
 * working tree and checkout play no part: an uncommitted edit in the factory clone is not law for
 * an autonomous mode (NFR-S1).
 *
 * <p>Three ways to end before any claim: a definition that fails to load keeps the exit-3 shape
 * every command has ({@link PipelineLoadFailedException}, located errors printed as is); a default
 * branch that cannot be established or refreshed — no remote, no default branch, a refused refresh
 * (refusals, FR5), or an origin that never answered (an infrastructure failure outside a claimed
 * run) — ends with {@link DefaultBranchUnboundException}, exit code 1. The remote outage gate is a
 * later concern; at startup an outage is simply a daemon that did not come up.
 *
 * <p>Implements FR5, FR13 of add-base-ref-resolution.
 */
final class TrustedTierStartup {

    private static final Logger log = LoggerFactory.getLogger(TrustedTierStartup.class);

    private TrustedTierStartup() {}

    /**
     * What startup bound: the definition, the trusted tier, and where they came from.
     *
     * @param definition the validated definition, the daemon's own for the process lifetime
     * @param base the trusted tier's base policy, bound once here and never re-read per claim
     * @param defaultBranch the branch origin named as its default
     * @param lawCommit the refreshed tip the definition was read from
     */
    record StartupLaw(PipelineDefinition definition, BaseDefinition base, String defaultBranch, ObjectId lawCommit) {}

    /**
     * Binds the trusted tier from the refreshed default branch of the clone at {@code dir}.
     *
     * @param dir the factory clone; never null
     * @param baseRefs the clone's base-ref operations; never null
     * @param pipelineSource where the definition is read from by binding; never null
     * @param registry known tracker adapter factories, asked which designator kinds the configured
     *     adapter extracts once the {@code tracker} section is mapped; never null
     * @return the bound startup law; never null
     * @throws DefaultBranchUnboundException if the default branch cannot be established or
     *     refreshed
     * @throws PipelineLoadFailedException if the definition at the refreshed tip fails to load
     * @throws IOException if the law cannot be read at all — an I/O fault
     */
    static StartupLaw bind(
            Path dir, BaseRefGit baseRefs, PipelineSource pipelineSource, Map<String, TrackerAdapterFactory> registry)
            throws IOException {
        String branch = discover(dir, baseRefs);
        String tip = refresh(dir, baseRefs, branch);
        BoundConfiguration bound = pipelineSource.bindConfiguration(
                LawBinding.atRevision(dir, tip), ConfiguredDesignatorKinds.fromRegistry(registry));
        return switch (bound.outcome()) {
            case LoadOutcome.Loaded(var definition) ->
                new StartupLaw(definition, bound.base(), branch, bound.lawCommit());
            case LoadOutcome.Invalid(List<ConfigError> errors) ->
                throw new PipelineLoadFailedException(
                        errors.stream().map(ConfigError::render).toList());
        };
    }

    private static String discover(Path dir, BaseRefGit baseRefs) {
        return switch (baseRefs.discoverDefaultBranch(dir)) {
            case DefaultBranchDiscovery.Discovered(String branch) -> branch;
            case DefaultBranchDiscovery.NoRemote() ->
                throw unbound(
                        dir,
                        "the clone has no 'origin' remote to read the default branch from; an autonomous"
                                + " mode binds its configuration from origin's default branch, never from the"
                                + " clone's local state");
            case DefaultBranchDiscovery.Undetermined(String reason) -> throw unbound(dir, reason);
            case DefaultBranchDiscovery.Unavailable(String reason) ->
                throw unbound(dir, "origin did not answer the default-branch read: " + reason);
        };
    }

    private static String refresh(Path dir, BaseRefGit baseRefs, String branch) {
        return switch (baseRefs.refresh(dir, branch)) {
            case BaseRefreshOutcome.Refreshed(var ignored, String commit, var _) -> commit;
            case BaseRefreshOutcome.Refused(String report) -> throw unbound(dir, report);
            case BaseRefreshOutcome.Unavailable(String reason) ->
                throw unbound(dir, "origin did not answer the refresh of default branch '" + branch + "': " + reason);
        };
    }

    /** The one ERROR of this failure class: logged here, printed by the command that ends on it. */
    private static DefaultBranchUnboundException unbound(Path dir, String cause) {
        log.error(
                OperatorEvent.STARTUP_DEFAULT_BRANCH_UNBOUND.head()
                        + "startup cannot bind the configuration of {} from origin's default branch: {}",
                dir,
                LogText.forLog(cause));
        return new DefaultBranchUnboundException(
                "cannot bind the configuration of " + dir + " from origin's default branch: " + cause);
    }
}
