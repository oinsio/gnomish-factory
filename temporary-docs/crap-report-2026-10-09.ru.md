# Отчёт CRAP: методы с CRAP > 8.0 — 2026-10-09

Источник: `./gradlew crapReport` (open-crap4j 1.0.0 по сводному отчёту JaCoCo всех 19 продуктовых модулей),
файл `build/reports/crap4j/crapReport/report.json`. Покрытие — по ветвлениям JaCoCo, CC — счётчик `COMPLEXITY`
из байткода (обычно выше, чем при подсчёте по исходникам).

Формула: `CRAP = CC² × (1 − покрытие)³ + CC`. Порог 8.0 — из crap4java (Uncle Bob); у самого инструмента
по умолчанию порог 15 и потолок CC 15 (по ним нарушений 4).

**Итого:** 44 из 4440 методов превышают 8.0.

## Плохо покрыты (покрытие < 70%) — балл поднимает покрытие

Здесь помогут тесты. Проверить в первую очередь: не исключены ли классы из PIT
(`excludedClasses`, `@DoNotMutate`); у конструкторов `<init>` — не остались ли без тестов ветки проверок аргументов.

| CRAP | CC | Покрытие | Метод | Где | Пакет |
|---:|---:|---:|---|---|---|
| 26.13 | 11 | 50% | `Reaper.repairInScope` | Reaper.java:209 | app.lease |
| 18.78 | 10 | 56% | `ObjectId.<init>` | ObjectId.java:18 | gitobjects |
| 18.44 | 13 | 68% | `BranchRepairLog.describe` | BranchRepairLog.java:73 | app.branch |
| 12.00 | 3 | 0% | `ConsoleTakeoverConfirmation.realTerminalAttached` | ConsoleTakeoverConfirmation.java:51 | app |
| 10.86 | 7 | 57% | `TakeResultDescription.describe` | TakeResultDescription.java:29 | app.take |
| 10.50 | 6 | 50% | `CommitMetadata.<init>` | CommitMetadata.java:21 | gitobjects |
| 10.50 | 6 | 50% | `CommitRequest.<init>` | CommitRequest.java:21 | gitobjects |
| 10.50 | 6 | 50% | `LedgerJsonMapper.toCategory` | LedgerJsonMapper.java:209 | serveobservability.json |
| 10.40 | 5 | 40% | `TaskBranchLister.outcomeLabel` | TaskBranchLister.java:156 | adapter.git |
| 8.30 | 6 | 60% | `GuardedHttpCheckExchange.isRedirect` | GuardedHttpCheckExchange.java:101 | adapter.check.http |

## Полный список

Для остальных 34 методов покрытие 75–100%, и CRAP почти равен CC: балл держится на сложности,
тесты его не снизят — только декомпозиция (пример: `RefNameSyntax.violation`, CC 18 при 100% покрытии).

