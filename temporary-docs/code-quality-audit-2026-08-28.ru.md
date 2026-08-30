# Аудит качества кодовой базы gnomish-factory

Дата: 2026-08-28. Охват: production-код (`*/src/main`, ~1020 файлов Java).
Четыре направления: дублирование/размазывание логики, мёртвый код, антипаттерны,
недостающие паттерны. Файл — рабочая заметка, не постоянный артефакт проекта
(язык документации проекта — английский; этот отчёт написан по-русски по запросу).

---

## Общий вывод

Кодовая база в целом дисциплинированная: слоение чистое (ноль импортов
domain→adapter/Spring/Jackson, ноль application→adapter), домен не анемичный,
sealed-иерархии с исчерпывающими switch, Null Object (`ClaimBeat.NONE`,
`ReaperDuty.NONE`), Composite-обсерверы и таблицы восстановления
(`BranchShape`/`TrackerShape`) применены образцово. Статического мутабельного
состояния нет, catch-and-ignore без объяснений нет.

Но найдены:

- **один реальный баг конкурентности** (небезопасная публикация полей);
- **два уже случившихся расхождения ручной синхронизации «двойников»
  host/container** с последствиями для корректности;
- системная проблема: правило «≤200 строк на файл» соблюдается механическим
  разрезанием классов — связность не падает, а прячется (конструкторы на
  27 параметров, пары классов с открытыми друг другу полями).

---

## 1. Критичное — исправить в первую очередь

### 1.1. Гонка публикации в `TakeSlotRunner`

`application/.../app/serve/TakeSlotRunner.java:61-63` — три не-`volatile` поля
(`drainReport`, `ledgerWriter`, `runSummaryAccumulator`) записываются методами
`attach*` в потоке сборки (`ServeRuntimeAssembly.java:141`), а читаются в
`run(TaskRef)` (`:174`, `:180`) в другом виртуальном потоке слота (запуск в
`FeedCycle.java:144`). Happens-before между записью и чтением нет.

Последствие: молча пропавшие строки ledger `taskOutcome` и `runSummary` — та
самая наблюдаемость, по которой оператор судит о здоровье демона; сбой никогда
не будет громким. Остальной модуль корректно использует `volatile`
(`ForwardingDirtyNotifier:22`, `FeedViewTracker:20`, `SnapshotWriter:44`) — это
единичный пропуск. Исправление — одна строка (`volatile` на три поля).

### 1.2. Два расходящихся варианта проверки границы `.gnomish-task/`

Правило «гном не трогает `.gnomish-task/`» реализовано дважды, по одному на
режим исполнения, и копии **расходятся по существу**:

- `adapters/git/.../RoundBoundaryCheck.java:97-101` (host): отвергает любые
  изменения под `.gnomish-task/`, включая decision-файл; **exit code
  `git diff` не проверяется** — упавший diff читается как «чисто».
- `adapters/git/.../HarvestedBoundaryCheck.java:51-67` (container): разрешает
  decision-файл раунда (FR23); exit code проверяется; текст ошибки другой.

Если host-режим когда-нибудь переведут на decision-файл внутри
`.gnomish-task/decisions/` (для паритета с container), host начнёт бросать
`RoundBoundaryViolationException` на каждом раунде. Ошибка в самом вызове diff
громкая в одном режиме и немая в другом.

**Рекомендация:** одно правило `TaskDirBoundaryRule(previousTip, endRef,
AttemptKey)`, всегда разрешающее ровно `decisionPath(key)`; проверка exit code
в обоих путях.

### 1.3. Container-salvage не ставит claim-epoch trailer

`adapters/git/.../EnvironmentSalvage.java:65-73` формирует salvage-коммит без
`ClaimEpochTrailer.stamp(...)`. Host-вариант (`WorktreeSalvage.java:64-72`) и
все остальные писатели коммитов trailer ставят (`GitAttemptPersistence:104`,
`EnvironmentAttemptPersistence:131`, `CleanupCommit:48`,
`TaskLifecycleCommitWriter:136`, `GitTaskRepository:210`); читатели на него
полагаются (`GitShowTip:43`, `BareObjectsTipSource:48`, `BranchTipSource:30`,
FR13).

Последствие: salvage-коммит из контейнера становится tip ветки без epoch, и
реконсиляция tip-epoch↔live-epoch (`BranchTipFactsReader`) видит
`tipEpoch == null` на tip, который на самом деле создал живой claim.

Вторичные расхождения той же пары: host охраняет `restoreFactoryFiles()`
проверкой `cat-file -e HEAD:.gnomish-task`, container — нет; container
отключает hooks (`-c core.hooksPath=`), host — нет.

### 1.4. «Резолв tip ветки» написан 7 раз; в 3 копиях игнорируется exit code

Корректные копии: `LocalBranchTip.java:35-36`,
`ReplicaPairReconciler.java:153-156`, `ContainerRunTermination.java:193-197`.
Копии с проигнорированным exit code, возвращающие `""` при сбое `git
rev-parse`: `EnvironmentAttemptPersistence.java:205-209`,
`MidRoundHarvestListener.java:120-124`, `EnvironmentRoundSnapshot.java:85-89`.

Пустая строка — load-bearing: `EnvironmentAttemptPersistence:101` сеет
`previousTip` и передаёт его в `HarvestedBoundaryCheck.verify(...)`;
`MidRoundHarvestListener:113` по `tip.equals(lastObservedTip)` решает, пушить
ли. Вкупе с 1.2 сбой git может вообще не всплыть.

