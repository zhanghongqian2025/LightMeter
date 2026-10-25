#!/usr/bin/env node
/* 用真实浏览器（Chrome 引擎）跑一遍界面流程的端到端驱动。
 * 浏览器二进制由 --chrome / E2E_CHROME 指定；e2e-browser.sh 会把
 * 实际探测到的那个构建连同版本一起打进日志。
 *
 * 【为什么要有这个】——本项目长期缺口：界面那半边只有字符串匹配的静态断言。
 * 那些断言看不见「脚本在浏览器里根本没跑起来」这件事：
 * app.js 只要有一个语法错误，页面就一片空白，而服务端几十条单测全绿。
 * 静态断言能补的只有括号配平，补不了「运行时的异常」。
 *
 * 【为什么自己写 CDP 客户端而不是用现成库】
 * 核心包硬指标 ≤90 MiB，测试脚本不进包，但也不想为一个测试引入依赖树。
 * Node 22 自带全局 WebSocket，够用了：连上 devtools 的 ws，发命令收事件。
 *
 * 【怎么保证它会咬人】
 * --mutate-app-js 可以在**传输途中**改写 app.js 再喂给页面：
 *   - drop-cancel-listener：把取消按钮的监听器整段删掉
 *   - syntax-error：制造一个语法错误
 * 断言逻辑与正常跑完全一致，所以「变异后必须失败」这件事是自证的：
 * 驱动不认得自己被变异了，它只认页面上的事实。
 *
 * 用法：
 *   node tools/browser-cdp.js --url http://127.0.0.1:18932/ \
 *        --file sample.docx --big-file big.docx --screenshot out.png
 *
 * 退出码：0 = 全部断言通过；1 = 有断言没通过；2 = 基础设施问题（Chrome 起不来等）。
 * 判定结果以 JSON 打到 stdout，人看的行打到 stderr。
 */

'use strict';

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// 默认的 Chrome 路径。优先认 E2E_CHROME 环境变量：
// 这台机器上 /Applications/Google Chrome.app 已经被移走（2026-10-21 23:39），
// 驱动如果只认死路径，就会在「浏览器不在」时直接报基础设施错误。
// e2e-browser.sh 会用 --chrome 显式传进来；这里认环境变量是给
// 「单独跑驱动」的场景留的同一条路。
const CHROME_DEFAULT = process.env.E2E_CHROME ||
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

// ------------------------------------------------------------------ 参数

function parseArgs(argv) {
  const opt = {
    url: 'http://127.0.0.1:18932/',
    file: null,
    bigFile: null,
    longFile: null,
    badFile: null,
    pdfFile: null,
    pdfOnly: false,
    manyFile: null,
    occOnly: false,
    passphrase: null,
    skipSlow: false,
    artifactDir: null,
    screenshot: null,
    mutate: 'none',
    chrome: CHROME_DEFAULT,
    timeoutMs: 60000
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      if (i + 1 >= argv.length) throw new Error('参数 ' + a + ' 缺值');
      return argv[++i];
    };
    if (a === '--url') opt.url = next();
    else if (a === '--file') opt.file = next();
    else if (a === '--big-file') opt.bigFile = next();
    else if (a === '--long-file') opt.longFile = next();
    else if (a === '--bad-file') opt.badFile = next();
    else if (a === '--pdf-file') opt.pdfFile = next();
    else if (a === '--pdf-only') opt.pdfOnly = true;
    else if (a === '--many-file') opt.manyFile = next();
    else if (a === '--occ-only') opt.occOnly = true;
    else if (a === '--passphrase') opt.passphrase = next();
    else if (a === '--skip-slow') opt.skipSlow = true;
    else if (a === '--artifact-dir') opt.artifactDir = next();
    else if (a === '--screenshot') opt.screenshot = next();
    else if (a === '--mutate') opt.mutate = next();
    else if (a === '--chrome') opt.chrome = next();
    else if (a === '--timeout-ms') opt.timeoutMs = Number(next());
    else throw new Error('不认识的参数：' + a);
  }
  return opt;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 进程退出码：0 = 断言全过，1 = 有断言没过，2 = 基础设施问题。 */
let EXIT_CODE = 2;

/**
 * 没验到的项（如环境不支持某项能力）。
 *
 * <p>刻意与「断言失败」分开：把「没验到」混进失败里，读者会以为产品有毛病；
 * 把它混进「通过」里，读者会以为验过了。两边都不能要。
 */
const SKIPPED = [];

/**
 * 浏览器真下载下来的映射件。
 *
 * <p>【为什么驱动要把它带出去】界面上「已加密」只是一句话；
 * 真正交给用户的是**那个文件**，所以它必须落到盘上、被独立标尺查一遍。
 * 驱动只负责「下载下来并报出它的位置」，判定交给 e2e 脚本与独立标尺
 * （标尺不许复用产品代码，否则就成了「产品读得懂自己写的东西」）。
 */
const MAPPING_DOWNLOAD = { name: null, path: null, size: 0 };

/**
 * 浏览器真下载下来的脱敏件。
 *
 * <p>它要拿去做**命令行还原**的输入：GUI 封装的映射件能不能被
 * {@code restore --map-pass-env} 还原回来，靠的是这两个文件的配合。
 */
const REDACTED_DOWNLOAD = { name: null, path: null, size: 0 };

// ------------------------------------------------------------------ CDP 客户端

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.seq = 0;
    this.pending = new Map();
    this.listeners = [];
    ws.onmessage = (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      if (msg.id !== undefined && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message || JSON.stringify(msg.error)));
        else resolve(msg.result || {});
        return;
      }
      for (const fn of this.listeners) {
        try { fn(msg); } catch (e) { /* 监听器自己的问题不该拖垮整个驱动 */ }
      }
    };
  }

  static connect(wsUrl) {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(wsUrl);
      const timer = setTimeout(() => reject(new Error('连 devtools 超时')), 15000);
      ws.onopen = () => { clearTimeout(timer); resolve(new Cdp(ws)); };
      ws.onerror = () => { clearTimeout(timer); reject(new Error('连 devtools 失败：' + wsUrl)); };
    });
  }

  send(method, params) {
    const id = ++this.seq;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      try {
        this.ws.send(JSON.stringify({ id, method, params: params || {} }));
      } catch (e) {
        this.pending.delete(id);
        reject(e);
      }
    });
  }

  on(fn) { this.listeners.push(fn); }

  close() { try { this.ws.close(); } catch (e) { /* 已经断了就算了 */ } }
}

// ------------------------------------------------------------------ 启动 Chrome

async function launchChrome(opt) {
  if (!fs.existsSync(opt.chrome)) {
    throw new Error('找不到 Chrome：' + opt.chrome);
  }
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'litemask-cdp-'));
  const args = [
    '--headless=new',
    '--remote-debugging-port=0',
    '--user-data-dir=' + profile,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-gpu',
    '--disable-dev-shm-usage',
    '--disable-extensions',
    '--disable-background-networking',
    '--hide-scrollbars',
    '--window-size=1280,900',
    'about:blank'
  ];
  const proc = spawn(opt.chrome, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  let stderr = '';
  proc.stderr.on('data', (d) => { stderr += d.toString(); });

  const portFile = path.join(profile, 'DevToolsActivePort');
  const deadline = Date.now() + 30000;
  let port = 0;
  while (Date.now() < deadline) {
    if (fs.existsSync(portFile)) {
      const txt = fs.readFileSync(portFile, 'utf8');
      const first = txt.split('\n')[0].trim();
      if (first) { port = Number(first); break; }
    }
    if (proc.exitCode !== null) {
      throw new Error('Chrome 退出了，退出码 ' + proc.exitCode + '：\n' + stderr.slice(-800));
    }
    await sleep(100);
  }
  if (!port) throw new Error('没拿到 devtools 端口：\n' + stderr.slice(-800));
  return { proc, port, profile };
}

