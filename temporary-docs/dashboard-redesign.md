# Dashboard redesign — task for Claude Code

Redesign the generated status page (`dashboard.html`) of Gnomish Factory. This
document is the complete brief: context, constraints, information architecture,
ready-to-use CSS/HTML/JS, and acceptance criteria. Follow it exactly; where it
leaves a choice open it says so.

Scope: **one PR**. Markup, styles, number/time formatting, and the extraction of
CSS/JS into resource files all land together.

---

## 1. Context

The page is a wallboard for an orchestrator in which humans are exception
handlers, not participants. It is watched two ways: parked on a second monitor
for hours, and opened in a tab for a few seconds at a time.

Both modes ask the same question, and the page must answer it before the reader
focuses on anything: **is anything waiting for me right now?**

Everything else — throughput, token spend, sweep state — is context the reader
consults deliberately, not something that should compete for attention.

The current page fails at this in six ways, and the redesign must fix all six:

| Problem | Fix |
| --- | --- |
| Four sections of equal visual weight; `awaitingHuman` is buried in a table column | Four explicit priority layers (§3) |
| Four ISO-8601 UTC timestamps the reader has to subtract mentally | Relative time with a live ticker (§6) |
| Raw counts like `4305175` | Compact formatting server-side (§7) |
| Empty `<ul>` reads as breakage, not as "nothing pending" | Explicit empty states (§5) |
| Staleness banner covers the whole viewport, hiding the last known state | Persistent freshness strip + card dimming (§5.1) |
| Staleness is computed once at load, so a dead renderer can go unnoticed forever | `setInterval` re-check every second (§8) |

---

## 2. Constraints

- **Output is one self-contained HTML file.** No external CSS, JS, fonts, or
  images. It must render correctly opened over `file://` with no network.
- **Source is not one file.** `dashboard.css` and `dashboard.js` live under
  `src/main/resources/dashboard/` as normal files (IDE support, Spotless,
  reviewable diffs) and are inlined into the output at render time. See §9.
- **Automatic theme** via `prefers-color-scheme`. No theme toggle, no persisted
  preference — there is nowhere to persist it.
- **System fonts only.** The stacks in §4 are chosen so the page looks
  deliberate on macOS, Windows, and Linux without downloading anything.
- **JavaScript is an enhancement, not a requirement.** With JS disabled the page
  must still show every number and every timestamp — just in absolute form,
  without the live ticker. Never render a value only from JS.
- **Keep `<meta http-equiv="refresh" content="10">`.** It is the fallback that
  makes the page correct even when scripting fails.

---

## 3. Information architecture

Four layers, top to bottom, in strict order of how urgently the reader needs
them. Do not reorder, and do not let a later layer grow visually louder than an
earlier one.

```
1  Freshness strip     can I trust what I am looking at?
2  Status line         is the daemon alive, and is a slot busy?
3  Waiting for a human  <- the loudest block on the page
4  In progress
─────────────────────  everything below is reference, visually quieter
5  Outcomes by day
6  Tokens
7  Sandbox hygiene
```

Two rules that matter more than they look:

**Blocks never appear or disappear.** "Waiting for a human" occupies the same
position whether the queue holds two tasks or none — only its contents and its
border change. A reader who has learned where to look must keep being right, and
an empty queue must read as a deliberate "all clear" rather than as a missing
section.

**Staleness degrades, it does not block.** When the renderer stops, the page
dims its cards and turns the freshness strip red. The stale data stays readable,
because "what was the last known state before it died?" is exactly the question
being asked at that moment.

---

## 4. Design tokens

Put this at the top of `dashboard.css`. Every colour in the stylesheet must come
from a variable here — no literal hex below this block.

The palette is a cool industrial neutral (this is a factory floor readout, not a
document) with three semantic roles carried by hue, and a separate hue family for
token accounting so spend never reads as an alarm.