Также `"refs/heads/" + branch` конкатенируется в 8+ местах вместо одной
константы/функции.

**Рекомендация:** единый `BranchTip.resolve(runner, repo, ref) →
Optional<String>` с `--verify` и проверкой exit code.

---

## 2. Размазывание логики

### 2.1. Семейство «двойников» host/container — главный системный риск

~12 пар классов синхронизируются вручную:

| Host | Container |
|---|---|
| `TakeFreshClaim` | `TakeContainerFreshClaim` |
| `TakeEngineExecution` | `TakeContainerEngineExecution` |
| `TakeResumeBootstrap` | `TakeContainerResumeBootstrap` |
| `TakeResumeRunner` | `TakeContainerResumeRunner` |
| `GitModeRunner` | `ContainerGitModeRunner` |
| `GitResumeRunner` | `ContainerResumeRunner` |
| `GitAttemptPersistence` | `EnvironmentAttemptPersistence` |
| `WorktreeSalvage` | `EnvironmentSalvage` |
| `RoundBoundaryCheck` | `HarvestedBoundaryCheck` |
| `GitTaskRepository` | `GitObjectsTaskRepository` |
| `HostRoundEnvironmentSource` | `SandboxRoundEnvironmentSource` |
| `HostResumeMechanics` | `ContainerResumeMechanics` |

Пункты 1.2 и 1.3 — уже случившиеся сбои этой ручной синхронизации. Только
последняя пара спрятана за настоящей абстракцией — `ResumeMechanics<B>`
(`application/.../app/ResumeMechanics.java`); её javadoc прямо объясняет, зачем:
обе ветки получают одну и ту же routing table, и ветка не может существовать
только в одном режиме. Это образец, на который стоит перевести остальных.

Конкретно: `TakeWorkRouter.resume()` (`:125`) уже использует Strategy, а
`TakeWorkRouter.freshClaim()` (`:70`) — switch на два статических вызова с 14 и
15 позиционными аргументами (`TakeFreshClaim.claim:46`,
`TakeContainerFreshClaim.claim:31`), тела которых — один и тот же рецепт
(harden → synthesize → createTask → build execution → run). Плюс два таких же
ad-hoc switch в `ManualRunDrive.java:59,93`.

**Рекомендация:** расширить `ResumeMechanics<B>` до полного `TakeMechanics<B>`
(Strategy + Template Method); решение о режиме схлопывается с 4 точек до 1.

### 2.2. Протокол `GNOMISH_DECISION_FILE` реализован дважды в разных модулях

- `adapters/agent/.../DecisionFileTransport.java:37-41,124-126` (host): temp-dir,
  **без cap'а размера**;
- `adapters/git/.../BranchDecisionFile.java:37-74` (container):
  `.gnomish-task/decisions/<stage>-a<n>.json`, `SIZE_CAP = 1 MiB`.

Оба объявляют собственную константу `ENV_VAR = "GNOMISH_DECISION_FILE"`
(разные Gradle-модули, компилятор рассинхронизацию не поймает). Переименование
в одном модуле → агент во втором режиме пишет решение, которое никто не читает,
и раунд деградирует до «нет решения» вместо ошибки.

**Рекомендация:** общий тип (env-переменная + cap + контракт `Handle`), два
режима — две стратегии над ним.

### 2.3. Пути `.gnomish-task/*` продублированы в ~17 файлах двух модулей

`FactoryOwnedPaths.java:25` объявляет себя «единственным списком владения
`.gnomish-task/`», но его используют только 2 вызывающих
(`EnvironmentSalvage:66`, `WorktreeSalvage:87,99`). Остальные ~15 мест — сырые
литералы: `BranchStateReader:47-48`, `DeliveredBranchReader:41-42`,
`TaskBranchLister:30-31`, `BranchTipFactsReader:34-35`,
`TaskLifecycleCommitWriter:42-44`, `GitTaskStore:30,66,72`,
`GitTaskRepository:179-193`, `ContainerRunTermination:145,164` (другой модуль!)
и т.д. Вдобавок приватный хелпер `show(cloneDir, ref, filePath)` скопирован
дословно в 3 классах (`BranchStateReader:95-101`, `DeliveredBranchReader:90-96`,
`TaskBranchLister:100-106`).

**Рекомендация:** сделать `FactoryOwnedPaths`/`TaskStatePaths` действительно
единственным источником; удалить три копии `show()`.

### 2.4. `requireNonBlank` скопирован ~35 раз в 6 модулях

Побайтово идентичное тело в ~28 файлах domain, gnomish-plugin-api, application,
adapters/agent + `requireNonEmpty` дважды в sandbox/core. Уже существуют
4 несовместимые сигнатуры (возвращающая/void, с параметром-именем/без).
Негде централизованно поменять политику (тип исключения, формат сообщения,
доп. проверки).

**Рекомендация:** один `Preconditions.requireNonBlank(value, owner, component)`
в модуле, достижимом из domain, plugin-api и sandbox; механическая замена.

### 2.5. Три парсера длительностей, две несовместимые грамматики

- `RoundTimeout.java:63-69` — ISO-8601 с домысленным `PT`; пустое → `PT0S`;
  мусор → **молча** дефолт;
- `AgentSettingsValidator.java:140-153` — дословная копия тех же двух строк
  (javadoc признаёт ручную синхронизацию), но пустую строку **отвергает** —
  валидатор и резолвер уже расходятся;
- `DurationConfig.java:56-83` — совсем другая грамматика (`30s`, `15m`, `2h`,
  `500ms`, `7d`) для остальных YAML-полей.

