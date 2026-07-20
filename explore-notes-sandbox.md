# Бэклог explore: песочница исполнения (sandbox)

> Временный файл — скоупинг будущего sandbox-change, собранный в explore-сессии
> factory loop (2026-07-20, нить Д + Д-доп) и дополненный исследованием лучших
> практик индустрии (2026-07-20, 4 параллельных research-агента; источники внизу).
> Не для коммита. Сырьё для `/opsx:propose` будущего sandbox-change, когда
> наступит триггер (Д7). Родственные заметки: `explore-notes-factory-loop.md`
> (нить Д — позиция v1), `explore-notes-future-changes.md` (исходная пометка).

## Статус решения (из нити Д factory loop)

- **v1 factory loop — БЕЗ песочницы**, явная позиция: исполнение на хосте в
  worktree, как сейчас. Защитимо моделью доверенного исполнения, не безрассудно.
- **Envelope v1** = доверенный командный ПРИВАТНЫЙ репозиторий. Ворота готовности:
  «кто может повесить `gnomish:ready`» = «кто может исполнить код на хосте»;
  GitHub пускает к label'ам только triage/write → ворота = команда. Требование
  оператору: НЕ подключать авто-`ready` из недоверенных источников.
- **Код гнома всё равно исполняется** (сборка/тесты его результата) — неотъемлемо
  от назначения фабрики; защита хоста от собственного продукта — задача ЭТОГО change.
- **Триггер постройки (Д7)**: репо с недоверенными источниками задач/pipeline-config
  — публичные + авто-labeling, мультитенант, SaaS. До тех пор поддерживаемый режим —
  доверенная команда, приватный репо.
- **Тир 0 (env-allowlist / скраб)**: подмножество Д5 уже уехало в
  `add-claim-heartbeat` (решение С5.5) — расширение declared-scrub-list на
  `CommandProcessRunner`. Полный позитивный allowlist — в sandbox-change; шов уже
  назван в javadoc `AgentProcessLauncher` (`agentCliEnvPassthrough` из
  «документации намерения» становится механизмом).

## Поправка масштаба (Д1): изолировать ВСЁ исполнение, не только чеки

Объект изоляции — всё исполнение в worktree: agent-cli executor запускает
ИИ-агента с шеллом (свободы больше, чем у чека), плюс command-чеки. Песочница
только чеков = театр. Имя change'а: «изоляция исполнения worktree».

## Поверхность исполнения (из кода, 2026-07-20)

- `adapter/agent/AgentProcessLauncher` — запускает агент-CLI через
  `ProcessBuilder`, полный env унаследован; stdout читает `StreamJsonEventMapper`.
- `adapter/check/CommandProcessRunner` — `sh -c <command>` в worktree с полным
  наследованием env; результат — `CommandOutcome` (exit code + хвост вывода).

Изоляции в production нет; E2E-контейнеры (testing.md) = capability уже есть.
`Workspace` абстрагирует «где корень», НЕ «как изолировано исполнение».

## Находка: «worktree + контейнер» наивно несовместимы (2026-07-20)

Заметки предполагали bind-mount рабочей копии, но рабочая копия — git worktree, а
он не самодостаточен: `<worktree>/.git` — файл-ссылка на `<clone>/.git/worktrees/…`,
коммиты пишут объекты в ОБЩИЙ `.git/objects` клона, refs всех задач — там же.
Монтировать `.git` клона в контейнер на запись = сломать и защиту хост-клона, и
взаимную изоляцию N слотов.

**Решение-кандидат: в sandbox-режиме рабочая копия = полный клон внутри песочницы**
(named volume — заодно снимает bind-mount-производительность на Mac); гном коммитит
в нём свободно; фабрика **fetch'ит ветку задачи ИЗ клона песочницы** и пушит на
настоящий remote снаружи (harvest-паттерн). Монополия push (Р11) сохраняется даже
чище: внутри нет ни remote-кредов, ни общего стора.

**Прямые прецеденты harvest-паттерна**: Docker Sandboxes clone mode (клон в
microVM, наружу git daemon на localhost, хост делает `git fetch sandbox-<name>` и
пушит сам) и Dagger container-use (авто-коммит в ветку локального remote, хост
забирает `git fetch`). Строжайший вариант индустрии: песочнице не нужно вообще
никаких remote-кредов.

