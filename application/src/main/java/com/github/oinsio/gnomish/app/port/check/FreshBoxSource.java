package com.github.oinsio.gnomish.app.port.check;

import java.util.List;

/**
 * Where a {@code program} verdict check gets the box of one request (design D6, D11 as amended
 * 2026-11-14 of migrate-verify-to-executor-contract): a fresh box materialized at the attempt commit
 * its binding names, its guard admitting the operator's {@code factory.egress.sandbox} and the hosts
 * the law grants that one executor — never another executor's grant, never the round box. Released
 * by disposal. Only the sandboxed medium has one; a host run binds no {@code program} check (proposal
 * NG14), so the host has no source.
 *
 * <p>Implements FR3, FR11, NFR-S1 of migrate-verify-to-executor-contract.
 */
public interface FreshBoxSource {

    /**
     * Materializes a fresh box for one request.
     *
     * @param attemptCommit the commit the box's working copy is put on, its {@code HEAD}; never blank
     * @param egress the hosts the law grants the executor, admitted beside the operator's sandbox
     *     egress; never null, may be empty
     * @return the box and its release, which disposes it; never null
     * @throws RuntimeException when no box could be materialized — an infrastructure failure the
     *     binding answers as {@code failed/infrastructure}
     */
    CheckEnvironmentSource.Acquired freshBox(String attemptCommit, List<String> egress);
}
