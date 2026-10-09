package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import java.time.InstantSource;

/**
 * The equipment every agent round is launched with: the installation config (CLI binary, tail
 * drain grace), the clock that stamps the round, the live-progress subscriber, and the extractor
 * that shapes the round's essential result. Built once in the constructor of each round's owner
 * ({@link CliStageExecutor}, {@link CliJudgeVoter}) and taken whole by the round executions
 * ({@link ExecutorRoundExecution#run}, {@link JudgeRoundExecution#run}), so the group is
 * assembled in one place per owner instead of held as four fields and re-listed at every call.
 *
 * <p>Implements FR6 of add-parameter-count-gate (design D6).
 *
 * @param factoryProperties installation config: the CLI binary path and the tail drain grace;
 *     never null
 * @param clock the read-time source for process start/exit stamping; never null
 * @param progressListener the live-progress subscriber for the owner's rounds; never null
 * @param resultExtractor shapes a round's drained events into its essential result; never null
 */
record AgentRoundEquipment(
        FactoryProperties factoryProperties,
        InstantSource clock,
        AgentProgressListener progressListener,
        AgentRoundResultExtractor resultExtractor) {}