async function findPageTarget(port) {
  const deadline = Date.now() + 15000;
  let last = null;
  while (Date.now() < deadline) {
    try {
      const res = await fetch('http://127.0.0.1:' + port + '/json/list');
      const list = await res.json();
      last = list;
      const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
      if (page) return page;
    } catch (e) {
      last = String(e && e.message || e);
    }
    await sleep(150);
  }
  throw new Error('没找到页面 target：' + JSON.stringify(last));
}

// ------------------------------------------------------------------ 页面操作

async function evalJs(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', {
    expression: expression,
    returnByValue: true,
    awaitPromise: true
  });
  if (r.exceptionDetails) {
    throw new Error('页面求值抛错：' + JSON.stringify(r.exceptionDetails.exception || r.exceptionDetails));
  }
  return r.result ? r.result.value : undefined;
}

async function waitFor(cdp, expression, timeoutMs) {
  const t0 = Date.now();
  let last = null;
  for (;;) {
    try {
      const v = await evalJs(cdp, expression);
      if (v) return { ok: true, waitedMs: Date.now() - t0 };
      last = v;
    } catch (e) {
      last = String(e && e.message || e);
    }
    if (Date.now() - t0 >= timeoutMs) return { ok: false, waitedMs: Date.now() - t0, last: last };
    await sleep(120);
  }
}