```css
:root {
  color-scheme: light dark;

  --bg:            #f4f5f7;
  --surface:       #ffffff;
  --sunken:        #e8eaee;
  --text:          #14171a;
  --text-dim:      #5b6570;
  --text-faint:    #8b95a1;
  --border:        #dfe3e8;
  --border-strong: #c3cad2;

  --ok-fg:   #2f7d32;  --ok-bg:   #e7f4e7;  --ok-dot:  #3f9c42;
  --warn-fg: #8a5300;  --warn-bg: #fdf0da;  --warn-dot: #d99a2b;
  --bad-fg:  #b3261e;  --bad-bg:  #fce9e8;  --bad-dot: #d9534f;
  --info-fg: #1f6feb;  --info-bg: #e8f0fd;

  --seg-delivered: #4f9d3f;
  --seg-waiting:   #d99a2b;
  --seg-aborted:   #d9534f;
  --seg-revoked:   #8892a0;

  --seg-cache-read:  #74c3a8;
  --seg-cache-write: #2f8f74;
  --seg-in:          #8b7fd4;
  --seg-out:         #574bb0;

  --radius: 10px;
  --hair:   1px solid var(--border);

  --font: system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  --mono: ui-monospace, "SF Mono", "JetBrains Mono", "Cascadia Code", Menlo, Consolas, monospace;
}

@media (prefers-color-scheme: dark) {
  :root {
    --bg:            #0f1216;
    --surface:       #171b21;
    --sunken:        #22272e;
    --text:          #e6eaee;
    --text-dim:      #98a2ad;
    --text-faint:    #6b7681;
    --border:        #262c34;
    --border-strong: #38414b;

    --ok-fg:   #7fd18a;  --ok-bg:   #17301b;  --ok-dot:  #4caf50;
    --warn-fg: #f0b458;  --warn-bg: #33260f;  --warn-dot: #d99a2b;
    --bad-fg:  #f28b84;  --bad-bg:  #38191a;  --bad-dot: #e05a54;
    --info-fg: #7fb0f0;  --info-bg: #12233a;
  }
}
```

The eight `--seg-*` values are deliberately mid-tone and are **not** overridden
in dark mode: they sit on a `--sunken` track in both themes and hold contrast
either way. Do not add dark-mode variants for them.

---

## 5. Blocks

For each block: what it shows, what it does when there is no data, and what it
does when data cannot be trusted.

### 5.1 Freshness strip

The signature element of the page and the only thing that is always saying
something. Full-width, directly under the top edge, above everything else.

- **Fresh** (`data-state="fresh"`): `--ok-bg` / `--ok-fg`, refresh icon,
  text `данные свежие · обновлено N с назад`, where `N` ticks upward every second
  in the browser.
- **Stale** (`data-state="stale"`): `--bad-bg` / `--bad-fg`, warning icon, text
  `вид устарел — рендерер молчит Nм Nс`, and `.is-stale` is set on `<body>`,
  which dims the cards (see the stylesheet).

Because the counter ticks client-side, a dead renderer becomes visible even when
the meta-refresh itself is failing — nothing needs to arrive from the server for
the page to notice it has been abandoned.

Threshold: 30 s, unchanged from the current page. Re-evaluated every second.

This strip **replaces** `#staleness-banner` entirely. Delete that element, its
CSS, and its inline script.

### 5.2 Status line

One card: state dot, human-readable state, instance id in mono, then slots and
consecutive failures right-aligned as two small stat columns.

- Dot and state text: `--ok-dot` / "Демон работает" when alive;
  `--bad-dot` / "Снимок не обновляется" when the snapshot is stale or the daemon
  reports itself down.
- `consecutiveFailures` renders in `--bad-fg` when non-zero, plain otherwise. A
  zero here should be quiet — it is the normal case.

### 5.3 Waiting for a human

The loudest block on the page, and the reason the page exists.

- **Non-empty:** `border: 2px solid var(--warn-dot)`, background `--warn-bg`,
  count in the header, then one row per task: an icon distinguishing *attempts
  exhausted* from *undecidable choice*, the task id in mono, a one-line reason,
  and the age of the escalation right-aligned.
- **Empty:** ordinary card chrome (`--hair`, `--surface`), count `0`, and the
  body reads `Очередь пуста — гномы справляются сами` with a check glyph in
  `--ok-fg`. An invitation to relax, not an apology for having nothing.