В одном семействе `stage.yaml`: `timeout: 30s` валиден, `roundTimeout: 30s`
работает по случайности (uppercase), `roundTimeout: 500ms` → `PT500MS` →
ошибка парсинга → молчаливый дефолт, при том что валидатор его отверг.

**Рекомендация:** один `Durations.parse(String)` с обеими грамматиками;
валидатор = `tryParse(...).isPresent()`.

### 2.6. Четыре параллельных иерархии терминальных исходов + два enum

`TaskOutcome` (domain) / `TaskOutcomeDto` (wire) / `RecordedOutcome` (порт) /
`status.Outcome` (отчёт) — одни и те же четыре варианта
`Completed/Paused/Escalated/Aborted`; плюс `RecordedTerminal` и
`TaskLifecycleEvent`. Ручные исчерпывающие switch — в 16 файлах. Добавление
пятого исхода = 4 иерархии + 2 enum + 16 switch + `ServiceCommitMessages.eventName`;
switch поймает компилятор, но ошибки *маппинга между* иерархиями — нет.

Быстрые победы:
- `RecordedOutcome` и `status.Outcome` идентичны по полям — схлопнуть;
- маппер `TaskOutcome → TaskLifecycleEvent` дословно утроен в
  `GitTaskRepository:216-223`, `GitObjectsTaskRepository:193-200`,
  `PushBestEffortTaskRepository:84-91` — вынести в
  `TaskLifecycleEvent.of(TaskOutcome)`.

### 2.7. Wire-словари ledger/snapshot определены дважды без compile-time связи

Writer и reader одного JSON-контракта — две ручные таблицы строк в разных
пакетах: `TaskOutcome` (`LedgerJsonMapper:188` ↔ `LedgerAggregator:151`),
`SweepVerdictCategory` (`LedgerJsonMapper:177`, 6 значений ↔
`SweepActionAggregator:108`, **только 3** — уже живое расхождение), `FeedPhase`,
`HeartbeatState`, `LifecycleState` (`SnapshotJsonMapper` ↔ `SnapshotJsonReader`).

**Рекомендация:** wire-кодек на enum'е (`String wire()` +
`static fromWire(String)` над одной Map) + property-тест round-trip.

### 2.8. Прочее дублирование (средне/низко)

- **6 ручных экспоненциальных backoff** (`GitInfrastructureRetry:52`,
  `TerminalWriteRetry:66`, `RestartBackoff:37`, `BackoffPolicy:55`,
  `FeedOutageRetry:63`, `GithubRetryConfig:50`). У них по-настоящему разная
  семантика завершения и тестируемость через `Sleeper`/`Clock` — полная
  унификация под Resilience4j не нужна; достаточно общего value-объекта
  `Backoff`, заодно чинящего переполнение `1L << (count-1)` в
  `BackoffPolicy.delay` при `count >= 64`.
- **Twin-классы дренажа потоков**: `subprocess/Drain.java` ↔
  `sandbox/docker/ExecPipeDrain.java` (структурно идентичны;
  `ExecPipeDrain.join()` не ограничен по времени — ровно тот hang, от которого
  `Drain.join(bound)` защищён) + ещё два варианта (`GitExecStreams.drain` на
  platform-потоке без синхронизации, `adapters/agent/StreamDrain`).
- **Парсинг массива комментариев GitHub скопирован 3–5 раз**
  (`GithubCommentParser:28`, `GithubClaimComment:63` — теряет cause,
  `GithubCommentThread:44`; ещё копии в `GithubClaimWindow:110`,
  `GithubCommentUpsert:121`); ни одна копия не null-чекает `comment.get("body")`.
- **19 независимых `new ObjectMapper()`** (17 в adapters/github); «единственный
  настроенный» `TaskStateJson.mapper():37-39` создаёт новый экземпляр на каждый
  вызов при 7 вызывающих.
- **Cap чтения 1 MiB объявлен в 6 местах** двумя разными написаниями
  (`1L << 20` и `1024 * 1024`); `BareObjectsTipSource:20` даже комментирует
  «тот же потолок, что у остальных» — комментарий там, где должна быть
  константа.
- **`Verdict → CheckDto` написан трижды** (`StateJsonMapper:172`,
  `status/json/AttemptMapper:50`, `usage/json/UsageReportJsonMapper:94`); два
  `CheckDto` идентичны поле-в-поле.
- **Цикл «пройди verify-лист стадии, выбери вариант X» написан 5 раз**
  (`ReferencedFiles:82`, `AgentSettingsValidator:89`,
  `ExternalCheckSeamValidator:61`, `PipelineLawReader:59`,
  `CheckProviderSeam:128`); литерал `"stages/%s/stage.yaml"` — в 10 файлах.

---

## 3. Мёртвый код

Контекст: значительная часть «неиспользуемого» — это незакоммиченная работа
текущей ветки `harden-task-branch-contract`; такие находки — «ещё не
подключено», а не мёртвое, но стоит сверить с tasks change'а.

### 3.1. Достоверно мёртвое / test-only в закоммиченном коде

- **`StatusSnapshotHolder.updateAttemptLimit(int)`**
  (`application/.../status/StatusSnapshotHolder.java:75`) — javadoc (`:25`)
  утверждает, что вызывается «при переходе на новую стадию»; вызывающего не
  существует. Либо мёртвый метод, либо **баг пропавшей проводки** (attempt
  limit в статусе не обновляется). Проверить руками до удаления.
