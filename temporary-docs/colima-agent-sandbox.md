# Изолированная песочница coding-агента на Colima

Локальный контур: машины разработчиков на macOS и Linux.

---

## 1. Ключевое решение

**Граница безопасности — виртуальная машина, а не контейнер.**

Testcontainers требует доступ к Docker-сокету. Доступ к сокету равен root в VM: можно создать сеть без флага `internal`, запустить контейнер с `--network host`, смонтировать корень. Поэтому любая конструкция внутри VM — internal-сети, непривилегированный пользователь, nftables в госте — это второй эшелон, а не барьер.

Из этого следует всё остальное в документе: прокси на хосте, фильтр на хосте, монтирования выключены, урожай через SSH.

### Приоритеты угроз

1. Побег из сэндбокса к чужим тенантам
2. Утечка секретов и токенов
3. Компрометация машины разработчика
4. Утечка исходного кода наружу

### Область применимости

Colima закрывает **только локальный контур**. Мультитенантный облачный контур — отдельный пилот на E2B или Northflank, критерий отбора: поднимается ли DinD внутри их сэндбокса. Модели угроз разные, не смешивать.

---

## 2. Конфигурация Colima

### Запуск

```bash
colima start agent \
  --vm-type vz \
  --network-address \
  --mount=none \
  --port-forwarder=none \
  --cpu 4 --memory 8 --disk 60
```

Одна VM на задачу. Уничтожение — обязательный шаг протокола, а не уборка по крону.

### `~/.colima/agent/colima.yaml`

```yaml
cpu: 4
memory: 8
disk: 60

vmType: vz
mountType: virtiofs

# Монтирования выключены полностью.
# По умолчанию Colima монтирует ~ НА ЗАПИСЬ.
mounts: []

# Проброс SSH-агента. Никогда не включать:
# с ним агент внутри VM пользуется ключами хоста,
# включая ключи к git-серверу.
forwardAgent: false

network:
  address: true

# DNS резолвит только прокси.
dns:
  - 127.0.0.1
```

### Почему каждый флаг

| Флаг | Что закрывает |
|---|---|
| `--mount=none` | Colima по умолчанию монтирует домашний каталог **на запись**. Без этого агент читает `~/.ssh`, `~/.aws/credentials`, `~/.config/gh`, а также приватный ключ Lima — и заходит по нему в соседние сэндбоксы. Тенант-побег без единого эксплойта. |
| `forwardAgent: false` | Иначе агент пушит твоими ключами. |
| `--port-forwarder=none` | Убирает направление гость → хост. |
| `--vm-type vz` | Аппаратная изоляция через Virtualization.framework + virtiofs. При недоступности VZ Colima **молча откатывается на QEMU**, а там дефолтный `mountType: sshfs` экспонирует ФС хоста через SFTP-сервер на хосте. Проверять явно. |

> Документация Colima рекомендует `--mount=none` именно для CI/CD и AI-агентов.

### Проверка после старта

```bash
colima list                      # vmType должен быть vz
colima ssh -p agent -- mount | grep -E 'virtiofs|9p|sshfs'   # пусто
colima ssh -p agent -- ls /Users 2>&1                        # No such file or directory
```

---

## 3. Сеть

Топология «идти некуда», поднятая на уровень VM.

```
┌─ macOS / Linux host ──────────────────────────────┐
│                                                   │
│   mitmproxy :8080 ──────────────► интернет        │
│        ▲                          (allowlist)     │
│        │                                          │
│   pull-through cache :5000 ─────► registry        │
│        ▲                                          │
│   ─────┼────── pf / nftables ──────────────────   │
│        │       block out all                      │
│  ┌─────┴──────────────────────┐                   │
│  │  Colima VM                 │                   │
│  │  агент + Docker + tests    │                   │
│  └────────────────────────────┘                   │
└───────────────────────────────────────────────────┘
```

### Слой 2 — фильтр на хосте

Определить интерфейс и адрес VM:

```bash
colima list -j | jq -r '.address'
ifconfig | grep -B4 "$(colima list -j | jq -r '.address' | cut -d. -f1-3)"
```

> Имя интерфейса на macOS зависит от режима (`bridge100`, `vmenet*`) и версии. Определять эмпирически, в скрипт запуска подставлять динамически.

`/etc/pf.anchors/colima-agent`:

```
# Переменные подставляет скрипт запуска
# vm_if    — интерфейс VM на хосте
# vm_ip    — адрес VM
# proxy_ip — адрес, на котором слушает mitmproxy

block drop out log quick on $vm_if from $vm_ip to any
pass out quick on $vm_if from $vm_ip to $proxy_ip port 8080
pass out quick on $vm_if from $vm_ip to $proxy_ip port 5000
```

Подключение якоря в `/etc/pf.conf`:

```
anchor "colima-agent"
load anchor "colima-agent" from "/etc/pf.anchors/colima-agent"
```

```bash
sudo pfctl -f /etc/pf.conf && sudo pfctl -e
```

