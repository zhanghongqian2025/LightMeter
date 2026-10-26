#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const vm = require('node:vm');

function deferred() {
  let resolve;
  const promise = new Promise((r) => { resolve = r; });
  return { promise, resolve };
}

function response(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get() { return null; } },
    json() { return Promise.resolve(body); },
    blob() { return Promise.resolve(new Blob(['x'])); }
  };
}

class Element {
  constructor(id, tagName) {
    this.id = id || '';
    this.tagName = (tagName || 'div').toUpperCase();
    this.disabled = false;
    this.hidden = true;
    this.checked = false;
    this.textContent = '';
    this.className = '';
    this.value = '';
    this.files = [];
    this.style = {};
    this.dataset = {};
    this.children = [];
    this.listeners = {};
    this.classList = { contains() { return false; } };
  }

  addEventListener(type, listener) { this.listeners[type] = listener; }
  appendChild(child) { this.children.push(child); return child; }
  removeChild(child) { this.children = this.children.filter((x) => x !== child); }
  querySelector() { return new Element('', 'span'); }
  querySelectorAll() { return []; }
  closest() { return null; }
  click() {
    if (this.listeners.click) { this.listeners.click({ target: this }); }
  }
  dispatch(type) {
    if (this.listeners[type]) { this.listeners[type]({ target: this }); }
  }
}

const elements = new Map();
function element(id) {
  if (!elements.has(id)) { elements.set(id, new Element(id)); }
  return elements.get(id);
}

const timers = [];
const delayedTask = deferred();
const delayedModelPoll = deferred();
const delayedModelAction = deferred();
let modelGets = 0;

function fetchMock(url) {
  const path = String(url);
  if (path === '/api/health') {
    return Promise.resolve(response(200, {
      ok: true,
      ruleCount: 1,
      sessionId: '1234567890abcdef',
      securityPosture: {},
      routes: ['POST /api/shutdown [写]']
    }));
  }
  if (path === '/api/rules') {
    return Promise.resolve(response(200, { rules: [] }));
  }
  if (path === '/api/model') {
    modelGets++;
    if (modelGets === 1) {
      return Promise.resolve(response(200, {
        state: 'READY', stateLabel: '已就绪', usable: true, actions: ['install']
      }));
    }
    return delayedModelPoll.promise;
  }
  if (path === '/api/model/install') { return delayedModelAction.promise; }
  if (path === '/api/task-scan') {
    return Promise.resolve(response(202, { ok: true, taskId: 'late-task' }));
  }
  if (path.startsWith('/api/task?')) { return delayedTask.promise; }
  if (path === '/api/shutdown') {
    return Promise.resolve(response(200, {
      ok: true, stopping: true, message: '正在停止'
    }));
  }
  throw new Error('unexpected fetch: ' + path);
}

const documentMock = {
  body: new Element('body', 'body'),
  getElementById: element,
  querySelector(selector) {
    if (selector === 'meta[name="litemask-token"]') {
      return { getAttribute() { return 'test-token'; } };
    }
    return element(selector);
  },
  querySelectorAll(selector) {
    if (selector === 'button, input') { return Array.from(elements.values()); }
    return [];
  },
  createElement(tag) { return new Element('', tag); },
  createTextNode(text) { const n = new Element('', 'text'); n.textContent = text; return n; }
};

const context = {
  document: documentMock,
  window: { location: { origin: 'http://127.0.0.1:1' }, confirm() { return true; } },
  fetch: fetchMock,
  setTimeout(fn) { timers.push(fn); return timers.length; },
  clearTimeout() {},
  TextEncoder,
  Blob,
  URL: { createObjectURL() { return 'blob:test'; }, revokeObjectURL() {} },
  console
};
context.window.document = documentMock;

async function flush() {
  for (let i = 0; i < 8; i++) {
    await new Promise((resolve) => setImmediate(resolve));
  }
}

async function main() {
  const app = fs.readFileSync(process.argv[2], 'utf8');
  vm.runInNewContext(app, context, { filename: 'app.js' });
  await flush();

  if (element('modelStateBadge').textContent !== '已就绪') {
    throw new Error('initial model response was not rendered');
  }
  const modelPoll = timers.shift();
  if (!modelPoll) { throw new Error('model poll was not scheduled'); }
  modelPoll();

  element('file').files = [{ name: 'sample.txt', size: 10 }];
  element('file').dispatch('change');
  element('scanBtn').click();
  element('modelInstallBtn').click();
  await flush();

  element('shutdownBtn').click();
  await flush();
  const controls = Array.from(elements.values());
  if (controls.some((node) => !node.disabled)) {
    throw new Error('shutdown did not lock every existing control');
  }

  delayedTask.resolve(response(500, { error: 'late task error' }));
  delayedModelPoll.resolve(response(200, {
    state: 'BROKEN', stateLabel: 'late broken state', usable: false, actions: ['install']
  }));
  delayedModelAction.resolve(response(500, { error: 'late model action error' }));
  await flush();

  if (controls.some((node) => !node.disabled)) {
    throw new Error('a delayed callback re-enabled a control after shutdown');
  }
  if (element('modelStateBadge').textContent !== '已就绪') {
    throw new Error('a delayed model poll rendered after shutdown');
  }
  for (const id of ['modelError', 'scanError', 'taskError']) {
    if (element(id).textContent) {
      throw new Error('a delayed callback showed an error after shutdown: ' + id);
    }
  }
  process.stdout.write('shutdown-race-ok\n');
}

main().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exitCode = 1;
});
