package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import java.time.InstantSource;

/**
 * The equipment every agent round is launched with: the installation config (CLI binary, tail
 * drain grace), the clock that stamps the round, the live-progress subscriber, the extractor
 * that shapes the round's essential result, and the child-environment allowlist the round's AI
 * seam variables are resolved through ({@link AgentAiSeam#variables}). Built once in the constructor of each round's owner
 * ({@link CliStageExecutor}, {@link CliJudgeVoter}) and taken whole by the round executions
 * ({@link ExecutorRoundExecution#run}, {@link JudgeRoundExecution#run}), so the group is
 * assembled in one place per owner instead of held as four fields and re-listed at every call.
 *
 * <p>Implements FR6 of add-parameter-count-gate (design D6); NFR-S3 of tighten-spec-contract.
 *
 * @param factoryProperties installation config: the CLI binary path and the tail drain grace;
 *     never null
 * @param clock the read-time source for process start/exit stamping; never null
 * @param progressListener the live-progress subscriber for the owner's rounds; never null
 * @param resultExtractor shapes a round's drained events into its essential result; never null
 * @param childEnv the run's child-environment allowlist, whose values — the secrets port, in
 *     production — the AI seam variables are resolved through; never null
 */
record AgentRoundEquipment(
        FactoryProperties factoryProperties,
        InstantSource clock,
        AgentProgressListener progressListener,
        AgentRoundResultExtractor resultExtractor,
        ChildEnvAllowlist childEnv) {}