- **`FactoryProperties.agentCliEnvPassthrough`** (`FactoryProperties.java:69`) —
  биндится, дефолтится, валидируется, нигде не читается; собственный javadoc
  говорит «superseded and ignored». Удаление — вопрос совместимости конфигов.
- **`InMemoryTrackerHarness`** (в `src/main` модуля adapters) — используется
  только 20 spec-файлами; в production — только `{@link}`. Перенести в
  `test-fixtures`.
- Публичные методы только с тестовыми вызовами: `ExecutorUsage.totalTokens()`
  (:102), `StatusTextRenderer.renderAttemptSummary` (:62),
  `SweepVital.keptTruncated()` (:59), `PipelineLaw.ofContent(Map)` (:56),
  `GithubLabelOps.addLabel` (:47 — сделать private),
  `ContainerEnvironments.baseKey()`/`scrubsCredential()` (:160/:170),
  `EnvironmentLease.currentIfLeased()` (:96). Большинству — сузить видимость,
  не удалять.

### 3.2. Не подключено в незакоммиченной работе (сверить с планом change'а)

- `application/.../app/branch/BranchRepairLog.java` — весь класс без
  production-ссылок (не bean, не в auto-config imports);
- `BranchShape.disposition()` + весь enum `RecoveryDisposition` — ноль
  production-вызовов; `recoveryOwner()`/`isClean()` достижимы только из
  `BranchRepairLog`;
- `TrackerShape.isSteady()`, `TrackerShape.recoveryOwner()` + весь enum
  `TrackerRecoveryOwner` — только тесты;
- `EffectDelivery.settled()` (:30) — не вызывается даже тестами production-пути.

### 3.3. Проверено и чисто

Enum-константы (все производятся/матчатся), поверхность `gnomish-plugin-api`
(вся потребляется; самые тонкие — `AttemptCommitWorkspace`, 2 ссылки),
неиспользуемые top-level типы (все кандидаты — ServiceLoader/AutoConfiguration
записи), пары `FeedState`↔`FeedPhase` и `HeartbeatWorkerState`↔`HeartbeatState`
(намеренная межслойная дупликация, обе половины живые), config-свойства (все,
кроме 3.1, читаются).

Пограничное: 31 из 68 `*Exception` нигде не ловится по собственному типу —
все fail-fast; ценность ~30 почти одинаковых классов исключений — дизайнерское
решение, не находка статанализа.

---

## 4. Антипаттерны

### 4.1. Композиционный шов (bootstrap + application/app) — главный очаг

- **`ManualRunRunner`** (`bootstrap/.../ManualRunRunner.java:160`) — конструктор
  на **27 коллабораторов**, тело 71 строка, файл 278 строк (лимит 200). Spring
  резолвит все 27 по типу; соседние одинаково типизированные `Path`
  (worktreesRoot/homeDir) переставляются без ошибки компиляции.
- **`ServeRuntimeAssembly.assemble`** (:65) — 18 параметров, 86 строк (самый
  длинный метод в кодовой базе); среди параметров два разных типа `Clock`
  рядом. Заканчивается пост-конструкционным `attach*` (см. 1.1).
- **`SubcommandDispatchFactory.of`** (:47) — 18 параметров, всё тело —
  переброска; собственный javadoc признаёт «extracted for file size» — резали
  ради лимита строк, не ради ответственности.
- Родня: `TakeCommandFactory.of` (12), `TakeOutcomeDispatch.dispatch` (11),
  `TakeClaimAndWork` ctor (11), `ManualRunAssembly` (11/9).
- **Транзитный груз**: `credentialEnvVarsToScrub` — 72 вхождения в 22 файлах,
  `abortThreshold` — в 17; в большинстве — чистая переброска.

**Рекомендация:** parameter object `TakeContext` (assembly, git, worktreesRoot,
abortHandler, abortThreshold, credentialEnvVarsToScrub, claimLossFlag,
containerTakeSupport) — прецедент уже есть (`EnginePorts`, `TakeClaimAndWork`
передаётся целиком). Делать после 2.1 — Strategy сначала сузит поверхность.

### 4.2. `ContainerRunSupport` + `ContainerRunTermination` — один класс в двух файлах

`ContainerRunSupport.java:62-73` — 13 package-visible полей;
`ContainerRunTermination` (198 строк static-методов) читает их 27 раз;
делегация циклическая (`support.keepStopped()` →
`ContainerRunTermination.keepStopped(this)` → чтение полей support обратно).
`ContainerRunSupport` при этом всё равно 309 строк — крупнейший файл репо.
Инкапсуляция ordering-инвариантов терминальной границы (D19) не обеспечивается.

**Рекомендация:** либо слить пару обратно и разрезать по ответственности, либо
дать `ContainerRunTermination` узкий конструкторно-внедряемый интерфейс.

### 4.3. Primitive obsession: `String taskId` / `String branch` в ~410 сигнатурах

Сырой tracker-id и санитизированное имя ветки — разные строки одной задачи,
обе `String`, текут через одни сигнатуры. Худшая точка:
`ReplicaPairReconciler.reconcile(String taskId, String branch)` (:78) —
перестановка компилируется и реконсилирует не ту ветку. Концентрация:
`GitTaskBranches` (8 методов `(Path, String taskId)`),
`TaskLifecycleCommitWriter` (9 методов). Value-тип уже есть (`TaskRef`,
`GithubTaskId`), конверсия `TaskIdSanitizer.branchName` вызывается ad hoc в
~12 местах.

**Рекомендация:** value-типы `TaskId`/`BranchName`; первые точки конверсии —
`ReplicaPairReconciler` и `GitTaskBranches`.