async function setFileInput(cdp, filePath) {
  const doc = await cdp.send('DOM.getDocument', { depth: 1 });
  const node = await cdp.send('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '#file' });
  if (!node.nodeId) throw new Error('页面上找不到 #file 输入框');
  await cdp.send('DOM.setFileInputFiles', { files: [path.resolve(filePath)], nodeId: node.nodeId });
}

async function click(cdp, id) {
  await evalJs(cdp, 'document.getElementById(' + JSON.stringify(id) + ').click()');
}

/**
 * 把界面上所有错误框的原文收回来。
 *
 * <p>断言失败时如果只说「等不到下载卡」，读的人还得自己去猜是哪一步错了；
 * 而界面本来就写着原因（"这个文件已经应用过了"），把它带出来，
 * 失败信息才是可用的。
 */
async function errorText(cdp) {
  return await evalJs(cdp,
    '["scanError","applyError","taskError","restoreError"]'
    + '.map(function (id) { var n = document.getElementById(id);'
    + '  return (n && !n.hidden && n.textContent) ? (id + "=" + n.textContent) : ""; })'
    + '.filter(Boolean).join(" | ")');
}

async function screenshot(cdp, file) {
  if (!file) return null;
  const r = await cdp.send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
  return file;
}

/**
 * 把「脱敏件」按钮点下去，让文件落到指定目录。
 *
 * <p>只给「只保留第 N 处」那一段用：主流程那一段的下载逻辑是
 * 「脱敏件 + 映射件」两件一起下的，还带一堆别的断言，
 * 抽出来共用会把一条稳定的流程搅浑——出了错分不清是哪边的问题。
 * 这里另写一份小的，各自独立。
 */
async function 下载脱敏件(cdp, chrome, 目录) {
  fs.mkdirSync(目录, { recursive: true });
  try {
    await cdp.send('Browser.setDownloadBehavior', {
      behavior: 'allow', downloadPath: 目录, eventsEnabled: true
    });
  } catch (e) {
    try {
      await cdp.send('Page.setDownloadBehavior', { behavior: 'allow', downloadPath: 目录 });
    } catch (e2) {
      // 环境不支持落盘下载：如实说没验到，不记成「通过」。
      return { ok: false, why: '环境不支持落盘下载：' + (e2 && e2.message || e2) };
    }
  }
  await click(cdp, 'downloadBtn');
  const deadline = Date.now() + 20000;
  let landed = null;
  while (Date.now() < deadline) {
    const files = fs.readdirSync(目录).filter((f) => !f.endsWith('.crdownload'));
    if (files.length) { landed = files[0]; break; }
    await sleep(150);
  }
  if (!landed) {
    return { ok: false, why: '目录里空着：' + fs.readdirSync(目录).join(',') };
  }
  const p = path.join(目录, landed);
  return { ok: true, name: landed, path: p, size: fs.statSync(p).size };
}

// ------------------------------------------------------------------ 变异

/**
 * 这条 console error 是不是浏览器自己发的 favicon 请求。
 *
 * <p>服务端对未知路径返 404，浏览器会自动去要 /favicon.ico，
 * 于是控制台里固定有一条 "Failed to load resource"。那不是页面的缺陷，
 * 但它会让「控制台无 error」这条断言永远红——于是这条断言就废了。
 *
 * <p>只认 favicon 这一个词，不放行别的 404：放行得宽了，
 * 页面里真正的资源加载失败（app.css 404 之类）就会被一起放过。
 */
function isBenign(text) {
  return /favicon/i.test(text || '');
}

/**
 * 这一轮**故意**造出来的失败请求，还能放过几条。
 *
 * <p>「不支持的后缀」那个场景就是要让服务端返 400，
 * 而浏览器对任何 4xx 的 fetch 都会在控制台记一条
 * "Failed to load resource: the server responded with a status of 400"。
 * 那条不是页面的缺陷，但它会让「控制台无 error」这条断言变红——
 * 于是这条断言就废了（这正是本项目反复遇到的那类「探针空转」）。
 *
 * <p><b>所以只按「次数 + URL + 措辞」三者同时对上才放行</b>：
 * 必须是 /api/task-scan、必须提到 400、而且只能放过预期条数那么多。
 * 只认「400」两个字的话，页面里真正的 400 会被一起放过。
 */
let 预期内的失败数 = 0;

function 是预期内的失败(text, url) {
  if (预期内的失败数 <= 0) return false;
  if (!/400/.test(text || '')) return false;
  // Log 域那条带 URL，必须精确对上；console 域那条没有 URL，
  // 只能认浏览器自己那句措辞（页面自己 console.error("400") 不会长这样）。
  if (url) {
    if (url.indexOf('/api/task-scan') < 0) return false;
  } else if (!/status of 400/.test(text || '')) {
    return false;
  }
  预期内的失败数 -= 1;
  return true;
}

function applyMutation(text, mode) {
  if (mode === 'none') return { changed: false, text: text, note: '未变异' };
  if (mode === 'syntax-error') {
    // 少一个右括号：浏览器会整个文件丢掉，页面一片空白。
    return { changed: true, text: text + '\n(function () {\n', note: '尾部追加未闭合的函数' };
  }
  if (mode === 'occ-lines-first-three') {
    // 退回块 22 之前的行为：逐处勾选框只挂在**默认显示的那几条**位置上，
    // 第 4 次及以后的出现又没有入口了（这正是块 22 要消掉的那条限制）。
    //
    // 【为什么不能只把 total 换成 locs.length —— 实测踩到的坑】
    //   换成 locs.length 是个**空操作**：这份样本 occurrences=5，
    //   位置说明也是 5 条（服务端 20 条上限没碰到），两个数一样 →
    //   变异落盘了、字节也变了，但行为一点没变 →
    //   负控制于是「什么都没坏」，永远绿。
    //   → 变异必须换成**真的改行为**的那一个。
    const marker = 'for (var oi = 0; oi < total; oi++) {';
    const i = text.indexOf(marker);
    if (i < 0) throw new Error('变异锚点没找到：按出现序号铺行的那个循环不在脚本里');
    return {
      changed: true,
      text: text.slice(0, i)
        + text.slice(i).replace(marker,
          'for (var oi = 0; oi < Math.min(total, LOCS_COLLAPSED); oi++) {'),
      note: '把逐处勾选框退回只挂在默认显示的前 3 条位置上'
    };
  }
  if (mode === 'drop-cancel-listener') {
    const marker = '$("cancelBtn").addEventListener("click", function () {';
    const i = text.indexOf(marker);
    if (i < 0) throw new Error('变异锚点没找到：取消按钮的监听器不在脚本里');
    const end = '\n  });';
    const j = text.indexOf(end, i);
    if (j < 0) throw new Error('变异锚点没找到：找不到监听器结尾');
    return {
      changed: true,
      text: text.slice(0, i) + text.slice(j + end.length),
      note: '删掉取消按钮的监听器整段'
    };
  }
  throw new Error('未知变异：' + mode);
}

// ------------------------------------------------------------------ 主流程

async function main() {
  const opt = parseArgs(process.argv.slice(2));
  const checks = [];
  // group：给负控制用的分组标记（默认空串）。
  // 「依赖某条能力」的检查标同一个 group，负控制就能断言：
  // 这一组**逐条**变红、组外一条都不红。
  const add = (name, ok, detail, group) => {
    checks.push({ name: name, ok: !!ok, detail: detail === undefined ? '' : String(detail),
      group: group || '' });
    const mark = ok ? '  ok  ' : ' FAIL ';
    process.stderr.write(mark + name + (detail ? '  —— ' + detail : '') + '\n');
  };

  if (opt.pdfOnly) {
    // PDF 专用模式：只跑 PDF 那一段，不碰 docx 主流程。
    if (!opt.pdfFile) throw new Error('--pdf-only 需要 --pdf-file');
    if (!fs.existsSync(opt.pdfFile)) throw new Error('PDF 样本不存在：' + opt.pdfFile);
  } else if (opt.occOnly) {
    // 「只保留第 N 处」专用模式：只跑那一段，不碰 docx 主流程。
    if (!opt.manyFile) throw new Error('--occ-only 需要 --many-file');
    if (!fs.existsSync(opt.manyFile)) throw new Error('样本不存在：' + opt.manyFile);
  } else {
    if (!opt.file || !opt.bigFile) {
      throw new Error('必须给 --file 与 --big-file');
    }
    for (const f of [opt.file, opt.bigFile]) {
      if (!fs.existsSync(f)) throw new Error('样本不存在：' + f);
    }
  }

  const exceptions = [];
  const consoleErrors = [];
  let mutation = { changed: false, note: '未变异' };
  let chrome = null;
  let cdp = null;

  try {
    chrome = await launchChrome(opt);
    const target = await findPageTarget(chrome.port);
    cdp = await Cdp.connect(target.webSocketDebuggerUrl);

    cdp.on((msg) => {
      if (msg.method === 'Runtime.exceptionThrown') {
        const d = msg.params.exceptionDetails || {};
        exceptions.push((d.exception && (d.exception.description || d.exception.value)) || d.text || '未知异常');
      } else if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
        const text = (msg.params.args || []).map((a) => a.value || a.description || '').join(' ');
        if (是预期内的失败(text, '')) return;
        if (isBenign(text)) return;
        consoleErrors.push(text);
      } else if (msg.method === 'Log.entryAdded' && msg.params.entry) {
        const e = msg.params.entry;
        // favicon 是浏览器自己发的请求，服务端返 404；那不是页面的缺陷。
        // 但只放行这一条具体路径，别把别的 404 一起放过去。
        if ((e.url || '').indexOf('favicon') >= 0) return;
        const text = e.text || '';
        // 语法错误在经典脚本里是走 Log 报的，level 偶尔不是 error；
        // 只要提到 SyntaxError / Uncaught 就当错误收下——宁可多收，不要漏收。
        if (!(e.level === 'error' || /SyntaxError|Uncaught/i.test(text))) return;
        // 故意造出来的那次 400（不支持的后缀）：按次数与 URL 精确放行。
        if (是预期内的失败(text, e.url)) return;
        if (isBenign(text)) return;
        consoleErrors.push(text + (e.url ? ' (' + e.url + ')' : ''));
      }
    });

    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    await cdp.send('Log.enable');
    await cdp.send('DOM.enable');
    // 为了抓「应用」那一次的请求体（见下面 applyBodies）。
    await cdp.send('Network.enable');

    if (opt.mutate !== 'none') {
      cdp.on(async (msg) => {
        if (msg.method !== 'Fetch.requestPaused') return;
        const rid = msg.params.requestId;
        const url = msg.params.responseURL || (msg.params.request && msg.params.request.url) || '';
        try {
          if (/app\.js(\?|$)/.test(url)) {
            const bodyRes = await cdp.send('Fetch.getResponseBody', { requestId: rid });
            const raw = bodyRes.base64Encoded
              ? Buffer.from(bodyRes.body, 'base64').toString('utf8')
              : bodyRes.body;
            mutation = applyMutation(raw, opt.mutate);
            await cdp.send('Fetch.fulfillRequest', {
              requestId: rid,
              responseCode: 200,
              responseHeaders: [{ name: 'Content-Type', value: 'application/javascript; charset=utf-8' }],
              body: Buffer.from(mutation.text, 'utf8').toString('base64')
            });
            return;
          }
        } catch (e) {
          consoleErrors.push('变异失败：' + (e && e.message || e));
        }
        try { await cdp.send('Fetch.continueRequest', { requestId: rid }); } catch (e) { /* 已放弃 */ }
      });
      await cdp.send('Fetch.enable', {
        patterns: [{ urlPattern: '*app.js', requestStage: 'Response' }]
      });
    }

    // 抓「应用」那一次请求的报文体。
    //
    // 为什么必须抓报文而不是看界面：界面上勾了框、发出去的是不是带序号的那一条，
    // 只有报文说了算。断言「界面上有那个勾选框」并不能证明提交里带了 occurrence。
    const applyBodies = [];
    cdp.on((msg) => {
      if (msg.method !== 'Network.requestWillBeSent') return;
      const r = msg.params && msg.params.request;
      if (!r || !r.url) return;
      if (r.url.indexOf('/api/task-apply') < 0) return;
      applyBodies.push(r.postData || '');
    });

    const loaded = new Promise((resolve) => {
      const h = (msg) => { if (msg.method === 'Page.loadEventFired') resolve(true); };
      cdp.on(h);
      setTimeout(() => resolve(false), 20000);
    });
    await cdp.send('Page.navigate', { url: opt.url });
    const didLoad = await loaded;

    add('页面已加载', didLoad, didLoad ? opt.url : '等不到 load 事件');

    // 初始状态：脚本跑起来了没有（规则表有行 = app.js 执行到了最后）
    const rulesReady = await waitFor(cdp,
      'document.querySelectorAll("#ruleBody tr").length > 0', 15000);
    add('规则表已填充', rulesReady.ok,
      rulesReady.ok ? '规则行数 ' + await evalJs(cdp, 'document.querySelectorAll("#ruleBody tr").length')
                     : '15 秒内 #ruleBody 一直是空的 —— app.js 很可能没跑起来');

    const postureRows = await evalJs(cdp, 'document.querySelectorAll("#posture tbody tr").length');
    add('访问控制姿态已填充', postureRows > 0, '姿态行数 ' + postureRows);

    const routeRows = await evalJs(cdp, 'document.querySelectorAll("#routes li").length');
    add('接口清单已填充', routeRows > 0, '接口行数 ' + routeRows);

    const statusLine = await evalJs(cdp, 'document.getElementById("statusLine").textContent');
    add('状态行显示服务已就绪', /已就绪/.test(statusLine || ''), statusLine);

    const taskHiddenAtStart = await evalJs(cdp, 'document.getElementById("taskCard").hidden');
    add('任务卡初始隐藏', taskHiddenAtStart === true, 'hidden=' + taskHiddenAtStart);

    if (!rulesReady.ok) {
      // 脚本没跑起来，后面全是无意义的等待：直接收尾，把失败原因报清楚。
      add('脚本无未捕获异常', exceptions.length === 0,
        exceptions.length ? exceptions.join(' | ') : '（没有捕获到异常，但脚本没跑起来）');
      finish(opt, checks, exceptions, consoleErrors, mutation);
      return;
    }

    // ---- PDF 专用模式：只验「PDF 的位置在界面上真的显示出来」 ----
    //
    // 【为什么单独一个模式，而不是并进 docx 主流程】
    //   主流程那几十条断言走的是 docx（上传 → 清单 → 应用 → 下载 → 取消 → 窄屏…），
    //   上一轮已经反复跑过。为了验 PDF 而往里插一段「再扫一次别的格式」，
    //   会把一个稳定的流程搅浑——出了错分不清是 PDF 的问题还是 docx 流程被搅坏了。
    //   → 独立模式：自己的进程、自己的断言、自己的 JSON，
    //     包装层（e2e-browser.sh）另外数它，两边都不含糊。
    if (opt.pdfOnly) {
      await setFileInput(cdp, opt.pdfFile);
      const picked = await waitFor(cdp, '!document.getElementById("scanBtn").disabled', 5000);
      add('选中 PDF 后扫描按钮可用', picked.ok, picked.ok ? '' : 'change 事件后按钮仍是禁用的');
      const scanHint = await evalJs(cdp, 'document.getElementById("scanHint").textContent');
      add('扫描提示显示 PDF 文件名', (scanHint || '').indexOf(path.basename(opt.pdfFile)) >= 0, scanHint);

      await click(cdp, 'scanBtn');
      const ready = await waitFor(cdp, 'document.querySelectorAll("#hitBody tr").length > 0', opt.timeoutMs);
      add('扫描后命中清单有行', ready.ok, ready.ok ? '' : '等不到命中行（' + opt.timeoutMs + 'ms）');

      // 位置必须**真的显示在屏幕上**，而且要长得像 PDF 的位置。
      //
      // 「位置只下发、不显示」是这一块要修的毛病，所以这里盯的是**渲染出来的文字**，
      // 不是「脚本引用过 locators」。
      //
      // 断言为什么是这三条（每一条都有牙）：
      //   · 位置列非空 —— app.js 不渲染 locators 时会红；
      //   · 含「第 N 页」—— 服务端没透传页码时会红；
      //   · 含「文本块 M」—— 页码被写成常量以外的东西时会红。
      // 不写「清单里没有『无位置信息』」这种断言：那句话现在根本不在源码里，
      // 写出来就是一条**恒真断言**——恒真的守卫等于没有守卫。
      const locText = await evalJs(cdp,
        'Array.prototype.map.call(document.querySelectorAll("#hitBody td.num .locs"),'
        + ' function (n) { return n.textContent; }).join(" | ")');
      add('PDF 的命中显示了位置', (locText || '').length > 0,
        '位置列内容：' + (locText || '（空——PDF 的位置没渲染出来）'));
      add('位置里说得出在第几页', /第\s*\d+\s*页/.test(locText || ''),
        '位置列内容：' + (locText || '（空——PDF 的位置没渲染出来）'));
      add('位置里说得出是哪个文本块', /文本块\s*\d+/.test(locText || ''),
        '位置列内容：' + (locText || '（空——PDF 的位置没渲染出来）'));

      finish(opt, checks, exceptions, consoleErrors, mutation);
      return;
    }

    // ---- 「只保留第 N 处」专用模式（块 22）----
    //
    // 【为什么单独一个模式】主流程那份样本里手机号只出现两次，
    // 而界面上「出现处」默认只显示 3 条 —— 两次出现**碰不到**那条限制。
    // 要验「第 4 次出现选不选得到」，必须有一份同一个值出现 4 次以上的样本。
    if (opt.occOnly) {
      await setFileInput(cdp, opt.manyFile);
      const picked = await waitFor(cdp, '!document.getElementById("scanBtn").disabled', 5000);
      add('选中样本后扫描按钮可用', picked.ok, picked.ok ? '' : 'change 事件后按钮仍是禁用的');
      await click(cdp, 'scanBtn');
      const ready = await waitFor(cdp,
        'document.querySelectorAll("#hitBody tr").length > 0', opt.timeoutMs);
      add('扫描后命中清单有行', ready.ok, ready.ok ? '' : '等不到命中行（' + opt.timeoutMs + 'ms）');

      // 这一行就是这个值：手机号出现 5 次（正文 4 段 + 页眉 1 段）。
      const info = await evalJs(cdp,
        '(function () {'
        + '  var rows = document.querySelectorAll("#hitBody tr");'
        + '  var tr = null;'
        + '  for (var i = 0; i < rows.length; i++) {'
        + '    if ((rows[i].textContent || "").indexOf("13800138000") >= 0) { tr = rows[i]; break; }'
        + '  }'
        + '  if (!tr) { return { found: false, rows: rows.length }; }'
        + '  var cnt = tr.querySelector("td.num .cnt");'
        + '  var locs = tr.querySelectorAll("td.num .locs .loc");'
        + '  var visible = 0;'
        + '  for (var j = 0; j < locs.length; j++) {'
        + '    if (window.getComputedStyle(locs[j]).display !== "none") { visible++; }'
        + '  }'
        + '  var boxes = tr.querySelectorAll("input.occ-pick");'
        + '  var occs = [];'
        + '  for (var k = 0; k < boxes.length; k++) { occs.push(boxes[k].dataset.occurrence); }'
        + '  var more = tr.querySelector("button.locMore");'
        + '  return { found: true, count: cnt ? cnt.textContent : "", locs: locs.length,'
        + '    visible: visible, occs: occs.join(","), hasMore: !!more,'
        + '    moreText: more ? more.textContent : "" };'
        + '})()');
      add('清单里有手机号那一行', !!(info && info.found),
        '命中行数=' + (info && info.rows !== undefined ? info.rows : '?')
        + '；没找到含 13800138000 的行');
      add('那一行写着「出现 5 次」', !!(info && info.count === '5'),
        '次数格=' + (info && info.count));
      // 按出现序号铺行：5 次出现就是 5 行（只按位置条数铺的话这里只有 3 行）。
      add('位置清单按出现序号铺了 5 行', !!(info && info.locs === 5),
        '位置行数=' + (info && info.locs) + '（只按位置条数铺的话只有 3 行）', 'occ-line');
      add('每一行都挂着「只保留这一处」的勾选框', !!(info && info.occs === '0,1,2,3,4'),
        '勾选框序号=' + (info && info.occs), 'occ-line');
      // 默认只显示前 3 条（可读性），其余折叠。
      add('默认只显示前 3 条位置', !!(info && info.visible === 3),
        '可见行数=' + (info && info.visible));
      add('有「展开全部」的入口', !!(info && info.hasMore),
        '按钮文案=' + (info && info.moreText));

      // 展开：折叠起来的那几处必须真的露出来，而且能勾。
      const opened = await evalJs(cdp,
        '(function () {'
        + '  var more = document.querySelector("#hitBody button.locMore");'
        + '  if (!more) { return { ok: false }; }'
        + '  more.click();'
        + '  var locs = document.querySelectorAll("#hitBody tr td.num .locs .loc");'
        + '  var visible = 0;'
        + '  for (var j = 0; j < locs.length; j++) {'
        + '    if (window.getComputedStyle(locs[j]).display !== "none") { visible++; }'
        + '  }'
        + '  var boxes = document.querySelectorAll("#hitBody tr input.occ-pick");'
        + '  var b3 = null;'
        + '  for (var q = 0; q < boxes.length; q++) {'
        + '    if (boxes[q].dataset.occurrence === "3") { b3 = boxes[q]; break; }'
        + '  }'
        + '  return { ok: true, visible: visible, boxes: boxes.length,'
        + '    occ3: b3 ? b3.dataset.occurrence : null,'
        + '    aria3: b3 ? (b3.getAttribute("aria-label") || "") : "" };'
        + '})()');
      add('展开后 5 条位置都显示出来了', !!(opened && opened.visible === 5),
        '可见行数=' + (opened && opened.visible), 'occ-line');
      add('第 4 处出现的勾选框存在且序号是 3', !!(opened && opened.occ3 === '3'),
        '序号=' + (opened && opened.occ3) + '；aria=' + (opened && opened.aria3), 'occ-line');
      add('第 4 处出现的勾选框说清了是「第 4 次出现」',
        !!(opened && /只保留第\s*4\s*次出现/.test(opened.aria3 || '')),
        opened && opened.aria3, 'occ-line');

      // ---- 逐处勾选与整行勾选互斥（这一段必须非恒真）----
      //
      // 【为什么不能只勾第 4 处就断言「整行被取消」】
      //   整行本来就没勾过 → 「整行是未勾选」恒为真 → 这条断言什么也证明不了。
      //   （实测踩过：这条断言一直绿，与互斥有没有实现无关。）
      //
      // 【真正能验的是这两步，都是用户可达的】
      //   ① 先勾整行（「这个值全部保留」）→ 逐处勾选框必须被**清空并置灰**；
      //   ② 取消整行 → 逐处勾选框必须**重新可用**；
      //   ③ 再勾第 4 处 → 它自己得留着勾，整行必须是未勾选（互斥）。
      const mutual = await evalJs(cdp,
        '(function () {'
        + '  var boxes = document.querySelectorAll("#hitBody tr input.occ-pick");'
        + '  var b = null;'
        + '  for (var q = 0; q < boxes.length; q++) {'
        + '    if (boxes[q].dataset.occurrence === "3") { b = boxes[q]; break; }'
        + '  }'
        + '  if (!b) { return { ok: false, why: "没有序号为 3 的逐处勾选框" }; }'
        + '  var tr = b.closest ? b.closest("tr") : null;'
        + '  var rowBox = tr ? tr.querySelector("td.pick input[type=checkbox]") : null;'
        + '  if (!rowBox) { return { ok: false, why: "这一行没有整行勾选框" }; }'
        + '  var hint = function () {'
        + '    return (document.getElementById("choiceHint") || {}).textContent || ""; };'
        + '  rowBox.checked = true;'
        + '  rowBox.dispatchEvent(new Event("change", { bubbles: true }));'
        + '  var rowOn = rowBox.checked;'
        + '  var cleared = !b.checked;'
        + '  var disabled = b.disabled;'
        + '  var hintRow = hint();'
        + '  rowBox.checked = false;'
        + '  rowBox.dispatchEvent(new Event("change", { bubbles: true }));'
        + '  var rowOff = rowBox.checked;'
        + '  var reEnabled = !b.disabled;'
        + '  b.checked = true;'
        + '  b.dispatchEvent(new Event("change", { bubbles: true }));'
        + '  return { ok: true, rowOn: rowOn, cleared: cleared, disabled: disabled,'
        + '    hintRow: hintRow, rowOff: rowOff, reEnabled: reEnabled,'
        + '    rowChecked: rowBox.checked, occChecked: b.checked, hint: hint() };'
        + '})()');
      add('勾整行会把逐处勾选清空并置灰',
        !!(mutual && mutual.ok && mutual.rowOn === true
          && mutual.cleared === true && mutual.disabled === true),
        '整行=' + (mutual && mutual.rowOn)
        + '；第 4 处仍勾着=' + (mutual && !mutual.cleared)
        + '；第 4 处被置灰=' + (mutual && mutual.disabled)
        + '；提示=' + (mutual && mutual.hintRow), 'occ-line');
      add('取消整行后逐处勾选重新可用',
        !!(mutual && mutual.ok && mutual.rowOff === false && mutual.reEnabled === true),
        '整行=' + (mutual && mutual.rowOff)
        + '；第 4 处仍被置灰=' + (mutual && !mutual.reEnabled), 'occ-line');
      add('勾「第 4 次出现」时整行勾选不再成立（互斥）',
        !!(mutual && mutual.ok && mutual.occChecked === true
          && mutual.rowChecked === false),
        '第 4 处勾着=' + (mutual && mutual.occChecked)
        + '；整行=' + (mutual && mutual.rowChecked)
        + '（前置：整行先被勾上过=' + (mutual && mutual.rowOn) + '）', 'occ-line');
      add('提示语说的是「已保留 1 处出现」',
        !!(mutual && /已保留\s*1\s*处出现/.test(mutual.hint || '')),
        mutual && mutual.hint, 'occ-line');

      // 应用：报文里必须真的带上 occurrence=3。
      // 这是这一段里唯一决定性的断言：界面上有勾选框、勾上会改状态，
      // 都不等于「发出去的那条排除项带着第 4 次的序号」。
      await click(cdp, 'applyBtn');
      const doneReady = await waitFor(cdp,
        '!document.getElementById("downloadCard").hidden', opt.timeoutMs);
      add('应用后出现下载卡', doneReady.ok, doneReady.ok ? '' : '等不到下载卡');
      const applyBody = applyBodies.length ? applyBodies[applyBodies.length - 1] : '';
      add('提交报文里带上了「第 4 次出现」的序号', /"occurrence"\s*:\s*3\b/.test(applyBody),
        '报文：' + (applyBody || '（没抓到应用请求的报文）'), 'occ-line');

      // 脱敏件落盘：判定在包装层（它要逐段核对「第 4 段留了原值、其余是令牌」）。
      const dl = await 下载脱敏件(cdp, chrome, path.join(chrome.profile, 'dl'));
      add('脱敏件下载到了盘上', !!(dl && dl.ok), dl && (dl.ok ? dl.name : dl.why));
      if (dl && dl.ok) {
        add('脱敏件非空', dl.size > 0, dl.size + ' 字节');
        if (opt.artifactDir) {
          fs.mkdirSync(opt.artifactDir, { recursive: true });
          const to = path.join(opt.artifactDir, dl.name);
          fs.copyFileSync(dl.path, to);
          REDACTED_DOWNLOAD.name = dl.name;
          REDACTED_DOWNLOAD.path = to;
          REDACTED_DOWNLOAD.size = dl.size;
        }
      }

      finish(opt, checks, exceptions, consoleErrors, mutation);
      return;
    }

    // ---- 选文件 → 扫描 ----
    await setFileInput(cdp, opt.file);
    const picked = await waitFor(cdp, '!document.getElementById("scanBtn").disabled', 5000);
    add('选中文件后扫描按钮可用', picked.ok, picked.ok ? '' : 'change 事件后按钮仍是禁用的');
    const scanHint = await evalJs(cdp, 'document.getElementById("scanHint").textContent');
    add('扫描提示显示文件名', (scanHint || '').indexOf(path.basename(opt.file)) >= 0, scanHint);

    // 记录任务卡是否曾经显示过（它会被 hideTask 收起来，事后看不到）
    await evalJs(cdp,
      'window.__shown = false;'
      + 'new MutationObserver(function () {'
      + '  if (!document.getElementById("taskCard").hidden) { window.__shown = true; }'
      + '}).observe(document.getElementById("taskCard"), { attributes: true, attributeFilter: ["hidden"] });'
      + 'true');

    await click(cdp, 'scanBtn');
    const hitsReady = await waitFor(cdp,
      'document.querySelectorAll("#hitBody tr").length > 0', opt.timeoutMs);
    add('扫描后命中清单有行', hitsReady.ok,
      hitsReady.ok ? '' : '等不到命中行（' + opt.timeoutMs + 'ms）');

    const hitText = await evalJs(cdp, 'document.getElementById("hitBody").textContent || ""');
    add('命中清单含手机号原值', hitText.indexOf('13800138000') >= 0,
      hitText.indexOf('13800138000') >= 0 ? '13800138000' : '清单里没有 13800138000');
    add('命中清单含身份证原值', hitText.indexOf('11010519491231002X') >= 0,
      hitText.indexOf('11010519491231002X') >= 0 ? '11010519491231002X' : '清单里没有 11010519491231002X');

    // 位置必须**显示**出来。
    //
    // 服务端一直在回传 locators，界面以前却只显示「出现 N 次」——
    // 位置只下发、不显示，对用户等于没有：清单上写着「出现 1 次」，
    // 用户还是不知道它在文档的哪儿，这份清单就没法用来核对。
    // 这一条盯的是**真的渲染出了文字**，不是「脚本里引用过 locators」。
    const locText = await evalJs(cdp,
      'Array.prototype.map.call(document.querySelectorAll("#hitBody td.num .locs"),'
      + ' function (n) { return n.textContent; }).join(" | ")');
    add('命中清单显示位置', (locText || '').length > 0,
      '位置列内容：' + (locText || '（空——位置没渲染出来）'));
    add('位置里说得出在第几段', /第\s*\d+\s*段/.test(locText || ''),
      '位置列内容：' + (locText || '（空——位置没渲染出来）'));

    const stepReview = await evalJs(cdp, 'document.getElementById("stepReview").className');
    add('流程推进到「挑要保留的命中」', stepReview === 'on', 'stepReview.className=' + stepReview);

    // 这一条是被真实缺陷逼出来的：曾经「生成脱敏文件」按钮在扫完之后一直是禁用的，
    // 界面能扫、不能脱。点一个禁用的按钮不会报错、也不会有请求，
    // 后面那条「下载卡没出现」会让人以为服务端坏了。
    const applyDisabled = await evalJs(cdp, 'document.getElementById("applyBtn").disabled');
    add('扫描后「生成脱敏文件」按钮可用', applyDisabled === false,
      'applyBtn.disabled=' + applyDisabled);

    const cardShown = await evalJs(cdp, '!!window.__shown');
    add('扫描过程中出现进度卡', cardShown === true, '任务卡曾可见=' + cardShown);

    // 终态的进度文案是「扫描完成」——服务端在任务收尾时会把 message 改掉，
    // 所以「已处理 N 个文本单元」只在**任务还活着的时候**才看得到（下面大文档那一步）。
    const doneText = await evalJs(cdp, 'document.getElementById("progressText").textContent || ""');
    add('终态文案是「扫描完成」', doneText === '扫描完成', doneText);

    const fillClass = await evalJs(cdp, 'document.getElementById("progressFill").className');
    add('总量未知时进度条走不确定态', /indeterminate/.test(fillClass || ''), fillClass);

    await screenshot(cdp, opt.screenshot);

    // ---- 「只保留这一处」的入口 ----
    //
    // 这一条盯的是**真的能勾、真的能发出去**：
    // 服务端早就支持位置级排除（报文里带 occurrence），界面上却没有入口——
    // 引擎有能力、清单看得见位置，用户却没法用。
    // 断言分三层：入口在 → 勾上会改状态（且与整行互斥）→ 提交时真的带上了序号。
    //
    // 样本里手机号出现两次（正文 + 页眉），所以这一行必须挂得出逐处勾选框。
    const occInfo = await evalJs(cdp,
      '(function () {'
      + '  var boxes = document.querySelectorAll("#hitBody input.occ-pick");'
      + '  if (!boxes.length) { return { count: 0 }; }'
      + '  var b = boxes[0];'
      + '  var row = b.closest ? b.closest("tr") : null;'
      + '  var rowBox = row ? row.querySelector("td.pick input[type=checkbox]") : null;'
      + '  return {'
      + '    count: boxes.length,'
      + '    occurrence: b.dataset.occurrence,'
      + '    aria: b.getAttribute("aria-label") || "",'
      + '    title: b.title || "",'
      + '    rowCheckedBefore: rowBox ? rowBox.checked : null'
      + '  };'
      + '})()');
    add('清单上挂出了「只保留这一处」的勾选框', occInfo.count > 0,
      'occ-pick 数量：' + occInfo.count);
    add('逐处的勾选框带着出现序号', /^\d+$/.test(String(occInfo.occurrence)),
      'data-occurrence=' + occInfo.occurrence);
    add('逐处的勾选框说清了它是「第 N 次出现」',
      /只保留第\s*\d+\s*次出现/.test(occInfo.aria || ''), occInfo.aria);

    // 勾上它：整行的勾选必须被取消。
    // 「这个值全部保留」与「只保留其中一次出现」是对立的，
    // 两种排除项一起发回去，结果是「整个值都留下了」——比用户勾的宽松。
    const mutual = await evalJs(cdp,
      '(function () {'
      + '  var b = document.querySelector("#hitBody input.occ-pick");'
      + '  if (!b) { return { ok: false }; }'
      + '  b.checked = true;'
      + '  b.dispatchEvent(new Event("change", { bubbles: true }));'
      + '  var row = b.closest ? b.closest("tr") : null;'
      + '  var rowBox = row ? row.querySelector("td.pick input[type=checkbox]") : null;'
      + '  return {'
      + '    ok: true,'
      + '    rowChecked: rowBox ? rowBox.checked : null,'
      + '    hint: (document.getElementById("choiceHint") || {}).textContent || ""'
      + '  };'
      + '})()');
    add('勾上「只保留这一处」后整行勾选被取消（两者互斥）',
      mutual.ok && mutual.rowChecked === false,
      '整行勾选状态：' + mutual.rowChecked);
    add('提示语说了这是「保留 N 处出现」',
      /处出现/.test(mutual.hint || ''), mutual.hint);

    // ---- 映射件口令（可选）----
    //
    // 【为什么要真填一次】界面上的加密只有一个入口：这里那个输入框。
    // 输入框摆在页面上而没人填过，等于没验过——
    // 「填了 → 请求体里带上 → 服务端把落盘的件封起来」这条链必须真走一遍。
    if (opt.passphrase) {
      const filled = await evalJs(cdp,
        '(function () {'
        + '  var i = document.getElementById("mapPass");'
        + '  if (!i) { return { ok: false, why: "没有 mapPass 输入框" }; }'
        + '  i.value = ' + JSON.stringify(opt.passphrase) + ';'
        + '  i.dispatchEvent(new Event("input", { bubbles: true }));'
        + '  return { ok: true, len: i.value.length };'
        + '})()');
      add('口令输入框在页面上并填上了口令', !!(filled && filled.ok),
        filled && filled.ok ? ('长度 ' + filled.len) : ('填不进去：' + (filled && filled.why || '未知')));
    }

    // ---- 应用 ----
    await click(cdp, 'applyBtn');
    const doneReady = await waitFor(cdp,
      '!document.getElementById("downloadCard").hidden', opt.timeoutMs);
    add('应用后出现下载卡', doneReady.ok,
      doneReady.ok ? '' : '等不到下载卡；界面上的错误：' + (await errorText(cdp) || '（没有）'));

    // 提交报文里必须真的带上序号。
    //
    // **这是这一段里唯一决定性的断言**：界面上有勾选框、勾上会改状态，
    // 都不等于「发出去的那条排除项带着序号」——
    // 少写一个 occurrence，服务端会按「整条不脱敏」处理，
    // 「只保留这一处」就变成了「这个值全部保留」，比用户勾的宽松得多。
    const applyBody = applyBodies.length ? applyBodies[applyBodies.length - 1] : '';
    add('提交报文里带上了出现序号', /"occurrence"\s*:\s*\d+/.test(applyBody),
      '报文：' + (applyBody || '（没抓到应用请求的报文）'));

    // 口令在报文里吗？
    //
    // 【两种情形必须分开判】
    //   填了口令 → 报文里必须有 mapPassphrase，且值与填进去的一致；
    //   没填口令 → 报文里**不许**有这个字段（服务端把「字段缺失」当成不加密，
    //   而「字段存在但为空串」会被当成错误，用户留空就会莫名失败）。
    if (opt.passphrase) {
      add('提交报文里带上了映射件口令', /"mapPassphrase"\s*:/.test(applyBody),
        '报文：' + (applyBody || '（没抓到应用请求的报文）'));
      add('报文里的口令与填进去的一致', applyBody.indexOf(opt.passphrase) >= 0,
        '报文里有没有那串口令');
    } else {
      add('留空时提交报文里没有口令字段', applyBody.indexOf('mapPassphrase') < 0,
        '报文：' + (applyBody || '（没抓到应用请求的报文）'));
    }
    const doneSummary = await evalJs(cdp, 'document.getElementById("doneSummary").textContent || ""');
    add('完成摘要非空', (doneSummary || '').length > 0, doneSummary);
    const mapBtn = await evalJs(cdp, 'document.getElementById("mappingBtn").textContent');
    add('映射件按钮在', /映射/.test(mapBtn || ''), mapBtn);
    const stepDownload = await evalJs(cdp, 'document.getElementById("stepDownload").className');
    add('流程推进到「下载」', stepDownload === 'on', 'stepDownload.className=' + stepDownload);

    // 「映射件已加密」的提示按服务端说的显示。
    // 界面自己猜的话，「填了口令但服务端没封」会被显示成「已加密」——
    // 用户于是拿着一份明文映射件当成密文处理。
    const sealedShown = await evalJs(cdp, '!document.getElementById("sealedNote").hidden');
    add('「映射件已加密」的提示与是否给口令一致', sealedShown === !!opt.passphrase,
      '提示可见=' + sealedShown + ' / 给了口令=' + !!opt.passphrase);

    // ---- 下载：文件名与页面显示的是不是同一个 ----
    // M2-c 的已知限制里有一条「下载后浏览器的文件名是否与页面显示一致」，
    // 原本只能靠真人看。这里让 Chrome 真把文件落到盘上，比对落盘文件名与按钮文案。
    const dlDir = path.join(chrome.profile, 'dl');
    fs.mkdirSync(dlDir, { recursive: true });
    let dlOk = false;
    try {
      await cdp.send('Browser.setDownloadBehavior', {
        behavior: 'allow', downloadPath: dlDir, eventsEnabled: true
      });
      dlOk = true;
    } catch (e) {
      try {
        await cdp.send('Page.setDownloadBehavior', { behavior: 'allow', downloadPath: dlDir });
        dlOk = true;
      } catch (e2) {
        // 环境不支持落盘下载：如实记成「跳过」，不记成断言失败。
        // 把「没验到」说成「验过了」比不验更坏。
        SKIPPED.push('下载落盘（环境不支持 Browser/Page.setDownloadBehavior）：'
          + (e2 && e2.message || e2));
      }
    }
    if (dlOk) {
      const btnText = await evalJs(cdp, 'document.getElementById("downloadBtn").textContent');
      const shownName = (btnText || '').replace(/^下载\s*/, '').trim();
      await click(cdp, 'downloadBtn');
      let landed = null;
      const dlDeadline = Date.now() + 20000;
      while (Date.now() < dlDeadline) {
        const files = fs.readdirSync(dlDir).filter((f) => !f.endsWith('.crdownload'));
        if (files.length) { landed = files[0]; break; }
        await sleep(150);
      }
      add('下载的文件落到了盘上', !!landed,
        landed || ('目录里空着：' + fs.readdirSync(dlDir).join(',')));
      if (landed) {
        add('下载文件名与页面显示一致', landed === shownName,
          '落盘=' + landed + ' / 页面=' + shownName);
        const p = path.join(dlDir, landed);
        const size = fs.statSync(p).size;
        add('下载的文件非空', size > 0, size + ' 字节');
        REDACTED_DOWNLOAD.name = landed;
        REDACTED_DOWNLOAD.path = p;
        REDACTED_DOWNLOAD.size = size;
      }

      // ---- 映射件也要真下载一次 ----
      //
      // 【为什么不能只下载脱敏件】界面上「已加密」只是一句话，
      // 真正交给用户的是**映射件那个文件**——它封没封、里面有没有原值，
      // 只有把这个文件落到盘上、用独立标尺查一遍才算验过。
      // 这里只负责「下载下来并报出位置」，判定在 e2e 脚本里。
      await click(cdp, 'mappingBtn');
      let mapLanded = null;
      const mapDeadline = Date.now() + 20000;
      while (Date.now() < mapDeadline) {
        const files = fs.readdirSync(dlDir)
          .filter((f) => f.indexOf('.redacted.map.json') >= 0 && !f.endsWith('.crdownload'));
        if (files.length) { mapLanded = files[0]; break; }
        await sleep(150);
      }
      add('映射件下载到了盘上', !!mapLanded,
        mapLanded || ('目录里没有 .redacted.map.json：' + fs.readdirSync(dlDir).join(',')));
      if (mapLanded) {
        const p = path.join(dlDir, mapLanded);
        MAPPING_DOWNLOAD.name = mapLanded;
        MAPPING_DOWNLOAD.path = p;
        MAPPING_DOWNLOAD.size = fs.statSync(p).size;
        add('映射件非空', MAPPING_DOWNLOAD.size > 0, MAPPING_DOWNLOAD.size + ' 字节');
      }
    }

    // ---- 把下载下来的东西复制出去 ----
    //
    // 【为什么必须复制】Chrome 的临时 profile 在驱动退出时会被整棵删掉
    // （不留垃圾的代价），而包装层要在驱动**退出之后**才去查那些文件
    // （独立标尺、命令行还原都在驱动之后跑）。
    // → 复制到调用方指定的目录，并把「复制后的路径」报出去。
    //   不复制的话，包装层拿到的就是一个已经不存在的路径——
    //   症状是「驱动全绿、包装层说文件没了」，看着像产品坏了，其实是驱动没留货。
    if (opt.artifactDir && (MAPPING_DOWNLOAD.path || REDACTED_DOWNLOAD.path)) {
      fs.mkdirSync(opt.artifactDir, { recursive: true });
      for (const d of [MAPPING_DOWNLOAD, REDACTED_DOWNLOAD]) {
        if (!d.path || !fs.existsSync(d.path)) continue;
        const to = path.join(opt.artifactDir, d.name);
        fs.copyFileSync(d.path, to);
        d.path = to;
      }
    }

    // ---- 取消：真在浏览器里点一次 ----
    //
    // 【--skip-slow 跳过这一段】这一段要扫一份 8000 段的大文档（几十秒），
    // 而「口令那一轮」要验的东西在它前面（应用 → 下载）就已经验完了。
    // 重复跑一遍大文档扫描只是把时间花在已知会过的断言上。
    if (!opt.skipSlow) {
    await click(cdp, 'againBtn');
    await setFileInput(cdp, opt.bigFile);
    const picked2 = await waitFor(cdp, '!document.getElementById("scanBtn").disabled', 5000);
    add('重置后能再选一个文件', picked2.ok, picked2.ok ? '' : 'againBtn 之后按钮仍是禁用的');

    await click(cdp, 'scanBtn');
    const running = await waitFor(cdp,
      '/已处理\\s*[1-9]/.test(document.getElementById("progressText").textContent || "")', 30000);
    add('大文档任务在跑（进度开始爬）', running.ok,
      running.ok ? '' : '30 秒内进度文案里没有正数的单元数');

    if (running.ok) {
      // 任务还活着的时候才看得到「已处理 N 个文本单元」：
      // 终态时服务端会把 message 改成「扫描完成」，所以这条必须在这里断言。
      const liveText = await evalJs(cdp, 'document.getElementById("progressText").textContent || ""');
      const lm = /已处理\s*(\d+)\s*个文本单元/.exec(liveText);
      add('进度文案报了已处理的单元数', !!lm && Number(lm[1]) > 0, liveText || '（空）');
      add('进度文案不带百分比', liveText.indexOf('%') < 0, liveText);
      const liveClass = await evalJs(cdp, 'document.getElementById("progressFill").className');
      add('任务在跑时进度条走不确定态', /indeterminate/.test(liveClass || ''), liveClass);

      await click(cdp, 'cancelBtn');
      const cancelling = await waitFor(cdp,
        '/正在取消/.test(document.getElementById("progressText").textContent || "")'
        + ' || /正在取消/.test(document.getElementById("cancelBtn").textContent || "")', 8000);
      add('点取消后界面进入「正在取消」', cancelling.ok,
        cancelling.ok ? '' : '点了取消，界面毫无反应：'
          + JSON.stringify(await evalJs(cdp, 'document.getElementById("progressText").textContent')));

      const canceled = await waitFor(cdp,
        '/已取消/.test(document.getElementById("scanError").textContent || "")'
        + ' || /已取消/.test(document.getElementById("taskError").textContent || "")', 30000);
      add('任务终态是「已取消」', canceled.ok,
        canceled.ok ? '' : '等了 30 秒也没等到已取消：'
          + JSON.stringify(await evalJs(cdp, 'document.getElementById("scanError").textContent')));
    } else {
      add('进度文案报了已处理的单元数', false, '前置不成立：任务没跑起来');
      add('进度文案不带百分比', false, '前置不成立：任务没跑起来');
      add('任务在跑时进度条走不确定态', false, '前置不成立：任务没跑起来');
      add('点取消后界面进入「正在取消」', false, '前置不成立：任务没跑起来');
      add('任务终态是「已取消」', false, '前置不成立：任务没跑起来');
    }

    await screenshot(cdp, opt.screenshot ? opt.screenshot.replace(/\.png$/, '-cancel.png') : null);
    } // !opt.skipSlow

    // ---- 窄屏 + 长文件名 ----
    // M2-c 的已知限制里还有两条只能靠真人看：「步骤条在窄屏下是否折行」
    // 与「长文件名在表格里的换行表现」。这里把视口压到 420 像素宽，
    // 传一个 60 个汉字的文件名，然后量「有没有横向溢出」。
    // 真人审美（比如断在哪里、挤不挤）仍然要人看，但「有没有溢出」可以自动判。
    if (opt.longFile && fs.existsSync(opt.longFile)) {
      await click(cdp, 'againBtn');
      await cdp.send('Emulation.setDeviceMetricsOverride', {
        width: 420, height: 900, deviceScaleFactor: 1, mobile: false
      });
      await setFileInput(cdp, opt.longFile);
      const picked3 = await waitFor(cdp, '!document.getElementById("scanBtn").disabled', 5000);
      add('窄屏下能选文件', picked3.ok, picked3.ok ? '' : 'change 事件后扫描按钮仍是禁用的');

      await click(cdp, 'scanBtn');
      // 等的是**这一次**的结果，不是上一轮留下的表格行：
      // 命中表早就非空了，拿它当条件会立刻返回，
      // 于是读到的是旧摘要 —— 那是测试自己的竞态，不是产品的问题。
      const longReady = await waitFor(cdp,
        '/长文件名/.test(document.getElementById("summary").textContent || "")', opt.timeoutMs);
      add('窄屏下长文件名的扫描结果出来了', longReady.ok,
        longReady.ok ? '' : '等不到长文件名的结果；界面上的错误：' + (await errorText(cdp) || '（没有）'));

      const summary = await evalJs(cdp, 'document.getElementById("summary").textContent || ""');
      add('长文件名在摘要里没被吞掉', summary.indexOf('长文件名') >= 0,
        summary.slice(0, 90) || '（空）');

      const overflow = await evalJs(cdp,
        'document.documentElement.scrollWidth - document.documentElement.clientWidth');
      add('窄屏下没有横向溢出', Number(overflow) <= 1, '溢出 ' + overflow + ' 像素');

      const stepRight = await evalJs(cdp,
        'document.getElementById("stepPick").getBoundingClientRect().right');
      add('窄屏下步骤条没跑出屏幕', Number(stepRight) <= 421, 'stepPick 右边界 ' + stepRight);

      await screenshot(cdp, opt.screenshot ? opt.screenshot.replace(/\.png$/, '-narrow.png') : null);
    }

    // ---- 不支持的后缀：提交时就被拒，界面上必须说清楚 ----
    // 「上传先落盘」之后，不支持的后缀与超限都在**提交时**同步 400，
    // 不再是「先收下、任务跑起来才失败」。这一条验的是界面把这件事说清楚：
    // 错误落在扫描那一步的提示位上，任务卡收起来，文件框重新可点。
    //
    // 【为什么这条要在真浏览器里验】服务端返 400 是服务端的事；
    // 「界面上有没有把这句话显示出来、显示完之后还能不能继续操作」
    // 只有真浏览器知道——静态断言只能看见 app.js 里引用过某个 id。
    if (opt.badFile && fs.existsSync(opt.badFile)) {
      await click(cdp, 'againBtn');
      await setFileInput(cdp, opt.badFile);
      const picked4 = await waitFor(cdp, '!document.getElementById("scanBtn").disabled', 5000);
      add('能选中一个后缀不受支持的文件', picked4.ok,
        picked4.ok ? '' : 'change 事件后扫描按钮仍是禁用的（accept 过滤掉了？）');

      if (picked4.ok) {
        // 这一次请求**注定**是 400，浏览器会为它在控制台记一条
        // "Failed to load resource: ... status of 400"。先声明「预期内的失败」，
        // 让控制台那条断言只放行这一次——不是放行所有 400。
        预期内的失败数 = 1;
        await click(cdp, 'scanBtn');
        const rejected = await waitFor(cdp,
          '/不支持/.test(document.getElementById("scanError").textContent || "")', 15000);
        add('不支持的后缀：界面上明确说了不支持', rejected.ok,
          rejected.ok ? '' : '界面上没有报错：'
            + JSON.stringify(await evalJs(cdp, 'document.getElementById("scanError").textContent')));

        const cardHidden = await evalJs(cdp, 'document.getElementById("taskCard").hidden');
        add('被拒之后任务卡收起来了（没有卡在「正在扫描」）', cardHidden === true,
          'taskCard.hidden=' + cardHidden);

        const fileEnabled = await evalJs(cdp, 'document.getElementById("file").disabled');
        add('被拒之后还能再选一个文件', fileEnabled === false, 'file.disabled=' + fileEnabled);

        const noJob = await evalJs(cdp,
          'document.getElementById("reviewCard").hidden === true');
        add('被拒之后没有出命中清单', noJob === true, 'reviewCard.hidden=' + !noJob);
      } else {
        add('不支持的后缀：界面上明确说了不支持', false, '前置不成立：文件没选上');
        add('被拒之后任务卡收起来了（没有卡在「正在扫描」）', false, '前置不成立：文件没选上');
        add('被拒之后还能再选一个文件', false, '前置不成立：文件没选上');
        add('被拒之后没有出命中清单', false, '前置不成立：文件没选上');
      }
    }

    add('脚本无未捕获异常', exceptions.length === 0,
      exceptions.length ? exceptions.join(' | ') : '无');
    add('控制台无 error 输出', consoleErrors.length === 0,
      consoleErrors.length ? consoleErrors.join(' | ') : '无');

    finish(opt, checks, exceptions, consoleErrors, mutation);
  } finally {
    if (cdp) cdp.close();
    if (chrome) {
      try { chrome.proc.kill('SIGTERM'); } catch (e) { /* 已经退了 */ }
      await sleep(200);
      try { chrome.proc.kill('SIGKILL'); } catch (e) { /* 已经退了 */ }
      try { fs.rmSync(chrome.profile, { recursive: true, force: true }); } catch (e) { /* 临时目录 */ }
    }
  }
}

function finish(opt, checks, exceptions, consoleErrors, mutation) {
  const failed = checks.filter((c) => !c.ok);
  const out = {
    ok: failed.length === 0,
    total: checks.length,
    failed: failed.length,
    mutation: { mode: opt.mutate, changed: mutation.changed, note: mutation.note },
    exceptions: exceptions,
    consoleErrors: consoleErrors,
    skipped: SKIPPED,
    mappingDownload: MAPPING_DOWNLOAD,
    redactedDownload: REDACTED_DOWNLOAD,
    checks: checks
  };
  process.stdout.write(JSON.stringify(out, null, 2) + '\n');
  // 只记退出码，不在这里 process.exit：
  // process.exit 不会展开 try/finally，Chrome 会留下来（进程泄漏）。
  EXIT_CODE = out.ok ? 0 : 1;
}

main().then(() => {
  process.exit(EXIT_CODE);
}).catch((e) => {
  process.stderr.write('驱动自身出错：' + (e && e.stack || e) + '\n');
  process.stdout.write(JSON.stringify({
    ok: false, infrastructure: true, error: String(e && e.message || e)
  }, null, 2) + '\n');
  process.exit(2);
});
