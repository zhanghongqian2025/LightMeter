#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const vm = require('node:vm');

function deferred() {
  let resolve;
  const promise = new Promise((r) => { resolve = r; });
  return { promise, resolve };
}

function response(status, body, blobText) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get(name) { return name === 'X-Litemask-Restored' ? '0' : null; } },
    json() { return Promise.resolve(body); },
    blob() { return Promise.resolve(new Blob([blobText || 'restored'])); }
  };
}

let downloadClicks = 0;
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
    if (this.tagName === 'A') { downloadClicks++; return; }
    if (!this.disabled && this.listeners.click) { this.listeners.click({ target: this }); }
  }
  dispatch(type) {
    if (!this.disabled && this.listeners[type]) { this.listeners[type]({ target: this }); }
  }
}

const elements = new Map();
function element(id) {
  if (!elements.has(id)) { elements.set(id, new Element(id)); }
  return elements.get(id);
}

const firstUpload = deferred();
const cancelRequest = deferred();
const retryUpload = deferred();
const shutdownUpload = deferred();
let uploadCalls = 0;
let cancelCalls = 0;
let shutdownCalls = 0;
let firstSignal = null;
let shutdownSignal = null;

function fetchMock(url, options) {
  const path = String(url);
  if (path === '/api/health') {
    return Promise.resolve(response(200, {
      ok: true, ruleCount: 1, sessionId: '1234567890abcdef',
      securityPosture: {}, routes: ['POST /api/shutdown [写]']
    }));
  }
  if (path === '/api/rules') { return Promise.resolve(response(200, { rules: [] })); }
  if (path === '/api/model') {
    return Promise.resolve(response(200, {
      state: 'READY', stateLabel: '已就绪', usable: true, actions: []
    }));
  }
  if (path === '/api/restore-import') {
    uploadCalls++;
    if (uploadCalls === 1) {
      firstSignal = options && options.signal;
      // 故意忽略 abort 并在稍后返回成功，模拟 Chromium/传输层的迟到完成。
      return firstUpload.promise;
    }
    if (uploadCalls === 2) { return retryUpload.promise; }
    shutdownSignal = options && options.signal;
    return shutdownUpload.promise;
  }
  if (path === '/api/restore-import-cancel') {
    cancelCalls++;
    if (!options || !options.headers || options.headers['X-Litemask-Token'] !== 'test-token') {
      throw new Error('cancel request did not carry the session token');
    }
    return cancelRequest.promise;
  }
  if (path === '/api/shutdown') {
    shutdownCalls++;
    return Promise.resolve(response(200, { ok: true, stopping: true, message: '正在停止' }));
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
  querySelectorAll() { return []; },
  createElement(tag) { return new Element('', tag); },
  createTextNode(text) { const n = new Element('', 'text'); n.textContent = text; return n; }
};

const context = {
  document: documentMock,
  window: { location: { origin: 'http://127.0.0.1:1' }, confirm() { return true; } },
  fetch: fetchMock,
  setTimeout() { return 1; },
  clearTimeout() {},
  TextEncoder,
  Blob,
  AbortController,
  URL: { createObjectURL() { return 'blob:test'; }, revokeObjectURL() {} },
  console
};
context.window.document = documentMock;

async function flush() {
  for (let i = 0; i < 8; i++) {
    await new Promise((resolve) => setImmediate(resolve));
  }
}

function assert(condition, message) {
  if (!condition) { throw new Error(message); }
}

async function main() {
  vm.runInNewContext(fs.readFileSync(process.argv[2], 'utf8'), context, { filename: 'app.js' });
  await flush();

  const product = new Blob(['redacted']); product.name = 'case.redacted.txt';
  const mapping = new Blob(['sealed-map']); mapping.name = 'case.map.json';
  element('restoreProductFile').files = [product];
  element('restoreMappingFile').files = [mapping];
  element('restorePass').value = 'test-passphrase';
  element('restoreProductFile').dispatch('change');
  element('restorePass').dispatch('input');
  assert(!element('restoreImportBtn').disabled, 'valid files did not enable import');

  element('restoreImportBtn').click();
  await flush();
  assert(uploadCalls === 1, 'first import was not submitted exactly once');
  assert(firstSignal && !firstSignal.aborted, 'first import did not own an AbortSignal');

  element('restoreImportCancelBtn').click();
  await flush();
  assert(firstSignal.aborted, 'cancel did not abort the original upload');
  assert(cancelCalls === 1, 'cancel did not notify the server exactly once');
  for (const id of ['restoreProductFile', 'restoreMappingFile', 'restorePass', 'restoreImportBtn']) {
    assert(element(id).disabled, id + ' unlocked before upload and cancel both settled');
  }

  element('restoreImportBtn').click();
  assert(uploadCalls === 1, 'a new import was admitted while cancellation was unsettled');

  firstUpload.resolve(response(200, {}, 'late-success'));
  await flush();
  assert(downloadClicks === 0, 'late successful upload downloaded a restored file after cancel');
  assert(element('restoreImportBtn').disabled,
    'controls unlocked after upload settled but before cancel request settled');
  assert(!element('restoreImportStatus').textContent.includes('已还原'),
    'late successful upload displayed a success status after cancel');

  cancelRequest.resolve(response(200, { ok: true, canceled: true, message: '已请求取消' }));
  await flush();
  assert(!element('restoreProductFile').disabled, 'product input stayed locked after cancellation settled');
  assert(!element('restoreImportBtn').disabled, 'retry was not enabled after cancellation settled');
  assert(element('restoreImportCancelBtn').hidden, 'cancel button stayed visible after settlement');

  element('restoreImportBtn').click();
  await flush();
  assert(uploadCalls === 2, 'retry was not admitted after cancellation settled');
  retryUpload.resolve(response(200, {}, 'retry-success'));
  await flush();
  assert(downloadClicks === 1, 'successful retry did not download exactly one restored file');
  assert(element('restoreImportStatus').textContent.includes('已还原'),
    'successful retry did not report completion');

  element('restorePass').value = 'shutdown-passphrase';
  element('restorePass').dispatch('input');
  element('restoreImportBtn').click();
  await flush();
  assert(uploadCalls === 3 && shutdownSignal && !shutdownSignal.aborted,
    'shutdown scenario did not start an owned import upload');
  element('shutdownBtn').click();
  await flush();
  assert(shutdownSignal.aborted, 'shutdown did not abort the owned import upload');
  assert(cancelCalls === 2, 'shutdown did not notify the restore cancel endpoint');
  assert(shutdownCalls === 1, 'active import incorrectly blocked the shutdown request');
  process.stdout.write('restore-cancel-race-ok\n');
}

main().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exitCode = 1;
});
