# Выбор base-ветки для task branch и пересмотр D7 «never fetch/pull»

Заметки дизайн-сессии от 2026-09-04 (`/architect`, режим B). Черновик — не
durable-артефакт; при оформлении изменения содержимое инлайнится в
proposal/design, ссылаться отсюда из `openspec/**` нельзя.

## Исходная точка

`TaskBranchCreator` ветвится от локального HEAD клона, `--base` переопределяет,
и **никогда** не делает fetch/pull — design D7 изменения `add-git-workflow`
(архив 2026-07-19). Сам D7 закладывал пересмотр: «updating the clone is the
human's job **(later the factory loop's)**». С появлением `gnomish take` /
`serve` человека у клона нет, и устаревание накапливается неограниченно.

Ключевое уточнение: отклонённая в D7 альтернатива («silent network mutation of
the operator's clone») смешивает fetch и pull. `git fetch` не трогает working
tree, HEAD и локальные ветки — инвариант FR7 «the clone itself is untouched»
fetch не нарушает. И запрет уже не абсолютен: resume делает narrow fetch ровно
`gnomish/<task>` (D9).

## Главный вывод

«Откуда ветвиться» — три разных решения, а не один параметр:

1. **Меню допустимых баз** — конфигурация проекта, правилами-паттернами
   (релизные ветки — серия `release/1.18`, `2-3-stable`, не константа).
2. **Выбор для конкретной задачи** — метаданные задачи в трекере (label/поле,
   называющее ветку или версию). Конфиг хранит функцию отображения; выбор —
   всегда per-task. Значение вне меню → эскалация.
3. **Обязательство распространения** — куда фикс должен *ещё* попасть.
   В каждой модели, где ветвятся не от main, решение «откуда» неотделимо от
   «куда потом».

Нулевая конфигурация разрешается в **default branch репозитория** (запрошенный
у remote, не захардкоженный `main`) — всё остальное opt-in.

## Исследование 1: канонические branching-модели

| Модель | Feature/bugfix от | Hotfix от | Обязательство после |
|---|---|---|---|
| git-flow ([nvie](https://nvie.com/posts/a-successful-git-branching-model/)) | `develop` | `master` | merge в `master` **и** `develop` (или в открытую release-ветку, если она сейчас существует — state-dependent!) |
| GitHub Flow ([docs](https://docs.github.com/en/get-started/using-github/github-flow)) | default branch | нет понятия hotfix | — |
| GitLab Flow (docs `topics/gitlab_flow.md`) | `main` | `main` («upstream first») | cherry-pick в release-ветки (`2-3-stable` — паттерн) |
| Trunk-based ([branch-for-release](https://trunkbaseddevelopment.com/branch-for-release/), [release-from-trunk](https://trunkbaseddevelopment.com/release-from-trunk/)) | trunk | trunk | cherry-pick в release-ветку; явное предостережение против fix-on-release («забыть merge-back = регрессия в проде через недели») |
| OneFlow ([endoflineblog](https://www.endoflineblog.com/oneflow-a-git-branching-model-and-workflow)) | `master` | **от последнего version-тега** | merge в `master` или в открытую release-ветку |
| gitworkflows(7) ([git-scm](https://git-scm.com/docs/gitworkflows)) | — | «форкайся от **самой старой** интеграционной ветки, куда фикс должен попасть» | merge только вверх (`maint`→`master`→`next`); инвариант `git log master..maint` пуст — машинно проверяем |

Позиции по hotfix merge-back:

- **Fix-on-main + cherry-pick вниз** — доминирующая современная позиция (TBD,
  GitLab «upstream first», [Kubernetes cherry-picks](https://github.com/kubernetes/community/blob/master/contributors/devel/sig-release/cherry-picks.md):
  cherry-pick-PR возможен только после мержа в master). Деградирует безопасно
  для исполнителя, который может упасть или забыть — описывает автономного
  агента лучше, чем человека.
- **Fix-on-release + merge-back вверх** (git-flow, OneFlow) — известный режим
  отказа: забытый merge-back и долгая дивергенция. Автоматизации merge-back в
  каноне практически нет — само по себе свидетельство.
- Driessen в заметке 2020 отправляет большинство проектов на более простые
  модели: «Panaceas don't exist».

Машинно-читаемое выражение выбора: везде label/поле называет **целевую ветку
или версию, разрешаемую по паттерну** — Jira `fixVersion` (маппинг всегда
bespoke), milestone ↔ ветка у Kubernetes, backport-labels
([sorenlouv/backport](https://github.com/sorenlouv/backport) `branchLabelMapping`:
`"^backport-to-(.+)$": "$1"`; [spring-io/backport-bot](https://github.com/spring-io/backport-bot);
[korthout/backport-action](https://github.com/korthout/backport-action)).

### GAP-список против нашего эскиза (сокращённо)

1. Решение о ветвлении и обязательство распространения — **одно решение**:
   выход маппинга = (base ref, набор ref'ов куда должно попасть, направление:
   merge-up | cherry-pick-down); невыполненный набор — durable-состояние задачи.
2. Default-позиция hotfix — branch-from-main + cherry-pick-down; fix-on-release
   — явно сконфигурированное исключение с отслеживаемым merge-back.
3. Base ref — per-task; per-project — только правило отображения (regex с
   capture-группой из метаданных).
4. Имена релизных веток — серия по паттерну, никогда не константа; при
   недоопределённости (hotfix без fixVersion) — эскалация.
5. State-dependent правила (git-flow «в release-ветку, если существует»;
   OneFlow «от последнего тега») не выражаются статической таблицей — нужны
   runtime-предикаты. Решение: non-goal первой итерации, записать явно.
6. База может быть тегом или SHA, не только веткой — «base ref» в конфиге и
   `task.json` — общий refspec; fetch должен уметь теги.
7. Пиновка — `(resolved ref, SHA, правило-источник)`, чтобы resume мог понять,
   что правило теперь разрешилось бы иначе.
8. Environment-ветки (staging/production в GitLab Flow) — deploy-указатели,
   никогда не базы; конфиг должен классифицировать роли веток.
9. Тип задачи недоопределяет решение: у Kubernetes есть eligibility-гейт
   («можно ли этому классу задач в stable-ветку вообще») до «в какую».
10. Не переусложнять: доминирующий случай — «всегда default branch»
    (zero-config).

## Исследование 2: системы-аналоги (BORROW-список)

1. **Меню веток паттернами в конфиге репозитория** — Renovate
   [`baseBranchPatterns`](https://docs.renovatebot.com/configuration-options/)
   (имена + regex; конфиг читается только с default branch → нет drift'а
   per-branch конфигов).
2. **Per-task цель через label, называющий ветку** — prow
   [cherrypicker](https://docs.prow.k8s.io/docs/components/external-plugins/cherrypicker/)
   (`cherrypick/<branch>`), backport-actions (`targetBranchChoices` = allowlist
   в конфиге, label = выбор). Разделение «конфиг = допустимое множество,
   задача = выбор».
3. **Разрешение base — явный шаг старта задачи** — GitHub Copilot coding agent
   ([base-branch picker per task](https://github.blog/changelog/2025-07-23-agents-page-set-the-base-branch-for-github-copilot-coding-agent-tasks/)),
   [OpenAI Codex cloud](https://developers.openai.com/codex/cloud/environments)
   («selected branch or commit SHA» + maintenance script при переиспользовании
   контейнера).
4. **Клон устарел по построению; освежать на каждом старте** —
   [Devin](https://docs.devin.ai/onboard-devin/repo-setup) (pull-latest при
   старте сессии), GitLab runner
   [`GIT_STRATEGY: fetch`](https://docs.gitlab.com/ci/runners/configure_runners/)
   с fallback на clone.
5. **Narrow refspec ровно на нужный ref** —
   [actions/checkout](https://github.com/actions/checkout) (single-ref,
   `fetch-depth: 1`), [Jenkins git plugin](https://plugins.jenkins.io/git/)
   (minimal refspec). Предпочесть full-depth-single-branch, а не
   shallow-all-branches (каверза GitLab: depth должен покрывать всё, что
   resume должен разрешить).
6. **Fail-closed при упавшем fetch** — ни одна CI-система не работает молча от
   устаревшего состояния; bounded retries, затем отказ. У нас: инфраструктурный
   сбой, попытка не сгорает.
7. **Пиновка SHA в durable-состоянии** — Codex/Devin записывают SHA;
   merge queues ([matklad о GitHub merge queue](https://matklad.github.io/2023/06/18/GitHub-merge-queue.html),
   [Zuul](https://zuul-ci.org/docs/zuul/latest/concepts.html)) тестируют
   сконструированное состояние, не имя.
8. **Свежесть базы проверяется и на merge-конце** — не наша забота при
   создании ветки; полагаться на merge queue хоста, не переизобретать
   speculative merge. Записать design-note.
9. **Одна задача — одна база** — Renovate/Dependabot/cherrypicker открывают
   отдельную ветку+PR на каждую базу; «на несколько баз» = fan-out на
   per-base задачи (ложится на дизайн иерархии задач), не перенацеливание.
10. Предостережение: асимметрия Dependabot (security-апдейты игнорируют
    `target-branch`) показывает, как type-dependent политика вползает
    захардкоженной — у нас это должна быть явная колонка конфига.

## Аудит кода: карта и точка владения

Полная карта — в отчёте волны; ключевое:

- **Funnel существует**: все четыре пути свежего старта (host/container ×
  run/take) проходят через `GitFreshTaskSupport`
  (`application/.../app/GitFreshTaskSupport.java:45`) — единственное место,
  где `null` → `"HEAD"`. Естественный владелец нового `BaseRefResolver`.
- **Default выражен дважды и один раз отсутствует**: второй раз в
  `TaskBranchCreator.startPoint()` (host, `adapters/git/.../TaskBranchCreator.java:60-62`);
  `GitObjectsTaskRepository` (container) своего default'а не имеет. Резолвер
  схлопывает: ниже funnel'а все говорят готовыми ref'ами.
- **`baseCommit` в `task.json` пишется, протаскивается через все
  lifecycle-записи, но не имеет ни одного production-читателя** — слот для
  пиновки `(ref, sha, rule)` уже свободен.
- **Точка для fetch**: между `harden()` и `createTask()` в `TakeFreshClaim` и
  `TakeContainerFreshClaim` (declared pair из `manual-sync-pairs.md`). Retry —
  существующий `GitInfrastructureRetry` (`adapters/git`), **не** Resilience4j
  (он живёт только в GitHub-адаптере).
- **Контейнер получает базу factory-side**: `DockerSeedCloneCommand` клонирует
  уже созданную task-ветку из read-only маунта, origin внутри бокса удаляется,
  `--network none` → fetch бывает только с фабричной стороны, до `createTask`,
  одна точка на оба медиума.
- Существующие fetch'и: locate `gnomish/<task>` на resume
  (`TaskBranchLocator.java:93`), реконсиляция реплик
  (`ReplicaPairReconciler.java:115`), harvest из сендбокса
  (`ContainerHarvestFetch.java`). Pull'ов нет нигде.
- Конфиг-поверхности: `.gnomish/config.yaml` (`ConfigDto`: schemaVersion,
  autonomy, tracker — git/base секции нет; `tracker:`-блок — прецедент формы),
  фабричная `FactoryProperties` (есть `factory.git-network-timeout`).
  `TaskType`/label-plumbing в коде **нет** — это `add-pipeline-routing`.
- Спеки, которые придётся править: `git-task-persistence/spec.md:57`
  («SHALL NOT fetch or pull the base»), `:145` («never fetching anything
  else»), `:384-391` (bounded network — новый fetch наследует),
  `tracker-take/spec.md:17,25-27,38` (`--base` только для explicit-mode).

### Пересечения с активными изменениями

- **`add-pipeline-routing`** — точный структурный близнец: факт типа из
  трекера, таблица label→тип в `.gnomish/` с явным default, пиновка выбора в
  `task.json` при первом claim. Выбор base **едет на том же факте типа и той
  же форме таблицы**. Порядок: после/вместе с routing.
- **`add-pipeline-entry-precondition`** — вставляет baseline-пробу в те же
  строки `TakeFreshClaim`/`TakeContainerFreshClaim`; порядок
  «fetch+resolve → проба» согласовать в одном дизайне (проба должна бежать на
  освежённом base).
- Средний/низкий overlap: `add-epic-decomposition` (subtask'ам тоже нужна
  база), `add-decision-inheritance`.

## Steelman статус-кво (D7 как есть)

За: детерминизм, офлайн, ноль сетевых отказов при создании ветки. Ломается
ровно в serve-режиме (человека у клона нет). Забираем: **ручной `run` без
`--base` продолжает ветвиться от локального HEAD без fetch**; `--base <sha>` и
клон без origin работают как раньше.

Steelman полного языка резолюции (runtime-предикаты для git-flow/OneFlow
state-dependent правил): правила реальны, но моделировать сейчас —
переусложнение. Начать с pattern-mapping + эскалация при недоопределённости;
state-dependent правила — явный non-goal первой итерации.

## Согласованная нарезка

1. **Изменение 1 — base-ref resolution + fetch policy** (суперсид D7-формулировок
   в `git-task-persistence`):
   - `BaseRefResolver` в application у funnel'а `GitFreshTaskSupport`;
     схлопывание двойного default'а;
   - блок в `.gnomish/config.yaml` (меню баз паттернами, роли веток,
     правило отображения из метаданных задачи);
   - приоритет: явный `--base` > per-task метаданные > правило по типу >
     default branch репозитория > (только manual run) локальный HEAD;
   - narrow fetch fail-closed в автономном режиме (между `harden` и
     `createTask`, `GitInfrastructureRetry`, инфраструктурный сбой — попытка
     не сгорает);
   - пиновка `(ref, sha, rule)` в `task.json` (слот `baseCommit` уже есть).
   - Линия отреза: «куда потом» не трогаем — только записываем выбранное
     правило.
2. **Изменение 2 — propagation obligations**: merge-back / cherry-pick-down
   как durable-обязательства задачи; «несколько баз» = fan-out per-base задач
   через дизайн иерархии. После изменения 1 и после routing.

## Модульная нарезка (обсуждено 2026-09-04)

Вопрос: можно ли вынести логику работы с ветками в отдельный модуль наподобие
`:gitobjects` / `:atomicfile`? Ответ: да, но только **чистую политику
резолюции** — не «работу с ветками» целиком.

Критерий лист-модуля в проекте (`:atomicfile`, `:subprocess`, `:gitobjects`,
`:logtext`): механизм-дисциплина, ноль зависимостей (или одна листовая),
никаких типов фабрики, гейт `layering { allowedProjects = [] }`,
извлекаемость как заявленное свойство.

Раскладка изменения 1 по этому критерию:

| Часть | Куда | Почему |
|---|---|---|
| Политика резолюции: грамматика паттернов меню, валидация выбора против меню, приоритет источников, классификация «недоопределено → эскалация», типы `Decision (ref, rule, reason)` | **лист-модуль** (рабочее имя `:baseref`; термин в глоссарий тем же изменением) | чистая функция от значений; ни Jackson, ни git, ни типов фабрики |
| Fetch base-ветки, `rev-parse`, запрос default branch у remote (`ls-remote --symref`) | `:adapters:git` | субпроцессы; `GitProcessRunner` + `GitInfrastructureRetry` уже там |
| Парсинг блока в `.gnomish/config.yaml` | `:adapters` (pipeline loader) | Jackson и `ConfigError`-инфраструктура там; DTO маппится в value-типы лист-модуля |
| Оркестрация «resolve → fetch → createTask» и пиновка в `task.json` | `:application` (funnel `GitFreshTaskSupport`) | точка владения из аудита |

Контраргумент (честный): сегодня у модуля один потребитель —
`:application`; критерий из `settings.gradle` («module overhead would exceed
the benefit») по умолчанию оставил бы это пакетом `app/baseref`.

Что перевесило в пользу модуля:

- **Изменение 2 — запланированный второй потребитель**: propagation
  obligations считаются той же грамматикой меню/паттернов (тот же словарь
  правил), это роадмап, не гипотеза.
- Гейт `allowedProjects = []` **конструктивно** гарантирует ключевое
  дизайн-свойство: политика не знает ни про субпроцессы, ни про трекер — всё
  приходит значениями. В пакете это держится только ревью.
- Политика — плотная решающая логика (regex-матчинг серий, приоритеты, роли
  веток), противоположность arid wiring: выгодно держать под отдельным
  100%-PIT и `layering`-гейтом; конвенции делают стоимость модуля низкой.
- Пиновка `rule` в `task.json` делает словарь правил wire-словарём с
  round-trip-спекой (rule из `testing.md`) — отдельный модуль даёт ему одно
  бесспорное место.

Жёсткая граница модуля: **ни одного субпроцесса и ни одного порта внутри** —
default branch репозитория, метаданные задачи и распарсенный конфиг приходят
аргументами; исполнение решения (fetch, branch) остаётся в `:adapters:git`.
Запасной выход: если при propose политика выродится в тривиальный if (меню не
нужно) — откатиться на пакет в `:application` без потерь.

## Кастомная реализация выбора (обсуждено 2026-09-04)

Вопрос: может ли понадобиться кастомная (не декларативная) реализация выбора
base-ветки, и как её предусмотреть.

Случаи, которые декларативный pattern-mapping не покрывает, по нарастанию:

1. **State-dependent правила канона** — git-flow «в release-ветку, если она
   сейчас открыта, иначе develop»; OneFlow «hotfix от последнего тега». Это
   два-три известных предиката («новейшая ветка по паттерну P, если есть,
   иначе F»; «коммит новейшего тега по паттерну P») — расширение
   декларативного языка, не произвольный код.
2. **Запросы во внешние системы** — открытый релизный поезд из календаря,
   корпоративный маппинг Jira fixVersion → ветка, monorepo-политика «база
   зависит от каталога задачи». Настоящий код с IO.
3. **Вычисляемые базы** — «последний зелёный коммит main» (статусы CI),
   «коммит последнего деплоя». Тоже код с IO.

Три механизма и вердикты:

- **a) Исполняемый файл из `.gnomish/` целевого репозитория** (по образцу
  `command`-проверки QC) — **отклонено, никогда**. Селектор бежит на хосте
  фабрики до создания ветки и до всякого сендбокса, а фабрика намеренно
  нейтрализует код репозитория на хосте (`FactoryCloneHardening` перекрывает
  `core.hooksPath`; «Controls are data, not code inside the engine»). Хук в
  момент claim = исполнение кода на фабрике с её credentials для любого, кто
  может писать в целевой репозиторий. Зафиксировать как явно отклонённую
  альтернативу в design.md изменения 1.
- **b) Операторский плагин через `gnomish-plugin-api` + ServiceLoader** —
  правильная дверь, *если когда-нибудь понадобится*. Механизм обкатан
  (`TrackerAdapterFactory`, `CheckClientFactory`, `SecretsProvider`,
  discovery в `:bootstrap`); граница доверия — classpath оператора, тот же
  уровень, что сборка фабрики. Форма: порт `BaseRefPolicy`, дискриминатор
  `type:` в конфиг-блоке (как `tracker: type`); декларативный резолвер из
  `:baseref` — встроенная реализация того же порта. Модульная нарезка это
  переживает без изменений. Сейчас не строим (YAGNI, нет конкретного
  запроса).
- **c) Внешняя автоматизация ставит per-task label** (модель
  prow/backport-ботов) — **предусмотренный способ кастомизации уже в
  изменении 1**, не обходной путь. Классы 2 и 3 исполняются снаружи (Jira
  automation, GitHub Action, cron): логика вычисляет базу и ставит на задачу
  `base:release-1.4`; фабрика валидирует против меню. Ноль новой поверхности
  доверия. Задокументировать явно.

Решения для изменения 1:

- механизм кастомной реализации **не строится**; escape hatch = (c),
  документируется как предусмотренный;
- дешёвая форвард-совместимость: дискриминатор в схеме конфиг-блока
  (`type: patterns` по умолчанию), чтобы (b) позже пришёл новым значением без
  ломки схемы и без пересмотра `:baseref`;
- предикаты класса 1 — кандидаты второй итерации декларативного языка, когда
  попросит реальный проект (сейчас — записанный non-goal);
- (a) — в design.md как отклонённая альтернатива с обоснованием безопасности.

## Решения по открытым точкам (2026-09-04)

**1. Форма конфиг-блока** — по прецеденту `tracker:`-блока, валидация
существующей `ConfigError`-инфраструктурой (паттерны компилируются при
загрузке, неизвестные ключи — ошибка, label вне меню при claim — эскалация):

```yaml
base:
  type: patterns              # дискриминатор (форвард-совместимость с плагином)
  default: main               # опционально; отсутствует → default branch репозитория
  menu:
    - pattern: "main"         # роль по умолчанию: development
    - pattern: "release/*"
      role: release           # роли: development | release; environment-ветки не в меню
  select:
    label: "base:(.+)"        # regex с capture → имя ветки, валидируется против меню
```

Роль `release` — зацепка для будущего eligibility-гейта (тип × роль) и
изменения 2; сам гейт — не в первой итерации.

**Разрыв цикла «конфиг выбирает базу, но откуда конфиг»** (модель Renovate):
блок `base:` читается **только с default branch** (освежённой fetch'ем); он
выбирает базу; остальной pipeline law замораживается уже с выбранной базы
(сегодняшняя семантика `PipelineLaw` — «factory-owned clone of the base
branch»). Записать явно в design.

**2. Label** — в первой итерации имя ветки напрямую (`base:release-1.4`),
prow-стиль. Версия + паттерн-резолюция (fixVersion → `release-{v}`) — Jira-
специфика, откладывается до Jira-адаптера (та же логика, что Jira-таблица в
routing-дизайне); capture-группа в `select.label` этому не мешает.

**3. Serve при падающем fetch** — fetch бежит после claim, значит на
исчерпании retry: **отпустить claim** существующим механизмом,
классификация — инфраструктурный сбой (попытка не сгорает, задача
возвращается в Ready). На стороне serve — `RepeatSuppressor` (первый отказ
WARN с кодом `OperatorEvent`, повторы DEBUG, roll-up). Автоэскалации в
трекер на первом отказе нет; накопление claim-отказов ловит существующий
`abort-threshold`-учёт. Точные механики отпускания — в propose по
существующему пути инфраструктурных отказов.

**4. Порядок** — пересмотрен, см. следующий раздел.

## Пересмотр порядка: изменение 1 раньше routing (2026-09-04)

Изменение 1 важнее — проверили, можно ли его сделать до
`add-pipeline-routing`. Можно, с одним срезом скоупа и разворотом
зависимости:

- От routing в изменении 1 зависит только ярус «правило по типу» в
  приоритетной цепочке. Оба мотивирующих сценария покрываются без него:
  «проект от develop» → `default: develop`; «фикс от релиза» → label
  `base:release-1.4`. **Ярус типа — явный non-goal изменения 1** с отсылкой
  к routing.
- Label-плумбинга в коде нет (факты задачи — id, title, body). Routing
  вводил факт «type designator» (absent | single | conflict, адаптерное
  префикс-правило, конфликты не разрешает, contract-suite для всех
  адаптеров). Вместо параллельного механизма — **разворот зависимости**:
  изменение 1 строит обобщённый механизм «label-derived designator» и
  добавляет первый вид — `base`; routing потом добавляет вид `type`,
  потребляя механизм. Его spec-дельты ребейзятся без конфликта форм.
- Пин в `task.json`: изменение 1 первым расширяет формат ((ref, sha, rule)
  в коммите создания, version gate, legacy-чтение) — routing добавляет свой
  пин по проторенному образцу (его дельта это и так предусматривает).

Итоговая последовательность:

1. **Изменение 1** — base-ref resolution + fetch policy + обобщённый
   designator-механизм + прецедент расширения пина.
2. **`add-pipeline-routing`** — едет на designator-механизме, добавляет вид
   `type`; ярус тип-дефолтов base-конфига — одна колонка поверх обоих
   механизмов (в routing или мелким follow-up).
3. `add-pipeline-entry-precondition` — в любом месте после изменения 1;
   контракт «fetch+resolve до baseline-пробы» записывается в оба дизайна.
4. **Изменение 2** — propagation obligations.

Минус: изменение 1 тяжелеет (designator-механизм — его ноша). Принято как
цена за приоритет.

## Безопасность: может ли гном поломать настройку (2026-09-04)

Векторы и вердикты:

- **Подменить `.gnomish/config.yaml` на своей ветке** — не работает: блок
  `base:` читается с default branch факторной стороной; копия в рабочей
  директории гнома — project content, законом становится после
  человеческого мержа (формулировка reward-hacking из javadoc
  `PipelineLaw`, D14 add-sandbox-core).
- **Перевыбрать базу своей задачи** — не работает: выбор пинуется в
  `task.json` `(ref, sha, rule)` при claim, до запуска агента; **resume
  читает пин и никогда не перерезолвивает** из данных, доступных гному на
  запись; пин наследует защиты task-branch-contract.
- **Достучаться до трекера и поставить label** — не работает из бокса:
  `--network none`, origin внутри удалён; labels читаются факторной
  стороной при claim.
- **Создать на origin ветку под паттерн меню** — не работает: гном не
  пушит; harvest и push — факторные фиксированные refspec'ы только для
  `gnomish/<task>`.
- **Остаточные риски (человеческие, записать в design)**: (а) triage-права
  = выбор валидной, но неправильной базы — ограничено меню оператора, видно
  в target'е PR, ловится ревью, сузит роль-гейт; (б) push-права = ветка под
  паттерн меню — свойство паттерн-меню как таковых (у Renovate так же),
  мерж гейтится ревью; (в) гном предлагает изменение `.gnomish/` в своём
  PR — гейт: человеческое ревью мержа, существующая граница доверия.

Два правила, при которых настройка не даёт гному новых рычагов (материал
для design.md; окно fetch→пин — пункт чеклиста crash-consistency):

1. `base:`-блок читается только с default branch.
2. Пин при claim; resume не перерезолвивает.

## Смежная идея (передана в add-pipeline-routing)

Отдельный тип задачи «настройка/исправление конфигурации самой фабрики»
(правки `.gnomish/` целевого репозитория через задачу в трекере): работа
гнома над конфигом — обычный PR, законом становится после мержа; хорошо
ложится на routing (свой пайплайн для такого типа) и на модель безопасности
выше.