### 4.4. Обработка исключений

- `catch (Throwable)` в `TakeSlotRunner:183` и `StandingReaper:110,143` —
  намерение («упавший слот не валит демон») верное для `RuntimeException`, но
  `Throwable` глотает OOM/StackOverflow/LinkageError: демон после OOM
  продолжает «принимать» слоты и вечно логирует пер-слотовые ошибки. Сузить до
  `Exception` (или перебрасывать `Error`).
- `catch (Exception)` в `JudgeVerdictExtractor:75` — ожидается только
  `JsonProcessingException`; NPE экстрактора превращается в правдоподобный
  вердикт «агент написал плохой JSON» и уходит в retry вместо отчёта о баге.
  Единственный широкий `catch (Exception)` в кодовой базе.

### 4.5. Среднее/низкое

- **I/O в конструкторах**: `GitAttemptPersistence:76` форкает `git rev-parse`;
  `ContainerRunSupport:96-98` открывает `GitObjects` и тянет
  `System.getProperty("java.io.tmpdir")` (скрытая глобальная зависимость,
  продублирована в `RunAssembler:232`, `ContainerRunSupportFactory:87`).
- **Мутабельные не-`volatile` поля-курсоры** на классах рядом с конкурентными
  slot-потоками: `ContainerEnvironments:51 restoredCursor`,
  `GitAttemptPersistence:58 previousTip`,
  `EnvironmentAttemptPersistence:72 previousTip`,
  `MidRoundHarvestListener:58`, `MidRoundPushListener:48`. Если экземпляр
  когда-либо разделяется между слотами — сравнение со stale tip.
- **Temporal coupling** `start()`-до-использования: `ObservabilityWiring:75`,
  `StandingReaper:77`, `SandboxLifecycleTick:60`, `WorktreeJanitor:92`,
  `SnapshotWriter:75` — «сконструирован, но не запущен» = молчаливый no-op.
- **Boolean-флаги**: главное — `StatusRenderer.render(boolean json)` (:21) —
  флаг формата зашит в контракт порта; третий формат = слом всех реализаций.
- **Прямые `System.out/err`** в ~10 файлах application-слоя при наличии порта
  `ConsoleIO` — единственная брешь в иначе полной дисциплине (проект даже
  `System.exit` заменяет исключениями кода выхода).
- **27 файлов сверх лимита 200 строк**; значимые уже покрыты выше
  (`ContainerRunSupport` 309, `ManualRunRunner` 278), остальные — в основном
  мапперы данных.

### 4.6. Проверено и чисто

Нарушений слоёв нет (импорты application → `gnomish.sandbox` — только value/port
типы sandbox/core, легитимно); домен не анемичный; singleton'ов и статического
мутабельного состояния нет; enum/instanceof-цепочек вместо полиморфизма нет
(везде record-деконструкция и sealed-switch); catch-and-ignore без комментария
нет; исключения кода выхода — задокументированная альтернатива `System.exit`,
приемлемо.

---

## 5. Недостающие паттерны (ранжировано по ценности/усилию)

1. **`TaskLifecycleEvent.of(TaskOutcome)`** вместо утроенного `eventFor`
   (тривиально; см. 2.6).
2. **Default-аксессоры на sealed-типах** вместо повторных switch:
   `TakeResult.finalState()`/`category()` (5 switch-мест, из них
   `RunSummaryAccumulator:43` и `TaskOutcomeLineAssembler:54` выводят одно и то
   же); `DaemonSnapshotView.snapshot()`
   (`DashboardStatusCardRenderer:120`). Идиома уже есть в кодовой базе —
   `BranchShape.recoveryOwner()`.
3. **Общий `CheckDto` + одна фабрика `Verdict`→DTO** (см. 2.8).
4. **Wire-кодеки на enum'ах** (см. 2.7) — закрывает реальную дыру молчаливого
   дрейфа.
5. **`PipelineChecks.locate(pipeline, Type.class)`** — хелпер обхода
   verify-листов + одна точка для литерала `"stages/%s/stage.yaml"` (см. 2.8).
6. **`TakeMechanics<B>`** — Strategy + Template Method для fresh-claim-пути
   (см. 2.1) — крупнейший структурный выигрыш.
7. **`TakeContext`** parameter object (см. 4.1) — после п. 6.
8. Опционально: `FeedViewTracker` как subject + `FeedStateLogger` как listener
   (`FeedAutomaton:180-197` — каждый переход обновляет двоих вручную);
   реестр правил в `PipelineValidator:59-67` — только если набор правил
   продолжает расти.

Расхождение аудитов про retry: предложение «всё под Resilience4j» отвергнуто —
у циклов по-настоящему разная семантика завершения и тестируемость через
`Sleeper`/`Clock`, которую Resilience4j ухудшил бы. Достаточно общего
value-объекта `Backoff` (см. 2.8).

Over-engineering не найден: все интерфейсы с одной реализацией — функциональные
швы с Null Object, используемые для тестовой подмены.

---

## 6. Рекомендуемый порядок действий

1. **Точечные фиксы** (мелко, реальные дефекты): `volatile` в `TakeSlotRunner`;
   `catch (Throwable)` → `catch (Exception)` в `TakeSlotRunner`/`StandingReaper`;
   `catch (Exception)` → `catch (JsonProcessingException)` в
   `JudgeVerdictExtractor`.
2. **Проверить `StatusSnapshotHolder.updateAttemptLimit`** — мёртвый код или
   пропавшая проводка.