| CRAP | CC | Покрытие | Метод | Где | Пакет |
|---:|---:|---:|---|---|---|
| 26.13 | 11 | 50% | `Reaper.repairInScope` | Reaper.java:209 | app.lease |
| 18.78 | 10 | 56% | `ObjectId.<init>` | ObjectId.java:18 | gitobjects |
| 18.44 | 13 | 68% | `BranchRepairLog.describe` | BranchRepairLog.java:73 | app.branch |
| 18.00 | 18 | 100% | `RefNameSyntax.violation` | RefNameSyntax.java:51 | baseref |
| 13.49 | 13 | 86% | `BranchShapeDiagnosis.phrase` | BranchShapeDiagnosis.java:30 | app.branch |
| 13.03 | 13 | 94% | `BaseRefresh.attempt` | BaseRefresh.java:87 | adapter.git |
| 13.00 | 13 | 100% | `AlertConditionEvaluator.evaluate` | AlertConditionEvaluator.java:70 | dashboard |
| 12.28 | 12 | 88% | `TrackerShape.recoveryOwner` | TrackerShape.java:82 | app.lease |
| 12.17 | 12 | 89% | `BranchShape.isClean` | BranchShape.java:145 | domain.branch |
| 12.17 | 12 | 89% | `BranchShape.tipCarriesState` | BranchShape.java:119 | domain.branch |
| 12.00 | 3 | 0% | `ConsoleTakeoverConfirmation.realTerminalAttached` | ConsoleTakeoverConfirmation.java:51 | app |
| 11.04 | 11 | 93% | `BranchShape.recoveryOwner` | BranchShape.java:76 | domain.branch |
| 11.03 | 11 | 94% | `TakeDispositionResume.routeByShape` | TakeDispositionResume.java:61 | app |
| 11.03 | 11 | 94% | `TrackerShape.isSteady` | TrackerShape.java:102 | app.lease |
| 11.02 | 11 | 94% | `BranchShape.disposition` | BranchShape.java:95 | domain.branch |
| 11.00 | 11 | 100% | `RunExitCodeMapper.getExitCode` | RunExitCodeMapper.java:58 | app |
| 10.86 | 7 | 57% | `TakeResultDescription.describe` | TakeResultDescription.java:29 | app.take |
| 10.50 | 6 | 50% | `CommitMetadata.<init>` | CommitMetadata.java:21 | gitobjects |
| 10.50 | 6 | 50% | `CommitRequest.<init>` | CommitRequest.java:21 | gitobjects |
| 10.50 | 6 | 50% | `LedgerJsonMapper.toCategory` | LedgerJsonMapper.java:209 | serveobservability.json |
| 10.46 | 10 | 83% | `GithubCommentBoundary.foldAbortStreakBeforeClaim` | GithubCommentBoundary.java:194 | adapter.tracker.github |
| 10.40 | 5 | 40% | `TaskBranchLister.outcomeLabel` | TaskBranchLister.java:156 | adapter.git |
| 10.27 | 9 | 75% | `RemoteBaseRef.read` | RemoteBaseRef.java:85 | adapter.git |
| 10.24 | 10 | 87% | `LedgerAggregator.dayRow` | LedgerAggregator.java:87 | dashboard |
| 10.00 | 10 | 100% | `RemoteAttemptDelivery.ensureDelivered` | RemoteAttemptDelivery.java:60 | adapter.git |
| 10.00 | 10 | 100% | `Subcommand.parse` | Subcommand.java:59 | app |
| 9.53 | 9 | 81% | `GithubMarker.parse` | GithubMarker.java:160 | adapter.tracker.github |
| 9.19 | 9 | 87% | `LawPathWalk.fileStatus` | LawPathWalk.java:123 | adapter.law |
| 9.11 | 9 | 89% | `DashboardAlertLabels.label` | DashboardAlertLabels.java:30 | dashboard |
| 9.04 | 9 | 92% | `RunSummaryAccumulator.record` | RunSummaryAccumulator.java:44 | serveobservability |
| 9.03 | 9 | 93% | `LawPathWalk.walk` | LawPathWalk.java:89 | adapter.law |
| 9.02 | 9 | 94% | `ReplicaPairReconciler.reconcile` | ReplicaPairReconciler.java:114 | adapter.git |
| 9.00 | 9 | 100% | `HttpConditionValidator.requirePairing` | HttpConditionValidator.java:58 | adapter.check.http |
| 9.00 | 9 | 100% | `TakeExitCodeMapper.exitCodeFor` | TakeExitCodeMapper.java:43 | app.take |
| 9.00 | 9 | 100% | `CharacterTable.isNeutralized` | CharacterTable.java:68 | untrustedtext |
| 8.30 | 6 | 60% | `GuardedHttpCheckExchange.isRedirect` | GuardedHttpCheckExchange.java:101 | adapter.check.http |
| 8.30 | 8 | 83% | `OperatorSources.location` | OperatorSources.java:149 | config |
| 8.19 | 8 | 86% | `EgressGuard.ensureRunning` | EgressGuard.java:93 | sandbox.environment |
| 8.19 | 8 | 86% | `SandboxLifecycleDecision.handleContainerState` | SandboxLifecycleDecision.java:105 | sandbox.environment |
| 8.04 | 8 | 92% | `GithubDesignatorRules.from` | GithubDesignatorRules.java:56 | adapter.tracker.github |
| 8.03 | 8 | 92% | `GitObjectsLawSource.list` | GitObjectsLawSource.java:92 | adapter.law |
| 8.03 | 8 | 92% | `WorkingTreeLawSource.list` | WorkingTreeLawSource.java:64 | adapter.law |
| 8.03 | 8 | 92% | `SummaryAccumulatorListener.onEvent` | SummaryAccumulatorListener.java:67 | status |
| 8.02 | 8 | 93% | `PinCheckedExternalCheckClient.poll` | PinCheckedExternalCheckClient.java:99 | adapter.check |

## Как воспроизвести

```bash
./gradlew crapReport
```

Запускать отдельно: прогон с `--tests …` в той же команде подменяет покрытие модуля покрытием одной спеки
и раздувает число нарушений.
