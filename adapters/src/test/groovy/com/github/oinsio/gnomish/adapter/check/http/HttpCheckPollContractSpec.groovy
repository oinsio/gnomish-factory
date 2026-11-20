package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.port.contract.CheckPollContract

/**
 * FR3, FR9 of add-plugin-architecture: the {@code http} built-in's judgement ({@link
 * HttpCheckProbe#probe}, the one the built-in executor renders) passes the same single-poll contract
 * as the {@code github-actions} built-in's, over the four {@link PollStatus} variants (FR14 of
 * add-manual-run, metric M2).
 */
class HttpCheckPollContractSpec extends CheckPollContract implements HttpCheckFixture {

    @Override
    protected Optional<PollStatus> arrange(PollVariant variant) {
        Optional.of(switch (variant) {
                    case PollVariant.PASS -> probe([url: URL], new ScriptedExchange(200, 'ok'))
                    case PollVariant.FAIL_WITH_FINDINGS -> probe([url: URL], new ScriptedExchange(500, 'boom'))
                    case PollVariant.RUNNING -> probe(
                            [url: URL, ('pending-when'): [('json-path'): 'state', equals: 'RUNNING']],
                            new ScriptedExchange(200, '{"state":"RUNNING"}'))
                    case PollVariant.CANNOT_VERIFY -> probe(
                            [url: URL], new ScriptedExchange(new IOException('unreachable')))
                })
    }
}