I do not know which fields the tracker port exposes. Use, in order of preference:
task id, escalation reason (one line, truncate with ellipsis), escalation
timestamp. If a field is unavailable, drop it rather than substituting a
placeholder — a row of "n/a" is worse than a shorter row. Note in the PR
description what you had to leave out.

### 5.4 In progress

Slots and board contents as compact rows, not `<ul>`. Per row: a status dot in
`--info-fg`, task id in mono, current stage and attempt counter (`стадия
implement · попытка 2 из 3`), and elapsed time right-aligned.

Empty state: `Слот свободен, готовых задач в трекере нет`.

Keep `Ready` and `Working` distinguishable, but do not give each its own heading
and its own empty state — that is three headings for what is usually zero rows.
A single list with the ready items marked is enough.

### 5.5 Outcomes by day

Replaces both the history table and the abstract `volume` bar.

One horizontal stacked bar per day: delivered / awaitingHuman / aborted /
revoked, widths proportional **within the day** so the mix is comparable across
days. Above each bar, the date on the left and the day's total on the right. One
shared legend below all bars, not one per bar.

The absolute daily total is carried by the number, not by bar length — every bar
is full width. Comparing mix is the job here; comparing volume is what the
totals column is for.

Empty state (no days recorded): `Пока нет завершённых задач`.

### 5.6 Tokens

Header: `Токены` on the left, grand total and the covered period on the right.

Per model, a stacked bar of cacheRead / cacheCreation / input / output, then a
caption line that leads with the cache share:
`90% из кэша · in 3.7K · out 28.8K`.

Cache share is the actionable number here — with the current data Sonnet reuses
90% of its context while Haiku uses no cache at all, and that difference was
invisible in the old table. When `cacheRead + cacheCreation` is zero, the caption
reads `кэш не используется` instead of `0% из кэша`.

Cost estimation is explicitly **out of scope** for this PR.

### 5.7 Sandbox hygiene

Not a card. A single dashed-border row in `--text-faint`, visually the quietest
thing on the page: `Уборка песочницы ещё не запускалась`. When sweep data does
exist, it becomes a normal card.

---

## 6. Time

Every timestamp the server writes uses the same element shape:

```html
<time datetime="2026-08-22T15:01:56.639454Z" data-epoch="1787410916639">15:01:56 UTC</time>
```

- `datetime` — full ISO, for machines.
- `data-epoch` — epoch millis, so JS never parses a string.
- Text content — server-rendered absolute time. This is what a reader without JS
  sees, so it must be legible on its own.

`dashboard.js` walks every `<time[data-epoch]>` once per second, replaces the
text with a relative form, and moves the absolute value into `title` so hovering
still gives the exact instant.

Relative形 (Russian, no library):

| Age | Rendering |
| --- | --- |
| < 10 s | `только что` |
| < 60 s | `N с назад` |
| < 60 min | `N мин назад` |
| < 24 h | `N ч назад` |
| otherwise | `N дн назад` |

Plurals: use the standard three-form rule (`1 минуту` / `2 минуты` /
`5 минут`) or stick to the abbreviated forms above, which need no agreement. The
abbreviated forms are preferred — they are shorter and they never look wrong.

---

## 7. Numbers

Format **server-side, in Java**, where the raw values already are. Do not ship
raw longs into the DOM and format them in JS — that breaks the no-JS path.

- Counts ≥ 1000 → compact: `4.79M`, `455K`, `25.6K`. One decimal place, dropped
  when it would be `.0` (`5M`, not `5.0M`). Below 1000, print as-is.
- Put the exact value in `title` on the same element, so the precise figure is
  one hover away.
- Every numeric cell gets `font-variant-numeric: tabular-nums` and `--mono`, so
  digits line up between refreshes and the page does not jitter every 10 s.
- Percentages: integers. `90% из кэша`, never `89.8%`.

Add a small formatting helper alongside the renderer rather than inlining the
logic at each call site — it will need unit tests either way (§9).

---

## 8. Stylesheet

Complete, ready to drop into `src/main/resources/dashboard/dashboard.css` under
the token block from §4.