**Блокировать надо и выход в интернет, и обращения к самому хосту** — иначе агент найдёт открытый Remote Login или другой локальный сервис.

На Linux — то же самое через nftables на соответствующем интерфейсе.

### Слой 1 — mitmproxy

Два пути трафика:

| Трафик | Через что | Режим |
|---|---|---|
| API модели, git, пакетные менеджеры | mitmproxy | вскрытие TLS, allowlist по путям, инъекция токенов |
| Слои Docker-образов | pull-through cache напрямую | без вскрытия, один разрешённый адрес |

Образы через mitmproxy не гонять: Python не предназначен для гигабайтных блобов.

Запуск:

```bash
mitmdump \
  --listen-port 8080 \
  --set stream_large_bodies=1m \
  --set termlog_verbosity=info \
  -s ~/agent-sandbox/policy.py
```

`mitmdump`, а не `mitmproxy` — второй тянет TUI и держит все флоу в памяти, на длинной сессии это утечка.

### `policy.py`

```python
import os
import json
import logging
from mitmproxy import http

ALLOW = {
    "api.anthropic.com":  ["/v1/"],
    "github.com":         ["/myorg/"],
    "registry.npmjs.org": ["/"],
    "pypi.org":           ["/simple/"],
    "files.pythonhosted.org": ["/"],
    "repo.maven.apache.org":  ["/maven2/"],
}

INJECT = {
    "api.anthropic.com": ("x-api-key", "ANTHROPIC_API_KEY"),
    "github.com":        ("authorization", "GITHUB_TOKEN"),
}

log = logging.getLogger("policy")


def _deny(flow, reason):
    log.warning(json.dumps({
        "event": "denied",
        "reason": reason,
        "host": flow.request.pretty_host,
        "path": flow.request.path,
        "method": flow.request.method,
    }))
    flow.response = http.Response.make(
        403, b"blocked by sandbox policy",
        {"Content-Type": "text/plain"},
    )


def request(flow: http.HTTPFlow) -> None:
    host = flow.request.pretty_host
    prefixes = ALLOW.get(host)

    if prefixes is None:
        return _deny(flow, "host not in allowlist")

    if not any(flow.request.path.startswith(p) for p in prefixes):
        return _deny(flow, "path not in allowlist")

    # Инъекция секретов: агент токенов не видит
    if host in INJECT:
        header, env_var = INJECT[host]
        value = os.environ.get(env_var)
        if value:
            flow.request.headers[header] = value

    log.info(json.dumps({
        "event": "allowed",
        "host": host,
        "path": flow.request.path,
    }))
```

**Логи содержат расшифрованный трафик.** Тела не писать: там окажутся токены, которые сам же и подставил. Только метаданные и факт отказа.

**Прокси — самая ценная мишень в системе.** Единственное место с открытым текстом и настоящими кредами. Живёт на хосте, его конфиг агенту недоступен ни в каком виде.

---

## 4. CA-сертификат в образе VM

Каждый рантайм имеет своё хранилище доверия. Всё запекается в образ заранее.

```bash
# Системный store
cp mitmproxy-ca-cert.pem /usr/local/share/ca-certificates/mitmproxy.crt
update-ca-certificates

# Node
export NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/mitmproxy.crt

# Python
export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt

# JVM — переменные окружения игнорирует
keytool -importcert -noprompt \
  -keystore "$JAVA_HOME/lib/security/cacerts" \
  -storepass changeit \
  -alias mitmproxy \
  -file /usr/local/share/ca-certificates/mitmproxy.crt
```

`gradle.properties`:

```properties
systemProp.https.proxyHost=192.168.x.x
systemProp.https.proxyPort=8080
systemProp.http.proxyHost=192.168.x.x
systemProp.http.proxyPort=8080
```

Отдельно: `GRADLE_OPTS` для обёртки `gradlew`, `settings.xml` для Maven.

> Инструменты с certificate pinning сломаются, и починить это нельзя — только вынести хост в `--ignore-hosts`. Проверить заранее, есть ли такие в тулчейне.

---

## 5. Прокси для демона Docker

**Образы тянет демон, а не клиент.** `HTTP_PROXY` в окружении агента на `docker pull` не влияет.

`/etc/systemd/system/docker.service.d/http-proxy.conf` внутри VM:

```ini
[Service]
Environment="HTTP_PROXY=http://192.168.x.x:8080"
Environment="HTTPS_PROXY=http://192.168.x.x:8080"
Environment="NO_PROXY=localhost,127.0.0.1"
```

```bash
systemctl daemon-reload && systemctl restart docker
```

Registry-mirror в `/etc/docker/daemon.json`:

```json
{
  "registry-mirrors": ["http://192.168.x.x:5000"]
}
```

---

## 6. Git: рабочая копия и сбор урожая

Клон живёт внутри VM. Это единственный вариант, совместимый с `--mount=none`.

### Подготовка

```bash
# Внутри VM, из копии в образе — без сети и без ключей
git clone --no-hardlinks /opt/seed/repo.git /work/repo
```

