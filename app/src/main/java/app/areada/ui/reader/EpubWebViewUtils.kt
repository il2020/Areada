package app.areada.ui.reader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
internal fun restoreWebViewScroll(
    webView: WebView,
    fraction: Float,
) {
    if (fraction <= 0f) {
        return
    }

    val restore = {
        val fullHeight = (webView.contentHeight * webView.scale).toInt()
        val maxScroll = (fullHeight - webView.height).coerceAtLeast(0)
        if (maxScroll > 0) {
            scrollWebViewToProgress(webView, fraction)
        }
    }

    webView.post(restore)
    webView.postDelayed(restore, 150L)
}

@Suppress("DEPRECATION")
internal fun scrollWebViewToProgress(
    webView: WebView,
    progress: Float,
) {
    val fullHeight = (webView.contentHeight * webView.scale).toInt()
    val maxScroll = (fullHeight - webView.height).coerceAtLeast(0)
    val targetY = (maxScroll * progress.coerceIn(0f, 1f)).roundToInt()
    webView.scrollTo(webView.scrollX, targetY)
}

internal fun applyEpubScrollRequest(
    webView: WebView,
    progress: Float,
) {
    val scroll = {
        scrollWebViewToProgress(webView, progress)
    }
    scroll()
    webView.post(scroll)
    webView.postDelayed(scroll, 80L)
}

@Suppress("DEPRECATION")
internal fun epubWebViewScrollState(webView: WebView): Pair<Float, Boolean> {
    val fullHeight = webView.contentHeight * webView.scale
    val maxScroll = (fullHeight - webView.height).coerceAtLeast(0f)
    if (maxScroll <= 1f) {
        return 0f to false
    }
    return (webView.scrollY / maxScroll).coerceIn(0f, 1f) to true
}

@Suppress("DEPRECATION")
internal fun resetWebViewZoom(webView: WebView) {
    val currentScale = webView.scale.takeIf { it > 0f } ?: 1f
    val zoomFactor = (1f / currentScale).coerceIn(0.2f, 5f)
    if (abs(currentScale - 1f) > 0.02f) {
        webView.zoomBy(zoomFactor)
    }
}

@Suppress("DEPRECATION")
internal fun scrollByWebView(webView: WebView, deltaX: Int, deltaY: Int) {
    webView.scrollBy(deltaX, deltaY)
}

internal class NoteBridge(
    private val onNoteOpen: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun open(note: String?) {
        val cleanNote = note?.trim().orEmpty()
        if (cleanNote.isBlank()) {
            return
        }

        mainHandler.post {
            onNoteOpen(cleanNote)
        }
    }
}

internal fun injectNoteHandler(webView: WebView) {
    webView.evaluateJavascript(
        """
        (function() {
          if (window.__areadaNoteHandlerInstalled) return;
          window.__areadaNoteHandlerInstalled = true;

          function findAnchor(start) {
            var node = start;
            while (node && node.tagName !== 'A') {
              node = node.parentElement;
            }
            return node;
          }

          function noteFor(targetId, fallback) {
            if (!targetId) return fallback || '';
            var node = document.getElementById(targetId) ||
              document.querySelector('[name="' + targetId.replace(/"/g, '\\"') + '"]');
            if (!node) return fallback || '';
            return (node.innerText || node.textContent || fallback || '').trim();
          }

          document.addEventListener('click', function(event) {
            var anchor = findAnchor(event.target);
            if (!anchor) return;

            var href = anchor.getAttribute('href') || '';
            var hash = '';
            try {
              hash = new URL(href, window.location.href).hash || '';
            } catch (error) {
              var hashIndex = href.indexOf('#');
              if (hashIndex >= 0) hash = href.substring(hashIndex);
            }
            if (!hash || hash.length < 2) return;

            var targetId = decodeURIComponent(hash.substring(1));
            var fallback = (anchor.getAttribute('title') || anchor.innerText || anchor.textContent || '').trim();
            var note = noteFor(targetId, fallback);
            if (note && window.AreadaNote) {
              event.preventDefault();
              window.AreadaNote.open(note);
            }
          }, true);
        })();
        """.trimIndent(),
        null,
    )
}