Следствие: изоляция склеивает материализацию рабочей копии и запуск процессов —
порт, видимо, один. Проверить в design: механика resume/salvage (tracker-port 5.6)
должна пережить подмену worktree на клон.

## Что защищать и от чего (threat model)

| Поверхность       | Угроза                                                        | Тонкость                                              |
|-------------------|---------------------------------------------------------------|-------------------------------------------------------|
| Файловая система  | чтение/запись вне worktree: хост, чужие worktree, `~/.gnomish`, ключи | у гнома должен остаться СВОЙ workspace на запись |
| Секреты в env     | токен трекера, push-креды, облачные ключи, API-ключи          | AI-доступ гному НУЖЕН — см. virtual keys ниже          |
| Сеть              | эксфильтрация, обращения к чужой инфре, SSRF к внутренним      | нельзя отрезать: AI-провайдер, реестры → политика      |
| Процесс/хост      | эскалация прав, fork-бомбы, забить диск, помешать другим слотам | N слотов одновременно → ещё и взаимная изоляция        |

Дополнения из инцидентов индустрии (2026-07-20):

- **Канал AI-провайдера — неустранимый exfil-путь**: у Anthropic задокументирован
  кейс «эксфильтрация через api.anthropic.com на ЧУЖОЙ аккаунт» (инъекция подменяет
  auth-заголовок). Урок: **allowlist домена = grant возможности, не фильтр
  назначения**; лечится прокси, который пинит собственный ключ песочницы и срезает
  посторонние auth-заголовки. Широкий allow `github.com` — тоже exfil-канал (gists,
  чужие репо).
- **Репо-локальный конфиг = недоверенный вход** (инцидент Anthropic: parsed-до-доверия
  `.claude/settings.json` = исполнение кода). Для нас: `.gnomish/` манифесты и
  setup-скрипты исполняются ТОЛЬКО внутри границы, никогда на хосте.
- **Пути персистенции инъекции** обязаны быть read-only для агента: `.git/hooks/`,
  `.gitconfig`, shell rc, конфиг самого агента (Codex принудительно делает `.git/`
  и `.codex/` read-only даже внутри writable-корней).
- Supply-chain прецедент (Nx «s1ngularity»): злонамеренный `npm postinstall`
  собирает креды и вооружает локальные AI CLI — ровно наша модель угроз для
  command-чеков и стадий.

**Главное напряжение** (подтверждено): гному нужна настоящая среда разработчика +
сеть по политике — «дать окружение в коробке», не «запретить всё».

**Существующие швы, которые песочница обязана сохранить**:
- монополия push (Р11 / git-workflow NFR-S1): гном коммитит, фабрика пушит СНАРУЖИ;
- AI-доступ — единственная «валюта» внутри, но в форме virtual key, не сырого ключа.

## Конвергентный шаблон индустрии (2026-07-20)

Anthropic (Claude Code web, srt), OpenAI (Codex CLI/cloud), GitHub Copilot coding
agent, Cursor, Docker Sandboxes построили одно и то же:

1. **Эфемерная среда на задачу**, durable-состояние только в git (у нас уже так).
2. **Egress в два слоя**: policy-прокси с domain-allowlist default-deny СНАРУЖИ
   границы + структурный backstop, делающий обход физически невозможным (srt на
   Linux: у песочницы вообще нет network namespace, единственный канал — unix-сокет
   к прокси; контейнерный мир: internal-only сеть, единственный маршрут — прокси).
   `HTTP_PROXY` — конвенция, не enforcement: postinstall-скрипт просто снимает env;
   останавливает только слой 2.
3. **Две фазы**: setup с сетью (установка зависимостей) → агент-фаза без сети или
   с allowlist; секреты доступны только setup-фазе и вычищаются до старта агента
   (Codex).
4. **Push-креды никогда не входят в среду агента**: либо платформа пушит сама,
   либо git-прокси с СЕМАНТИЧЕСКОЙ политикой («push только в ветку задачи»),
   внутри — placeholder-строка, настоящий токен подставляется снаружи (Claude Code
   web: `GH_TOKEN=proxy-injected`).