```css
* { box-sizing: border-box; }

body {
  margin: 0;
  padding: 14px 16px 32px;
  background: var(--bg);
  color: var(--text);
  font: 400 14px/1.5 var(--font);
  -webkit-font-smoothing: antialiased;
}

.wrap { max-width: 880px; margin: 0 auto; }

.num {
  font-family: var(--mono);
  font-variant-numeric: tabular-nums;
}

/* --- freshness strip ------------------------------------------------ */

.freshness {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  margin-bottom: 12px;
  border-radius: var(--radius);
  font-size: 13px;
  background: var(--ok-bg);
  color: var(--ok-fg);
}
.freshness[data-state="stale"] {
  background: var(--bad-bg);
  color: var(--bad-fg);
  font-weight: 500;
}
.freshness svg { width: 16px; height: 16px; flex: none; }

body.is-stale .card,
body.is-stale .footnote { opacity: .45; }

/* --- cards ---------------------------------------------------------- */

.card {
  background: var(--surface);
  border: var(--hair);
  border-radius: var(--radius);
  padding: 1rem 1.25rem;
  margin-bottom: 12px;
  transition: opacity .15s linear;
}
.card__title {
  font-size: 15px;
  font-weight: 500;
  margin: 0 0 10px;
}
.card__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}
.card__meta { font-size: 12px; color: var(--text-faint); }

/* --- status line ---------------------------------------------------- */

.status {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 1.25rem;
}
.status__dot {
  width: 10px; height: 10px; border-radius: 50%;
  background: var(--ok-dot); flex: none;
}
.status--down .status__dot { background: var(--bad-dot); }
.status__id { font-size: 12px; color: var(--text-faint); }
.status__main { flex: 1; min-width: 0; }
.status__state { font-size: 15px; font-weight: 500; }

.stat { text-align: right; padding-left: 14px; }
.stat + .stat { border-left: var(--hair); }
.stat__label {
  font-size: 11px;
  color: var(--text-faint);
  letter-spacing: .03em;
  margin-bottom: 2px;
}
.stat__value { font-size: 17px; }
.stat__value--bad { color: var(--bad-fg); }

/* --- attention ------------------------------------------------------ */

.card--attention {
  border: 2px solid var(--warn-dot);
  background: var(--warn-bg);
}
.card--attention .row { border-color: rgba(217, 154, 43, .35); }

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-top: var(--hair);
  font-size: 13px;
}
.row:first-of-type { border-top: none; }
.row__reason {
  flex: 1;
  min-width: 0;
  color: var(--text-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row__age { color: var(--text-faint); }
.row__dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--info-fg); flex: none;
}

.empty {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 0 2px;
  color: var(--text-dim);
}
.empty--ok { color: var(--ok-fg); }

/* --- bars ----------------------------------------------------------- */

.bar {
  height: 10px;
  border-radius: 5px;
  overflow: hidden;
  background: var(--sunken);
  display: flex;
}
.bar__seg { height: 100%; }
.bar-group + .bar-group { margin-top: 14px; }
.bar-head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 5px;
}
.bar-head__total { color: var(--text-faint); }
.bar-note { font-size: 12px; color: var(--text-dim); margin-top: 5px; }

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-top: 12px;
  font-size: 12px;
  color: var(--text-dim);
}
.legend__swatch {
  display: inline-block;
  width: 8px; height: 8px;
  border-radius: 2px;
  margin-right: 5px;
}

/* --- footnote ------------------------------------------------------- */

.footnote {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 1.25rem;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius);
  color: var(--text-faint);
  font-size: 13px;
}

@media (max-width: 640px) {
  body { padding: 10px 10px 24px; }
  .card { padding: .875rem 1rem; }
  .status { flex-wrap: wrap; }
  .status__main { flex-basis: 100%; }
}

@media (prefers-reduced-motion: reduce) {
  * { transition: none !important; animation: none !important; }
}
```

---

## 9. Script

Complete, for `src/main/resources/dashboard/dashboard.js`. Three jobs: tick the
relative times, re-evaluate staleness, and restore scroll across the
meta-refresh.

