(function () {
  "use strict";

  var GENERATED_AT = Number(document.body.dataset.generatedAt);
  var WATCH = document.body.dataset.mode === "watch";
  var STALE_AFTER_MS = 30000; /* 3 × the fixed 10 s render cadence */

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
    if (WATCH) {
      var stale = age > STALE_AFTER_MS;
      strip.dataset.state = stale ? "stale" : "fresh";
      document.body.classList.toggle("is-stale", stale);
      stripText.textContent = stale
        ? "вид устарел — рендерер молчит " + duration(age)
        : "данные свежие · обновлено " + relative(age);
    } else {
      /* one-shot: age is plain information, never a degradation */
      stripText.textContent = "разовый снимок · сделан " + relative(age);
    }
  }

  tick();
  setInterval(tick, 1000);

  /* UX5: keep the reading position across the 10 s meta-refresh reload. */
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