internal fun openChapterNote(
    webView: WebView,
    fragment: String,
    onNoteOpen: (String) -> Unit,
) {
    val quotedFragment = JSONObject.quote(fragment)
    val script = """
        (function() {
          var id = $quotedFragment;
          var node = document.getElementById(id) || document.querySelector('[name="' + id.replace(/"/g, '\\"') + '"]');
          return node ? node.innerText : '';
        })();
    """.trimIndent()

    webView.evaluateJavascript(script) { result ->
        val note = decodeJavascriptString(result).trim()
        if (note.isNotBlank()) {
            onNoteOpen(note)
        }
    }
}

internal fun decodeJavascriptString(value: String?): String {
    if (value.isNullOrBlank() || value == "null") {
        return ""
    }

    return runCatching {
        JSONArray("[$value]").optString(0)
    }.getOrDefault("")
}

/**
 * Page-side read-aloud helpers, installed once per loaded page. Text is indexed the same way
 * [htmlReadAloudSegments] extracts it, so spoken segments can be found in the page by text.
 * The highlight is drawn as an overlay, leaving the DOM (and the index) untouched.
 */
private const val ReadAloudScript = """
window.__areadaTts = window.__areadaTts || (function() {
  var index = null, current = null, overlay = null;
  function build() {
    var text = [], nodes = [], starts = [], nodeOf = [], offsetOf = [], space = true, node;
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
    while ((node = walker.nextNode())) {
      var parent = node.parentElement;
      if (!parent || parent.closest('rt, rp, noscript, script, style')) continue;
      var value = node.nodeValue, n = nodes.length;
      nodes.push(node);
      starts.push(text.length);
      for (var k = 0; k < value.length; k++) {
        var c = value.charCodeAt(k);
        if (c === 173 || c === 8203) continue;
        if (c === 32 || c === 160 || (c >= 9 && c <= 13)) {
          if (space) continue;
          space = true;
          text.push(' ');
        } else {
          space = false;
          text.push(value.charAt(k));
        }
        nodeOf.push(n);
        offsetOf.push(k);
      }
    }
    return { text: text.join(''), nodes: nodes, starts: starts, nodeOf: nodeOf, offsetOf: offsetOf };
  }
  function getIndex() {
    if (index || !document.body) return index;
    var built = build();
    if (document.readyState === 'complete') index = built;
    return built;
  }
  function find(idx, text, fraction) {
    var hint = fraction * idx.text.length, best = -1, pos = idx.text.indexOf(text);
    while (pos >= 0) {
      if (best < 0 || Math.abs(pos - hint) < Math.abs(best - hint)) best = pos;
      if (pos > hint) break;
      pos = idx.text.indexOf(text, pos + 1);
    }
    return best;
  }
  function rangesFor(idx, start, end) {
    var ranges = [];
    for (var i = start; i < end;) {
      var n = idx.nodeOf[i], from = idx.offsetOf[i], to = from + 1;
      while (i + 1 < end && idx.nodeOf[i + 1] === n) {
        i++;
        to = idx.offsetOf[i] + 1;
      }
      i++;
      var range = document.createRange();
      range.setStart(idx.nodes[n], from);
      range.setEnd(idx.nodes[n], to);
      ranges.push(range);
    }
    return ranges;
  }
  function draw() {
    if (overlay && overlay.parentNode) overlay.parentNode.removeChild(overlay);
    overlay = null;
    if (!current) return;
    overlay = document.createElement('div');
    overlay.style.cssText = 'position:absolute;left:0;top:0;width:0;height:0;pointer-events:none;z-index:2147483647;';
    current.ranges.forEach(function(range) {
      var rects = range.getClientRects();
      for (var i = 0; i < rects.length; i++) {
        var r = rects[i];
        if (r.width < 1 || r.height < 1) continue;
        var box = document.createElement('div');
        box.style.cssText = 'position:absolute;border-radius:2px;background:' + current.color +
          ';left:' + (r.left + window.pageXOffset) + 'px;top:' + (r.top + window.pageYOffset) +
          'px;width:' + r.width + 'px;height:' + r.height + 'px;';
        overlay.appendChild(box);
      }
    });
    document.documentElement.appendChild(overlay);
  }
  window.addEventListener('resize', draw);
  return {
    highlight: function(text, fraction, color, scroll) {
      current = null;
      var idx = text ? getIndex() : null;
      var pos = idx ? find(idx, text, fraction) : -1;
      if (pos >= 0) current = { ranges: rangesFor(idx, pos, pos + text.length), color: color };
      draw();
      if (!scroll || !current) return;
      var rect = null, height = window.innerHeight;
      for (var i = 0; !rect && i < current.ranges.length; i++) {
        var r = current.ranges[i].getBoundingClientRect();
        if (r.width >= 1 && r.height >= 1) rect = r;
      }
      // Hidden text has no box to scroll to.
      if (rect && (rect.top < height * 0.12 || rect.top > height * 0.75)) window.scrollBy(0, rect.top - height * 0.25);
    },
    probe: function() {
      var idx = getIndex();
      if (!idx) return null;
      var width = window.innerWidth, height = window.innerHeight, pos = -1, visible = false;
      if (current && current.ranges.length) {
        var first = current.ranges[0].getBoundingClientRect();
        var last = current.ranges[current.ranges.length - 1].getBoundingClientRect();
        visible = last.bottom > 0 && first.top < height;
      }
      for (var y = 4; pos < 0 && y < height / 2 && document.caretRangeFromPoint; y += 12) {
        var caret = document.caretRangeFromPoint(width / 2, y);
        var n = caret && caret.startContainer.nodeType === 3 ? idx.nodes.indexOf(caret.startContainer) : -1;
        if (n < 0) continue;
        pos = idx.starts[n];
        while (pos < idx.nodeOf.length && idx.nodeOf[pos] === n && idx.offsetOf[pos] < caret.startOffset) pos++;
      }
      if (pos < 0) {
        var maxScroll = document.documentElement.scrollHeight - height;
        pos = Math.round((maxScroll > 0 ? window.pageYOffset / maxScroll : 0) * idx.text.length);
      }
      return { text: idx.text.substr(pos, 80), fraction: pos / Math.max(1, idx.text.length), visible: visible };
    }
  };
})();
"""