`--no-hardlinks` **обязателен**. Без него клон коробки физически делит объекты с исходным клоном, и агент может испортить репозиторий фабрики ниже уровня git-механики — в обход всей модели безопасности.

Внутри клона:

```bash
git config user.name  "agent"
git config user.email "agent@sandbox.local"
git config gc.auto 0        # одноразовый клон, фоновая упаковка не нужна
```

Ключей и адреса сервера внутри нет.

### Сбор урожая

```bash
git fetch "ssh://$VM_HOST/work/repo" \
  task-branch:refs/heads/task-branch \
  --no-recurse-submodules
```

Параметры подключения — из `colima ssh-config -p agent`, они переживают пересоздание VM.

### Инварианты

- **Рефспек фиксированный**, задаётся фабрикой. Никогда не подставлять имена, полученные из VM — иначе агент сам решает, куда в твоём клоне что положить.
- **Fast-forward по умолчанию.** При явном рефспеке без префикса `+` git откажет на переписанной истории. Инвариант «ветку задачи не переписывать силой» сохраняется сам собой.
- **Хуки через fetch не передаются** — они локальны для клона. Наружу вредный скрипт попадёт только как обычный файл в ветке, а её смотрит человек.
- **Недоверенную ветку на хосте не выкладывать до ревью.** Забор в ref безопасен, checkout — уже нет: работают `.gitattributes`, фильтры, симлинки. Смотреть diff, не рабочую копию.
- `--no-recurse-submodules` — не инициализировать ничего по ссылкам изнутри коробки.

---

## 7. Отслеживание коммитов

Основной сигнал — **окончание раунда**, фабрика знает о нём первой. Наблюдение за ФС нужно только для коммитов в середине раунда.

| Механизм | Задержка | Когда |
|---|---|---|
| `git fetch` по таймеру | до N сек | по умолчанию |
| `git rev-parse` по SSH | до N сек | если репозиторий крупный |
| `inotifywait` через SSH | миллисекунды | если нужна живая лента для человека |

Начинать с первого: повторный fetch того же хеша практически no-op.

### SSH-мультиплексирование — обязательно

`~/.ssh/config`:

```
Host colima-agent
  ControlMaster auto
  ControlPath ~/.ssh/cm-%r@%h:%p
  ControlPersist 10m
```

Без него каждый опрос — TCP-хендшейк плюс аутентификация.

### Блокирующее ожидание

```bash
ssh colima-agent 'inotifywait -qq -e modify /work/repo/.git/logs/HEAD'
```

Смотреть на `.git/logs/HEAD`, а не на `.git/refs/heads/*`: ref-ы могут быть упакованы в `packed-refs`, и наблюдение за файлом молча перестанет работать.

**Ограничение частоты — на стороне хоста.** Если детекция управляется событиями изнутри VM, агент может устроить шторм заборов в цикле.

---

## 8. Чек-лист проверок

Эти три теста важнее остальной работы. Пока не зелёные — схемы нет.

- [ ] **Testcontainers поднимает Postgres при заблокированном egress.**
      Проверяет registry-mirror, прокси демона, allowlist разом.
- [ ] **`dig @8.8.8.8 example.com` изнутри VM не отвечает.**
      Host resolver Lima проксирует DNS мимо прокси — известный канал утечки, данные кодируются прямо в именах.
- [ ] **Тесты, пробрасывающие файлы с хоста, не сломались от `--mount=none`.**
      Если сломались — фикстуры и init-скрипты переезжают в репозиторий.

Дополнительно:

- [ ] `colima ssh -- ls /Users` возвращает ошибку
- [ ] `vmType` в `colima list` равен `vz`, не `qemu`
- [ ] `forwardAgent` в конфиге равен `false`
- [ ] SSH на хост изнутри VM не проходит
- [ ] `git fetch` отвергает переписанную историю
- [ ] Отказы прокси попадают в лог

---

## 9. Отложено

| Что | Когда пересмотреть |
|---|---|
| **Apple `container` + socktainer** | Через полгода. Частичная совместимость с Docker API, жёсткая привязка версий, последний релиз socktainer старше выхода `container` 1.0. Можно поставить на одну машину и прогнать тесты — чтобы знать, готово ли к миграции. |
| **DiskImageKit** | Когда парк переедет на macOS 27. Golden image + copy-on-write оверлей на сессию убирает главную боль эфемерных VM — холодный старт. |
| **vmnet per-VM NAT** | Там же. Заменит самодельные правила pf на штатный механизм. |
| **Tart** | Только если понадобятся macOS-гости (сборка iOS). Команда ушла в OpenAI, в фундамент не класть. |

---

## 10. Самая дорогая часть работ

Не инфраструктура.

Перестройка git-механики фабрики — возобновление прерванной задачи, спасение недоделанной работы, запись служебного файла состояния. Сейчас всё это написано в расчёте на локальный путь на диске, а переезжает на работу через транспорт. Смысл тот же, мест много, каждое надо перенести и перепроверить.

Закладывать отдельной строкой от инфраструктурной части.
