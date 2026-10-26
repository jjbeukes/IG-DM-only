// Tests for the injected page filter (app/src/main/assets/dmonly.js), run in jsdom.
//
// jsdom has no layout engine, so elements report their size through data-h / data-w / data-top
// attributes, and scroll-snap styles through data-snap-type / data-snap-align. The URL policy is a
// simplified stand-in for the Kotlin UrlPolicy (which has its own unit tests); these tests are about
// what the script does with the answers.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { JSDOM, VirtualConsole } from 'jsdom';

const SCRIPT = readFileSync(new URL('../../app/src/main/assets/dmonly.js', import.meta.url), 'utf8');

function mockDecide(abs) {
  const u = new URL(abs);
  if (!/(^|\.)instagram\.com$/.test(u.hostname)) return 'external';
  const p = u.pathname;
  if (/^\/direct(\/|$)/.test(p) || /^\/explore\/search\/?$/.test(p)) return 'allow';
  if (/^\/accounts\//.test(p)) return /^\/accounts\/activity/.test(p) ? 'block' : 'allow';
  if (p === '/' || /^\/(explore|reels?|p|tv|stories|s|create|notifications)(\/|$)/.test(p)) return 'block';
  if (/^\/[A-Za-z0-9._]{1,30}\/((followers|following)\/?)?$/.test(p)) return 'allow';
  return 'block';
}

function mockRoute(abs) {
  const p = new URL(abs).pathname;
  if (/^\/direct\/(inbox\/?)?$/.test(p)) return 'inbox';
  if (/^\/direct\/t\//.test(p)) return 'thread';
  if (/^\/direct\//.test(p)) return 'dm';
  if (/^\/explore\/search\/?$/.test(p)) return 'search';
  if (/^\/accounts\/(login|onetap)/.test(p)) return 'auth';
  if (/^\/[A-Za-z0-9._]{1,30}\/(followers|following)\/?$/.test(p)) return 'follow';
  if (/^\/[A-Za-z0-9._]{1,30}\/?$/.test(p) && mockDecide(abs) === 'allow') return 'profile';
  return 'other';
}

function load(path, body, { config = {} } = {}) {
  const virtualConsole = new VirtualConsole(); // swallow jsdom's "navigation not implemented"
  const dom = new JSDOM(`<!doctype html><html><head></head><body>${body}</body></html>`, {
    url: 'https://www.instagram.com' + path,
    runScripts: 'outside-only',
    pretendToBeVisual: true,
    virtualConsole,
  });
  const { window } = dom;
  const calls = { usernames: [], blocked: 0 };
  window.__dmoBridge = {
    decide: (u) => mockDecide(u),
    route: (u) => mockRoute(u),
    config: () => JSON.stringify(config),
    reportUsername: (n) => calls.usernames.push(n),
    blocked: () => {
      calls.blocked++;
    },
  };
  window.Element.prototype.getBoundingClientRect = function () {
    const h = +(this.getAttribute('data-h') || 0);
    const w = +(this.getAttribute('data-w') || 0);
    const top = +(this.getAttribute('data-top') || 0);
    return { top, bottom: top + h, left: 0, right: w, width: w, height: h, x: 0, y: top };
  };
  const realStyle = window.getComputedStyle.bind(window);
  window.getComputedStyle = (el) => {
    const s = realStyle(el);
    return {
      scrollSnapType: el.getAttribute('data-snap-type') || 'none',
      scrollSnapAlign: el.getAttribute('data-snap-align') || 'none',
      position: s.position || 'static',
      overflowY: s.overflowY,
    };
  };
  window.eval(SCRIPT);
  return { window, document: window.document, calls };
}

const settle = (window, ms = 750) => new Promise((resolve) => window.setTimeout(resolve, ms));

function click(window, el) {
  const event = new window.MouseEvent('click', { bubbles: true, cancelable: true });
  el.dispatchEvent(event);
  return event.defaultPrevented;
}

test('installs once, marks the route and injects its stylesheet', async () => {
  const { window, document } = load('/direct/inbox/', '<main></main>');
  window.eval(SCRIPT); // second run is a no-op
  await settle(window, 50);
  assert.equal(document.documentElement.getAttribute('data-dmo-route'), 'inbox');
  assert.equal(document.querySelectorAll('#dmo-style').length, 1);
  window.close();
});

test('taps on links to refused pages are cancelled, chat links are not', async () => {
  const { window, document, calls } = load(
    '/direct/inbox/',
    '<a id="reels" href="/reels/">Reels</a><a id="post" href="https://www.instagram.com/p/abc/">post</a>' +
      '<a id="thread" href="/direct/t/1/">thread</a><a id="profile" href="/natgeo/">natgeo</a>'
  );
  assert.equal(click(window, document.getElementById('reels')), true);
  assert.equal(click(window, document.getElementById('post')), true);
  assert.equal(click(window, document.getElementById('thread')), false);
  assert.equal(click(window, document.getElementById('profile')), false);
  assert.ok(calls.blocked >= 1);
  window.close();
});

test('a button nested in a refused link still receives the tap', async () => {
  const { window, document } = load('/direct/t/1/', '<a href="/p/abc/"><div id="inner" role="button">x</div></a>');
  let reached = false;
  document.getElementById('inner').addEventListener('click', () => {
    reached = true;
  });
  assert.equal(click(window, document.getElementById('inner')), true); // the link's own navigation is cancelled
  assert.equal(reached, true);
  window.close();
});

test('pushState to a refused page is cancelled; allowed pages go through', async () => {
  const { window } = load('/direct/inbox/', '<main></main>');
  window.history.pushState({}, '', '/p/abc/');
  assert.equal(window.location.pathname, '/direct/inbox/');
  assert.equal(JSON.parse(window.sessionStorage.getItem('__dmoBounces')).length, 1);
  window.history.pushState({}, '', '/direct/t/42/');
  assert.equal(window.location.pathname, '/direct/t/42/');
  window.history.replaceState({}, '', '/direct/t/42/?x=1');
  assert.equal(window.location.search, '?x=1');
  window.close();
});

test("Instagram's tab bar is hidden and the username read from it", async () => {
  const { window, document, calls } = load(
    '/explore/search/',
    '<main><input aria-label="Search input"></main>' +
      '<div id="bar"><a href="/">Home</a><a href="/explore/">Explore</a><a href="/reels/">Reels</a>' +
      '<a href="/direct/inbox/">Messages</a><a href="/jj.test_1/"><img alt="Profile"></a></div>'
  );
  await settle(window);
  assert.ok(document.getElementById('bar').hasAttribute('data-dmo-hide'));
  assert.deepEqual(calls.usernames, ['jj.test_1']);
  window.close();
});

test('a "bar" that holds the chat is never hidden', async () => {
  const { window, document } = load(
    '/direct/inbox/',
    '<div id="page"><a href="/explore/">Explore</a><main><a href="/direct/t/1/">t</a></main><a href="/reels/">Reels</a></div>'
  );
  await settle(window);
  assert.equal(document.getElementById('page').hasAttribute('data-dmo-hide'), false);
  window.close();
});

const THREAD = `
  <main>
    <div role="button" id="shared" data-h="320" data-w="250">
      <a href="/someauthor/">someauthor</a><img src="a.jpg" data-h="300" data-w="240">
    </div>
    <div role="button" id="photo" data-h="310" data-w="250"><img src="b.jpg" data-h="300" data-w="240"></div>
    <div role="button" id="reel" data-h="420" data-w="250">
      <img src="c.jpg" data-h="400" data-w="240"><svg aria-label="Reel"></svg>
    </div>
    <div role="button" id="linked" data-h="300" data-w="250">
      <a href="/reel/XYZ/"><img src="d.jpg" data-h="280" data-w="240"></a>
    </div>
    <img id="avatar" src="e.jpg" data-h="32" data-w="32">
  </main>
  <textarea></textarea>`;

test('posts and reels shared into a chat are covered and untappable; own photos are not', async () => {
  const { window, document } = load('/direct/t/1/', THREAD);
  await settle(window);
  const shared = document.getElementById('shared');
  assert.ok(shared.hasAttribute('data-dmo-shared'));
  assert.equal(shared.getAttribute('data-dmo-label'), 'Shared post hidden');
  assert.equal(document.getElementById('reel').getAttribute('data-dmo-label'), 'Shared reel hidden');
  assert.equal(document.getElementById('linked').getAttribute('data-dmo-label'), 'Shared reel hidden');
  assert.equal(document.getElementById('photo').hasAttribute('data-dmo-shared'), false);
  assert.equal(document.getElementById('photo').hasAttribute('data-dmo-blur'), false);
  assert.equal(click(window, shared.querySelector('img')), true);
  assert.equal(click(window, document.getElementById('photo').querySelector('img')), false);
  window.close();
});

test('with blur on, own photos are blurred until the first tap', async () => {
  const { window, document } = load('/direct/t/1/', THREAD, { config: { blurMedia: true } });
  await settle(window);
  const photo = document.getElementById('photo');
  assert.ok(photo.hasAttribute('data-dmo-blur'));
  assert.equal(click(window, photo.querySelector('img')), true); // first tap only reveals
  assert.equal(photo.hasAttribute('data-dmo-blur'), false);
  assert.ok(photo.hasAttribute('data-dmo-revealed'));
  await settle(window, 300);
  assert.equal(photo.hasAttribute('data-dmo-blur'), false, 'stays revealed');
  assert.equal(click(window, photo.querySelector('img')), false);
  window.close();
});

test('the in-chat reel viewer is detected and shut', async () => {
  const { window, document, calls } = load(
    '/direct/t/1/',
    '<main><textarea></textarea></main><div role="dialog"><div id="viewer" data-snap-type="y mandatory">' +
      '<div data-snap-align="start"><video></video></div><div data-snap-align="start"><video></video></div>' +
      '</div><div role="button" aria-label="Close" id="close"></div></div>'
  );
  let closed = false;
  document.getElementById('close').addEventListener('click', () => {
    closed = true;
  });
  await settle(window);
  assert.ok(document.getElementById('viewer').hasAttribute('data-dmo-viewer'));
  assert.equal(closed, true);
  assert.ok(calls.blocked >= 1);
  window.close();
});

test("someone's single video in a snap container is left alone", async () => {
  const { window, document } = load(
    '/direct/t/1/',
    '<main><textarea></textarea></main><div id="viewer" data-snap-type="y mandatory">' +
      '<div data-snap-align="start"><video></video></div></div>'
  );
  await settle(window);
  assert.equal(document.getElementById('viewer').hasAttribute('data-dmo-viewer'), false);
  window.close();
});

test('profile: posts grid, highlights and story links are dealt with; follow lists stay', async () => {
  const { window, document } = load(
    '/natgeo/',
    '<main><header><a id="story" href="/stories/natgeo/"><img alt="pic"></a>' +
      '<a id="followers" href="/natgeo/followers/">10 followers</a><a id="following" href="/natgeo/following/">5</a></header>' +
      '<div><a id="highlight" href="/stories/highlights/123/">trip</a></div>' +
      '<div id="grid"><div><a href="/p/A1/"><img></a><a href="/p/A2/"><img></a></div>' +
      '<div><a href="/natgeo/reel/B1/"><img></a></div></div></main>'
  );
  await settle(window);
  assert.ok(document.getElementById('grid').hasAttribute('data-dmo-hide'));
  assert.ok(document.getElementById('highlight').hasAttribute('data-dmo-hide'));
  assert.ok(document.getElementById('story').hasAttribute('data-dmo-inert'));
  assert.equal(document.getElementById('story').hasAttribute('data-dmo-hide'), false);
  assert.equal(document.getElementById('followers').hasAttribute('data-dmo-hide'), false);
  assert.equal(document.getElementById('following').hasAttribute('data-dmo-hide'), false);
  assert.equal(click(window, document.getElementById('story')), true);
  window.close();
});

test('suggested accounts are hidden when the setting is on', async () => {
  const body =
    '<main><header><a href="/natgeo/followers/">10</a></header>' +
    '<div id="sugg"><div><span>Suggested for you</span></div><a href="/a/">a</a><a href="/b/">b</a></div></main>';
  let page = load('/natgeo/', body, { config: { hideSuggestions: true } });
  await settle(page.window);
  assert.ok(page.document.getElementById('sugg').hasAttribute('data-dmo-hide'));
  page.window.close();

  page = load('/natgeo/', body, { config: { hideSuggestions: false } });
  await settle(page.window);
  assert.equal(page.document.getElementById('sugg').hasAttribute('data-dmo-hide'), false);
  page.window.close();
});

test('the notes tray at the top of the inbox is hidden, the chat list is not', async () => {
  const { window, document } = load(
    '/direct/inbox/',
    '<main><div id="notes" data-h="120" data-w="360" data-top="60">' +
      '<div data-h="100" data-w="80" data-top="60"><span>Your note</span><img><img></div>' +
      '<div data-h="100" data-w="80" data-top="60"><img></div></div>' +
      '<div id="list" data-h="900" data-w="360" data-top="200"><div role="button">Alice: hi</div></div></main>'
  );
  await settle(window);
  assert.ok(document.getElementById('notes').hasAttribute('data-dmo-hide'));
  assert.equal(document.getElementById('list').hasAttribute('data-dmo-hide'), false);
  window.close();
});

test('nothing is hidden on login pages', async () => {
  const { window, document } = load(
    '/accounts/login/',
    '<main><a href="/">Instagram</a><input name="username"><input type="password"><a href="/accounts/emailsignup/">Sign up</a></main>'
  );
  await settle(window);
  assert.equal(document.querySelectorAll('[data-dmo-hide]').length, 0);
  assert.equal(document.documentElement.getAttribute('data-dmo-route'), 'auth');
  window.close();
});

test('__dmoGo clicks Instagram\'s own link for in-app navigation', async () => {
  const { window, document } = load('/natgeo/', '<div><a id="inbox" href="/direct/inbox/">Messages</a></div>');
  let clicked = false;
  document.getElementById('inbox').addEventListener('click', (e) => {
    clicked = true;
    e.preventDefault();
  });
  assert.equal(window.__dmoGo('/direct/inbox/'), true);
  assert.equal(clicked, true);
  assert.equal(window.__dmoGo('/explore/search/'), false);
  window.close();
});

test('username lookup reports what the tab bar shows', async () => {
  const { window, calls } = load(
    '/direct/inbox/',
    '<div><a href="/explore/">E</a><a href="/reels/">R</a><a href="/someone/">me</a></div>'
  );
  calls.usernames.length = 0;
  window.__dmoLookupUsername();
  assert.ok(calls.usernames.includes('someone'));
  window.close();
});