```js
(function () {
  "use strict";

  var GENERATED_AT = Number(document.body.dataset.generatedAt);
  var STALE_AFTER_MS = 30000;

  function relative(ms) {
    var s = Math.max(0, Math.round(ms / 1000));
    if (s < 10) return "только что";
    if (s < 60) return s + " с назад";
    var m = Math.round(s / 60);
    if (m < 60) return m + " мин назад";
    var h = Math.round(m / 60);
    if (h < 24) return h + " ч назад";
    return Math.round(h / 24) + " дн назад";
  }

  function duration(ms) {
    var s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return s + "с";
    return Math.floor(s / 60) + "м " + (s % 60) + "с";
  }

  var times = Array.prototype.slice.call(
    document.querySelectorAll("time[data-epoch]")
  );
  times.forEach(function (el) {
    if (!el.title) el.title = el.textContent.trim();
  });

  var strip = document.getElementById("freshness");
  var stripText = document.getElementById("freshness-text");

  function tick() {
    var now = Date.now();

    times.forEach(function (el) {
      el.textContent = relative(now - Number(el.dataset.epoch));
    });

    var age = now - GENERATED_AT;
    var stale = age > STALE_AFTER_MS;
    strip.dataset.state = stale ? "stale" : "fresh";
    document.body.classList.toggle("is-stale", stale);
    stripText.textContent = stale
      ? "вид устарел — рендерер молчит " + duration(age)
      : "данные свежие · обновлено " + relative(age);
  }

  tick();
  setInterval(tick, 1000);

  try {
    var saved = sessionStorage.getItem("gf-scroll");
    if (saved) window.scrollTo(0, Number(saved));
    window.addEventListener("beforeunload", function () {
      sessionStorage.setItem("gf-scroll", String(window.scrollY));
    });
  } catch (e) {
    /* private mode, file:// restrictions — scroll restore is optional */
  }
})();
```

Note `GENERATED_AT` comes from `<body data-generated-at="...">`, not from a
number baked into the script — that keeps the script a static resource with no
templating inside it.

The two icons in the freshness strip are inline SVG in the markup, one shown per
state via CSS, so the script never touches them.

---

## 10. Markup skeleton

Placeholders in `{{...}}`. Structure is fixed; repetition points are marked.

```html
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>gnomish factory</title>
<meta http-equiv="refresh" content="10">
<style>{{INLINED dashboard.css}}</style>
</head>
<body data-generated-at="{{generatedAtMillis}}">
<div class="wrap">

  <div class="freshness" id="freshness" data-state="fresh" role="status" aria-live="polite">
    {{inline svg: refresh icon + warning icon}}
    <span id="freshness-text">обновлено {{absolute time}}</span>
  </div>

  <div class="card status">
    <span class="status__dot"></span>
    <div class="status__main">
      <div class="status__state">{{Демон работает | Снимок не обновляется}}</div>
      <div class="status__id num">{{instanceId}}</div>
    </div>
    <div class="stat">
      <div class="stat__label">слоты</div>
      <div class="stat__value num">{{occupied}} / {{total}}</div>
    </div>
    <div class="stat">
      <div class="stat__label">сбои подряд</div>
      <div class="stat__value num">{{consecutiveFailures}}</div>
    </div>
  </div>

  <section class="card{{ card--attention if non-empty}}">
    <div class="card__head">
      <h2 class="card__title">Ждут человека</h2>
      <span class="num card__meta">{{count}}</span>
    </div>
    <!-- repeat per escalated task, or render the .empty block -->
    <div class="row">
      <span class="row__dot"></span>
      <span class="num">{{taskId}}</span>
      <span class="row__reason">{{reason}}</span>
      <time class="row__age num" datetime="{{iso}}" data-epoch="{{millis}}">{{absolute}}</time>
    </div>
  </section>

  <section class="card">
    <h2 class="card__title">В работе</h2>
    <!-- repeat per active task, or render the .empty block -->
  </section>

  <section class="card">
    <h2 class="card__title">Исходы по дням</h2>
    <!-- repeat per day -->
    <div class="bar-group">
      <div class="bar-head">
        <span class="num">{{date}}</span>
        <span class="num bar-head__total">{{total}}</span>
      </div>
      <div class="bar" role="img" aria-label="{{delivered}} доставлено, {{awaiting}} ждут человека, {{aborted}} прервано, {{revoked}} отозвано">
        <span class="bar__seg" style="width:{{pct}}%;background:var(--seg-delivered)"></span>
        <!-- ... waiting / aborted / revoked -->
      </div>
    </div>
    <div class="legend"><!-- four swatches --></div>
  </section>

  <section class="card">
    <div class="card__head">
      <h2 class="card__title">Токены</h2>
      <span class="card__meta num">{{grandTotal}} · {{period}}</span>
    </div>
    <!-- repeat per model: bar-head, bar with four segments, bar-note -->
  </section>

  <div class="footnote">{{sweep summary or "Уборка песочницы ещё не запускалась"}}</div>

</div>
<script>{{INLINED dashboard.js}}</script>
</body>
</html>
```

