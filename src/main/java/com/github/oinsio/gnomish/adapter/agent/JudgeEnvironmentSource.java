package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.adapter.environment.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;

/**
 * Supplies the {@link TaskExecutionEnvironment} a CLI judge vote executes in (FR15, D9 of
 * add-sandbox-core): in host mode the stage workspace as today (a host environment over the
 * {@code DirectoryWorkspace} root); in sandboxed mode a fresh environment materialized from the
 * attempt commit ({@link FreshJudgeEnvironments}) — never the gnome-touched round environment,
 * whose out-of-branch residue (PATH shims, planted binaries) could fake a verdict.
 *
 * <p>Implements FR15, D9 of add-sandbox-core.
 */
public interface JudgeEnvironmentSource {

    /**
     * The environment the next vote runs its CLI process through. Ownership stays with the
     * source: callers exec through the returned environment but never dispose it — the source
     * decides sharing (votes of one attempt may share a fresh box, judges are read-only) and
     * teardown.
     *
     * @param workspace the engine workspace the vote grades; never null
     * @return a ready-to-exec environment; never null
     */
    TaskExecutionEnvironment environmentFor(Workspace workspace);
}
