# Бэклог explore: распил на модули + плагинная архитектура

> Временный файл — реестр решений explore-сессии по модуляризации (2026-08-12).
> Не для коммита. Сырьё для `/opsx:propose` будущих changes A/B/C (см. низ).
> Родственные заметки: `explore-notes-sandbox.md` (sandbox-бэкенды),
> `explore-notes-tracker-port.md` (порт трекера).

## Триггер

Медленная сборка; особенно долгий прогон мутационных тестов (PIT) по всему
проекту. Кодовая база выросла: 748 production Java-классов, 594 Spock-спека,
**один** Gradle-модуль, `build.gradle` на 796 строк. PIT зашит в `check` и
локально гоняет полное дерево (748 классов). Выбрано структурное лечение —
распил на модули (вариант 4); быстрые правки PIT/forks сознательно отложены.

## Ключевые находки по коду

- `domain` уже чист: не импортирует `app`/`adapter` — готовое ядро.
- `app` делает двойную работу: сценарии + composition root. 53 из 196 файлов
  `app` импортируют `adapter` (цех перемешан с логикой) — главный узел распила.
- Порт трекера уже почти-SPI: интерфейс `Tracker`, SPI-фабрика
  `TrackerAdapterFactory`, дискриминатор `tracker.type`, реестр. Единственная
  жёсткость — реестр это `Map.of("github", ..., "inmemory", ...)`.
- «GitHub» — три пакета: `adapter/github` (7, общий http-клиент/rate-limit/
  cache/retry) + `adapter/tracker/github` (44) + `adapter/check/github` (18).
  И tracker, и check делят общее github-ядро. Нулевая утечка наружу.
- Порт проверок (`ExternalCheckClient`) — github зашит, поля `provider` нет,
  реестра нет. Реальный CI-провайдер один (github); остальные «реализаторы» —
  декоратор (`PinChecked…`) и консольный фолбэк (`Interactive…`).
- `VerifyOrchestrator` уже гоняет `List<VerifyCheck>` в цикле с exhaustive
  `switch` по sealed-вариантам (Builtin/Command/External/Judge) — список
  разнотипных проверок в стадии уже работает и исполняется.
- Порт песочницы (`TaskExecutionEnvironment`) устроен сложнее: capability-passport
  negotiation (`CapabilityPassport` на 4 измерения) + reconciliation нужд стадии
  против паспорта, fail-closed (FR14). `IsolationLevel`/`AdapterBinding` —
  «открытый набор», но сегодня закрытый sealed-тип (константы редактируют core).
- `EgressGuard` стережёт egress **песочницы агента** (mitmdump-контейнер), НЕ
  исходящие вызовы самой фабрики. `ExternalPolling` выполняется из процесса
  фабрики.

## Принятые решения

### Общее направление
- **DEC-1.** Лечим медленную сборку/мутации распилом на Gradle-модули (вариант 4).
  Быстрые правки (PIT из `check`, incremental, `maxParallelForks`) не делаем.

### Модульный распил
- **DEC-2.** Слои по ports&adapters: `domain` / `application` / `adapters:*` /
  `bootstrap`. `domain` уже чист.
- **DEC-3.** `app` разделяется: `application` (сценарии + порты) и `bootstrap`
  (composition root, Spring-wiring, `main()`). Причина — 53/196 файлов тянут адаптеры.
- **DEC-4.** Общая конфигурация сборки → `build-logic` (convention-плагины);
  `build.gradle` модулей становится тонким (сейчас монолит 796 строк).
- **DEC-5.** Два захода: (1) горизонтальный (слои + build-logic); (2) вертикальный
  (`adapters` по технологиям + `:sandbox:*`).
- **DEC-6.** Тонкий публикуемый версионируемый артефакт `gnomish-plugin-api` —
  контракт для третьих сторон: порты + SPI-фабрики + типы конфига + `SecretsProvider`
  + SPI-валидаторы. Внутренности `application`/`domain` меняются свободно; api по semver.
- **DEC-7.** Общие тест-фикстуры (`testsupport`) → `:test-fixtures` или gradle
  `java-test-fixtures`.

### Плагинная архитектура
- **DEC-8.** Обнаружение через Java `ServiceLoader` вместо зашитого `Map.of(...)`.
  Механизм един для встроенных и внешних; различается только упаковка.