3. **Change «унификация host/container границы»**: единое правило
   `.gnomish-task/`-границы (1.2), epoch-trailer в container-salvage (1.3),
   единый `BranchTip.resolve` с exit code (1.4). Самый значимый для
   корректности блок.
4. **Change «decision-file протокол в одном месте»** (2.2).
5. **Структурный change**: `TakeMechanics<B>` + `TakeContext` (2.1, 4.1) —
   схлопывает god-конструкторы и половину семейства двойников.
6. **Механическая уборка**: `requireNonBlank` в одно место (2.4),
   `TaskStatePaths` (2.3), схлопнуть `RecordedOutcome`/`status.Outcome` и
   утроенный `eventFor` (2.6), wire-кодеки (2.7), общий `CheckDto`,
   `Durations.parse` (2.5), перенос `InMemoryTrackerHarness` в `test-fixtures`,
   сужение видимости test-only методов (3.1), value-типы `TaskId`/`BranchName`
   (4.3).

Пункты 3–5 тянут на полноценные OpenSpec-change'и; 1–2 и 6 — мелкие
исправления.

---

## 7. Непокрытые направления — какие проверки стоит сделать дальше

Проведённые аудиты — «статика» кода. Ниже направления, которые они не
покрывают, в порядке ценности для этого проекта.

### 7.1. Конкурентность и виртуальные потоки (приоритет 1)

Гонка в `TakeSlotRunner` (1.1) найдена попутно, не систематическим поиском.
Целевой аудит всего `serve`-пути:

- какие объекты разделяются между slot-потоками, где мутабельные поля без
  синхронизации — уже есть пять подозрительных «курсоров»
  (`ContainerEnvironments.restoredCursor`,
  `GitAttemptPersistence.previousTip`,
  `EnvironmentAttemptPersistence.previousTip`,
  `MidRoundHarvestListener.lastObservedTip`,
  `MidRoundPushListener.lastObservedTip`, см. 4.5);
- interrupt-протокол при остановке демона: доходит ли прерывание до всех
  ожиданий, корректно ли восстанавливается флаг;
- pinning виртуальных потоков: нет ли `synchronized` вокруг
  subprocess/блокирующего I/O.

Для оркестратора, где вся ценность — в корректности параллельных слотов, это
направление №1.

### 7.2. Соответствие crash-consistency контракту (приоритет 2)

У проекта есть собственный жёсткий стандарт (`.claude/rules/crash-consistency.md`,
ADR 0003). Проверить реализацию против его чек-листа:

- все ли многошаговые переходы в `adapters/git` следуют порядку
  intent → effect → receipt;
- каждое ли kill-окно классифицируется в именованную shape с одним владельцем
  восстановления;
- существует ли kill-point-матрица спеков для каждого перехода (правило её
  требует);
- «конструктивное до деструктивного» — порядок cleanup-шагов.

Находка 1.3 (отсутствующий epoch-trailer в `EnvironmentSalvage`) намекает, что
расхождения между декларируемым контрактом и кодом уже есть.

### 7.3. Безопасность (приоритет 3)

Конкретные поводы из проведённых аудитов:

- `EnvironmentSalvage.commitScript()` (:65-73) строит shell-скрипт
  конкатенацией строк — метасимволы в путях/taskId = инъекция в shell внутри
  контейнера;
- `"refs/heads/" + branch` конкатенируется в 8+ местах (1.4) — проверить, что
  branch везде проходит через `TaskIdSanitizer`;
- полнота `credentialEnvVarsToScrub`: не утекают ли секреты в логи, коммиты,
  env контейнера;
- обращение с токенами tracker'а.

Готовый инструмент: `/security-review` для текущей ветки.

### 7.4. Качество тестов, а не только их наличие

Планка PIT — 100%, но есть три категории исключений (`@DoNotMutate`,
`excludedClasses`, `excludedTestClasses`, см. `.claude/rules/testing.md`).
Проверить:

- каждое исключение реально соответствует своему письменному критерию
  (например, «named covering suite» существует и покрывает заявленные
  сценарии);
- не разрослись ли исключения тихо;
- traceability: у каждого FR активных change'ей есть хотя бы одна реализующая
  сущность (правило `traceability.md` само предлагает grep-верификацию).

### 7.5. Ресурсы и таймауты на границах процессов

Проект живёт на `ProcessBuilder` и Docker:

- закрываются ли потоки процессов во всех путях ошибок;
- у каждого ли subprocess/HTTP-вызова есть таймаут — аудит уже нашёл
  неограниченный `ExecPipeDrain.join()` (2.8);
- судьба осиротевших контейнеров и worktree при аварийном завершении;
- утечки файловых дескрипторов в долгоживущем `serve`.

### 7.6. Дрейф документации

- glossary против кода: домен именуется по глоссарию, banned-синонимы не
  встречаются (правило `process-invariants.md`);
- ADR против реализации;
- README / operator-guide против фактического CLI.

Дёшево проверяется; для проекта, где «Controls are data», рассинхрон
документации — функциональный баг.

### 7.7. Консистентность OpenSpec-артефактов текущей ветки

Готовые скиллы: `review-artifacts` (свежесть артефактов против кода) и
`audit-implementation` (полнота реализации по tasks). Аудит мёртвого кода нашёл
неподключённый кластер `BranchRepairLog` / `TrackerShape` в незакоммиченной
работе (3.2) — `audit-implementation` по `harden-task-branch-contract` ответил
бы, ожидаемо это или пропуск.

### Что сейчас делать не стоит