5. **Read-only контрольные поверхности** (см. threat model выше) — все считают
   prompt-injection данностью (адаптивные атаки >85% успеха по survey'ям) и
   закрывают персистенцию/эскалацию, а не только вход.
6. **Заблокированный egress — сигнал, не тишина**: Copilot выносит срезанные
   запросы предупреждениями в PR. Для нас: дены прокси → findings в отчёт трекера
   (NFR-O).
7. **Fail-closed для автономного режима**: «песочница не поднялась» = жёсткий отказ
   (инфраструктурный), не «исполним без неё» (`failIfUnavailable` у srt).

## Пересмотренная лесенка (была: Тир 0–3)

- **Тир 0 — env-allowlist**: без изменений; подмножество (скраб) уже в
  `add-claim-heartbeat`, полный allowlist — в Change A.
- **Тир 1 (OS-конфайнмент UID/rlimits) — СНЯТ**: никто в индустрии не строит голый
  UID-конфайнмент; OS-примитивы (bubblewrap/Landlock/Seatbelt) используются как
  defense-in-depth ВНУТРИ контейнера или для локально-интерактивного случая; не
  дают ни лимитов ресурсов per-task, ни взаимной изоляции слотов; Linux-only.
  Ступенька, которую перешагнут — не строить.
- **Резать «контейнер отдельно, сеть потом» НЕЛЬЗЯ** (пересмотр старого шва
  Тир 2 / Тир 3). Доктрина Anthropic из инцидентов: ФС-изоляция и сетевая изоляция
  работают только вместе — каждая поодиночке обходится через другую. Контейнер с
  открытой сетью — театр для главной угрозы (эксфильтрация). Базовый egress в
  контейнерном мире дёшев (см. механику ниже) — это не «недели фиддли-работы».

Новый шов на два change'а:

- **Change A — порт изоляции + контейнер + egress (ядро песочницы)**:
  1. порт изоляции исполнения (см. форму ниже), реализации host-inplace | container;
     оба потребителя (`AgentProcessLauncher`, `CommandProcessRunner`) идут через него;
  2. клон-вместо-worktree внутри песочницы + harvest (fetch из клона песочницы,
     push снаружи);
  3. egress default-deny сразу: internal-only сеть + CONNECT-прокси + allowlist
     статикой из конфига фабрики; smoke-test при старте;
  4. env — позитивный allowlist; внутри вместо AI-ключа — virtual key (или хотя бы
     задел под него);
  5. образ пока задаёт оператор в installation-конфиге (`factory.*`), read-only
     набор путей, лимиты ресурсов (cgroups), N контейнеров + отлов осиротевших
     (зеркало Г3 loop).
- **Change B — поверхность образа/setup + доводки**:
  1. setup-скрипт в `.gnomish/` (двухфазная модель: setup с сетью → агент по
     allowlist) + кэш/снапшот пост-setup состояния;
  2. virtual keys полноценно (бюджет per-task → смычка с NFR-C токен-бюджетами);
  3. по необходимости: TLS-terminate/L7-правила на прокси, впрыск auth-заголовка
     (ключ вообще не входит внутрь).

## Ответы на бывшие «открытые под-вопросы»

### Egress-механизм (был открыт → решён по образцам)

- Internal-only сеть контейнера → прокси СНАРУЖИ namespace → allowlist по
  CONNECT/SNI без расшифровки TLS (tinyproxy/squid; mitmproxy если понадобится
  динамика). DNS резолвится прокси — открытый 53-й порт сам по себе exfil-канал
  (DNS-туннелирование).
- Типовой allowlist: AI-провайдер, `repo.maven.apache.org`, `plugins.gradle.org`,
  `services.gradle.org`, `registry.npmjs.org`, git-remote host (HTTPS only).
- **JVM игнорирует proxy-env** — в образ запекать: `~/.gradle/gradle.properties`
  (`systemProp.https.proxyHost/Port`, `nonProxyHosts`) + `GRADLE_OPTS` (wrapper
  качает дистрибутив мимо properties, gradle/gradle#11065) + `~/.m2/settings.xml`.
  Gradle daemon кэширует прокси-настройки со старта — рестарт при смене политики.
- git внутри — только HTTPS-remote (SSH идёт мимо HTTP-прокси; у srt для не-HTTP
  TCP — SOCKS5). SSE-стриминг AI API через CONNECT работает; ломает его только
  буферизующий MITM.
- **«Как тестировать deny» — machine-verifiable smoke-test при старте песочницы**:
  прямой `curl --noproxy '*'` наружу обязан сдохнуть (backstop работает);
  прокси-запрос вне списка → 403; запрос в allowlist → ok. `TCP_DENIED`-логи
  прокси → findings в трекер. (Прецеденты «allowlist молча не enforce'ился»
  задокументированы — smoke-test обязателен.)

### Владение образом тулчейна (был открыт → решён по образцам)

Доминирующий паттерн — НЕ Dockerfile репо: **базовый kitchen-sink-образ платформы
(фабрики) + setup-скрипт репо, исполняемый ВНУТРИ песочницы + кэш/снапшот
пост-setup состояния** (Codex: universal image + setup-скрипт + 12h-кэш; Jules:
setup-скрипт + snapshot; Copilot: `copilot-setup-steps.yml` — примечательно,
devcontainer репо он демонстративно НЕ использует). Для нас: `.gnomish/setup.sh`
вместо Dockerfile — доверие сходится само (скрипт из репо бежит только в
одноразовой коробке). Repo-Dockerfile (Cursor, GitLab CI `image:`) — осознанная
альтернатива, если захочется точности среды ценой build/cache-бремени.
Наследование при форке (критерий Р20): setup-скрипт наследуется с репо естественно.

### Форма порта изоляции (был открыт → кандидат)

Один порт уровня «исполняемое окружение задачи», непрозрачный handle:

```
TaskExecutionEnvironment (жизненный цикл = задача)
  ├─ materialize(branch)   → рабочая копия готова
  ├─ exec(cmd, env)        → поток stdout + exit code   ← оба адаптера
  ├─ harvest()             → фабрика забирает ветку (fetch из окружения)
  └─ dispose()             → снос (контейнер + volume)

host-реализация:      materialize = нынешний worktree, exec = ProcessBuilder,
                      harvest = no-op
container-реализация: materialize = клон в volume, exec = docker exec,
                      harvest = git fetch из volume
```

Выше шва ничего не меняется: `AgentCommandLine`, `StreamJsonEventMapper`,
`CommandOutcome` потребляют поток/exit code откуда угодно. `dispose()` — одна
точка согласованной уборки с worktree-cleaner'ом loop (закрывает старый вопрос).
Непрозрачность — хедж: апгрейд до `runsc` (gVisor) на сервере или до Docker
Sandboxes (microVM) = замена адаптера.

### AI-ключ (усиление против старой позиции «ключ легитимно внутри»)

Паттерн индустрии: **gateway с per-task виртуальным ключом** (LiteLLM-класс:
бюджет $, лимит моделей, expiry, отзыв) через штатный шов
`ANTHROPIC_BASE_URL`/`ANTHROPIC_AUTH_TOKEN`; blast radius утечки = один
отзываемый ключ с потолком. Сильнее — впрыск заголовка на прокси (внутри только
sentinel). Virtual key с бюджетом смыкается с NFR-C токен-бюджетами фабрики.

## Выбор технологии (решено исследованием)

**Rootless-контейнер на задачу (Docker/Podman) + прокси снаружи — sweet spot** для
наших ограничений:

- тулчейн-совместимость решает: gVisor наказывает именно сборки (syscall-штормы
  Gradle/npm, 2–6×, долгий хвост несовместимостей); Kata/Firecracker —
  Linux+KVM-only (разработка на macOS мимо); self-hosted Firecracker — «продукт,
  а не компонент» (ops-бремя платформенной команды);
- на macOS контейнер и так бежит внутри VM Docker Desktop/OrbStack — граница
  «агент↔хост» аппаратная бесплатно; остаточный риск только «задача↔задача»
  в общем ядре VM — приемлемо для dev;
- OCI-интерфейс = хедж: `--runtime=runsc` на Linux-сервере — апгрейд одной строкой,
  если threat model ужесточится;
- **Docker Sandboxes** (март 2026: microVM на задачу, нативно macOS Apple Silicon,
  Sandbox Kits декларируют тулчейн/креды/allowlist-домены) — продукт ровно нашей
  формы; следить, кандидат на замену адаптера, когда дозреет Linux-поддержка.

Фора остаётся: E2E уже в контейнерах (testing.md), Docker в пререквизитах ADR;
латентность старта ~секунды на фоне часовых раундов — ничтожна.

## Итог размера (ревизия)

- Change A (порт + контейнер + клон/harvest + egress + env-allowlist) — самый
  дорогой кусок теперь не Docker-адаптер, а **пересборка git-workflow под
  клон-вместо-worktree** (resume/salvage 5.6). Оценка: недели, масштаб
  GitHub-адаптера, но БЕЗ открытых исследовательских вопросов — все закрыты
  прецедентами.
- Change B (setup-поверхность + кэш + virtual keys + L7-доводки) — недели,
  главная новизна — конфиг/docs-поверхность `.gnomish/`, не механика.
- Вывод прежний: выносить из loop v1 правильно (Д); заводить по триггеру Д7;
  Тир 0-подмножество уже едет в `add-claim-heartbeat`.

## Дополнение (2026-07-20): вложенный Docker и внешние сервисы

- **Вложенный Docker** (гному нужен compose-стек / Testcontainers): docker.sock
  в коробку = отдача хоста (позиция Anthropic), DinD = privileged = снятие
  изоляции — оба исключены. Лесенка решений: (1) **сервисы-соседи от фабрики**
  — статичный compose из репо поднимает фабрика снаружи в internal-сети задачи
  (паттерн CI `services:`); compose = недоверенный вход → фильтр (no privileged
  / no host-mounts вне workspace / no published ports) — механизм-кандидат для
  design change A или B; (2) динамический Docker (Testcontainers) в
  контейнер-режиме честно не поддержан (host-inplace или п.3); Sysbox — Linux-
  only серединка (dockerd в контейнере без privileged); (3) **потребность в
  Docker внутри = главный триггер microVM-адаптера** (Claude Code web: dockerd
  внутри VM; Docker Sandboxes то же) — подтверждает непрозрачный порт.
- **Внешние сервисы (SonarQube и т.п.)**: для верификации не нужны гному —
  `external`-чеки опрашивает фабрика снаружи (уже в stage-контракте), токен
  сервиса в коробку не попадает. Активный доступ гнома (sonar-scanner) = строка
  в allowlist прокси (работает и для LAN — прокси снаружи). Секрет сервиса — по
  общему правилу (фабрика/временный токен/впрыск в B). **Владение allowlist —
  оператор (конфиг фабрики), НЕ репо**: репо может просить, оператор разрешает
  (иначе injection-задача разрешает себе exfil-домен).

## Остающиеся открытые вопросы (сузились)

- **TLS-terminate — РЕШЕНО (2026-07-20)**: вскрытие (MITM-прокси, mitmproxy) —
  в Change B, НЕ в A. Мотив: базовый вахтёр Change A уже даёт default-deny
  (главный слой); TLS-terminate закрывает изощрённые утечки (allowlist-as-
  capability «чужой аккаунт», domain fronting), критичные для недоверенных
  источников — сценарий триггера Д7 = зрелость Change B; Change A и так самый
  тяжёлый (git-механика). Два дешёвых задела в Change A: (а) собственный
  корневой CA вписать в образ сразу (включение вскрытия потом без пересборки
  заготовок); (б) выбор прокси с прицелом на апгрейд (mitmproxy работает и в
  passthrough-режиме → либо сразу mitmproxy, либо плановая замена
  tinyproxy→mitmproxy = смена одного контейнера). Побочно: TLS-terminate
  открывает вариант «ключ вообще не в коробке» (впрыск auth-заголовка у прокси,
  сильнее шлюза с virtual key) и L7-правила (github — только чтение). Цена:
  свои trust-store'ы у JVM/node/python (запекать в образ), certificate pinning
  → passthrough-исключения, не буферизовать SSE, прокси видит весь контент.
- **`.gnomish/` и гном — РЕШЕНО (2026-07-20, explore-сессия sandbox-explained)**:
  развести две роли файлов правил. «Закон задачи» = `.gnomish/` из клона ФАБРИКИ
  (основная ветка, гному недоступна); «содержимое проекта» = файлы в копии гнома,
  правит как любые, в силу вступают только после человеческого merge — для
  следующих задач. Мотив: иначе reward hacking («сам себе ставит зачёт») +
  injection. Сверка с кодом: `PipelineStartup` уже грузит из `args.dir()` на
  старте, worktree отдельно, `PipelineLoader` только читает текст — в
  контейнерной схеме разделение железное по построению. В design change'а:
  требование «фабрика никогда не читает правила из копии, доступной гному на
  запись» + контрактный тест; сверить instructions/acceptance-файлы (грузятся ли
  тоже из клона фабрики). Resume-семантика — фича: возобновление перечитывает
  правила из основной ветки на момент возобновления (сценарий «человек починил
  критерии → вернул задачу»); ветка гнома на закон не влияет никогда.
- **Кэш среды — РЕШЕНО (2026-07-20)**: снапшот = образ (`docker commit`), не
  volume. Заготовка — оптимизация, не источник истины (недостающее докачивается
  через прокси в ходе задачи → протухание стоит времени, не корректности) —
  поэтому простые механизмы: (1) инвалидация контентом через ИМЯ:
  `gnomish-env:<proj>-<sha256(setup.sh)+base-digest>`, существование —
  `docker image inspect`, кода слежения нет; (2) TTL из метаданных Docker
  (`.Created`), ручка `factory.sandbox.snapshot-max-age`, дефолт 7d, своей
  бухгалтерии нет; (3) ручной `--rebuild-env` / `gnomish env rebuild`; аварийно —
  `docker rmi`. Инвариант безопасности по построению: у порта окружения задачи
  НЕТ операции snapshot; единственный `docker commit` — в провижининге заготовки
  (свежий контейнер из base → setup.sh → commit → снос), снимок гномьей коробки
  невозможен физически (иначе зараза персистится между задачами); copy-on-write
  Docker даёт read-only образ контейнерам бесплатно. Уборка: LABEL
  `gnomish.project=<proj>`, после сборки новой снести прочие, prune при старте
  (зеркало worktree prune). Оператору в docs: пиновать версии в setup.sh
  (дисциплина lockfiles) — TTL тогда страховка.
- **Взаимная изоляция задач на macOS-dev — РЕШЕНО (2026-07-20)**: в Change A
  принимаем residual risk общей Docker-VM (dev-машина, доверенный envelope до
  Д7; граница «гном↔Mac» аппаратная, слабее только task↔task). Apple `container`
  (стабильная 1.0 с 09.06.2026, microVM на контейнер, ядро Kata, только
  Apple Silicon + macOS 26) — будущий альтернативный адаптер того же порта, не
  отдельный change. Блокеры сейчас: не Docker-совместимый CLI (второй адаптер);
  у `container network create` НЕТ документированного internal-режима (наш
  егресс-слой 2 может не воспроизвестись 1:1); exec/commit-аналоги не
  документированы. Спайк-чеклист перед принятием: no-egress сеть, exec в
  долгоживущий контейнер, аналог commit для заготовки.
- microVM-тир (Firecracker/Docker Sandboxes) — для SaaS/мультитенанта; порт
  непрозрачен, так что это адаптер, не редизайн.

## Источники (ключевые)

- Anthropic: sandbox-runtime (github.com/anthropic-experimental/sandbox-runtime);
  code.claude.com/docs/en/sandboxing; anthropic.com/engineering/claude-code-sandboxing;
  anthropic.com/engineering/how-we-contain-claude (инциденты: allowlist-as-capability,
  pre-trust config execution); code.claude.com/docs/en/claude-code-on-the-web
  (два прокси: security + git с семантической политикой).
- OpenAI Codex: developers.openai.com/codex/cloud/internet-access (две фазы,
  GET-only опция); codex-universal образ + setup-скрипты; CLI: Seatbelt/bubblewrap+
  Landlock+seccomp, сеть off by default.
- GitHub Copilot coding agent: firewall с allowlist + дены в PR;
  copilot-setup-steps.yml; push только в `copilot/*`, draft-PR, human gate на CI.
- Harvest-прецеденты: docs.docker.com/ai/sandboxes/workflows (clone mode + git
  daemon); github.com/dagger/container-use.
- Креды: docs.docker.com/ai/sandboxes/security/credentials (proxy-injected
  sentinel); GitHub App installation tokens (1h, per-repo, permissions subset);
  docs.litellm.ai/docs/proxy/virtual_keys (бюджеты).
- Технологии: gvisor.dev/docs (перформанс, syscall-штормы); Northflank-сравнения
  Kata/Firecracker/gVisor; docker.com/blog/why-microvms-the-architecture-behind-
  docker-sandboxes; paolomainardi.com (bind-mount на Mac, VirtioFS).
- Egress-механика: innoq.com/en/blog/2026/03/dev-sandbox-network (nftables
  backstop + прокси); docs.gradle.org/current/userguide/networking.html +
  gradle/gradle#11065 (JVM игнорирует proxy-env); mattolson/agent-sandbox.
- Инциденты: Nx «s1ngularity» (supply-chain → AI CLI); CamoLeak (exfil через
  доверенную инфраструктуру); Amazon Q wiper (конфиг агента как поверхность);
  Devin env-leak (секреты в env агента будут запрошены инъекцией).