- **DEC-9.** Плагин = вендорная интеграция, реализующая несколько портов из одного
  jar поверх приватного ядра (github = tracker + checks + общий github-http).
  Приватное ядро не входит в api.
- **DEC-10.** inmemory остаётся в ядре (эталон + тест-дубль). GitHub выносится в
  плагин — как приёмка плагинной архитектуры. Дистрибутив по умолчанию = core +
  вложенный github-jar: встроенный путь ≡ сторонний путь.
- **DEC-11.** SPI-фабрики создаются ServiceLoader'ом пустым конструктором;
  зависимости (`SecretsProvider`, конфиг) передаются аргументами методов.
- **DEC-12.** «Готовность порта к плагину» = ① SPI-фабрика с `type()`/`provider()`,
  ② реестр через ServiceLoader, ③ секция конфига + SPI-валидатор, ④ выбор по
  дискриминатору, независимо per-port.
- **DEC-13.** Упаковка независима от выбора; выбор per-port. Смешение вендоров между
  портами (github-трекер + gitlab-проверки) — штатный случай; неиспользуемые
  провайдеры дремлют.

### Порт проверок (check)
- **DEC-14.** Довести check-порт до паттерна трекера: поле `provider`, реестр
  `Map<provider, CheckClientFactory>` через ServiceLoader, SPI `CheckParamsValidator`
  per-provider.
- **DEC-15.** Выбор провайдера — per-check в манифесте стадии (не per-project). Одна
  стадия держит несколько `external` от разных вендоров, каждый резолвится независимо.
- **DEC-16.** `External` получает `provider` + opaque `params` (плоские JDK-типы,
  Jackson-free, как `Builtin.params`); `interval/timeout/timeoutClass/pinPaths`
  остаются движково-общими. Новый sealed-вариант не заводим.
- **DEC-17.** Разрез безопасности: `provider` + несекретные селекторы — в манифесте
  репо; креды по имени через `SecretsProvider` на уровне фабрики, никогда в
  коммитимом манифесте (NFR-S1).
- **DEC-18.** Идентичность проверки для корреляции findings = `provider` + `checkId`.
  Миграция: `external:` без провайдера → дефолт `provider: github`.
- **DEC-19.** Приёмочный сценарий: одна стадия = локальный скрипт (`Command`) +
  SonarQube code-smells + сборка на GitHub Actions — три провайдера.

### Дженерик HTTP-проверка
- **DEC-20.** Встроенный `provider: http` — универсальный escape-hatch внешних
  проверок (симметрично `command` для локальных). Шипается в core, через тот же реестр.
- **DEC-21.** Контракт вердикта — декларативный `pass_when` (status 2xx по умолчанию
  + опц. jsonPath/regex + `equals`) и опц. `pending_when` для опроса до терминального;
  переиспользует poll-loop `External`. Разовый зонд = вырожденный опрос.
- **DEC-22.** Авторизация через `SecretsProvider` по имени, заголовок в рантайме (NFR-S1).
- **DEC-23.** Новое требование безопасности: factory-side egress-allowlist для
  http-проверки (SSRF/exfil-вектор; `EgressGuard` стережёт только песочницу). Только
  https; блок link-local/метаданных/RFC1918 кроме разрешённых; лимиты редиректов/
  размера/времени; интерполяция `${...}` только из белого списка; аллоулист из
  конфига оператора, не из манифеста.

### Песочница (execution-environment)
- **DEC-24.** Распил песочницы: `:sandbox:core` + `:sandbox:docker` / `:sandbox:colima`
  / `:sandbox:gha` / `:sandbox:cloud` — тяжёлые SDK уходят из ядра. Контракт порта не
  меняется, можно делать параллельно.
- **DEC-25.** Sandbox-плагины — только first-party. Самодекларированный
  `CapabilityPassport` из недоверенного jar = дыра (песочница = граница доверия). Ввод
  порта в плагинную модель — позже, после стабилизации.
- **DEC-26.** Выбран вариант (b): открыть реестр `AdapterBinding` сейчас (sealed-enum
  → discovered-реестр), до colima/gha/cloud. Приостанавливаем активные sandbox-changes
  и продолжаем после распила — эти бэкенды лягут как первые discovered-бэкенды.
