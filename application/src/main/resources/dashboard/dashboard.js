(function () {
  "use strict";

  var GENERATED_AT = Number(document.body.dataset.generatedAt);
  var WATCH = document.body.dataset.mode === "watch";
  /* the server bakes three times its render cadence into the body; the
     fallback matches the shipped 10 s cadence, for a body missing the
     attribute (a one-shot page never reads this) */
  var STALE_AFTER_MS = Number(document.body.dataset.staleAfter) || 30000;

  function relative(ms) {
    if (!Number.isFinite(ms)) return "unknown";
    var s = Math.max(0, Math.round(ms / 1000));
    if (s < 10) return "just now";
    if (s < 60) return s + " s ago";
    var m = Math.round(s / 60);
    if (m < 60) return m + " min ago";
    var h = Math.round(m / 60);
    if (h < 24) return h + " h ago";
    return Math.round(h / 24) + " d ago";
  }

  function duration(ms) {
    var s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return s + "s";
    return Math.floor(s / 60) + "m " + (s % 60) + "s";
  }

  var strip = document.getElementById("freshness");
  var stripText = document.getElementById("freshness-text");

  /* a page missing its strip or its own timestamp cannot tick — leave the
     server-rendered absolutes in place rather than throwing once a second */
  if (!strip || !stripText || !Number.isFinite(GENERATED_AT)) return;

  /* The strip owns its whole sentence, so its <time> is dropped on the first
     tick — its ISO text moves to the strip's own title, and it stays out of
     the ticking list rather than being updated forever while detached. */
  var stripTime = strip.querySelector("time[data-epoch]");
  if (stripTime && !strip.title) strip.title = stripTime.textContent.trim();

  var times = Array.prototype.slice
    .call(document.querySelectorAll("time[data-epoch]"))
    .filter(function (el) {
      return !strip.contains(el);
    });
  times.forEach(function (el) {
    if (!el.title) el.title = el.textContent.trim();
  });

  function tick() {
    var now = Date.now();

    times.forEach(function (el) {
      el.textContent = relative(now - Number(el.dataset.epoch));
    });

    var age = now - GENERATED_AT;
    if (WATCH) {
      var stale = age > STALE_AFTER_MS;
      strip.dataset.state = stale ? "stale" : "fresh";
      document.body.classList.toggle("is-stale", stale);
      stripText.textContent = stale
        ? "view is stale — renderer silent for " + duration(age)
        : "data is fresh · updated " + relative(age);
    } else {
      /* one-shot: age is plain information, never a degradation */
      stripText.textContent = "one-shot snapshot · taken " + relative(age);
    }
  }

  tick();
  setInterval(tick, 1000);

  /* Implements UX5 of redesign-dashboard: keep the reading position across the meta-refresh reload. */
  try {
    var saved = sessionStorage.getItem("gf-scroll");
    if (saved) window.scrollTo(0, Number(saved));
    window.addEventListener("beforeunload", function () {
      /* the handler runs long after this try block returned, and a write can
         still fail on its own — a full quota, storage revoked mid-session */
      try {
        sessionStorage.setItem("gf-scroll", String(window.scrollY));
      } catch (writeFailed) {
        /* scroll restore is optional; the unload must not carry an error */
      }
    });
  } catch (e) {
    /* private mode, file:// restrictions — scroll restore is optional */
  }
})();
