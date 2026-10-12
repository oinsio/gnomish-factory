/* Contrast measurements of the dashboard browser specs (design D15, FR14 of tighten-spec-contract),
   evaluated in the page on the colours the browser computed -- the stylesheet's tokens as the
   current colour scheme resolved them, never the source text.

   contrast(el): the WCAG 2.x contrast ratio between the element's text colour and the background it
   is painted on. The background is every ancestor's background-color composited from the root down
   (a translucent one blends with what is below it); an ancestor with opacity below 1 (a dimmed card)
   composites the element's text and background, as one layer, over the backdrop below that ancestor.

   texts(): every rendered element that owns a non-blank text node, outside SVG, with the WCAG AA
   minimum for its size: 3 for large text (24px, or 18.66px bold), 4.5 otherwise.

   graphics(): every bar segment against its track and every legend swatch against its card -- the
   non-text graphics the reader needs to tell the mix, held to WCAG's 3:1 for non-text contrast. */
var gfLegibility = (function () {
  "use strict";

  function parse(css) {
    var m = /rgba?\(([^)]*)\)/.exec(css);
    if (!m) throw new Error("unparsed colour: " + css);
    var p = m[1].split(/[\s,\/]+/).filter(Boolean).map(Number);
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  }

  function over(top, bottom, alpha) {
    var a = alpha === undefined ? top.a : alpha;
    return {
      r: top.r * a + bottom.r * (1 - a),
      g: top.g * a + bottom.g * (1 - a),
      b: top.b * a + bottom.b * (1 - a),
      a: 1
    };
  }

  function luminance(c) {
    function lin(v) {
      v = v / 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
    return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b);
  }

  function ratio(x, y) {
    var a = luminance(x), b = luminance(y);
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  }

  function chain(el) {
    var out = [];
    for (var e = el; e; e = e.parentElement) out.push(e);
    return out.reverse();
  }

  /* the background painted under el, opacity ignored */
  function backgroundOf(el) {
    var bg = { r: 255, g: 255, b: 255, a: 1 };
    chain(el).forEach(function (e) {
      var c = parse(getComputedStyle(e).backgroundColor);
      if (c.a > 0) bg = over(c, bg);
    });
    return bg;
  }

  /* the outermost ancestor-or-self with opacity below 1, and the product of every opacity */
  function dimming(el) {
    var layer = null, opacity = 1;
    chain(el).forEach(function (e) {
      var o = Number(getComputedStyle(e).opacity);
      if (o < 1) {
        if (layer === null) layer = e;
        opacity *= o;
      }
    });
    return { layer: layer, opacity: opacity };
  }

  function seen(fg, bg, el) {
    var dim = dimming(el);
    if (dim.layer === null) return { fg: fg, bg: bg };
    var backdrop = backgroundOf(dim.layer.parentElement);
    return { fg: over(fg, backdrop, dim.opacity), bg: over(bg, backdrop, dim.opacity) };
  }

  function contrast(el) {
    var bg = backgroundOf(el);
    var fg = over(parse(getComputedStyle(el).color), bg);
    var s = seen(fg, bg, el);
    return ratio(s.fg, s.bg);
  }

  function rendered(el) {
    var style = getComputedStyle(el);
    return el.getClientRects().length > 0 && style.visibility === "visible";
  }

  function label(el) {
    var cls = el.getAttribute("class");
    return el.tagName.toLowerCase() + (cls ? "." + cls.trim().split(/\s+/).join(".") : "");
  }

  function texts() {
    return Array.prototype.slice
      .call(document.body.querySelectorAll("*"))
      .filter(function (el) {
        if (el.closest("svg") || !rendered(el)) return false;
        return Array.prototype.some.call(el.childNodes, function (n) {
          return n.nodeType === Node.TEXT_NODE && n.textContent.trim() !== "";
        });
      })
      .map(function (el) {
        var style = getComputedStyle(el);
        var size = parseFloat(style.fontSize);
        var large = size >= 24 || (size >= 18.66 && Number(style.fontWeight) >= 700);
        return {
          element: label(el),
          text: el.textContent.trim().slice(0, 60),
          ratio: contrast(el),
          required: large ? 3 : 4.5
        };
      });
  }

  function graphics() {
    return Array.prototype.slice
      .call(document.querySelectorAll(".bar__seg, .legend__swatch"))
      .filter(rendered)
      .map(function (el) {
        var fill = over(parse(getComputedStyle(el).backgroundColor), backgroundOf(el.parentElement));
        var s = seen(fill, backgroundOf(el.parentElement), el);
        return {
          element: label(el),
          text: el.getAttribute("style") || "",
          ratio: ratio(s.fg, s.bg),
          required: 3
        };
      });
  }

  return { contrast: contrast, texts: texts, graphics: graphics };
})();
