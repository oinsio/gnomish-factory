# Сводка решений по будущим changes

> Временный файл — сырьё для будущих proposal/design. Не для коммита.
>
> **Статус на 2026-08-20:** файл вычищен от реализованного. Changes 2–3, весь
> factory loop (tracker-port, claim-heartbeat, factory-serve), sandbox core и
> external-чеки — в архиве и на main; контракт status-report живёт в
> `openspec/specs/status-report/`, дельта «external-таймаут quality|infra» — в
> `openspec/specs/pipeline-config/`. Ниже — только нереализованное.

## Мелкие delta pipeline-config (когда появится потребитель)

- Опциональные явные `id` у элементов verify-списка (сейчас идентичность = индекс + производная метка).
- Пути/физика у `ArtifactOutput` (сейчас артефакты логические — только id; физика в instructions + files_exist-params).
- Submit-хук для external-чеков (NG8 движка): когда появится чек, который нельзя запустить push'ем ветки.

## Прочие зафиксированные решения на будущее

- **Ревью — это QC-чек порождающей стадии, не отдельная стадия.** Cross-stage backtracking (возврат назад по пайплайну) — non-goal; если реальная практика потребует стадий-ревьюеров с откатом — отдельный change с семантикой «позиция назад + инвалидация артефактов промежуточных стадий».
- **Бюджеты токенов/денег** — отложены; шов — usage-поля AttemptRecord; для денег нужен прайс-каталог моделей (учёт токенов уже есть в ledger — `tokensByModel`; enforcement нет).
- **ai-provider порт** — приедет с реальными api-executor и judge-адаптерами (сейчас `executor.type: api` отклоняется на старте).
- **Структурированные options у DecisionNeeded** (label + rationale вместо строк) — когда tracker-адаптер покажет, что строк мало.