internal fun injectReadAloudScript(webView: WebView) {
    webView.evaluateJavascript(ReadAloudScript, null)
}

/**
 * Highlights the spoken [segment] in the page, scrolling it into view when [scroll] is set;
 * null clears the highlight. Needs [injectReadAloudScript] first.
 */
internal fun applyReadAloudHighlight(
    webView: WebView,
    segment: ReadAloudSegment?,
    color: String,
    scroll: Boolean,
) {
    val text = segment?.text?.let(JSONObject::quote) ?: "null"
    val fraction = segment?.fraction ?: 0f
    webView.evaluateJavascript(
        "window.__areadaTts && window.__areadaTts.highlight($text, $fraction, ${JSONObject.quote(color)}, $scroll);",
        null,
    )
}

/**
 * Reports what the page shows at the top of the screen and whether the highlight is visible.
 * Needs [injectReadAloudScript] first.
 */
internal fun probeReadAloudStart(
    webView: WebView,
    onResult: (ReadAloudProbe) -> Unit,
) {
    webView.evaluateJavascript("window.__areadaTts ? window.__areadaTts.probe() : null;") { value ->
        val json = runCatching { JSONObject(value) }.getOrNull()
        onResult(
            ReadAloudProbe(
                text = json?.optString("text").orEmpty(),
                fraction = json?.optDouble("fraction", 0.0)?.toFloat() ?: 0f,
                highlightVisible = json?.optBoolean("visible") == true,
            ),
        )
    }
}