- **Аудит производительности** — для оркестратора с редкими задачами это не
  узкое место.
- **Аудит зависимостей/CVE** — уже закрыт в CI (OSV-Scanner, CodeQL).

---

## 8. Изменения процесса по итогам аудита (внесены 2026-08-28)

По итогам обсуждения часть находок переведена из разовых исправлений в
постоянные проверки. Принцип отбора: в per-change аудит попадают только
**классовые процедуры** (применимые к любому будущему дифу), а не перечни
конкретных экземпляров; перечни либо умирают после разовой миграции, либо
низводятся до примеров жанра.

### 8.1. Новое правило `.claude/rules/manual-sync-pairs.md`

Причина: пары «двойников» (2.1) возникают закономерно — второй режим исполнения
приходит позже первого и копируется (git-история подтверждает: host-версии
19–28 июля, container-двойники 13–22 августа), абстракция дозревает только у
третьего потребителя (`ResumeMechanics<B>` появился в день рождения
`TakeContainerFreshClaim`), а agentic-разработка с чисткой контекста делает
копирование режимом по умолчанию. Запретить пары нельзя — можно сделать их
видимыми и проверяемыми в момент рождения.

Содержание правила:

- порядок предпочтения: общая абстракция → задекларированная пара →
  незадекларированная (запрещена); третья реализация одного правила обязана
  извлечь абстракцию (правило трёх);
- greppable-маркер: точная фраза `Kept in sync with` + `{@link}` в javadoc
  **обоих** концов, с указанием синхронизируемого инварианта;
- стартовый реестр из 16 известных пар — строки удаляются по мере простановки
  маркеров или схлопывания в абстракцию; до тех пор аудит считает их
  задекларированными.

### 8.2. Правки `/audit-implementation` (`.claude/commands/audit-implementation.md`)

В шаг 5 (project-rule conformance) добавлено:

- `crash-consistency.md`: переход с 2+ durable-шагами без ответов на чек-лист
  правила и без kill-point-спеков — ❌ CRITICAL;
- `manual-sync-pairs.md`: диф, трогающий один конец пары (по маркеру или
  реестру), обязан менять второй конец либо письменно объяснять, почему нет;
  новая вторая реализация существующего правила — абстракция или декларация
  пары; третья — только абстракция.

В шаг 6 (code quality) добавлено:

- **reinvention check** (классовая замена списку «горячих точек» из 2.3/2.4/2.8):
  каждый новый приватный хелпер, именованная константа и повторяющийся
  строковый литерал из дифа греппится по кодовой базе; существующий эквивалент
  → «переиспользуй или обоснуй локальную копию»;
- три механических класса риска: непроверенный exit code у новых
  subprocess-вызовов (класс находки 1.4), новые не-`final` не-`volatile` поля
  на классах в конкурентных слотах и attach-после-конструктора (класс находок
  1.1/4.5), новые `catch (Throwable)`/`catch (Exception)` без письменного
  обоснования (класс находки 4.4).

В CLAUDE.md добавлена строка про `manual-sync-pairs.md` в таблицу Process Rules.

### 8.3. Сознательно НЕ добавлено в per-change аудит

- глобальные проходы (мёртвый код, дублирование, конкурентность по всей базе,
  ревизия всех PIT-исключений, полный дрейф документации) — не диф-скоупятся;
  их место — периодическая команда уровня `/audit-codebase` (не создана,
  кандидат на будущее) или ручные сессии как эта;
- аудит недостающих паттернов — judgment-heavy рекомендации по рефакторингу,
  на каждом change генерировали бы шум, не относящийся к готовности к архивации.

### 8.4. Оставшийся зазор

Маркеры `Kept in sync with` в javadoc самих 16 пар ещё не проставлены — это
правка production-файлов, её лучше делать отдельным мелким change'ом или
попутно с рефакторингом каждой пары. До тех пор реестр в правиле покрывает
зазор: аудит опирается на таблицу, а не на маркеры.

### 8.5. Предотвращение на этапе proposal и конвенции (внесено 2026-08-28, вторая волна)

Разбор находок как классов проблем (общие корни: незаписанное знание не
существует для агентной разработки; прокси-метрики и швы, позволяющие тихое
неправильное использование; эволюционное отставание абстракций от первой
реализации) дал иерархию предотвращения: система типов → механический гейт
сборки → обязательный тест-паттерн → маркер + per-change аудит → периодический
аудит. По ней внесено:

- **`.claude/commands/opsx/propose-checked.md`** — обёртка над `/opsx:propose`
  (сам propose не трогаем — его перезатрёт обновление OpenSpec; образец
  обёртки — `apply-sequential.md`). Три ограничения: sync-surface-разведка
  грепом по маркерам/реестру и по существующим реализациям того же правила в
  другом режиме — ДО генерации артефактов; обязательное решение «Sync
  surfaces» в design.md (abstraction vs declared pair, либо явное
  `Sync surfaces: none` — молчание = нарушение формата); пара/абстракция
  становится задачей в tasks.md, а не надеждой.
- **`.claude/rules/design-decisions.md`** — «Sync surfaces» как обязательная
  категория решения (работает и вне propose-обёртки: правило path-scoped и
  попадает в контекст любого агента, пишущего design.md).
- **`.claude/commands/review-artifacts.md`** — проверка Sync surfaces с
  верификацией против реальности (греп маркеров/реестра; «none»,
  противоречащее существующему двойнику, — CRITICAL).
