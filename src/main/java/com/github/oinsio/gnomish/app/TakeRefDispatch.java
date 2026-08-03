package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeExitCodeMapper;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;

/**
 * Routes one {@code take} invocation's refs to batch, bare, or explicit mode and converts the
 * outcome to the process exit code via {@link TakeExitCodeException}, extracted from {@link
 * TakeCommand#run} for file size.
 *
 * <p>Implements FR2, FR3 of add-factory-serve.
 */
final class TakeRefDispatch {

    private TakeRefDispatch() {}

    static void run(
            TakeDispatcher dispatcher,
            TakeArguments takeArguments,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            List<String> credentialEnvVarsToScrub,
            TrackerAdapterFactory factory,
            ManualRunAssembly takeAssembly,
            TakeHeartbeat heartbeat,
            ServeProperties serveProperties,
            Logger log)
            throws IOException, InterruptedException {
        List<String> refs = takeArguments.refs();
        if (refs.size() >= 2) {
            // Batch take (2+ refs), validated by TakeArgumentsParser (FR2, FR3 of
            // add-factory-serve): every ref through the disposition matrix, up to
            // serveProperties.slots() concurrently (FR2: "the N limit applies to batch and
            // serve" — no separate batch flag).
            List<TakeBatchOutcome> outcomes = dispatcher.runBatch(
                    takeArguments,
                    definition,
                    trackerConfig,
                    tracker,
                    instanceId,
                    credentialEnvVarsToScrub,
                    factory,
                    takeAssembly,
                    heartbeat,
                    serveProperties.slots());
            // FR3, NFR-O2, UX3: the checklist summary is logged before the aggregate exit code
            // is thrown, so it is visible regardless of how the caller handles the exit code.
            TakeBatchSummary.log(outcomes, log);
            throw new TakeExitCodeException(TakeBatchExitCode.aggregate(outcomes));
        }
        TakeResult result;
        if (refs.isEmpty()) {
            result = dispatcher.runBare(
                    takeArguments,
                    definition,
                    trackerConfig,
                    tracker,
                    instanceId,
                    credentialEnvVarsToScrub,
                    takeAssembly,
                    heartbeat);
        } else {
            result = dispatcher.runExplicit(
                    takeArguments,
                    refs.getFirst(),
                    definition,
                    trackerConfig,
                    tracker,
                    instanceId,
                    credentialEnvVarsToScrub,
                    factory,
                    takeAssembly,
                    heartbeat);
        }
        throw new TakeExitCodeException(TakeExitCodeMapper.exitCodeFor(result));
    }
}
