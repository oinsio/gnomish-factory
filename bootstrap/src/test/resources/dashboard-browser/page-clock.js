/* The page clock of the dashboard browser specs (design D15, FR14 of tighten-spec-contract):
   installed before every document, it replaces Date.now -- the clock the dashboard's script reads
   on each tick -- with a value the spec sets. Its start is the URL's `clock` query parameter, so the
   first tick, during the load, already reads it; a page loaded without one keeps the real clock. */
(function () {
  "use strict";
  var start = new URLSearchParams(window.location.search).get("clock");
  if (start === null) return;
  var now = Number(start);
  Date.now = function () {
    return now;
  };
  window.__gfPageClock = {
    set: function (epochMillis) {
      now = Number(epochMillis);
    }
  };
})();
