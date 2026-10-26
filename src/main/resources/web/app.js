/* 脱敏流程页脚本。
 *
 * 一条纪律贯穿全文件：服务端来的文本一律走 textContent，绝不拼进 innerHTML。
 * 用户上传的文件名与文档里抽出来的原值都会回到界面上，那是攻击者可控的字符串，
 * 一旦有人图省事用 innerHTML 拼一下，就是一个存储型 XSS——而这页面恰好是
 * 能看到敏感数据原值的地方。
 */
(function () {
  "use strict";

  var tokenMeta = document.querySelector('meta[name="litemask-token"]');
  var TOKEN = tokenMeta ? tokenMeta.getAttribute("content") : "";

  // ---------------------------------------------------------------- 状态
  //
  // 单个 state 对象，而不是一堆散在 DOM 属性上的标记：三步之间互有依赖
  // （拿到 jobId 才能 apply，apply 成功才有下载），状态分散时很容易在
  // 某条错误路径上对不上，界面就会显示「可以下载」但其实拿不到东西。
  var state = {
    file: null,
    result: null,
    jobId: null,
    downloadName: null,
    mappingName: null,
    restoreName: null,
    mappingSealed: false,
    taskId: null,
    taskKind: null,
    pollTimer: null,
    pendingDownloads: 0,
    productDownloaded: false,
    mappingDownloaded: false,
    restoreImportOperation: null,
    shutdownPending: false,
    stopping: false,
    // 用户手工补充的敏感词（本次处理内有效）。
    // 服务端会把它回传，这里存一份只是为了在重扫后还能显示出来。
    userTerms: []
  };

  /**
   * 轮询间隔（毫秒）。
   *
   * <p>500ms 是权衡出来的：再慢，用户会以为界面卡住了；
   * 再快（100ms），一次几秒的脱敏会问出几十次请求，
   * 而这些请求问的都是同一件事——状态没变。
   */
  var POLL_MS = 500;

  /**
   * 「出现处」这一格里**默认**显示几条位置（其余折叠，点「展开全部」才露出来）。
   *
   * <p>3 条是权衡出来的：一个值出现十几次时，全列出来会把这一列撑成长文，
   * 清单就变成一页位置而看不清「哪些值被命中」；
   * 而只列 1 条又看不出「同一个值散落在好几处」。
   *
   * <p><b>折叠的是「默认显示几条」，不是「能选到第几处」</b>（块 22）：
   * 折叠起来的那几处照样挂勾选框、照样发得出去。
   * 折叠掉的条数用「展开全部（N 处）」说出来——<b>不藏</b>：
   * 界面上少列不等于文档里少脱。
   */
  var LOCS_COLLAPSED = 3;

  /**
   * 轮询连续失败了几次。
   *
   * <p>单次网络抖动不该让整个进度视图夭折：服务端那个任务还在跑，
   * 界面却先说「连不上」，用户会以为白等了。
   * 连着失败三次才认输——那时多半是服务真的没了。
   */
  var 连击 = 0;

  // ---------------------------------------------------------------- 工具

  function el(tag, className, text) {
    var n = document.createElement(tag);
    if (className) { n.className = className; }
    if (text !== undefined && text !== null) { n.textContent = String(text); }
    return n;
  }

  function $(id) { return document.getElementById(id); }

  function headers(extra) {
    var h = extra || {};
    // 令牌走自定义头：外部网页因为 CORS 预检拿不到预检应答，所以根本发不出这个请求
    h["X-Litemask-Token"] = TOKEN;
    return h;
  }

  function setStatus(ok, line) {
    $("statusDot").className = "dot " + (ok ? "ok" : "bad");
    $("statusLine").textContent = line;
  }

  function showError(id, message) {
    if (state.stopping) { return; }
    var box = $(id);
    if (!message) {
      box.hidden = true;
      box.textContent = "";
      return;
    }
    box.hidden = false;
    box.textContent = message;
  }

  function markStep(which) {
    ["Pick", "Review", "Download"].forEach(function (name) {
      var node = $("step" + name);
      node.className = name === which ? "on" : "";
    });
  }

  /** 文件名走百分号编码：HTTP 头是 ISO-8859-1 的，中文直接发会变成问号。 */
  function encodeName(name) {
    var bytes = new TextEncoder().encode(name);
    var out = "";
    for (var i = 0; i < bytes.length; i++) {
      var c = bytes[i];
      var ch = String.fromCharCode(c);
      if (c >= 0x30 && c <= 0x39 || c >= 0x41 && c <= 0x5a
          || c >= 0x61 && c <= 0x7a || ch === "." || ch === "-" || ch === "_") {
        out += ch;
      } else {
        out += "%" + (c < 16 ? "0" : "") + c.toString(16).toUpperCase();
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- 接口

  function getJson(path) {
    return fetch(path, { headers: headers() })
      .then(function (r) { return r.json().then(function (j) { return { status: r.status, body: j }; }); });
  }

  function postJson(path, payload) {
    return fetch(path, {
      method: "POST",
      headers: headers({ "Content-Type": "application/json" }),
      body: JSON.stringify(payload)
    }).then(function (r) { return r.json().then(function (j) { return { status: r.status, body: j }; }); });
  }

  function postFile(path, file) {
    // 请求体就是文件本身，不引 multipart 解析器
    return fetch(path, {
      method: "POST",
      headers: headers({
        "Content-Type": "application/octet-stream",
        "X-Filename": encodeName(file.name)
      }),
      body: file
    }).then(function (r) { return r.json().then(function (j) { return { status: r.status, body: j }; }); });
  }

  // ---------------------------------------------------------------- 识别模型

  /**
   * 识别模型的状态（来自服务端）。
   *
   * <p>单个对象，而不是从 DOM 里读回来：界面上显示的东西必须<b>来自真实操作结果</b>，
   * 而不是「按钮点过了所以认为装好了」。
   */
  var modelState = null;

  /** 模型状态的轮询定时器。 */
  var modelTimer = null;

  /** 用户是否已经明确认可「只用规则识别、结果需人工处理」。 */
  var modelAcknowledged = false;

  /**
   * 状态轮询间隔（毫秒）。
   *
   * <p>不就绪时用短间隔：安装与校验就发生在这一段里，
   * 短间隔才看得出「正在安装 → 正在校验 → 已就绪」的推进。
   * 就绪后用长间隔：状态很少变，没必要一直打扰服务端。
   */
  function modelPollMs() {
    if (!modelState) { return 600; }
    return modelState.state === "READY" ? 5000 : 1000;
  }

  function scheduleModelPoll() {
    if (state.stopping) { return; }
    if (modelTimer) { clearTimeout(modelTimer); }
    modelTimer = setTimeout(refreshModel, modelPollMs());
  }

  function refreshModel() {
    getJson("/api/model").then(function (res) {
      if (state.stopping) { return; }
      if (res.status === 200) {
        renderModel(res.body);
      }
      scheduleModelPoll();
    }).catch(function () {
      if (state.stopping) { return; }
      // 连不上就下一轮再问：模型状态不是关键路径，断了不影响脱敏本身。
      scheduleModelPoll();
    });
  }

  /** 状态 → 徽标样式。 */
  function modelStateClass(m) {
    switch (m.state) {
      case "READY": return "ok";
      case "INSTALLING":
      case "VERIFYING": return "busy";
      case "UPDATE_FAILED": return "warn";
      case "BROKEN": return "bad";
      default: return "idle";
    }
  }

  function addFact(tb, name, value) {
    var tr = el("tr");
    tr.appendChild(el("td", null, name));
    tr.appendChild(el("td", null, value));
    tb.appendChild(tr);
  }

  /**
   * 把服务端报来的模型状态画出来。
   *
   * <p><b>每一样显示的东西都来自服务端返回的真实状态</b>，
   * 界面自己不去猜「模型是不是好了」。
   */
  function renderModel(m) {
    if (state.stopping) { return; }
    var wasUsable = modelState ? modelState.usable : null;
    modelState = m;

    var badge = $("modelStateBadge");
    badge.textContent = m.stateLabel || m.state || "";
    badge.className = "badge-state " + modelStateClass(m);
    $("modelMessage").textContent = m.message || "";
    $("modelCard").className = "card model-card " + modelStateClass(m);

    var tb = document.querySelector("#modelFacts tbody");
    tb.textContent = "";
    addFact(tb, "人名识别", m.personName ? "支持" : "不支持");
    addFact(tb, "机构名识别", m.organizationName ? "支持" : "不支持");
    addFact(tb, "当前模型版本", m.modelId || "尚未安装");
    addFact(tb, "实际可用状态", m.usable ? "已就绪，识别功能可用" : "不可用");

    var actions = m.actions || [];
    $("modelInstallBtn").hidden = actions.indexOf("install") < 0;
    $("modelUpdateBtn").hidden = actions.indexOf("update") < 0;
    $("modelRollbackBtn").hidden = actions.indexOf("rollback") < 0;
    // 同一个按钮在两种情形下是两件事：
    //   还没装过 → 「安装识别模型」；
    //   装过但坏了 / 更新失败 → 「重试」（用户要做的动作不一样，标签就该不一样）。
    $("modelInstallBtn").textContent =
      (m.state === "BROKEN" || m.state === "UPDATE_FAILED") ? "重试" : "安装识别模型";

    // 出错时把「下一步」摆在最显眼的地方：光说「失败了」用户不知道该干嘛。
    var err = m.failure ? m.failure : "";
    if (err && m.nextStep) {
      err = err + " " + m.nextStep;
    }
    showError("modelError", err);

    // 从「不可用」变成「可用」时，认可勾选就不作数了——
    // 那一次认可只针对「明知会漏人名」这件事。
    if (wasUsable === false && m.usable) {
      modelAcknowledged = false;
      $("nerAck").checked = false;
    }
    applyModelGate();
  }

  /**
   * 模型不可用时：挂上常驻提示，并要求用户明确认可才能继续。
   *
   * <p><b>为什么要这道闸</b>：规则识别模式是能用的，但它**不能**替代人名识别。
   * 默认降级之后仍让界面一路绿灯，用户会把漏了人名的文件当脱敏过的传出去。
   * → 降级必须由用户<b>明确选择</b>，而且那句话要一直挂着。
   */
  function applyModelGate() {
    if (state.stopping) { return; }
    var usable = !!(modelState && modelState.usable);
    $("nerWarn").hidden = usable;
    if (usable) {
      modelAcknowledged = false;
      $("nerAck").checked = false;
    }
    updateScanEnabled();
  }

  /** 模型不可用时，必须先勾了那一行才让扫。 */
  function modelGateSatisfied() {
    if (!modelState) {
      // 状态还没问出来之前**不放行**：宁可让用户多等一下，
      // 也不能在「模型其实是坏的」这种情况下一路绿灯。
      return false;
    }
    return modelState.usable || modelAcknowledged;
  }

  function updateScanEnabled() {
    if (state.stopping) { return; }
    var hasFile = !!state.file;
    $("scanBtn").disabled = !(hasFile && modelGateSatisfied());
    if (!hasFile) {
      $("scanHint").textContent = "先选一个文件";
    } else if (!modelGateSatisfied()) {
      $("scanHint").textContent = "请先确认上面的提示，再开始扫描";
    } else {
      $("scanHint").textContent = fileHint();
    }
  }

  $("nerAck").addEventListener("change", function () {
    modelAcknowledged = $("nerAck").checked;
    updateScanEnabled();
  });

  /** 跑一个模型动作，然后把回来的状态画出来。 */
  function runModelAction(path, body, button) {
    if (button) { button.disabled = true; }
    showError("modelError", "");
    var req = body === null
      ? fetch(path, { method: "POST", headers: headers() })
      : postJson(path, body);
    req.then(function (res) {
      return res.json().then(function (j) { return { status: res.status, body: j }; });
    }).then(function (res) {
      if (state.stopping) { return; }
      if (res.status === 200 && res.body && res.body.state) {
        renderModel(res.body);
      } else {
        showError("modelError", (res.body && res.body.error) || ("操作失败：" + res.status));
      }
    }).catch(function (e) {
      if (state.stopping) { return; }
      showError("modelError", "操作失败：" + e);
    }).then(function () {
      if (state.stopping) { return; }
      if (button) { button.disabled = false; }
      scheduleModelPoll();
    });
  }

  $("modelInstallBtn").addEventListener("click", function () {
    runModelAction("/api/model/install", null, $("modelInstallBtn"));
  });

  $("modelUpdateBtn").addEventListener("click", function () {
    runModelAction("/api/model/install", null, $("modelUpdateBtn"));
  });

  $("modelRollbackBtn").addEventListener("click", function () {
    runModelAction("/api/model/rollback", null, $("modelRollbackBtn"));
  });

  $("modelImportBtn").addEventListener("click", function () {
    $("modelFile").click();
  });

  $("modelFile").addEventListener("change", function () {
    var f = $("modelFile").files[0];
    if (!f) { return; }
    showError("modelError", "");
    $("modelImportBtn").disabled = true;
    postFile("/api/model/import", f).then(function (res) {
      if (state.stopping) { return; }
      if (res.status === 200 && res.body && res.body.state) {
        renderModel(res.body);
      } else {
        showError("modelError", (res.body && res.body.error) || ("安装失败：" + res.status));
      }
      $("modelImportBtn").disabled = false;
      $("modelFile").value = "";
      scheduleModelPoll();
    }).catch(function (e) {
      if (state.stopping) { return; }
      showError("modelError", "安装失败：" + e);
      $("modelImportBtn").disabled = false;
      $("modelFile").value = "";
    });
  });

  // ---------------------------------------------------------------- 扫描

  /** 选文件后的那行提示。抽成函数是因为它有两个地方要用：选完文件、任务结束。 */
  function fileHint() {
    var f = state.file;
    if (!f) { return "先选一个文件"; }
    return f.name + "（" + (f.size / 1024).toFixed(0) + " KB）";
  }

  $("file").addEventListener("change", function () {
    var f = $("file").files[0];
    state.file = f || null;
    // 能不能扫，取决于「有没有文件」**和**「模型那一关过没过」——
    // 两件事要一起看，所以统一交给 updateScanEnabled 算。
    updateScanEnabled();
    showError("scanError", "");
  });

  $("scanBtn").addEventListener("click", function () {
    if (!state.file) { return; }
    showError("scanError", "");
    // 换新文件时先清掉上一轮的结果，否则旧清单会留在屏幕上，
    // 用户可能对着上一份文件的命中勾排除。
    $("reviewCard").hidden = true;
    $("downloadCard").hidden = true;

    runTask("scan",
      function () { return postFile("/api/task-scan", state.file); },
      function (result) {
        state.result = result;
        state.jobId = result.jobId;
        state.downloadName = result.downloadName;
        renderResult(result);
        markStep("Review");
      });
  });

  // ---------------------------------------------------------------- 后台任务

  /**
   * 提交一个后台任务并一路轮询到终态。
   *
   * <p><b>为什么界面走异步而脚本走同步</b>：脱敏是在服务端跑的，
   * 一份大文件要跑好几秒。同步接口会让界面停在「上传中」——
   * 用户既看不到进度，也没法放弃，只能等。
   * 同步的 {@code /api/scan} 与 {@code /api/apply} 仍然留着，脚本与命令行式调用
   * 要的是一个「发过去、拿结果」的接口，拆成两段反而更麻烦。
   *
   * <p>任务完成后的 {@code result} 与同步接口<b>同形状</b>，
   * 所以拿到结果之后直接用，不必再调一次同步接口。
   */
  function runTask(kind, submit, onDone) {
    stopPolling();
    连击 = 0;
    showTask(kind);
    submit().then(function (res) {
      if (state.stopping) { return; }
      if (res.status !== 202 || !res.body.ok || !res.body.taskId) {
        failTask(res.body && res.body.error ? res.body.error : ("服务返回 " + res.status));
        return;
      }
      state.taskId = res.body.taskId;
      pollTask(state.taskId, onDone);
    }).catch(function (e) {
      if (state.stopping) { return; }
      failTask("无法连接本地服务：" + e);
    });
  }

  function showTask(kind) {
    state.taskKind = kind;
    $("taskCard").hidden = false;
    $("taskTitle").textContent = kind === "apply" ? "正在生成脱敏文件" : "正在扫描";
    $("progressText").textContent = "准备中…";
    $("progressFill").className = "progress-fill indeterminate";
    $("progressFill").style.width = "";
    $("cancelBtn").disabled = false;
    $("cancelBtn").textContent = "取消";
    showError("taskError", "");
    // 任务在跑的时候不许再挑文件：换了文件，屏幕上那份结果对应的是哪一份就说不清了。
    $("file").disabled = true;
    $("scanBtn").disabled = true;
    $("applyBtn").disabled = true;
    if (kind === "scan") { $("scanHint").textContent = "正在扫描…"; }
  }

  /**
   * 按钮的可用状态只由这一处决定。
   *
   * <p>之前这三个按钮的开关散在 {@code showTask} / {@code hideTask} / {@code onDone} 三处，
   * 于是出过一个很难发现的 bug：{@code showTask} 把「生成脱敏文件」禁用了，
   * 而 {@code hideTask} 只在「应用」那一种任务结束时才放开它——
   * **扫完之后那个按钮永远是禁用的**：界面能扫、不能脱。
   *
   * <p>那三处里没有任何一处能单独看出这个 bug：每一处单看都合理。
   * 收在一处之后，「有没有东西可应用」就只剩一个判据：{@code state.jobId}。
   */
  function syncButtons() {
    if (state.stopping) { return; }
    $("file").disabled = false;
    $("scanBtn").disabled = !state.file;
    $("applyBtn").disabled = !state.jobId;
  }

  function hideTask() {
    if (state.stopping) { return; }
    $("taskCard").hidden = true;
    $("file").disabled = false;
    state.taskId = null;
    if (state.taskKind !== "apply") {
      $("scanHint").textContent = fileHint();
    }
    syncButtons();
  }

  /**
   * 轮询任务状态。
   *
   * <p>用 {@code setTimeout} 递归而不是 {@code setInterval}：
   * 间隔定时器会在上一次请求还没回来时就发下一次，
   * 服务一慢，请求就叠在一起，界面上看到的进度会开始倒退。
   */
  function pollTask(taskId, onDone) {
    if (state.stopping) { return; }
    getJson("/api/task?taskId=" + encodeURIComponent(taskId)).then(function (res) {
      if (state.stopping) { return; }
      连击 = 0;
      if (res.status !== 200 || !res.body.ok) {
        failTask(res.body && res.body.error ? res.body.error : ("服务返回 " + res.status));
        return;
      }
      var t = res.body;
      if (t.status === "running") {
        updateProgress(t);
        if (t.cancelRequested) {
          $("cancelBtn").disabled = true;
          $("cancelBtn").textContent = "正在取消…";
          $("progressText").textContent = "正在取消…（跑到下一个检查点就停）";
        }
        state.pollTimer = setTimeout(function () { pollTask(taskId, onDone); }, POLL_MS);
        return;
      }
      // 终态
      updateProgress(t);
      hideTask();
      if (t.status === "done") {
        onDone(t.result);
      } else if (t.status === "canceled") {
        showError(errorId(), t.message || "已取消，没有产出任何文件");
      } else if (t.status === "timed_out") {
        // 超时**不是**用户按的取消，文案必须分开说：
        // 说成「已取消」会让人以为是自己弄的；说成「任务失败」更糟——
        // 那会让人以为服务崩了，而实际上服务好好的，是它把跑太久的活掐了。
        showError(errorId(), t.error || t.message || "超过时限被自动中止，没有产出任何文件");
      } else {
        showError(errorId(), t.error || "任务失败");
      }
      // onDone 跑完之后才知道有没有 jobId（扫描刚完成时，jobId 正是这时才有的），
      // 所以按钮状态要在这里再收一次。
      syncButtons();
    }).catch(function (e) {
      if (state.stopping) { return; }
      // 单次网络抖动不该让整个进度视图夭折：服务端那个任务还在跑，
      // 界面却先说「连不上」，用户会以为白等了。
      // 连着失败三次才认输——那时多半是服务真的没了。
      连击++;
      if (连击 <= 3) {
        state.pollTimer = setTimeout(function () { pollTask(taskId, onDone); }, 1000);
        return;
      }
      failTask("无法连接本地服务：" + e);
    });
  }

  function updateProgress(t) {
    $("progressText").textContent = t.message || "准备中…";
    if (typeof t.total === "number" && t.total > 0) {
      $("progressFill").className = "progress-fill";
      $("progressFill").style.width = Math.min(100, Math.round(t.done * 100 / t.total)) + "%";
    } else {
      // 总量未知：走不确定态动画。一根停在 0% 的死条会被当成「卡死了」。
      $("progressFill").className = "progress-fill indeterminate";
      $("progressFill").style.width = "";
    }
  }

  function failTask(message) {
    stopPolling();
    hideTask();
    showError(errorId(), message);
  }

  function stopPolling() {
    if (state.pollTimer) {
      clearTimeout(state.pollTimer);
      state.pollTimer = null;
    }
  }

  /** 报错的落点：扫描错在选文件那一步，应用错在命中清单那一步。 */
  function errorId() {
    return state.taskKind === "apply" ? "applyError" : "scanError";
  }

  $("cancelBtn").addEventListener("click", function () {
    if (!state.taskId) { return; }
    $("cancelBtn").disabled = true;
    $("cancelBtn").textContent = "正在取消…";
    fetch("/api/task-cancel?taskId=" + encodeURIComponent(state.taskId), {
      method: "POST",
      headers: headers()
    }).then(function (r) {
      if (state.stopping) { return; }
      if (!r.ok) {
        return r.json().then(function (j) {
          throw new Error(j && j.error ? j.error : ("服务返回 " + r.status));
        });
      }
      $("progressText").textContent = "正在取消…（跑到下一个检查点就停）";
    }).catch(function (e) {
      if (state.stopping) { return; }
      showError("taskError", "取消失败：" + e.message);
      $("cancelBtn").disabled = false;
      $("cancelBtn").textContent = "取消";
    });
  });

  // ---------------------------------------------------------------- 命中清单

  /**
   * 这一行能不能逐处勾选（位置级排除：「只保留第 N 处」）。
   *
   * <p>两个条件，少一条都不行：
   * <ul>
   *   <li><b>不是归组行</b>。归组行是原文里连在一起的一段（一个标识符被切成几段），
   *       逐处勾它会留下「保留了一半的标识符」——服务端也会直接拒掉这种请求。</li>
   *   <li><b>出现次数大于 1</b>。只出现一次时，「只保留这一处」与整条保留是一回事，
   *       多挂一个勾选框只会让人以为多了一个选项。</li>
   * </ul>
   */
  function canPickOccurrence(hit) {
    return (hit.valueCount || 1) === 1 && (hit.occurrences || 1) > 1;
  }

  /**
   * 一行命中 = 一个原值；原值相同的多处出现可以分别勾。
   *
   * <p><b>归组行</b>：一个标识符被多条规则分别认领时（比如地址被邮箱规则与人名规则
   * 各认领一段），服务端把「原文里紧挨着」的那几处合成一行，原值显示成完整的一段。
   * 界面上标一句「原文里紧挨着的 N 处命中」，免得用户以为工具认错了。
   *
   * <p>勾选框存的是这一行的<b>全部成员</b>：排除项按「(规则键, 原值)」发回，
   * 勾一行就得发这一行里的每一个成员。少发一个，被勾的那一处照样会被脱掉，
   * 而界面上看不出任何异常。
   */
  function renderResult(result) {
    var body = $("hitBody");
    body.textContent = "";

    result.hits.forEach(function (hit) {
      var tr = el("tr");
      var count = hit.valueCount || 1;
      if (count > 1) { tr.className = "merged"; }

      var tdPick = el("td", "pick");
      var box = document.createElement("input");
      box.type = "checkbox";
      box.checked = false;                    // 默认全脱
      box.dataset.members = JSON.stringify(hit.members || []);
      tdPick.appendChild(box);
      tr.appendChild(tdPick);

      var tdValue = el("td", "value");
      tdValue.appendChild(el("span", "val", hit.original));
      if (count > 1) {
        tdValue.appendChild(el("span", "mergedTag", "原文里紧挨着的 " + count + " 处命中"));
      }
      tr.appendChild(tdValue);

      tr.appendChild(el("td", null, hit.ruleKey));
      // 「出现处」这一列：先是次数，再是位置。
      //
      // 位置以前只下发给界面、**从不显示**：清单上写着「出现 3 次」，
      // 用户却不知道这 3 次分别在哪——这份清单就没法用来核对，
      // 位置信息也就等于没有。PDF 更是连位置都没有（上一块才补上）。
      //
      // 只显示前几条：位置可能有二十条，全列出来会把这一列撑成一篇长文，
      // 真正要看全的人有 title（鼠标悬停）与映射件两个去处。
      //
      // 每条位置前面挂一个「只保留这一处」的勾选框（位置级排除）。
      // **能不能挂是有条件的**：
      //   - 归组行不挂：它本身就是原文里连在一起的一段（一个标识符被切成几段），
      //     逐处勾它会留下「保留了一半的标识符」；
      //   - 只出现一次的值不挂：那一处的「只保留」与整条保留是一回事。
      var perOcc = canPickOccurrence(hit);
      var tdWhere = el("td", "num");
      tdWhere.appendChild(el("span", "cnt", hit.occurrences));
      var locs = hit.locators || [];
      // 这个值一共出现多少次。服务端给的是**总数**，
      // 而位置说明最多只给 20 条（服务端按位置说明截断）。
      // 界面上要按**出现序号**铺行，不能按位置条数铺行 ——
      // 差一位，界面上「第 4 次」就不是文件里的第 4 次（错在密的那一侧）。
      var total = hit.occurrences || 0;
      if (total < locs.length) { total = locs.length; }
      if (total > 0) {
        var locBox = el("div", "locs");
        // 位置说明按「出现序号」建索引：同一个值出现多次，每次都有自己的序号
        // （引擎分配，透传下来）。建索引而不是直接遍历位置清单，
        // 是为了让**没有位置说明的那几次**也能按序号占住自己的位置。
        var byOcc = {};
        locs.forEach(function (l) {
          if (l && typeof l.occurrence === "number" && l.occurrence >= 0) {
            byOcc[l.occurrence] = l.locator;
          }
        });
        for (var oi = 0; oi < total; oi++) {
          var locText = byOcc[oi];
          var line = el("div", "loc");
          if (perOcc) {
            var ob = document.createElement("input");
            ob.type = "checkbox";
            ob.className = "occ-pick";
            // 序号就是这一行的行号：引擎按 (规则键, 原值) 从 0 连续编号，
            // 服务端原样透传，界面原样用回。中间任何一层自己重数都会错位。
            ob.dataset.occurrence = String(oi);
            // 排除项按「(规则键, 原值)」发回。这里取**成员**的值而不是这一行的：
            // 归组行的 original 是拼接出来的，根本不在映射里，发过去会被服务端
            // 判成「对不上」而整份拒绝。逐处勾选只在非归组行上出现，
            // 那里 members 恰好有一条，取它最稳。
            var m0 = (hit.members && hit.members.length === 1) ? hit.members[0] : null;
            ob.dataset.ruleKey = m0 ? m0.ruleKey : hit.ruleKey;
            ob.dataset.original = m0 ? m0.original : hit.original;
            ob.title = "只保留这一次出现（同一个值的其它出现处照常脱敏）";
            ob.setAttribute("aria-label",
              "只保留第 " + (oi + 1) + " 次出现"
              + (locText ? "：" + locText : "（没有位置说明）"));
            line.appendChild(ob);
          }
          // 服务端没给位置说明的那几次，照样占一行、照样能勾：
          // 位置说明只是给人看的，序号才是机器认的。
          line.appendChild(el("span", "locText",
            locText || ("第 " + (oi + 1) + " 次出现（没有位置说明）")));
          if (oi >= LOCS_COLLAPSED) { line.className = "loc locHidden"; }
          locBox.appendChild(line);
        }
        if (total > LOCS_COLLAPSED) {
          var more = el("button", "locMore", "展开全部（共 " + total + " 处）");
          more.type = "button";
          more.addEventListener("click", function () {
            var open = locBox.classList.toggle("locsExpanded");
            more.textContent = open
              ? "收起（共 " + total + " 处）"
              : "展开全部（共 " + total + " 处）";
          });
          locBox.appendChild(more);
        }
        tdWhere.title = locs.map(function (l) { return l.locator; }).join("\n");
        tdWhere.appendChild(locBox);
      }
      tr.appendChild(tdWhere);
      tr.appendChild(el("td", "token", hit.token));
      body.appendChild(tr);
    });

    var s = result.summary;

    $("summary").textContent = result.fileName + " · " + result.formatLabel + " · "
      + "共 " + s.distinctHits + " 个值 / " + s.occurrences + " 处命中"
      + (s.rows && s.rows < s.distinctHits
          ? "（清单里归成了 " + s.rows + " 行）" : "");

    renderNotes("notes", result.notes);
    renderTerms(result.userTerms || []);

    // PDF 的位置粒度与别的格式不同（页 + 文本块，不是段、不是单元格），
    // 归组的边界也不同（只在同一个文本块内生效）——
    // 这两句必须说清楚，否则用户会以为「别的格式归成一行、PDF 没归」是漏了。
    var canLocate = result.hits.some(function (h) { return h.hasLocators; });
    var merged = result.hits.some(function (h) { return (h.valueCount || 1) > 1; });
    var perOcc = result.hits.some(canPickOccurrence);
    var hint;
    if (result.format === "PDF") {
      hint = "PDF 的位置是「第 N 页 · 文本块 M」："
        + "同一条内容流里由定位算子切出的一段文本，通常就是一行。"
        + "只有同一个文本块里紧挨着的几处命中才会归成一行。";
    } else {
      hint = (canLocate
        ? "同一个值在多处出现时，整行的勾选框保留的是它的所有出现处；"
          + "「出现处」是它在文档里的次数。"
        : "这份文档没有可用的位置信息，因此只能按值整体保留或整体脱敏。")
        + (merged
          ? "原文里紧挨着、被不同规则分别认领的几处命中会归成一行；"
            + "勾这一行等于保留这一行里的每一处。"
          : "");
    }
    if (perOcc) {
      hint += "出现不止一次的值，每处位置前面还有一个勾选框："
        + "勾上它就只保留那一次出现，同一个值的其它出现处照常脱敏。";
    }
    $("locatorHint").textContent = hint;

    $("reviewCard").hidden = false;
    updateChoiceHint();
  }

  function renderNotes(id, notes) {
    var box = $(id);
    box.textContent = "";
    if (!notes || notes.length === 0) {
      box.hidden = true;
      return;
    }
    notes.forEach(function (n) { box.appendChild(el("p", null, n)); });
    box.hidden = false;
  }

  /**
   * 整行的勾选框（值级排除：这个值的所有出现处都别脱）。
   *
   * <p>与逐处的勾选框（{@code .occ-pick}）分开取：两者语义不同，
   * 混在一起数会把「保留 1 处出现」报成「保留 1 个值」。
   */
  function rowBoxes() {
    return Array.prototype.slice.call(
      document.querySelectorAll("#hitBody td.pick input[type=checkbox]"));
  }

  /** 逐处的勾选框（位置级排除：只保留第 N 次出现）。 */
  function occBoxes() {
    return Array.prototype.slice.call(
      document.querySelectorAll("#hitBody input.occ-pick"));
  }

  /**
   * 已勾选的值数。
   *
   * <p>按<b>值</b>数而不是按行数：归组行勾一下是保留 N 个值，
   * 报「已保留 1 个」会让用户以为只留了一处。
   */
  function checkedValueCount() {
    var n = 0;
    rowBoxes().forEach(function (b) {
      if (!b.checked) { return; }
      var members = JSON.parse(b.dataset.members || "[]");
      n += members.length || 1;
    });
    return n;
  }

  /** 已勾选的「只保留这一处」的处数。 */
  function checkedOccurrenceCount() {
    var n = 0;
    occBoxes().forEach(function (o) { if (o.checked) { n++; } });
    return n;
  }

  function updateChoiceHint() {
    var n = checkedValueCount();
    var m = checkedOccurrenceCount();
    var parts = [];
    if (n > 0) { parts.push("已保留 " + n + " 个值"); }
    if (m > 0) { parts.push("已保留 " + m + " 处出现（同一个值的其它出现处照常脱敏）"); }
    $("choiceHint").textContent = parts.length ? parts.join("；") : "全部命中都会脱敏";
  }

  /**
   * 整行勾选与逐处勾选<b>互斥</b>。
   *
   * <p>两者的意思是对立的：「这个值全部保留」与「只保留其中一次出现」不能同时成立。
   * 不做互斥的话，两种排除项会一起发回服务端，界面上看不出哪条说了算，
   * 而结果是「整个值都留下了」——比用户勾的更宽松，方向还是错的那一边。
   *
   * <p>谁后勾谁生效：勾整行就把这一行的逐处勾选清掉并置灰；
   * 勾逐处就把整行的勾选取消。
   */
  $("hitBody").addEventListener("change", function (e) {
    var t = e.target;
    if (!t || t.type !== "checkbox") { return; }
    var tr = t.closest ? t.closest("tr") : null;
    if (!tr) { return; }
    if (t.classList.contains("occ-pick")) {
      if (t.checked) {
        var rb = tr.querySelector("td.pick input[type=checkbox]");
        if (rb) { rb.checked = false; }
      }
    } else {
      tr.querySelectorAll("input.occ-pick").forEach(function (o) {
        o.checked = false;
        o.disabled = t.checked;
      });
    }
    updateChoiceHint();
  });

  $("allBtn").addEventListener("click", function () {
    rowBoxes().forEach(function (b) {
      b.checked = true;
      var tr = b.closest ? b.closest("tr") : null;
      if (tr) {
        tr.querySelectorAll("input.occ-pick").forEach(function (o) {
          o.checked = false;
          o.disabled = true;
        });
      }
    });
    updateChoiceHint();
  });

  $("noneBtn").addEventListener("click", function () {
    rowBoxes().forEach(function (b) {
      b.checked = false;
      var tr = b.closest ? b.closest("tr") : null;
      if (tr) {
        tr.querySelectorAll("input.occ-pick").forEach(function (o) {
          o.checked = false;
          o.disabled = false;
        });
      }
    });
    updateChoiceHint();
  });

  // ---------------------------------------------------------------- 补充敏感词

  /**
   * 显示用户补过的词。
   *
   * <p><b>为什么要显式显示</b>：用户补了词之后，清单会变。
   * 如果界面上看不出「哪些是补的」，用户会以为「补了没反应」，
   * 或者分不清哪几行是自己补出来的。
   */
  function renderTerms(terms) {
    state.userTerms = terms || [];
    var box = $("termList");
    box.textContent = "";
    if (!state.userTerms.length) {
      box.hidden = true;
      return;
    }
    box.appendChild(el("span", "terms-label", "已补："));
    state.userTerms.forEach(function (t) {
      box.appendChild(el("span", "term-chip", t));
    });
    box.hidden = false;
  }

  /**
   * 把输入框里的内容拆成词表。
   *
   * <p><b>按逗号/换行拆，不按空格拆</b>：词里可能带空格
   * （「北京某某 公司」这种），按空格拆会把一个词拆成两个 ——
   * 拆出来的两个短的词会命中全文的每一个字。
   */
  function splitTerms(raw) {
    return (raw || "")
      .split(/[\n\r,，、;；]+/)
      .map(function (s) { return s.trim(); })
      .filter(function (s) { return s.length > 0; });
  }

  $("termAdd").addEventListener("click", function () {
    if (!state.jobId) { return; }
    showError("termError", "");
    var terms = splitTerms($("termInput").value);
    if (!terms.length) {
      showError("termError", "先写一个要补充的敏感词。");
      return;
    }
    var btn = $("termAdd");
    btn.disabled = true;
    // 重扫要重跑整条管线，一份大文档可能好几秒。
    // 按钮上得说清楚它在干什么 —— 否则界面看起来像卡死了。
    var oldText = btn.textContent;
    btn.textContent = "重扫中…";
    postJson("/api/terms", { jobId: state.jobId, terms: terms })
      .then(function (r) {
        if (state.stopping) { return; }
        btn.disabled = false;
        btn.textContent = oldText;
        if (r.status !== 200) {
          showError("termError", (r.body && r.body.error) || ("加入失败（状态 " + r.status + "）"));
          return;
        }
        // 重扫之后命中清单变了 → 整个重画，**勾选要清掉**：
        // 清单已经不是刚才那份了，旧的勾选框留着会误发。
        $("termInput").value = "";
        state.result = r.body;
        state.jobId = r.body.jobId;
        renderResult(r.body);
      })
      .catch(function (e) {
        if (state.stopping) { return; }
        btn.disabled = false;
        btn.textContent = oldText;
        showError("termError", "加入失败：" + e);
      });
  });

  // ---------------------------------------------------------------- 应用与下载

  $("applyBtn").addEventListener("click", function () {
    if (!state.jobId) { return; }
    showError("applyError", "");

    // 勾上的原值整条保留；occurrence 省略即「全部出现处」，
    // 与服务端的缺省语义一致（显式写 0 会被理解成「只保留第 0 次」）。
    //
    // 一行可能由若干处命中拼成（归组行），所以按成员逐个展开：
    // 少发一个，被勾的那一处照样会被脱掉，界面上却看不出任何异常。
    var excluded = [];
    rowBoxes().filter(function (b) { return b.checked; }).forEach(function (b) {
      var members = JSON.parse(b.dataset.members || "[]");
      members.forEach(function (m) {
        excluded.push({ ruleKey: m.ruleKey, original: m.original });
      });
    });

    // 逐处保留（位置级排除）：发的是「第 N 次出现」。
    //
    // **occurrence 必须写出来**：省略它，服务端按「整条不脱敏」处理，
    // 那样就变成「这个值的所有出现处都保留」——比用户勾的宽松得多，
    // 而且界面上看不出任何异常。写 0 与不写是两回事，语义不同。
    occBoxes().filter(function (o) { return o.checked; }).forEach(function (o) {
      excluded.push({
        ruleKey: o.dataset.ruleKey,
        original: o.dataset.original,
        occurrence: Number(o.dataset.occurrence)
      });
    });

    // ---- 映射件口令（可选）----
    //
    // 【为什么留空就整个字段不发】服务端把「字段缺失」和「字段存在但为空串」
    // 分成两种语义：前者是「不加密」（与从前一致），后者是「要求加密却没给口令」
    // → 报错。界面上留空若发一个空串过去，服务端就会拒绝这次应用，
    // 而用户看到的是「生成脱敏文件」失败，却不知道为什么。
    var passInput = $("mapPass");
    var passphrase = passInput ? (passInput.value || "") : "";
    var payload = { jobId: state.jobId, excluded: excluded };
    if (passphrase) { payload.mapPassphrase = passphrase; }

    runTask("apply",
      function () { return postJson("/api/task-apply", payload); },
      function (result) {
        renderApplied(result);
        markStep("Download");
        // 口令用完就从界面上抹掉：它留在输入框里会被下一次「再处理一个文件」沿用，
        // 而那时用户多半已经忘了自己填过——于是下一个文件被无声地加密了。
        if (passInput) { passInput.value = ""; }
      });
  });

  function renderApplied(result) {
    var s = result.summary;
    $("doneSummary").textContent = result.downloadName + " · "
      + "脱敏 " + s.redacted + " 个值（共 " + s.occurrences + " 处），"
      + "保留 " + s.excluded + " 个值";
    renderNotes("doneNotes", result.notes);

    // 映射件与还原件的文件名由服务端给：让客户端自己拼名字，
    // 拼出来的名字与服务端的实际产物对不上时，用户会以为下载失败。
    state.mappingName = result.mappingName || null;
    state.restoreName = result.restoreName || null;

    // 映射件封没封由服务端说：界面自己猜的话，
    // 「用户填了口令但服务端没封」这种情况会被显示成「已加密」，
    // 用户就会拿着一份明文映射件当成密文处理。
    state.mappingSealed = !!(result && result.mappingSealed);
    state.productDownloaded = false;
    state.mappingDownloaded = false;
    $("sealedNote").hidden = !state.mappingSealed;

    $("downloadBtn").textContent = "下载 " + result.downloadName;
    $("reviewCard").hidden = true;
    $("downloadCard").hidden = false;
  }

  /**
   * 下载：fetch + Blob，再把 object URL 交给一个临时 {@code <a download>} 触发保存。
   *
   * <p><b>不能用 {@code <a download>} 直接指过去</b>：那是一次浏览器导航请求，
   * 带不上 {@code X-Litemask-Token} 这个自定义头，而下载接口要求令牌。
   * 令牌仍然只在请求头上，不进 URL（URL 会进浏览器历史记录）。
   *
   * <p>Blob 用完立刻 revokeObjectURL：这份字节是脱敏后的文件或含原值的映射，
   * 让它一直挂在页面上没道理。
   */
  function 下载(path, 文件名, 按钮id, 错误id, savedFlag) {
    var btn = $(按钮id);
    btn.disabled = true;
    state.pendingDownloads += 1;
    showError(错误id, "");
    return fetch(path, { headers: headers() })
      .then(function (r) {
        if (!r.ok) {
          return r.json().then(function (j) {
            throw new Error(j && j.error ? j.error : ("服务返回 " + r.status));
          });
        }
        return r.blob();
      })
      .then(function (blob) {
        var url = URL.createObjectURL(blob);
        var a = document.createElement("a");
        a.href = url;
        a.download = 文件名;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        if (savedFlag) { state[savedFlag] = true; }
      })
      .catch(function (e) {
        showError(错误id, "下载失败：" + e.message);
      })
      .finally(function () {
        state.pendingDownloads = Math.max(0, state.pendingDownloads - 1);
        if (!state.stopping) { btn.disabled = false; }
      });
  }

  $("downloadBtn").addEventListener("click", function () {
    if (!state.jobId) { return; }
    下载("/api/download?jobId=" + encodeURIComponent(state.jobId),
        state.downloadName, "downloadBtn", "applyError", "productDownloaded");
  });

  /**
   * 下载映射文件。
   *
   * <p>映射是还原的唯一凭据：脱敏件里只有令牌，没有原值。
   * 丢了映射，那份脱敏件就<b>永久</b>不可逆——而界面上看不出任何异常。
   * 所以这个按钮和那句提醒必须都在。
   */
  $("mappingBtn").addEventListener("click", function () {
    if (!state.jobId || !state.mappingName) { return; }
    下载("/api/mapping?jobId=" + encodeURIComponent(state.jobId),
        state.mappingName, "mappingBtn", "restoreError", "mappingDownloaded");
  });

  /**
   * 还原并下载。
   *
   * <p>响应体就是还原出来的文件，替换了几处放在响应头 {@code X-Litemask-Restored} 里。
   * 那个数必须显示出来：还原一份没被脱过的东西，文件会**一模一样**，
   * 用户会以为「还原没生效」，而事实是「这份文档本来就没有可还原的内容」。
   */
  $("restoreBtn").addEventListener("click", function () {
    if (!state.jobId) { return; }
    var btn = $("restoreBtn");
    btn.disabled = true;
    state.pendingDownloads += 1;
    showError("restoreError", "");
    fetch("/api/restore?jobId=" + encodeURIComponent(state.jobId), {
      method: "POST",
      headers: headers()
    })
      .then(function (r) {
        if (!r.ok) {
          return r.json().then(function (j) {
            throw new Error(j && j.error ? j.error : ("服务返回 " + r.status));
          });
        }
        var n = r.headers.get("X-Litemask-Restored");
        var notice = r.headers.get("X-Litemask-Notice");
        return r.blob().then(function (b) {
          return { blob: b, count: n, notice: notice };
        });
      })
      .then(function (res) {
        if (state.stopping) { return; }
        var url = URL.createObjectURL(res.blob);
        var a = document.createElement("a");
        a.href = url;
        a.download = state.restoreName;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        var n = res.count === null ? "0" : res.count;
        $("restoreHint").textContent = "已还原 " + n + " 处。"
          + (res.notice ? decodeURIComponent(res.notice).split(" | ").join("；") : "");
      })
      .catch(function (e) {
        if (state.stopping) { return; }
        showError("restoreError", "还原失败：" + e.message);
      })
      .finally(function () {
        state.pendingDownloads = Math.max(0, state.pendingDownloads - 1);
        if (!state.stopping) { btn.disabled = false; }
      });
  });

  // -------------------------------------------------------- 重启后独立还原

  function currentRestoreImport(op) {
    return state.restoreImportOperation === op;
  }

  function lockRestoreImportControls(locked) {
    $("restoreProductFile").disabled = locked;
    $("restoreMappingFile").disabled = locked;
    $("restorePass").disabled = locked;
    if (locked) {
      $("restoreImportBtn").disabled = true;
    }
  }

  function updateRestoreImportReady() {
    if (state.stopping) { return; }
    if (state.restoreImportOperation || state.shutdownPending) {
      lockRestoreImportControls(true);
      return;
    }
    lockRestoreImportControls(false);
    var product = $("restoreProductFile").files[0];
    var mapping = $("restoreMappingFile").files[0];
    var passphrase = $("restorePass").value || "";
    $("restoreImportBtn").disabled = !(product && mapping && passphrase);
  }

  ["restoreProductFile", "restoreMappingFile"].forEach(function (id) {
    $(id).addEventListener("change", updateRestoreImportReady);
  });
  $("restorePass").addEventListener("input", updateRestoreImportReady);

  function finishRestoreImport(op) {
    if (!currentRestoreImport(op) || !op.uploadSettled || !op.cancelSettled) { return; }
    state.restoreImportOperation = null;
    $("restoreImportBtn").textContent = "核对并还原";
    $("restoreImportCancelBtn").hidden = true;
    if (state.stopping || state.shutdownPending) { return; }
    if (op.cancelRequested) {
      $("restoreImportStatus").textContent = op.cancelError
        ? "本机上传已停止，但服务端取消请求失败；请确认服务仍可用后再重试。"
        : "已取消，本次未生成还原文件，可以重新选择后再试。";
    }
    updateRestoreImportReady();
  }

  function cancelRestoreImport(op, forShutdown) {
    if (!op || !currentRestoreImport(op) || op.cancelRequested) {
      return op ? op.cancelPromise : Promise.resolve();
    }
    op.cancelRequested = true;
    op.cancelSettled = false;
    op.controller.abort();
    $("restoreImportCancelBtn").disabled = true;
    if (!forShutdown) {
      $("restoreImportStatus").textContent =
        "正在取消原上传，并通知本机服务清理；完成前不会开始下一次还原。";
    }
    op.cancelPromise = fetch("/api/restore-import-cancel", {
      method: "POST",
      headers: headers()
    })
      .then(function (r) {
        return r.json().then(function (body) {
          if (!r.ok) {
            throw new Error(body && body.error ? body.error : ("服务返回 " + r.status));
          }
          return body;
        });
      })
      .then(function (result) {
        if (!currentRestoreImport(op) || state.stopping || forShutdown) { return; }
        $("restoreImportStatus").textContent = result.message || "已请求取消，正在等待原上传结束。";
      })
      .catch(function (e) {
        op.cancelError = e;
        if (!currentRestoreImport(op) || state.stopping || forShutdown) { return; }
        showError("restoreImportError", "取消请求失败：" + e.message);
      })
      .finally(function () {
        op.cancelSettled = true;
        finishRestoreImport(op);
      });
    return op.cancelPromise;
  }

  $("restoreImportBtn").addEventListener("click", function () {
    if (state.restoreImportOperation || state.shutdownPending || state.stopping) { return; }
    var product = $("restoreProductFile").files[0];
    var mapping = $("restoreMappingFile").files[0];
    var passphrase = $("restorePass").value || "";
    if (!product || !mapping || !passphrase) { return; }

    var btn = $("restoreImportBtn");
    var cancelBtn = $("restoreImportCancelBtn");
    var op = {
      controller: new AbortController(),
      uploadSettled: false,
      cancelRequested: false,
      cancelSettled: true,
      cancelPromise: null,
      cancelError: null
    };
    state.restoreImportOperation = op;
    lockRestoreImportControls(true);
    state.pendingDownloads += 1;
    btn.textContent = "核对并还原中…";
    cancelBtn.hidden = false;
    cancelBtn.disabled = false;
    $("restoreImportStatus").textContent = "正在本机核对映射与脱敏文件…";
    showError("restoreImportError", "");

    // 固定结构信封，不引入通用 multipart 解析面：
    // magic + version + 元数据长度 + 两个文件长度 + JSON 元数据 + 两个文件。
    // File 直接作为 Blob 分段发送，浏览器不会先把两份大文件复制进 JS 数组。
    var meta = new TextEncoder().encode(JSON.stringify({
      productName: product.name,
      mappingName: mapping.name,
      passphrase: passphrase
    }));
    var header = new ArrayBuffer(28);
    var view = new DataView(header);
    view.setUint32(0, 0x4c4d5231); // "LMR1"
    view.setUint32(4, 1);
    view.setUint32(8, meta.byteLength);
    view.setUint32(12, 0); view.setUint32(16, product.size);
    view.setUint32(20, 0); view.setUint32(24, mapping.size);
    var envelope = new Blob([header, meta, product, mapping], {
      type: "application/octet-stream"
    });
    fetch("/api/restore-import", {
      method: "POST",
      headers: headers({ "Content-Type": "application/octet-stream" }),
      body: envelope,
      signal: op.controller.signal
    })
      .then(function (r) {
        if (!currentRestoreImport(op) || op.cancelRequested || state.stopping) { return null; }
        if (!r.ok) {
          return r.json().then(function (j) {
            throw new Error(j && j.error ? j.error : ("服务返回 " + r.status));
          }, function () {
            throw new Error("服务返回 " + r.status);
          });
        }
        var count = r.headers.get("X-Litemask-Restored");
        var notice = r.headers.get("X-Litemask-Notice");
        return r.blob().then(function (blob) {
          return { blob: blob, count: count, notice: notice };
        });
      })
      .then(function (result) {
        if (!result || !currentRestoreImport(op) || op.cancelRequested || state.stopping) { return; }
        var lower = product.name.toLowerCase();
        var dot = lower.lastIndexOf(".");
        var name = dot > 0
          ? product.name.slice(0, dot) + ".restored" + product.name.slice(dot)
          : product.name + ".restored";
        var url = URL.createObjectURL(result.blob);
        var a = document.createElement("a");
        a.href = url;
        a.download = name;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        $("restorePass").value = "";
        var n = result.count === null ? "0" : result.count;
        $("restoreImportStatus").textContent = "已还原 " + n + " 处并下载。"
          + (result.notice ? decodeURIComponent(result.notice).split(" | ").join("；") : "");
      })
      .catch(function (e) {
        if (!currentRestoreImport(op) || op.cancelRequested || state.stopping
            || (e && e.name === "AbortError")) { return; }
        $("restoreImportStatus").textContent = "未生成还原文件。";
        showError("restoreImportError", "还原失败：" + e.message);
      })
      .finally(function () {
        if (!op.uploadSettled) {
          op.uploadSettled = true;
          state.pendingDownloads = Math.max(0, state.pendingDownloads - 1);
        }
        finishRestoreImport(op);
      });
  });

  $("restoreImportCancelBtn").addEventListener("click", function () {
    cancelRestoreImport(state.restoreImportOperation, false);
  });

  $("againBtn").addEventListener("click", function () {
    stopPolling();
    state.file = null;
    state.result = null;
    state.jobId = null;
    state.mappingName = null;
    state.restoreName = null;
    state.mappingSealed = false;
    state.productDownloaded = false;
    state.mappingDownloaded = false;
    state.taskId = null;
    state.taskKind = null;
    $("file").value = "";
    // 口令也一并清掉：留着它，下一个文件会被无声地用上一个文件的口令加密。
    if ($("mapPass")) { $("mapPass").value = ""; }
    $("sealedNote").hidden = true;
    $("scanHint").textContent = "先选一个文件";
    $("reviewCard").hidden = true;
    $("downloadCard").hidden = true;
    $("taskCard").hidden = true;
    showError("scanError", "");
    showError("applyError", "");
    showError("restoreError", "");
    showError("taskError", "");
    markStep("Pick");
    syncButtons();
  });

  // ---------------------------------------------------------------- 诊断区

  function renderPosture(p) {
    var tb = document.querySelector("#posture tbody");
    tb.textContent = "";
    var rows = [
      ["绑定地址", (p.bindAddress || "?")],
      ["Host 头校验", p.hostHeaderChecked ? "开（防 DNS rebinding）" : "关"],
      ["CORS 响应头", p.corsHeaders ? "有" : "无（不配置是安全默认值）"],
      ["服务地址", window.location.origin]
    ];
    rows.forEach(function (r) {
      var tr = el("tr");
      tr.appendChild(el("td", null, r[0]));
      var td = el("td");
      if (r[1].indexOf("/") >= 0 || r[1].indexOf(".") >= 0 || r[1].indexOf(":") >= 0) {
        td.appendChild(el("code", null, r[1]));
      } else {
        td.textContent = r[1];
      }
      tr.appendChild(td);
      tb.appendChild(tr);
    });
  }

  function renderRoutes(list) {
    var ul = $("routes");
    ul.textContent = "";
    (list || []).forEach(function (r) {
      var li = el("li");
      var isWrite = r.indexOf("[写]") >= 0;
      li.appendChild(el("span", isWrite ? "w" : "r", isWrite ? "写" : "读"));
      li.appendChild(document.createTextNode(" " + r.replace(/ \[(读|写)\]$/, "") + " "));
      if (isWrite) {
        li.appendChild(el("span", "muted small", "需令牌"));
      }
      ul.appendChild(li);
    });
    $("shutdownCard").hidden = (list || []).indexOf("POST /api/shutdown [写]") < 0;
  }

  function stopAllPolling() {
    stopPolling();
    if (modelTimer) {
      clearTimeout(modelTimer);
      modelTimer = null;
    }
  }

  /** 退出响应确认后，这把锁覆盖所有会触发动作的控件。 */
  function lockControlsForStop() {
    document.querySelectorAll("button, input").forEach(function (control) {
      control.disabled = true;
    });
  }

  $("shutdownBtn").addEventListener("click", function () {
    if (state.shutdownPending || state.stopping) { return; }
    var activeImportUpload = state.restoreImportOperation
      && !state.restoreImportOperation.uploadSettled ? 1 : 0;
    if (state.pendingDownloads - activeImportUpload > 0) {
      $("shutdownStatus").textContent = "还有文件正在下载，请等下载结束后再退出。";
      return;
    }

    var warnings = ["退出会停止本机服务，并清理本次会话的临时文件。"];
    if (state.jobId && (!state.productDownloaded || !state.mappingDownloaded)) {
      warnings.push("当前成品的脱敏文件或映射文件尚未在本页确认下载。请先保存两份文件。");
    }
    if (state.taskId || !$("restoreImportCancelBtn").hidden) {
      warnings.push("正在处理的任务会被请求取消，退出可能需要等到下一个安全检查点。");
    }
    warnings.push("确定现在退出吗？");
    if (!window.confirm(warnings.join("\n\n"))) {
      $("shutdownStatus").textContent = "已取消退出，本机服务仍可继续使用。";
      return;
    }

    // 独立还原的上传属于本页，退出时先切断这个请求；服务端取消仍照常发送。
    // 普通成品下载不这样做：浏览器正在保存的文件不能被静默截断。
    state.shutdownPending = true;
    lockRestoreImportControls(true);
    cancelRestoreImport(state.restoreImportOperation, true);

    var btn = $("shutdownBtn");
    btn.disabled = true;
    btn.textContent = "正在退出…";
    showError("shutdownError", "");
    fetch("/api/shutdown", { method: "POST", headers: headers() })
      .then(function (r) {
        return r.json().then(function (body) {
          if (!r.ok || !body.ok || !body.stopping) {
            throw new Error(body && body.error ? body.error : ("服务返回 " + r.status));
          }
          return body;
        });
      })
      .then(function (body) {
        state.stopping = true;
        stopAllPolling();
        setStatus(false, "本机服务正在停止…");
        $("shutdownStatus").textContent = body.message
          || "已收到退出请求，服务正在停止。";
        lockControlsForStop();
      })
      .catch(function (e) {
        state.shutdownPending = false;
        btn.disabled = false;
        btn.textContent = "退出本机服务";
        updateRestoreImportReady();
        showError("shutdownError", "退出请求失败：" + e.message
          + "。本机服务尚未被确认停止。");
      });
  });

  function renderRules(rules) {
    $("ruleCount").textContent = String(rules.length);
    var tb = $("ruleBody");
    tb.textContent = "";
    rules.forEach(function (r) {
      var tr = el("tr");
      tr.appendChild(el("td", null, r.key));
      tr.appendChild(el("td", null, r.name));
      tr.appendChild(el("td", null, r.tokenPrefix));
      tr.appendChild(el("td", "num", r.sortOrder));
      tr.appendChild(el("td", null, r.validator));
      tr.appendChild(el("td", "muted", r.description));
      tb.appendChild(tr);
    });
  }

  getJson("/api/health").then(function (res) {
    if (state.stopping) { return; }
    if (res.status !== 200 || !res.body.ok) {
      setStatus(false, "服务返回异常：" + res.status);
      return;
    }
    var b = res.body;
    setStatus(true, "本地服务已就绪 · " + b.ruleCount + " 条规则 · 会话 " + b.sessionId.slice(0, 8));
    renderPosture(b.securityPosture || {});
    renderRoutes(b.routes);
    return getJson("/api/rules").then(function (rr) {
      if (state.stopping) { return; }
      if (rr.status === 200) { renderRules(rr.body.rules || []); }
    });
  }).catch(function (e) {
    if (state.stopping) { return; }
    setStatus(false, "无法连接本地服务：" + e);
  });

  // 识别模型的状态：开页就问，并且一直问。
  // 【为什么要一直问】首次启动时模型是**在后台装的**（解压 + 加载要几秒），
  // 界面上「正在安装 → 正在校验 → 已就绪」的推进只能靠轮询看到。
  refreshModel();
})();
