/*
 * DM Only page filter.
 *
 * Injected into every instagram.com page before the site's own scripts run (and re-run on page
 * finish as a fallback). It does three jobs:
 *
 *  1. Guards Instagram's client-side navigation. Taps on links to refused pages are cancelled before
 *     Instagram's router sees them, and history.pushState / Navigation API changes to refused pages
 *     are cancelled. The app's UrlPolicy (asked synchronously through window.__dmoBridge) decides
 *     what is refused, so there is one set of rules for the app and this script.
 *  2. Hides what is left: Instagram's own tab bar, story rings and highlights, profile post grids,
 *     the notes tray, suggested accounts and non-profile search results.
 *  3. Covers posts, reels and stories that people share into a chat, makes them untappable, and
 *     shuts the in-chat reel viewer if it ever opens.
 *
 * Instagram's class names are generated and change constantly, so nothing here uses them. Things
 * are found by where their links go, by ARIA roles, and by shape. The URL policy is the guarantee;
 * the hiding is cosmetic, and every heuristic fails towards "something stays visible", never towards
 * a blank screen.
 *
 * Idempotent: running it twice on the same page does nothing the second time.
 */
(function () {
  'use strict';
  if (window.top !== window) return;
  if (window.__dmoInstalled) return;
  window.__dmoInstalled = true;

  var bridge = window.__dmoBridge || null;
  var INBOX = '/direct/inbox/';

  // Defaults match the app's; they only apply if the bridge is somehow missing.
  var config = { hideSuggestions: true };
  try {
    if (bridge) config = JSON.parse(String(bridge.config())) || config;
  } catch (e) {}

  // ------------------------------------------------------------------------------------ policy

  var decisions = Object.create(null);
  var decisionCount = 0;

  function absolute(url) {
    try {
      return new URL(String(url), location.href).href;
    } catch (e) {
      return null;
    }
  }

  /** 'allow' | 'block' | 'external' | 'drop', from the app's UrlPolicy. */
  function decide(url) {
    var abs = absolute(url);
    if (!abs) return 'allow';
    var cached = decisions[abs];
    if (cached) return cached;
    var result;
    try {
      result = bridge ? String(bridge.decide(abs)) : fallbackDecide(abs);
    } catch (e) {
      result = fallbackDecide(abs);
    }
    if (decisionCount > 3000) {
      decisions = Object.create(null);
      decisionCount = 0;
    }
    decisions[abs] = result;
    decisionCount++;
    return result;
  }

  function refused(url) {
    var d = decide(url);
    return d === 'block' || d === 'drop';
  }

  function routeOf(url) {
    var abs = absolute(url);
    if (!abs) return 'other';
    try {
      return bridge ? String(bridge.route(abs)) : fallbackRoute(abs);
    } catch (e) {
      return fallbackRoute(abs);
    }
  }

  // Only used if the bridge is missing, which should never happen. Deliberately strict.
  function fallbackDecide(abs) {
    var u = new URL(abs);
    if (!/^(www\.|m\.)?instagram\.com$/.test(u.hostname)) return 'external';
    var p = u.pathname;
    if (/^\/direct(\/|$)/.test(p) || /^\/explore\/search\/?$/.test(p)) return 'allow';
    if (/^\/(accounts|challenge|two_factor|auth_platform|consent|api|graphql|ajax)(\/|$)/.test(p)) {
      return /^\/accounts\/activity/.test(p) ? 'block' : 'allow';
    }
    if (/^\/$|^\/(explore|reels?|p|tv|stories|s|live|create|notifications)(\/|$)/.test(p)) return 'block';
    return /^\/[A-Za-z0-9._]{1,30}\/((followers|following)\/?)?$/.test(p) ? 'allow' : 'block';
  }

  function fallbackRoute(abs) {
    var p = new URL(abs).pathname;
    if (/^\/direct\/(inbox\/?)?$/.test(p)) return 'inbox';
    if (/^\/direct\/t\//.test(p)) return 'thread';
    if (/^\/direct\//.test(p)) return 'dm';
    if (/^\/explore\/search\/?$/.test(p)) return 'search';
    if (/^\/(accounts|challenge|two_factor|auth_platform)(\/|$)/.test(p)) return 'auth';
    if (/^\/[A-Za-z0-9._]{1,30}\/(followers|following)/.test(p)) return 'follow';
    return 'profile';
  }

  var paths = Object.create(null);

  /** Path of an instagram.com link, or null for other hosts. Memoised per href. */
  function igPath(anchor) {
    var href = anchor.getAttribute('href') || '';
    if (href in paths) return paths[href];
    var path = null;
    try {
      var u = new URL(href, location.href);
      if (/^(www\.|m\.)?instagram\.com$/.test(u.hostname)) path = u.pathname;
    } catch (e) {}
    paths[href] = path;
    return path;
  }

  var lastNotice = 0;
  function noticeBlocked() {
    var now = Date.now();
    if (now - lastNotice < 1500) return;
    lastNotice = now;
    try {
      if (bridge && bridge.blocked) bridge.blocked();
    } catch (e) {}
  }

  // ------------------------------------------------------------------------------------ route

  var route = 'other';
  var routeHref = null;

  function updateRoute() {
    // The route only changes with the URL; don't ask the app again on every DOM change.
    if (routeHref !== location.href) {
      routeHref = location.href;
      route = routeOf(routeHref);
    }
    var root = document.documentElement;
    if (!root) return;
    if (root.getAttribute('data-dmo-route') !== route) root.setAttribute('data-dmo-route', route);
    if (config.hideSuggestions && !root.hasAttribute('data-dmo-nosuggest')) {
      root.setAttribute('data-dmo-nosuggest', '');
    }
    if (config.allowActivity && !root.hasAttribute('data-dmo-activity')) {
      root.setAttribute('data-dmo-activity', '');
    }
  }

  function onAuthPage() {
    return route === 'auth';
  }

  // ------------------------------------------------------------------------------- navigation

  var BOUNCE_KEY = '__dmoBounces';

  /** Records an event under [key] in sessionStorage; returns how many happened in the last [ms]. */
  function countRecent(key, ms) {
    var now = Date.now();
    var times = [];
    try {
      times = JSON.parse(sessionStorage.getItem(key) || '[]').filter(function (t) {
        return now - t < ms;
      });
    } catch (e) {}
    times.push(now);
    try {
      sessionStorage.setItem(key, JSON.stringify(times));
    } catch (e) {}
    return times.length;
  }

  /** Blank the page and stop all media at once, so nothing refused stays on screen while it reloads. */
  function leave() {
    var root = document.documentElement;
    if (root) root.setAttribute('data-dmo-leaving', '');
    silence(document);
  }

  /*
   * A client-side navigation to a refused page was cancelled. Instagram's router may already have
   * rendered the refused view under the old URL, so blank the page and reload the current one to
   * throw that away. From a login page (e.g. "Save your login info?" heading for the feed) go to the
   * inbox instead. If it keeps happening, stop and let the app show its "keeps redirecting" page
   * rather than reloading for ever.
   */
  function bounce(quiet) {
    if (!quiet) noticeBlocked();
    if (countRecent(BOUNCE_KEY, 10000) > 4) {
      try {
        sessionStorage.removeItem(BOUNCE_KEY);
        if (bridge && bridge.stuck) bridge.stuck();
      } catch (e) {}
      return;
    }
    leave();
    if (onAuthPage() || refused(location.href)) {
      location.replace(INBOX);
    } else {
      location.replace(location.href);
    }
  }

  /** Search's own Cancel button heads for /explore/ (the grid). That's expected, not worth a toast. */
  function quietRefusal(url) {
    if (route !== 'search') return false;
    try {
      return new URL(String(url), location.href).pathname.replace(/\/+$/, '') === '/explore';
    } catch (e) {
      return false;
    }
  }

  // 1. Taps. Capture phase on window runs before React's listeners on the root.
  function onClick(e) {
    var t = e.target;
    if (!t || !t.closest) return;

    // The script's own click on a reel viewer's close button.
    if (selfClick) return;

    // Posts, reels and stories shared into a chat.
    if (t.closest('[data-dmo-shared], [data-dmo-viewer]')) {
      e.preventDefault();
      e.stopImmediatePropagation();
      noticeBlocked();
      return;
    }

    // Blurred chat media: the first tap reveals it.
    var blurred = t.closest('[data-dmo-blur]');
    if (blurred) {
      blurred.removeAttribute('data-dmo-blur');
      blurred.setAttribute('data-dmo-revealed', '');
      e.preventDefault();
      e.stopImmediatePropagation();
      return;
    }

    var a = t.closest('a[href]');
    if (a && refused(a.href)) {
      // Always cancel the link itself (Instagram's router skips default-prevented clicks)...
      e.preventDefault();
      // ...but a button nested inside the link still gets the tap.
      var inner = t.closest('a[href], button, [role="button"]');
      if (inner === a) {
        e.stopImmediatePropagation();
        noticeBlocked();
      }
    }
  }
  window.addEventListener('click', onClick, true);
  window.addEventListener('auxclick', onClick, true);

  // 2. Navigation API: client-side navigations started by code rather than links. Full page loads
  //    are left to the app, which can refuse them before anything is drawn.
  if (window.navigation && typeof window.navigation.addEventListener === 'function') {
    window.navigation.addEventListener('navigate', function (e) {
      try {
        if (!e.destination || !e.destination.sameDocument || !e.cancelable) return;
        if (!refused(e.destination.url)) return;
        e.preventDefault();
        bounce(quietRefusal(e.destination.url));
      } catch (err) {}
    });
  }

  // 3. history.pushState / replaceState, for anything that got past the Navigation API.
  ['pushState', 'replaceState'].forEach(function (name) {
    var original = history[name];
    if (typeof original !== 'function') return;
    history[name] = function (state, title, url) {
      if (url != null && refused(url)) {
        bounce(quietRefusal(url));
        return undefined;
      }
      var result = original.apply(this, arguments);
      schedule();
      return result;
    };
  });

  window.addEventListener('popstate', function () {
    schedule();
  });

  // Enter in the search box opens keyword results, which are a grid of posts. Account results appear
  // as you type, so Enter does nothing here instead of reloading the page and losing the query.
  window.addEventListener(
    'keydown',
    function (e) {
      if (route !== 'search' || e.key !== 'Enter') return;
      if (e.target && e.target.tagName === 'INPUT') {
        e.preventDefault();
        e.stopImmediatePropagation();
      }
    },
    true
  );

  // ---------------------------------------------------------------------------------- styles

  var CSS = [
    // Set just before leaving a page whose view was refused.
    'html[data-dmo-leaving] body { visibility: hidden !important; }',

    // Doors to feeds, reels, explore, notifications, create, Threads, Meta AI and "get the app".
    // Prefixes end in "/" so a username that merely starts with "reels" is not caught.
    'a[href="/"], a[href="https://www.instagram.com/"], a[href^="/?"],',
    'a[href="/reels/"], a[href^="/reels/"], a[href*="/reels/audio/"], a[href="/explore/"],',
    'a[href^="/explore/"]:not([href^="/explore/search"]), a[href^="/explore/search/keyword"],',
    'a[href^="/create/"], a[href^="/stories/highlights/"], a[href^="/s/"],',
    'a[href*="://meta.ai"], a[href*="://www.meta.ai"], a[href*="://threads."], a[href*="://www.threads."],',
    'a[href^="intent:"], a[href^="instagram:"],',
    'html:not([data-dmo-activity]) a[href^="/notifications/"],',
    'html:not([data-dmo-activity]) a[href^="/accounts/activity"],',
    '[data-pagelet="story_tray"], [data-dmo-hide]',
    '{ display: none !important; }',

    // Story links around avatars stay visible but do nothing; the tap falls through to the row.
    'a[href^="/stories/"], [data-dmo-inert] { pointer-events: none !important; }',

    // Story rings (drawn on a canvas around avatars) everywhere except inside a chat, where a
    // canvas might be a voice message waveform.
    'html[data-dmo-route="inbox"] canvas, html[data-dmo-route="search"] canvas,',
    'html[data-dmo-route="profile"] canvas, html[data-dmo-route="follow"] canvas',
    '{ visibility: hidden !important; }',
    'html[data-dmo-route="inbox"] [role="button"]:has(> canvas),',
    'html[data-dmo-route="search"] [role="button"]:has(> canvas),',
    'html[data-dmo-route="profile"] [role="button"]:has(> canvas),',
    'html[data-dmo-route="follow"] [role="button"]:has(> canvas)',
    '{ pointer-events: none !important; }',

    // Profiles: posts grid, Reels/Tagged/Saved tabs, highlights.
    'html[data-dmo-route="profile"] main a[href*="/p/"], html[data-dmo-route="profile"] main a[href*="/reel/"],',
    'html[data-dmo-route="profile"] main a[href*="/tv/"], html[data-dmo-route="profile"] main a[href$="/reels/"],',
    'html[data-dmo-route="profile"] main a[href$="/tagged/"], html[data-dmo-route="profile"] main a[href$="/saved/"],',
    'html[data-dmo-route="profile"] main a[href$="/feed/"], html[data-dmo-route="profile"] main a[href$="/reposts/"],',
    'html[data-dmo-route="profile"] main [role="tablist"], html[data-dmo-route="profile"] main [role="menu"]',
    '{ display: none !important; }',

    // Search: only accounts. Hashtags, places, audio, keyword results and any media are hidden.
    'html[data-dmo-route="search"] a[href^="/explore/tags/"], html[data-dmo-route="search"] a[href^="/explore/locations/"],',
    'html[data-dmo-route="search"] main a[href*="/p/"], html[data-dmo-route="search"] main a[href*="/reel/"]',
    '{ display: none !important; }',

    // Suggested accounts.
    'html[data-dmo-nosuggest] a[href^="/explore/people"],',
    'html[data-dmo-nosuggest] div:has(> div > a[href^="/explore/people"])',
    '{ display: none !important; }',

    // Shared posts / reels / stories in a chat: collapsed to a labelled placeholder.
    '[data-dmo-shared] { position: relative !important; overflow: hidden !important;',
    '  max-height: 52px !important; min-height: 44px !important; min-width: 180px !important; }',
    '[data-dmo-shared] > * { visibility: hidden !important; }',
    '[data-dmo-shared]::after { content: attr(data-dmo-label); position: absolute; inset: 0;',
    '  display: flex; align-items: center; justify-content: center; padding: 0 14px;',
    '  border-radius: 18px; background: rgba(128, 128, 128, 0.18); color: inherit;',
    '  font: 600 13px/1.2 system-ui, sans-serif; visibility: visible; pointer-events: none; }',
    '[data-dmo-viewer] { display: none !important; }',

    // Optional blur for photos and videos people send directly.
    '[data-dmo-blur] img, [data-dmo-blur] video, img[data-dmo-blur], video[data-dmo-blur]',
    '{ filter: blur(28px) !important; }',
  ].join('\n');

  var style = null;
  function ensureStyle() {
    var root = document.documentElement;
    if (!root) return;
    if (!style) {
      style = document.createElement('style');
      style.id = 'dmo-style';
      style.textContent = CSS;
    }
    if (!style.isConnected) (document.head || root).appendChild(style);
  }

  // ------------------------------------------------------------------------------- utilities

  function hide(el) {
    if (el && !el.hasAttribute('data-dmo-hide')) el.setAttribute('data-dmo-hide', '');
  }

  /** Never hide anything that holds the conversation, the message box or the page itself. */
  function holdsChat(el) {
    return !!el.querySelector(
      'a[href^="/direct/t/"], textarea, [contenteditable="true"], [role="main"], main, input'
    );
  }

  function commonAncestor(a, b) {
    var seen = [];
    for (var n = a; n; n = n.parentElement) seen.push(n);
    for (var m = b; m; m = m.parentElement) if (seen.indexOf(m) !== -1) return m;
    return null;
  }

  function rect(el) {
    try {
      return el.getBoundingClientRect();
    } catch (e) {
      return { top: 0, bottom: 0, left: 0, right: 0, width: 0, height: 0 };
    }
  }

  function labelOf(el) {
    return (el.getAttribute('aria-label') || el.getAttribute('title') || '').trim();
  }

  // ------------------------------------------------------------------- Instagram's own tab bar

  var USERNAME_PATH = /^\/([A-Za-z0-9._]{1,30})\/?$/;
  var reportedUsername = null;

  function reportUsername(name) {
    if (!name || name === reportedUsername) return;
    reportedUsername = name;
    try {
      if (bridge) bridge.reportUsername(name);
    } catch (e) {}
  }

  /** The tab bar: the common ancestor of two of its links, if it looks like a small bar. */
  function findTabBar() {
    var pairs = [
      ['a[href="/explore/"]', 'a[href="/reels/"]'],
      ['a[href="/"]', 'a[href="/direct/inbox/"]'],
      ['a[href="/explore/"]', 'a[href="/direct/inbox/"]'],
    ];
    for (var i = 0; i < pairs.length; i++) {
      var a = document.querySelector(pairs[i][0]);
      var b = document.querySelector(pairs[i][1]);
      if (!a || !b || a === b) continue;
      var bar = commonAncestor(a, b);
      if (!bar || bar === document.body || bar === document.documentElement) continue;
      if (holdsChat(bar)) continue;
      if (bar.querySelectorAll('a[href]').length > 14) continue;
      return bar;
    }
    return null;
  }

  /** Instagram's profile tab links to "/<you>/". Read it before the bar is hidden. */
  function captureUsername(bar) {
    var links = bar.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      var href = links[i].getAttribute('href') || '';
      var m = USERNAME_PATH.exec(href);
      if (m && routeOf(href) === 'profile') {
        reportUsername(m[1]);
        return;
      }
    }
  }

  var hiddenBar = null;

  function hideTabBar() {
    // Runs every frame while the page changes, so skip the search once the bar is dealt with.
    if (hiddenBar && hiddenBar.isConnected && reportedUsername) return;
    var bar = findTabBar();
    if (!bar) return;
    if (!reportedUsername) captureUsername(bar);
    hide(bar);
    hiddenBar = bar;
  }

  // ------------------------------------------------------------------------- refused links

  /*
   * Any remaining link to a refused page is hidden. Story links around avatars are made inert
   * instead (hiding them would hide the profile picture). Highlights are hidden by the CSS above.
   */
  function sweepLinks() {
    if (onAuthPage()) return;
    var links = document.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      var a = links[i];
      var href = a.getAttribute('href') || '';
      if (a.getAttribute('data-dmo-seen') === href) continue;
      a.setAttribute('data-dmo-seen', href);
      if (!refused(a.href)) {
        // React reuses nodes: a link that now goes somewhere allowed must come back.
        if (a.getAttribute('data-dmo-hide') === 'link') a.removeAttribute('data-dmo-hide');
        a.removeAttribute('data-dmo-inert');
        continue;
      }
      // Shared posts in a chat are already covered by a placeholder; hiding the link would hide that.
      if (a.closest('[data-dmo-shared]')) continue;
      var path = igPath(a) || '';
      if (/^\/stories\//.test(path) && !/^\/stories\/highlights\//.test(path)) {
        a.setAttribute('data-dmo-inert', '');
      } else if (!holdsChat(a) && !a.hasAttribute('data-dmo-hide')) {
        a.setAttribute('data-dmo-hide', 'link');
      }
    }
  }

  // --------------------------------------------------------------------------- profile grid

  /** Collapse the whole posts grid, not just its tiles, when it can be told apart from the header. */
  function hideProfileGrid() {
    if (route !== 'profile') return;
    var tiles = [];
    var links = document.querySelectorAll('a[href*="/p/"], a[href*="/reel/"], a[href*="/tv/"]');
    for (var i = 0; i < links.length; i++) {
      if (refused(links[i].href)) tiles.push(links[i]);
    }
    if (tiles.length < 2) return;
    var grid = commonAncestor(tiles[0], tiles[tiles.length - 1]);
    if (!grid || grid === document.body || grid === document.documentElement) return;
    if (grid.tagName === 'MAIN' || grid.getAttribute('role') === 'main') return;
    if (grid.querySelector('header, a[href$="/followers/"], a[href$="/following/"], button, textarea')) return;
    hide(grid);
  }

  // ------------------------------------------------------------------------- suggestions

  var SUGGESTED_TEXT = new RegExp(
    '^(suggested for you|suggested|suggestions for you|suggestions|similar accounts|discover people|' +
      'people you may know|vorschläge für dich|vorgeschlagen für dich|sugerencias para ti|sugerido para ti|' +
      'sugestões para você|suggerimenti per te|suggestions pour vous|voorgesteld voor jou|önerilenler)$',
    'i'
  );

  function hideSuggestions() {
    if (!config.hideSuggestions) return;
    if (route !== 'profile' && route !== 'follow' && route !== 'search') return;
    var nodes = document.querySelectorAll('span, h2, h3, h4, div[role="heading"]');
    for (var i = 0; i < nodes.length && i < 4000; i++) {
      var n = nodes[i];
      if (n.closest('[data-dmo-hide]')) continue;
      if (n.childElementCount > 1) continue;
      var text = (n.textContent || '').trim();
      if (text.length > 40 || !SUGGESTED_TEXT.test(text)) continue;
      var section = suggestionSection(n);
      if (section) hide(section);
    }
  }

  /**
   * The block a "Suggested for you" heading belongs to: the heading and the few profile links after
   * it. Climbing stops before any ancestor that also holds content *above* the heading (on a
   * followers page that would be the followers list itself).
   */
  function suggestionSection(heading) {
    var best = null;
    for (var el = heading.parentElement, depth = 0; el && depth < 8; el = el.parentElement, depth++) {
      if (el === document.body || el.tagName === 'MAIN' || el.tagName === 'HEADER') break;
      if (el.getAttribute('role') === 'main' || el.getAttribute('role') === 'dialog') break;
      if (holdsChat(el) || el.querySelector('header, a[href$="/followers/"], a[href$="/following/"]')) break;
      if (hasLinkBefore(el, heading)) break;
      var links = el.querySelectorAll('a[href]').length;
      if (links > 60) break;
      if (links > 0) best = el;
    }
    return best;
  }

  /** True if [scope] holds a link that comes before [node] in the page (and doesn't contain it). */
  function hasLinkBefore(scope, node) {
    var first = scope.querySelector('a[href]'); // document order: the first link decides
    if (!first || first.contains(node)) return false;
    return !!(first.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_FOLLOWING);
  }

  // ------------------------------------------------------------------------------ DM inbox

  // Only the tray's own prompts. Bare words like "Note" or "Not" would also match a chat called
  // "Notes" or a message preview, and hide a conversation.
  var NOTE_TEXT = new RegExp(
    '^(your note|leave a note|share a note|add a note|share a thought|share a thought\\.\\.\\.|note\\.\\.\\.|' +
      'deine notiz|notiz hinterlassen|tu nota|deja una nota|sua nota|deixe uma nota|la tua nota|' +
      'lascia una nota|votre note|laisser une note|jouw notitie|notunuz)$',
    'i'
  );
  var BACK_LABEL = /^(back|go back|zurück|retour|atrás|volver|indietro|voltar|terug|wstecz|geri|назад)$/i;

  /** The notes tray: a short horizontal strip near the top of the inbox, labelled "Note"-ish. */
  function hideNotes() {
    if (route !== 'inbox') return;
    var nodes = document.querySelectorAll('[aria-label], [title], span, div[role="button"]');
    for (var i = 0; i < nodes.length && i < 2500; i++) {
      var n = nodes[i];
      if (n.closest('[data-dmo-hide]')) continue;
      var label = labelOf(n) || (n.childElementCount === 0 ? (n.textContent || '').trim() : '');
      if (!label || label.length > 40 || !NOTE_TEXT.test(label)) continue;
      var tray = notesTrayFrom(n);
      if (tray) hide(tray);
    }
  }

  function notesTrayFrom(node) {
    var viewport = window.innerWidth || 360;
    var best = null;
    for (var el = node.parentElement, depth = 0; el && depth < 12; el = el.parentElement, depth++) {
      if (el === document.body || el.tagName === 'MAIN' || el.getAttribute('role') === 'main') break;
      var chatish = 'a[href^="/direct/t/"], input, textarea, [contenteditable="true"]';
      if (el.matches(chatish) || el.querySelector(chatish)) break;
      var r = rect(el);
      if (r.height > 230 || r.bottom > 600) break;
      var wide = r.width >= Math.min(viewport * 0.65, 260);
      var strip = el.scrollWidth > el.clientWidth + 4 || el.querySelectorAll('img').length >= 2;
      if (r.top <= 360 && r.height >= 36 && wide && strip) best = el;
    }
    return best;
  }

  /** The inbox's back chevron leads to the feed; hide it. */
  function hideInboxBack() {
    if (route !== 'inbox') return;
    var icons = document.querySelectorAll('svg[aria-label]');
    for (var i = 0; i < icons.length; i++) {
      if (!BACK_LABEL.test(labelOf(icons[i]))) continue;
      if (rect(icons[i]).top > 140) continue;
      hide(icons[i].closest('a, button, [role="button"]') || icons[i]);
    }
  }

  // ------------------------------------------------------------------------- DM threads

  var SHARED_LABEL = /^(clip|clips|reel|reels|collage|story|post)$/i;
  var SHARED_PATH = /^\/(p|reels?|tv|s)\/|^\/stories\/(highlights\/|[^/]+\/\d)|^\/[^/]+\/(p|reels?|tv)\//;
  var PROFILE_PICTURE = /profile picture|profilbild|photo de profil|foto del perfil|foto do perfil|immagine del profilo|profielfoto/i;

  /** The message bubble around a photo/video: the outermost button not much taller than it. */
  function bubbleOf(media, mediaHeight) {
    var bubble = null;
    for (var el = media.parentElement, i = 0; el && i < 12; el = el.parentElement, i++) {
      if (el === document.body || el.tagName === 'MAIN') break;
      if (el.getAttribute('role') === 'button' && rect(el).height < mediaHeight * 2.5) bubble = el;
    }
    return bubble;
  }

  /*
   * What a shared item looks like. Photos and videos sent directly contain no links, while a shared
   * post/reel/story links to its author's profile or to the post itself. Anything linking to an
   * Instagram page outside /direct/ therefore counts, as does an explicit Reel/Post/Story label.
   */
  function sharedKind(bubble) {
    var links = bubble.querySelectorAll('a[href]');
    var kind = null;
    for (var i = 0; i < links.length; i++) {
      var path = igPath(links[i]);
      if (path === null || path.indexOf('/direct/') === 0) continue;
      if (/\/(reels?|clips)\//.test(path)) return 'reel';
      if (/^\/(stories|s)\//.test(path)) return 'story';
      kind = 'post';
    }
    if (kind) return kind;
    var labelled = bubble.querySelectorAll('[aria-label]');
    for (var j = 0; j < labelled.length; j++) {
      var label = labelOf(labelled[j]);
      if (!SHARED_LABEL.test(label)) continue;
      if (/reel|clip/i.test(label)) return 'reel';
      if (/story/i.test(label)) return 'story';
      return 'post';
    }
    return null;
  }

  var KIND_LABEL = {
    reel: 'Shared reel hidden',
    story: 'Shared story hidden',
    post: 'Shared post hidden',
  };

  function markShared(el, kind) {
    if (el.hasAttribute('data-dmo-shared')) return;
    el.setAttribute('data-dmo-shared', '');
    el.setAttribute('data-dmo-label', KIND_LABEL[kind] || KIND_LABEL.post);
    silence(el);
  }

  function silence(scope) {
    var videos = scope.getElementsByTagName('video');
    for (var i = 0; i < videos.length; i++) {
      try {
        videos[i].muted = true;
        videos[i].autoplay = false;
        videos[i].pause();
      } catch (e) {}
    }
  }

  function inChat() {
    return route === 'thread' || route === 'dm' || route === 'inbox';
  }

  function sweepChatMedia() {
    if (route !== 'thread' && route !== 'dm') return;
    var media = document.querySelectorAll('img, video');
    for (var i = 0; i < media.length; i++) {
      var m = media[i];
      if (m.closest('[data-dmo-shared], [data-dmo-blur], [data-dmo-revealed], [data-dmo-hide]')) continue;
      if (PROFILE_PICTURE.test(m.getAttribute('alt') || '')) continue; // e.g. a shared profile card
      var r = rect(m);
      if (r.height < 90 || r.width < 60) continue; // avatars, stickers, reactions

      // Media inside a link to a post/reel/story, whatever wraps it.
      var link = m.closest('a[href]');
      if (link && refused(link.href)) {
        var linkPath = igPath(link) || '';
        markShared(bubbleOf(m, r.height) || link, /\/(reels?|clips)\//.test(linkPath) ? 'reel' : 'post');
        continue;
      }

      var bubble = bubbleOf(m, r.height);
      if (!bubble) continue;
      var kind = sharedKind(bubble);
      if (kind) {
        markShared(bubble, kind);
      } else if (config.blurMedia && !bubble.hasAttribute('data-dmo-revealed')) {
        bubble.setAttribute('data-dmo-blur', '');
      }
    }

    // Links to posts/reels/stories without visible media yet (still loading) still get covered. A
    // bare /stories/<user>/ link is just a story ring around an avatar (made inert elsewhere); only
    // links to a specific story item count as a shared story.
    var links = document.querySelectorAll('a[href]');
    for (var j = 0; j < links.length; j++) {
      var a = links[j];
      if (a.closest('[data-dmo-shared]') || a.hasAttribute('data-dmo-inert')) continue;
      var p = igPath(a);
      if (p === null || !SHARED_PATH.test(p)) continue;
      var kindOfLink = /\/(reels?|clips)\//.test(p) ? 'reel' : /^\/(stories|s)\//.test(p) ? 'story' : 'post';
      markShared(boundedBubble(a.closest('[role="button"]'), a), kindOfLink);
    }
  }

  /** [candidate] if it is a plausible single message, otherwise [fallback]. */
  function boundedBubble(candidate, fallback) {
    if (!candidate || holdsChat(candidate) || rect(candidate).height > 640) return fallback;
    return candidate;
  }

  // ------------------------------------------------------------------------- reel viewer

  /*
   * Tapping a reel in a chat doesn't navigate: Instagram opens a viewer over the chat (the URL stays
   * /direct/t/<id>/), built as a vertical scroll-snap container holding the reel plus suggested
   * ones. Shared bubbles are untappable, so this should never open; if it does, it is hidden, its
   * videos are stopped, and it is closed (by its own close button, else by reloading the chat).
   */
  var CLOSE_LABEL = /^(close|back|go back|schließen|zurück|fermer|retour|cerrar|atrás|volver|chiudi|indietro|fechar|voltar|sluiten|terug|zamknij|kapat|закрыть|назад)$/i;

  function snaps(el) {
    var align = getComputedStyle(el).scrollSnapAlign;
    return !!align && align !== 'none';
  }

  function snapItemOf(node) {
    for (var n = node; n && n !== document.documentElement; n = n.parentElement) {
      if (snaps(n)) return n;
    }
    return null;
  }

  function verticalSnapAncestor(node) {
    for (var n = node.parentElement; n && n !== document.documentElement; n = n.parentElement) {
      if (/(^|\s)(y|block|both)(\s|$)/.test(getComputedStyle(n).scrollSnapType)) return n;
    }
    return null;
  }

  function snapItemCount(container) {
    var count = 0;
    var kids = container.children;
    for (var i = 0; i < kids.length && i < 40; i++) {
      var kid = kids[i];
      if (snaps(kid) || kid.querySelector('video')) count++;
    }
    return count;
  }

  /** The nearest dialog or fixed-position layer around the viewer (not the app shell further out). */
  function overlayOf(el) {
    for (var n = el; n && n !== document.body; n = n.parentElement) {
      if (n.getAttribute('role') === 'dialog' || getComputedStyle(n).position === 'fixed') return n;
    }
    return el;
  }

  /** The viewer's close control: outside the reel feed first, and "Close" before "Back". */
  function findCloseButton(overlay, viewer) {
    var labelled = overlay.querySelectorAll('[aria-label]');
    var best = null;
    var bestScore = -1;
    for (var i = 0; i < labelled.length; i++) {
      var label = labelOf(labelled[i]);
      if (!CLOSE_LABEL.test(label)) continue;
      var score = (viewer.contains(labelled[i]) ? 0 : 2) + (/back|zurück|retour|atrás|volver|indietro|voltar|terug|geri|назад/i.test(label) ? 0 : 1);
      if (score > bestScore) {
        bestScore = score;
        best = labelled[i].closest('button, [role="button"], a') || labelled[i];
      }
    }
    return best;
  }

  var RELOAD_KEY = '__dmoViewerReloads';
  var selfClick = false;

  function closeViewer(viewer) {
    var overlay = overlayOf(viewer);
    silence(overlay);
    var close = findCloseButton(overlay, viewer);
    if (close) {
      // Let this one click through the script's own guard on the viewer.
      selfClick = true;
      try {
        close.click();
      } catch (e) {}
      selfClick = false;
    } else {
      try {
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      } catch (e) {}
    }
    setTimeout(function () {
      if (!viewer.isConnected) return;
      // Still open: reload the chat. After a few of those in a short time, go to the inbox.
      var reloads = countRecent(RELOAD_KEY, 15000);
      leave();
      location.replace(reloads <= 3 ? location.href : INBOX);
    }, 700);
  }

  function shutReelViewers() {
    if (!inChat()) return;
    var videos = document.getElementsByTagName('video');
    for (var i = 0; i < videos.length; i++) {
      var v = videos[i];
      if (v.closest('[data-dmo-viewer], [data-dmo-shared]')) continue;
      var item = snapItemOf(v);
      var viewer = item && verticalSnapAncestor(item);
      if (!viewer) continue;
      // A single video in a snap container may be someone's own video opened full screen; a feed
      // of several is the reels viewer.
      if (snapItemCount(viewer) < 2) continue;
      viewer.setAttribute('data-dmo-viewer', '');
      noticeBlocked();
      closeViewer(viewer);
    }
  }

  // Stop anything playing inside hidden shared items or a viewer being shut.
  document.addEventListener(
    'play',
    function (e) {
      var t = e.target;
      if (t && t.closest && t.closest('[data-dmo-shared], [data-dmo-viewer]')) {
        try {
          t.pause();
        } catch (err) {}
      }
    },
    true
  );

  // ----------------------------------------------------------------------- app entry points

  /** Navigate inside the single-page app by clicking Instagram's own (hidden) link, if present. */
  window.__dmoGo = function (path) {
    try {
      var links = document.querySelectorAll('a[href]');
      for (var i = 0; i < links.length; i++) {
        if (links[i].getAttribute('href') === path) {
          links[i].click();
          return true;
        }
      }
    } catch (e) {}
    return false;
  };

  /** Find the logged-in username: Instagram's tab bar first, then the web API. */
  window.__dmoLookupUsername = function () {
    var bar = findTabBar();
    if (bar) captureUsername(bar);
    if (reportedUsername) {
      try {
        if (bridge) bridge.reportUsername(reportedUsername);
      } catch (e) {}
      return;
    }
    var m = /(?:^|;\s*)ds_user_id=(\d+)/.exec(document.cookie || '');
    if (!m || !window.fetch) return;
    fetch('/api/v1/users/' + m[1] + '/info/', {
      credentials: 'include',
      headers: { 'X-IG-App-ID': '1217981644879628', 'X-Requested-With': 'XMLHttpRequest' },
    })
      .then(function (r) {
        return r.ok ? r.json() : null;
      })
      .then(function (j) {
        if (j && j.user && j.user.username) reportUsername(j.user.username);
      })
      .catch(function () {});
  };

  // ----------------------------------------------------------------------------- scheduling

  function cheapPass() {
    ensureStyle();
    updateRoute();
    if (onAuthPage()) return;
    hideTabBar();
  }

  var lastTextScan = 0;

  function fullPass() {
    cheapPass();
    if (onAuthPage()) return;
    // Chat media first, so a shared post's own link is covered by its placeholder, not just hidden.
    var steps = [sweepChatMedia, sweepLinks, hideProfileGrid, hideInboxBack, shutReelViewers];
    // The text scans walk every span on the page, so they run less often.
    if (Date.now() - lastTextScan > 600) {
      lastTextScan = Date.now();
      steps.push(hideSuggestions, hideNotes);
    }
    for (var i = 0; i < steps.length; i++) {
      try {
        steps[i]();
      } catch (e) {}
    }
  }

  var frameQueued = false;
  var fullTimer = 0;
  var lastFull = 0;

  // Cheap work once a frame at most; the heavier sweeps at most every 200 ms.
  function schedule() {
    if (!frameQueued) {
      frameQueued = true;
      requestAnimationFrame(function () {
        frameQueued = false;
        try {
          cheapPass();
        } catch (e) {}
      });
    }
    if (fullTimer) return;
    var wait = Math.max(0, 200 - (Date.now() - lastFull));
    fullTimer = setTimeout(function () {
      fullTimer = 0;
      lastFull = Date.now();
      fullPass();
    }, wait);
  }

  function start() {
    ensureStyle();
    updateRoute();
    new MutationObserver(schedule).observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['href', 'src'],
    });
    // Images gaining their real size change what counts as chat media.
    document.addEventListener('load', schedule, true);
    window.addEventListener('resize', schedule);
    setInterval(fullPass, 1500);
    schedule();
  }

  if (document.documentElement) {
    start();
  } else {
    // Document-start scripts can run before <html> exists.
    new MutationObserver(function (records, observer) {
      if (!document.documentElement) return;
      observer.disconnect();
      start();
    }).observe(document, { childList: true });
  }
})();