- **DEC-27.** Модель «паспорт возможностей + reconciliation» (оператор биндит, репо
  только ужесточает, fail-closed при несовпадении) сохраняется через пликанизацию.

### Уровни портов
- **DEC-28.** Три уровня: ① внешние плагины — tracker, external-checks, (позже)
  ai-provider, sandbox(first-party); ② граница модуля без внешнего API — secrets,
  observability, workspace; ③ остаётся в ядре — clock, sleeper, persistence,
  builtin/command-checks.

## Открытые вопросы

Статусы обновлены при оформлении change A (`split-into-modules`, 2026-08-12).
Q1/Q5/Q7 закрыты в A; остальные перепривязаны к B/C.

- **Q1.** ✅ РЕШЁН (A / design D1). Глубина вертикального распила `adapters`:
  по вендорным швам — `:adapters:github` (бандл github+tracker/github+
  check/github, shared-http внутренним пакетом), `:adapters:git`,
  `:adapters:agent`; `inmemory` в ядре; мелочь (≤25, в осн. ≤4 файла) держим
  крупно. Модуль оправдан только выигрышем PIT-скоупинга или вендорной границей.
- **Q2.** 🔴 ОТКРЫТ → change **B**. github-плагин: модуль в этом же репо (лидируем)
  vs отдельный репозиторий.
- **Q3.** 🟡 отложено (A / NG7). Плоский classpath — решено и зафиксировано; папка
  `plugins/` со своими загрузчиками — позже.
- **Q4.** 🔴 ОТКРЫТ → change **B**. Доверие/безопасность загрузки сторонних jar
  (исполнение чужого кода в привилегированном процессе с кредами).
- **Q5.** ✅ РЕШЁН (A / design D4, spike выполнен). Поверхность `gnomish-plugin-api`:
  порты tracker/secrets/check + SPI-фабрики + валидатор `TrackerSubsectionValidator`;
  доменные value-типы остаются в `:domain`; `DoNotMutate` во внутреннем shared;
  5 протечек github→adapter резолвятся по таблице (4 через порты/DEC-11,
  `FindingsSanitizer` → util).
- **Q6.** 🔴 ОТКРЫТ → change **B**. Эргономика конфига, когда один вендор обслуживает
  два порта (github tracker + github checks): общий блок connection/creds vs
  дублирование per-port.
- **Q7.** ✅ РЕШЁН (A / design D10). japicmp: `maven-publish`+semver и japicmp в
  report-only сейчас; failing-gate — в change B при первом внешнем потреблении api.
  Остаточный под-вопрос (не блокирует A): на какой версии api флипаем gate.
- **Q8.** 🔴 ОТКРЫТ → change **B**. Для http-проверки — финальный «язык» `pass_when`
  (jsonPath, regex, оба?).
- **Q9.** 🔴 ОТКРЫТ → change **C** (open-adapter-binding-registry). Как именно открыть
  `AdapterBinding` и мигрировать HOST/CONTAINER в discovered-реестр.

## Предлагаемая разбивка на changes (подтвердить)

```
A. split-into-modules
   слои + build-logic + gnomish-plugin-api + test-fixtures + :sandbox:* + per-adapter
   (заход 1 → заход 2)

B. add-plugin-architecture         (зависит от A: нужен gnomish-plugin-api)
   ServiceLoader-обнаружение; check-порт до паттерна (provider, реестр, валидатор);
   per-check провайдеры; дженерик http-проверка + factory-side egress-allowlist;
   приёмка = GitHub вынесен в плагин

C. open-adapter-binding-registry   (зависит от A; вариант (b))
   AdapterBinding: sealed-enum → discovered-реестр; миграция HOST/CONTAINER;
   сохранение passport-reconciliation; first-party sandbox-плагин путь

ПАУЗА: add-sandbox-hardening / -colima-vm / -gha-executor / -cloud-executor
   → возобновить после A (и C), реализовать как discovered-бэкенды

Порядок: A → (B ∥ C) → возобновить приостановленные sandbox-changes
```

Явно вне скоупа сейчас: ai-provider плагинизация (judge→voter — тот же паттерн,
но потом); secrets/observability как внешние плагины (пока только модульная граница).
