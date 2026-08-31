# Tasks: add-stage-finished-event

## 1. Domain: the StagePassed event

- [ ] 1.1 Add the sealed variant `EngineEvent.StagePassed(taskId, stage, advancedTo)` with
      blank-`taskId`/blank-`stage` validation matching the bookend records' style (FR1, D1,
      D2); verify with a Spock spec covering construction, validation failures, and
      `taskId()` — red first, then green, PIT-clean.
- [ ] 1.2 Emit `StagePassed` in `Engine.runStages` after `StageResult.Passed` returns, in all
      three exits — AUTO advance, MANUAL pause, final-stage completion — carrying the
      advanced-to position (FR1, FR2, D2); verify with engine specs asserting one emission
      per passing stage and the payload per exit, and update `EngineEvent`/`Engine` javadoc
      from "seven events" to eight with the traceability line `Implements FR1, FR2 of
      add-stage-finished-event`.
- [ ] 1.3 Pin the ordering and no-replay contract (FR1, FR2, NFR-R2): a recording-listener
      spec asserts persist → `AttemptFinished` → `StagePassed` on a passing round; specs
      assert zero `StagePassed` for a run starting at `PipelineEnd` and for a resume of a
      state whose persisted position already reflects an earlier pass; failing-run specs
      assert zero emissions.

## 2. Application: existing listener arms

- [ ] 2.1 Add the compiler-forced `StagePassed` arms per D5: an INFO line in
      `LoggingEventListener`; commented no-op arms in `MdcEventListener`,
      `StatusEventListener`, and `HeartbeatProgress` (FR1, D5); verify the whole build
      compiles and each listener's spec covers its new arm (log line asserted for the
      logging listener; unchanged state asserted for the no-ops), mutation gate green.

## 3. Application: webhook notifier and config

- [ ] 3.1 Add the typed `factory.notify.webhook` section (url, optional delivery timeout with
      default) to `FactoryProperties` via `FactoryPropertyDefaults`, with assembly-time URL
      validation: malformed or non-https fails naming the property (FR5, UX1, UX2, D6);
      verify with property-binding specs for absent section, valid section, malformed URL,
      and `http` scheme.
- [ ] 3.2 Implement the webhook notifier as an `EngineEventListener` (D3): filters
      `StagePassed` and `TaskFinished`, builds the self-sufficient JSON payload (task id,
      boundary kind, stage/outcome, advanced-to position), hands it to a fire-and-forget
      virtual thread posting through a fakeable HTTP door under the configured timeout
      (FR3, FR4, NFR-P1, UX3); verify with unit specs on a faked door: payload content per
      boundary, non-notified events ignored, prompt return (virtual time, no `system()` call
      in specs).
- [ ] 3.3 Enforce best-effort semantics (NFR-R1, NFR-O1): connection error, non-2xx, and
      timeout each log one WARN with task id, boundary, and reason, and are swallowed;
      success logs the delivery; verify with unit specs asserting logging and that the
      listener never throws past `onEvent`.
- [ ] 3.4 Apply the egress rules to the webhook destination (NFR-S1, D7): share the
      check/http guard's address-classing and redirect re-check rather than copying it —
      or, if extraction is disproportionate, declare the pair with `Kept in sync with`
      markers at both ends; enforce https, blocked address classes (webhook host as the one
      implicit allowlist entry), redirect re-check, response-size and total-time bounds;
      verify with specs refusing link-local/metadata/RFC1918 targets and a redirect into a
      blocked class before any connection.
- [ ] 3.5 Wire the notifier conditionally in `RunAssembler`: configured section adds it to
      the composite listener list, absent section wires nothing (FR3); verify with an
      assembly spec asserting presence/absence of the notifier per config.

## 4. Integration verification

- [ ] 4.1 WireMock end-to-end suite (M1, M2): a run through a passing stage delivers the
      `StagePassed` and `TaskFinished` POSTs with asserted JSON bodies; with the endpoint
      returning 500 and timeouts for every delivery, the run's outcome and persisted state
      equal the no-notifier baseline and each failure is a WARN line.
- [ ] 4.2 Traceability and gates sweep (M3): grep confirms every FR/NFR/UX of
      add-stage-finished-event is referenced by at least one spec or code unit; run the full
      `check` (Spotless, Error Prone, JaCoCo, PIT) green, with any new exemption justified
      in place per `.claude/rules/testing.md`.
- [ ] 4.3 Documentation: update `docs/glossary.md` if `StagePassed` warrants an entry
      (stage boundary terminology), and confirm the listener port's "observability, never an
      effect" contract is restated on the new variant's javadoc (D-risks); verify by review
      of the diff.