---

## 11. Wiring the resources in

Find the renderer first — there is no committed path for it in this brief. Grep
`src/main/java` for `staleness-banner`, `generatedAtMillis`, or
`<!doctype html>`; the class that owns those strings is the one to change.

Then:

1. Add `src/main/resources/dashboard/dashboard.css` and `dashboard.js`.
2. Read both **once at construction**, not per render — the page regenerates
   every few seconds and re-reading the classpath each time is pure waste. Cache
   them in final fields.
3. Fail fast: if either resource is missing, throw at construction. A dashboard
   that silently renders unstyled is worse than one that refuses to start.
4. Guard the inlining. A literal `</script>` inside the JS would terminate the
   block early. Either assert at build time that neither file contains `</`
   followed by `script`, or escape it. A test for this is cheap and prevents a
   very confusing bug.
5. Keep the number formatter (§7) and the outcome-percentage arithmetic in
   plain, testable methods, separate from string assembly.

Repo gates that apply: Error Prone + NullAway, Spotless, and the **100% PIT
mutation gate**. The formatter and the percentage maths will need Spock coverage
including the boundaries — exactly 1000, exactly 1M, a `.0` decimal that must be
dropped, a zero total (do not divide by it), and a single-outcome day that must
come out at 100%. Run `./gradlew check` before opening the PR.

---

## 12. Do not

- Do not reintroduce a full-viewport blocking overlay for staleness.
- Do not hide a section when it has no data. Render its empty state.
- Do not add a CDN, a webfont, a chart library, or a build step for the CSS.
- Do not compute a displayed value only in JS.
- Do not scale the outcome bars by daily volume — they are mix, not magnitude.
- Do not colour token spend with the status palette. Spend is not an alarm.
- Do not add animation beyond the one opacity transition already in the CSS.
- Do not write literal hex outside the `:root` blocks.

---

## 13. Acceptance criteria

- [ ] Opened over `file://` with the network disabled, the page renders fully
      styled, with zero failed requests in the network panel.
- [ ] Light and dark both legible; no element disappears into its background in
      either. Check the bar segments and the freshness strip specifically.
- [ ] With the renderer stopped, the freshness strip turns red within 30 s
      **without the page reloading**, the counter keeps climbing, and every card
      remains readable through the dimming.
- [ ] With JS disabled, every number and every timestamp is still present and
      legible in absolute form.
- [ ] Empty queue, empty board, and absent sweep data each show their own
      sentence — no bare `<ul>`, no blank region.
- [ ] Numeric columns do not shift horizontally across a refresh.
- [ ] Hovering any relative time reveals the full ISO instant.
- [ ] At 375 px width nothing overflows horizontally.
- [ ] `./gradlew check` passes, including the mutation gate.
- [ ] `#staleness-banner` and its inline script no longer exist anywhere.

---

## 14. Later, not now

Emit `dashboard.json` next to the HTML and turn the page into a shell that
fetches it every 10 s and patches the DOM. That removes the reload flicker
entirely, preserves scroll and selection for free, and detects a dead renderer
from a failed request rather than from arithmetic on a timestamp. It needs an
HTTP server — `fetch` over `file://` is blocked by CORS — so it is not this PR.

The markup above is already shaped for it: every mutable value sits in an
element with a stable class or id, and the script reads its inputs from data
attributes rather than from templated literals. Getting there should be a patch
function, not a rewrite. **Keep it that way** — if a change here would require
re-templating the whole document to update one number, reconsider it.