- **`.claude/rules/process-invariants.md`** — три дополнения (классы 4 и 5):
  разрезание файла обязано разделять ответственность («extracted for file
  size» без переноса ответственности — нарушение, а не соблюдение); лимит
  >7 параметров → parameter object, два соседних параметра одного типа —
  transposition hazard; конвенция «иммутабельность после конструктора»
  (пост-конструкционная проводка запрещена; вынужденная — `volatile` +
  комментарий с именем цикла сборки).
- **`.claude/rules/testing.md`** — round-trip-спека обязательна для каждого
  wire-словаря (класс 8): `fromWire(wire(e)) == e` по `values()`, плюс
  закреплённое поведение неизвестного токена.

Эшелон для класса пар теперь: генерация (греп-разведка в propose-checked) →
формат (обязательная секция) → `review-artifacts` → `audit-implementation` →
`/audit-codebase`. Ограничение: «то же правило в другом режиме» — семантическое
суждение, греп лишь подсказывает кандидатов; «none» остаётся утверждением
агента, достоверная проверка — по коду в audit-implementation.

### 8.6. Запланированные code-changes (предотвращение, требующее кода)

Не сделано сейчас — это правки production/build-кода, им место в OpenSpec-changes:

1. **`GitCommandResult` с громким exit code** (класс 3 — молчаливые сбои).
   Три автора независимо забыли проверить exit code, потому что API делает
   забывание путём наименьшего сопротивления: `stdout()` отдаётся независимо
   от результата. Изменить контракт: `stdout()` бросает при непроверенном/
   ненулевом exit code, явный `stdoutIgnoringExit()` для настоящего
   «всё равно». Маленький change, закрывает класс на уровне типов — выше
   любой проверки. Попутно закрывает находки 1.4 и часть 1.2.
2. **Канонические дома + карта канонических мест** (класс 2 — тиражирование
   хелперов). Дома: `Preconditions.requireNonBlank` (модуль, достижимый из
   domain/plugin-api/sandbox), `TaskStatePaths`/настоящий `FactoryOwnedPaths`,
   wire-кодеки на enum'ах, общий read-cap, переиспользуемый `ObjectMapper`.
   Карта «где что живёт» — короткий список в правилах/CLAUDE.md, чтобы агент
   с чистым контекстом узнавал о доме до того, как напишет 36-ю копию.
   Дома — change; карта — правка правил в том же change.
3. **Механический гейт на число параметров** (класс 5) — правило уже записано
   в `process-invariants.md`, но включать сборочный гейт можно только вместе
   с рефакторингом `TakeContext`/`TakeMechanics` (п. 5 раздела 6): сегодня
   `ManualRunRunner` (27 параметров) покрасил бы сборку в красный немедленно.
   Гейт входит в тот структурный change, не раньше.


Подтверждено, не исправлено — нужны решения

- Пагинация — CONFIRMED по существу (12 call-site'ов, не 16; Link-follow нигде нет).
  Holder-проверка heartbeat (GithubHeartbeat.java:98) читает первую = самую старую страницу
  без sort/direction: при >100 комментариев fence ломается в обе стороны (зомби проходит
  проверку, легитимный holder получает ложный ClaimGone). Тот же дефект у
  GithubClaimLease:167, GithubStaleClaimRemoval:135, GithubOpenQuery:134. Это
  change-размерная работа (общий пагинатор + WireMock-спеки с Link-заголовками) — рекомендую
  отдельный /opsx:propose.
- freezeUntilReverified — CONFIRMED с поправкой: сгорает не stage-attempt, а abort/crash
  K-fuse (письменное правило формально не нарушено — оно молчит об этом счётчике).
  Незащищённый fetchTask (RevocationCheckingAttemptPersistence.java:184) при outage бросает
  до recordProgressOnce, аборт считается в fuse, сбрасывающий маркер не пишется, и тот же
  outage взводит сам freeze — режимы компаундятся до infra-park за K boundary. Политика «что
  делать при упавшем re-verification read» нигде не специфицирована — это design-решение
  (кандидат в fix-denial-attribution-durability), чинить без спеки не стал.

Опровергнуто (ничего не менялось)

- Throwable-как-format-arg (4 сайта) — 0 сайтов: все log.warn("… {}", x, e) — корректная
  SLF4J-перегрузка; кандидат AbortHandler.java:89 передаёт String, не throwable.
- Instanceof-цепочки — ни одной цепочки 2+; sealed-диспетчеризация через pattern switch.
- SelfFencingBoundarySpec и TrackerShapeSpec отсутствуют — обе существуют с
  traceability-атрибуцией (application/src/test/.../take/SelfFencingBoundarySpec.groovy:23,
  .../lease/TrackerShapeSpec.groovy:17).
- Closure-statement kill-point таблицы — есть в delta-спеках
  (specs/github-tracker/spec.md:299-301, specs/tracker-port/spec.md:167-169); в design.md
  его нет, но формулировка «design.md или spec» опровергнута.
- UX1-атрибуция — существует: TransitionKillPointSpec.groovy:8-11 цитирует UX1 в javadoc,
  именующем change.
- GitObjects.historyContains игнорирует exit-код — сознательный документированный идиом,
  идентичный принятому в GitShowTip.cleanupCommitInHistory (диагностика rev-list идёт в
  stderr); interrupt в GitExec бросает исключение, так что termination-дыры нет.

Частично: BranchShape.label()

Пиннинг существовал, но покрывал 6 из 11 форм и не проверял полноту. Переписал фичу: все
11 литеральных пинов + сверка набора с permittedSubclasses — двенадцатая форма теперь
падает в спеке.
