package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.StageDefinition

/**
 * Shared fixture for the {@code PipelineMapper} spec family (task 5.3, design D2 of
 * load-pipeline-config). The mapper is one function over a {@code ConfigDto} plus an
 * ordered list of {@code (stageName, StageDto)} entries, so its specs are split by the
 * contract under test — full mapping, defaults, settings, null → blank, durations,
 * sandbox, tracker, heartbeat — and every one of them needs the same two primitives:
 * a one-line entry pair and a {@code config.yaml} DTO.
 *
 * <p>The stage builders cover the two shapes the specs vary nothing about: the baseline
 * stage that declares a source input and one output, and the io-less one used where the
 * verify list alone is the subject. A spec that varies any other field builds its own
 * {@link StageDto} inline rather than growing a builder with defaults nobody reads.
 *
 * <p>Implements FR7, FR11, D2, D5a of load-pipeline-config.
 */
trait PipelineMapperFixtureSupport {

    /** The {@code (name, DTO)} pair the loader (task 6.5) hands the mapper, in pipeline order. */
    PipelineMapper.StageEntry entry(String name, StageDto dto) {
        new PipelineMapper.StageEntry(name, dto)
    }

    /** A {@code config.yaml} DTO with no tracker section; a null {@code attemptDefault} omits the autonomy block. */
    ConfigDto config(String schemaVersion, Integer attemptDefault) {
        new ConfigDto(schemaVersion, attemptDefault == null ? null : new AutonomyDto(attemptDefault))
    }

    /** The baseline stage: one source input, one output {@code o}, an api executor, auto advancement. */
    StageDto apiStage(List<VerifyCheckDto> verify) {
        new StageDto('p', [new ArtifactInputDto.Source()], [new ArtifactOutputDto('o')],
        new ExecutorDto('api', 'm', null), 'i.md', verify, null, 'auto')
    }

    /** The baseline stage without input/output sections — for specs whose subject is the verify list alone. */
    StageDto ioLessStage(List<VerifyCheckDto> verify) {
        new StageDto('p', null, null, new ExecutorDto('api', 'm', null), 'i.md', verify, null, 'auto')
    }

    /** Maps {@code dto} as the single stage {@code p} of a schema-1 config with attempt limit 1. */
    PipelineMapper.Result mapOne(StageDto dto) {
        PipelineMapper.map(config('1', 1), [entry('p', dto)])
    }

    /** The single mapped {@link StageDefinition} of {@link #mapOne}, for the success paths. */
    StageDefinition mappedStage(StageDto dto) {
        mapOne(dto).definition().stages()[0]
    }
}
