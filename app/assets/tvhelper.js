/* Wave TV — injected remote-control layer.
 *
 * The Subwave player is already fully keyboard-driven (space/k tune, m mute,
 * r request drawer, Escape closes Radix drawers). This layer adds what a TV
 * remote needs on top: D-pad spatial focus navigation between the player's
 * real focusable controls, a visible focus ring, and small hooks the Android
 * shell calls (__swtvBack, __swtvKey, __swtvOpenRequest, __swtvInsertText).
 */
(function () {
  'use strict';
  if (window.__swtvInstalled) return;
  window.__swtvInstalled = true;

  /* ---- focus ring ------------------------------------------------------ */
  var style = document.createElement('style');
  style.textContent =
    '.swtv-focus{outline:3px solid var(--accent,#c5302a)!important;' +
    'outline-offset:3px!important;border-radius:2px;' +
    'box-shadow:0 0 0 6px color-mix(in oklab,var(--accent,#c5302a) 25%,transparent)!important;}' +
    /* the request textarea/name input get a left-edge marker instead of a box */
    'textarea.swtv-focus,input.swtv-focus{outline-offset:1px!important;box-shadow:none!important;}';
  (document.head || document.documentElement).appendChild(style);

  var ringEl = null;
  function markFocus(el) {
    if (ringEl && ringEl !== el) ringEl.classList.remove('swtv-focus');
    ringEl = el;
    if (el && el.classList) el.classList.add('swtv-focus');
  }
  document.addEventListener('focusin', function (e) {
    markFocus(document.activeElement);
    // Lock synchronously — a field must never be editable on arrival, or the
    // keyboard opens before the debounced report below can gate it.
    var t = e.target;
    if (isEditable(t) && t !== activated) gate(t);
    scheduleReport();
  });
  document.addEventListener('focusout', function () {
    scheduleReport();
  });

  /* ---- restore focus after a drawer/dialog closes -----------------------
     The Schedule/Booth/Timeline/Request panels are Radix Dialogs mounted
     purely from controlled state (no <Dialog.Trigger>), so there's no
     guarantee the library's own "return focus to opener" behaviour has
     anything to restore to — a remote user has no mouse to re-aim with, so
     losing the spot entirely (landing on <body>) is a real dead end. This
     tracks whatever was last activated and puts focus straight back on it
     the moment a dialog disappears, however it closed (Back, the × button,
     or a swipe-dismiss) — a MutationObserver on the dialog itself, rather
     than hooking each closing path individually. */
  var lastTrigger = null;
  document.addEventListener(
    'click',
    function (e) {
      // A click INSIDE the dialog (a suggestion chip, the × button) is the
      // drawer's own content, not something to return to once it's gone.
      if (e.target && e.target.closest && e.target.closest('[role="dialog"]')) return;
      var el = e.target && e.target.closest
        ? e.target.closest('button, a[href], [role="button"], [tabindex]')
        : null;
      if (el) lastTrigger = el;
    },
    true
  );

  var dialogWasOpen = false;
  var dialogObserver = new MutationObserver(function () {
    var open = !!document.querySelector('[role="dialog"]');
    if (dialogWasOpen && !open) {
      var trigger = lastTrigger;
      setTimeout(function () {
        if (trigger && document.contains(trigger)) {
          if (document.activeElement !== trigger) {
            try { trigger.focus({ preventScroll: true }); } catch (e) {}
          }
        } else {
          var fallback = defaultTarget();
          if (fallback) focusEl(fallback);
        }
      }, 80); // after Radix's own close/unmount cycle settles
    }
    dialogWasOpen = open;
  });
  dialogObserver.observe(document.documentElement, { childList: true, subtree: true });

  /* ---- editable-field tracking (drives the native voice hint) ---------- */
  function isEditable(el) {
    if (!el || !el.tagName) return false;
    if (el.isContentEditable) return true;
    var t = el.tagName;
    if (t === 'TEXTAREA') return true;
    if (t !== 'INPUT') return false;
    var ty = (el.getAttribute('type') || 'text').toLowerCase();
    return ['text', 'search', 'email', 'url', 'tel', 'password', 'number'].indexOf(ty) !== -1;
  }
  /* ---- keyboard gating -------------------------------------------------
     A TV remote lands on fields while merely navigating, and a keyboard that
     springs up on arrival is unusable. Rather than fighting the IME from the
     native side (which could leave the keyboard visible but disconnected —
     keystrokes going nowhere), the FIELD itself is held read-only until the
     viewer presses OK. Browsers never raise the keyboard for a read-only
     field, and clearing the flag makes it an ordinary editable input again. */
  function gate(el) {
    if (!el) return;
    try {
      if (el.isContentEditable) {
        el.setAttribute('data-swtv-ce', '1');
        el.contentEditable = 'false';
      } else if ('readOnly' in el) {
        el.readOnly = true;
      }
    } catch (e) {}
  }
  function ungate(el) {
    if (!el) return;
    try {
      if (el.getAttribute && el.getAttribute('data-swtv-ce') === '1') {
        el.contentEditable = 'true';
      } else if ('readOnly' in el) {
        el.readOnly = false;
      }
    } catch (e) {}
  }

  /* Called from the shell when OK is pressed on a focused field: unlock it and
     hand focus back so the keyboard opens against a live, editable target. */
  window.__swtvActivateField = function () {
    var ae = document.activeElement;
    if (!isEditable(ae)) return false;
    ungate(ae);
    activated = ae;
    try { ae.focus({ preventScroll: true }); } catch (e) {}
    return true;
  };

  var activated = null;

  /* The shell needs to know not just "is a field focused" but *which* field:
     moving between two text fields must re-lock the keyboard, while a
     transient blur-and-return (React re-render, drawer animation) must not
     disturb an already-open keyboard. So each distinct element that gains
     focus gets a token; the shell re-locks only when the token changes.
     The debounce collapses blur→refocus churn into no event at all. */
  var lastEl = null;
  var fieldToken = 0;
  var reportTimer = null;
  var lossPending = false;

  function doReport() {
    reportTimer = null;
    var ae = document.activeElement;
    var ed = isEditable(ae);
    if (!ed) {
      if (lastEl === null) return;
      // Focus loss is usually transient: opening the keyboard resizes the
      // viewport, the page re-renders, and the field blurs for a frame before
      // coming back. Reporting that immediately made the shell dismiss the
      // keyboard it had just opened — the "flashes up then vanishes" bug. So
      // confirm the loss after a longer beat before believing it.
      if (!lossPending) {
        lossPending = true;
        reportTimer = setTimeout(doReport, 350);
        return;
      }
      lossPending = false;
      if (lastEl !== activated) gate(lastEl); // re-lock for next time
      lastEl = null;
      activated = null;
      notifyField(false, 0);
      return;
    }
    lossPending = false;
    if (ae === lastEl) return; // same field — nothing changed
    if (activated && ae !== activated && !document.contains(activated)) {
      // React re-rendered the field the viewer is typing into: same field to a
      // human, new DOM node to us. Carry the unlock over rather than re-locking
      // mid-sentence.
      activated = ae;
      ungate(ae);
      lastEl = ae;
      return;
    }
    if (lastEl && lastEl !== ae) gate(lastEl);
    if (ae !== activated) gate(ae); // arriving locked
    lastEl = ae;
    fieldToken++;
    notifyField(true, fieldToken);
  }

  function scheduleReport() {
    if (reportTimer) clearTimeout(reportTimer);
    // Short enough that an OK press right after landing on a field still sees
    // the shell's "a field is focused" state, long enough to swallow the
    // blur→refocus churn React emits mid-animation.
    reportTimer = setTimeout(doReport, 60);
  }

  function notifyField(focused, token) {
    try {
      if (window.WaveTV) window.WaveTV.editableFocused(!!focused, token);
    } catch (e) {}
  }

  /* ---- candidate collection -------------------------------------------- */
  /* Decorative/pointer-only controls a TV remote shouldn't land on:
     cover art, progress/scrub bars, volume knobs (TV remotes control TV
     volume directly). */
  var SKIP_SELECTORS = [
    '[aria-label="Open the timeline"]',
    '[role="progressbar"]',
    'progress',
    '[role="slider"]',
    /* elapsed/remaining clock toggle — a readout, not a control */
    '[aria-label*="switch to elapsed"]',
    '[aria-label*="switch to remaining"]',
    '[aria-label*="Elapsed time"]',
    '[aria-label*="Time remaining"]',
    '[aria-label*="AI tokens"]',
    /* the DJ's live commentary line (opens the booth feed on click) */
    '[title="Open booth feed"]',
  ];
  function hasCoverClass(el) {
    var cn = typeof el.className === 'string' ? el.className : '';
    return /(^|[\s_])cover|[-_]cover/.test(cn);
  }
  /* "2:16", "2:16 / 4:25", "-1:23" — a clock readout in any skin. */
  var TIME_RE = /^\s*-?\d{1,3}:\d{2}(\s*[\/·|-]\s*-?\d{1,3}:\d{2})?\s*$/;
  function isTimeReadout(el) {
    var t = el.textContent || '';
    if (t.length > 24) return false;
    return TIME_RE.test(t);
  }
  function shouldSkip(el) {
    for (var i = 0; i < SKIP_SELECTORS.length; i++) {
      try {
        if (el.matches && el.matches(SKIP_SELECTORS[i])) return true;
      } catch (e) {}
    }
    if (el.tagName === 'BUTTON' && hasCoverClass(el)) return true;
    if (isTimeReadout(el)) return true;
    return false;
  }

  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 3 || r.height < 3) return false;
    var cs = window.getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) return false;
    return true;
  }
  /* The full-screen "Tap to tune in" gate. It sits over everything at z-50, so
     while it's up it is the ONLY thing worth focusing — otherwise the D-pad
     wanders onto the controls behind it and OK never reaches the gate. */
  function tuneInGate() {
    var b = document.querySelector('button[aria-label="Tune in to the live stream"]');
    return b && visible(b) ? b : null;
  }

  function candidates() {
    var gate = tuneInGate();
    if (gate) return [gate];
    /* When a Radix drawer/dialog is open it traps focus — only navigate
       inside it. Otherwise navigate the whole player face. */
    var scope = document.querySelector('[role="dialog"]') || document;
    var sel = 'button, a[href], input, textarea, select, [role="slider"], [role="button"], [tabindex]';
    var all = scope.querySelectorAll(sel);
    var out = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.disabled) continue;
      if (el.getAttribute('tabindex') === '-1') continue;
      if (el.getAttribute('aria-hidden') === 'true') continue;
      if (shouldSkip(el)) continue;
      if (!visible(el)) continue;
      out.push(el);
    }
    return out;
  }

  function defaultTarget() {
    return (
      tuneInGate() ||
      document.querySelector('.fz-power') ||
      candidates()[0] ||
      null
    );
  }

  function focusEl(el) {
    if (!el) return;
    try {
      el.focus({ preventScroll: false });
      if (el.scrollIntoView) el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    } catch (e) {
      try { el.focus(); } catch (e2) {}
    }
    markFocus(el);
  }

  /* ---- spatial navigation ---------------------------------------------- */
  function navigate(dir) {
    var cur = document.activeElement;
    var list = candidates();
    if (!cur || cur === document.body || list.indexOf(cur) === -1) {
      focusEl(defaultTarget());
      return;
    }
    var cr = cur.getBoundingClientRect();
    var cx = cr.left + cr.width / 2;
    var cy = cr.top + cr.height / 2;
    var best = null;
    var bestScore = Infinity;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === cur) continue;
      var r = el.getBoundingClientRect();
      var ex = r.left + r.width / 2;
      var ey = r.top + r.height / 2;
      var dx = ex - cx;
      var dy = ey - cy;
      var inDir =
        dir === 'up' ? dy < -4 :
        dir === 'down' ? dy > 4 :
        dir === 'left' ? dx < -4 :
        dx > 4;
      if (!inDir) continue;
      var primary = dir === 'up' || dir === 'down' ? Math.abs(dy) : Math.abs(dx);
      var secondary = dir === 'up' || dir === 'down' ? Math.abs(dx) : Math.abs(dy);
      var score = primary + secondary * 2.5;
      if (score < bestScore) {
        bestScore = score;
        best = el;
      }
    }
    if (best) focusEl(best);
  }

  /* In the multi-line request textarea, up/down first move the caret between
     lines; navigation only takes over at the first/last line. */
  function caretAtEdge(ta, up) {
    try {
      var pos = up ? ta.selectionStart : ta.selectionEnd;
      var v = ta.value || '';
      if (up) return v.lastIndexOf('\n', Math.max(0, pos - 1)) === -1;
      return v.indexOf('\n', pos) === -1;
    } catch (e) {
      return true;
    }
  }

  document.addEventListener(
    'keydown',
    function (e) {
      if (e.__swtvSynthetic) return;
      var k = e.key;
      if (k !== 'ArrowUp' && k !== 'ArrowDown' && k !== 'ArrowLeft' && k !== 'ArrowRight') return;
      var ae = document.activeElement;
      if (isEditable(ae)) {
        if (k === 'ArrowLeft' || k === 'ArrowRight') return; /* caret movement */
        if (ae.tagName === 'TEXTAREA' && !caretAtEdge(ae, k === 'ArrowUp')) return;
      } else if (ae && ae.getAttribute && ae.getAttribute('role') === 'slider' &&
                 (k === 'ArrowUp' || k === 'ArrowDown')) {
        return; /* focused volume knob keeps its own up/down handling */
      }
      e.preventDefault();
      e.stopPropagation();
      navigate(k === 'ArrowUp' ? 'up' : k === 'ArrowDown' ? 'down' : k === 'ArrowLeft' ? 'left' : 'right');
    },
    true
  );

  /* ---- hooks called from the Android shell ------------------------------ */

  /* Re-dispatch a bare key so the page's own shortcuts fire (k = tune,
     m = mute, r = request drawer …). */
  window.__swtvKey = function (key) {
    var ev = new KeyboardEvent('keydown', { key: key, bubbles: true, cancelable: true });
    try { ev.__swtvSynthetic = true; } catch (e) {}
    (document.body || document).dispatchEvent(ev);
  };

  /* Choosing a station on a TV means "play it" — nobody wants to then hunt for
     a tap-to-tune gate with a remote. The shell disables the browser's
     user-gesture requirement, so dismissing the gate ourselves starts audio.
     Polls briefly because the gate mounts a moment after the page settles. */
  var autoTuneTimer = null;
  window.__swtvAutoTuneIn = function () {
    /* onPageFinished can fire more than once against one document — a
       fragment navigation is enough — and every call used to leave its own
       poller running, each clicking the gate for the next 7.5 seconds. */
    if (autoTuneTimer) clearInterval(autoTuneTimer);
    var tries = 0;
    autoTuneTimer = setInterval(function () {
      var gate = tuneInGate();
      if (gate) {
        gate.click();
        clearInterval(autoTuneTimer);
        autoTuneTimer = null;
      } else if (++tries > 25) {
        clearInterval(autoTuneTimer);
        autoTuneTimer = null;
      }
    }, 300);
  };

  /* Is the stream actually playing? The shell owns a single <audio> element. */
  window.__swtvTuned = function () {
    var a = document.querySelector('audio');
    return !!(a && !a.paused && !a.ended);
  };

  /* Sleep timer: stop the stream the way the player's own transport would, so
     its UI settles into the stopped state instead of desyncing. Returns true
     if something was actually playing. */
  window.__swtvStop = function () {
    if (!window.__swtvTuned()) return false;
    window.__swtvKey('k');
    setTimeout(function () {
      // Belt and braces: if the shortcut didn't take, stop the element itself.
      var a = document.querySelector('audio');
      if (a && !a.paused) { try { a.pause(); } catch (e) {} }
    }, 400);
    return true;
  };

  /* BACK: close an open drawer/dialog via Escape (Radix listens on document).
     Returns true when something was open (i.e. BACK was consumed). */
  window.__swtvBack = function () {
    if (document.querySelector('[role="dialog"]')) {
      var ev = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true, cancelable: true });
      try { ev.__swtvSynthetic = true; } catch (e) {}
      (document.activeElement || document.body || document).dispatchEvent(ev);
      return true;
    }
    return false;
  };

  /* Open the request drawer (page shortcut 'r') and focus its textarea. */
  var openRequestTimer = null;
  window.__swtvOpenRequest = function () {
    function focusTa() {
      var dlg = document.querySelector('[role="dialog"]');
      var ta = dlg && dlg.querySelector('textarea');
      if (ta) { focusEl(ta); return true; }
      return false;
    }
    /* Same reason as __swtvAutoTuneIn: pressing the request key twice in
       quick succession left two pollers both grabbing the textarea. */
    if (openRequestTimer) clearInterval(openRequestTimer);
    if (focusTa()) return;
    window.__swtvKey('r');
    var tries = 0;
    openRequestTimer = setInterval(function () {
      if (focusTa() || ++tries > 20) {
        clearInterval(openRequestTimer);
        openRequestTimer = null;
      }
    }, 150);
  };

  /* Voice dictation result → the focused field (React-safe value injection).
     Returns true when the text landed, so the shell knows the field is now
     live and the next OK should send rather than open a keyboard. */
  window.__swtvInsertText = function (text) {
    var ae = document.activeElement;
    if (!isEditable(ae)) {
      var dlg = document.querySelector('[role="dialog"]');
      ae = dlg && dlg.querySelector('textarea');
      if (!ae) return false;
      focusEl(ae);
    }
    // Dictation counts as taking the field: unlock it so the viewer can hit OK
    // to send, or carry on typing without a second unlock press.
    ungate(ae);
    activated = ae;
    var proto = ae.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    var cur = ae.value || '';
    var next = cur ? cur + (/\s$/.test(cur) ? '' : ' ') + text : text;
    if (desc && desc.set) desc.set.call(ae, next);
    else ae.value = next;
    ae.dispatchEvent(new Event('input', { bubbles: true }));
    try { ae.setSelectionRange(next.length, next.length); } catch (e) {}
  };

  /* ---- initial focus ---------------------------------------------------- */
  var seeded = false;
  var seedTries = 0;
  var seedTimer = setInterval(function () {
    if (seeded || ++seedTries > 40) { clearInterval(seedTimer); return; }
    var ae = document.activeElement;
    if (ae && ae !== document.body && ae !== document.documentElement) {
      seeded = true;
      clearInterval(seedTimer);
      return;
    }
    var t = defaultTarget();
    if (t) {
      focusEl(t);
      seeded = true;
      clearInterval(seedTimer);
    }
  }, 400);
})();
