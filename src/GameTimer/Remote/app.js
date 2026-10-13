// GameTimer phone UI (Phase 1, step 6). Plain JS, no build step; served from the exe.
// Talks to the PC through the REST API (/api/*) and live events over /ws (see docs/remote-api.http).
"use strict";
(() => {
  // ---------- storage (can throw in private mode) ----------
  const KEY = { token: "gametimer.token", lang: "gametimer.lang", last: "gametimer.lastStart" };
  const store = {
    get: k => { try { return localStorage.getItem(k); } catch { return null; } },
    set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* ignore */ } },
    del: k => { try { localStorage.removeItem(k); } catch { /* ignore */ } }
  };

  // ---------- strings (EN / RU; the language follows the desktop setting once connected) ----------
  const STR = {
    en: {
      tabTimer: "Timer", tabSettings: "Settings", tabDevices: "Phones",
      connecting: "Connecting to the PC…",
      offline: "PC unreachable. Reconnecting… Check that this phone is on the same Wi-Fi.",
      offlineLong: "PC still unreachable. If it is on and this phone is on the same Wi-Fi, the PC may have a new address: on the PC open Settings → Connect phone and scan the new QR code.",
      state_running: "Running", state_stopped: "Stopped", state_notStarted: "Not started", state_expired: "Time's up",
      endsThen: "Ends at {0}, then {1}",
      act_shutdown: "the PC shuts down", act_lock: "the screen locks", act_logoff: "the user is logged off",
      stop: "Stop", start: "Start",
      modeInterval: "Duration", modeAbsolute: "Until time",
      hours: "Hours", hour: "Hour", minutes: "Minutes",
      confirmStop: "Stop the timer?",
      confirmEnd: "Less than 15 minutes left: the timer ends now and {0}. Continue?",
      action: "When time is up", action_shutdown: "Shut down the PC", action_lock: "Lock the screen", action_logoff: "Log off",
      language: "Language (also on the PC)",
      colors: "Countdown colors", colorsHint: "The color changes when this many minutes or fewer are left.",
      zoneNormal: "Normal", zoneWarning: "Warning", zoneAlert: "Alert", minLeft: "min",
      alerts: "Sound alerts on the PC", minBeforeEnd: "min before the end", alertItem: "{0} min before the end",
      add: "Add", remove: "Remove", noAlerts: "No alerts",
      save: "Save", saved: "Saved",
      thisPhone: "this phone", lastSeen: "Last seen {0}",
      confirmRemove: "Remove “{0}”? It will have to be connected again.",
      disconnectThis: "Disconnect this phone", confirmDisconnect: "Disconnect this phone from the PC?",
      installTitle: "Home screen", install: "Install app",
      installIos: "Add GameTimer to the home screen: Safari → Share → Add to Home Screen.",
      installAndroid: "Add GameTimer to the home screen: Chrome menu ⋮ → Add to Home screen.",
      pairIntro: "On the PC open GameTimer → Settings → Connect phone, and scan the QR code with this phone.",
      pairWithCode: "Give this phone a name and connect it to the PC.",
      phoneName: "Phone name", connect: "Connect",
      revoked: "This phone was disconnected on the PC.",
      err_bad_code: "The code is invalid or has expired. Create a new one on the PC.",
      err_too_many: "Too many attempts. Wait a minute.",
      err_offline: "PC unreachable.",
      err_invalid_time: "Check the time.", err_already_running: "The timer is already running.",
      err_not_running: "The timer is not running.", err_invalid_minutes: "Invalid number of minutes.",
      err_invalid_threshold: "Colors: minutes must be 1–1440.",
      err_invalid_alerts: "Alerts: 1–1440 minutes, no duplicates.",
      err_invalid_color: "Invalid color.", err_invalid_language: "Unknown language.",
      err_generic: "Something went wrong ({0})."
    },
    ru: {
      tabTimer: "Таймер", tabSettings: "Настройки", tabDevices: "Телефоны",
      connecting: "Подключение к ПК…",
      offline: "ПК недоступен. Переподключение… Проверьте, что телефон в той же сети Wi-Fi.",
      offlineLong: "ПК всё ещё недоступен. Если он включён, а телефон в той же сети Wi-Fi, у ПК мог смениться адрес: на ПК откройте «Настройки» → «Подключить телефон» и отсканируйте новый QR-код.",
      state_running: "Идёт", state_stopped: "Остановлен", state_notStarted: "Не запущен", state_expired: "Время вышло",
      endsThen: "Закончится в {0}, затем {1}",
      act_shutdown: "ПК выключится", act_lock: "экран заблокируется", act_logoff: "произойдёт выход из системы",
      stop: "Стоп", start: "Старт",
      modeInterval: "Длительность", modeAbsolute: "До времени",
      hours: "Часы", hour: "Час", minutes: "Минуты",
      confirmStop: "Остановить таймер?",
      confirmEnd: "Осталось меньше 15 минут: таймер закончится сразу, и {0}. Продолжить?",
      action: "Когда время выйдет", action_shutdown: "Выключить ПК", action_lock: "Заблокировать экран", action_logoff: "Выйти из системы",
      language: "Язык (и на ПК)",
      colors: "Цвета отсчёта", colorsHint: "Цвет меняется, когда до конца остаётся столько минут или меньше.",
      zoneNormal: "Обычный", zoneWarning: "Предупреждение", zoneAlert: "Тревога", minLeft: "мин",
      alerts: "Звуковые оповещения на ПК", minBeforeEnd: "мин до конца", alertItem: "за {0} мин до конца",
      add: "Добавить", remove: "Удалить", noAlerts: "Нет оповещений",
      save: "Сохранить", saved: "Сохранено",
      thisPhone: "этот телефон", lastSeen: "Последний раз: {0}",
      confirmRemove: "Удалить «{0}»? Его придётся подключить заново.",
      disconnectThis: "Отключить этот телефон", confirmDisconnect: "Отключить этот телефон от ПК?",
      installTitle: "Главный экран", install: "Установить приложение",
      installIos: "Добавьте GameTimer на главный экран: Safari → «Поделиться» → «На экран „Домой“».",
      installAndroid: "Добавьте GameTimer на главный экран: меню Chrome ⋮ → «Добавить на главный экран».",
      pairIntro: "На ПК откройте GameTimer → Настройки → Подключить телефон и отсканируйте QR-код этим телефоном.",
      pairWithCode: "Назовите телефон и подключите его к ПК.",
      phoneName: "Название телефона", connect: "Подключить",
      revoked: "Этот телефон отключён на ПК.",
      err_bad_code: "Код неверный или истёк. Создайте новый на ПК.",
      err_too_many: "Слишком много попыток. Подождите минуту.",
      err_offline: "ПК недоступен.",
      err_invalid_time: "Проверьте время.", err_already_running: "Таймер уже запущен.",
      err_not_running: "Таймер не запущен.", err_invalid_minutes: "Неверное число минут.",
      err_invalid_threshold: "Цвета: от 1 до 1440 минут.",
      err_invalid_alerts: "Оповещения: от 1 до 1440 минут, без повторов.",
      err_invalid_color: "Неверный цвет.", err_invalid_language: "Неизвестный язык.",
      err_generic: "Что-то пошло не так ({0})."
    }
  };
  let lang = store.get(KEY.lang) || ((navigator.language || "").toLowerCase().startsWith("ru") ? "ru" : "en");
  const t = (key, ...args) => (STR[lang][key] ?? STR.en[key] ?? key).replace(/\{(\d)\}/g, (_, i) => args[i]);

  const $ = id => document.getElementById(id);
  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text; // never innerHTML: device names come from other phones
    return e;
  };

  // ---------- state ----------
  let token = store.get(KEY.token);
  let status = null;          // last status from the PC
  let statusAt = 0;           // performance.now() when it arrived (for the local countdown)
  let settings = null;        // last settings from the PC
  let ws = null, retryTimer = 0, retryMs = 1000;
  let online = false;
  let offlineSince = 0;       // performance.now() when the connection was lost (0 = online)
  const OFFLINE_HINT_MS = 20000;
  let tab = "timer";
  let mode = "interval";
  let settingsDirty = false;
  let editAlerts = [];

  // ---------- API ----------
  class ApiError extends Error {
    constructor(status, code) { super(code || "http_" + status); this.status = status; this.code = code; }
  }

  async function api(method, path, body) {
    let res;
    try {
      res = await fetch(path, {
        method, cache: "no-store",
        headers: Object.assign({ Authorization: "Bearer " + token }, body ? { "Content-Type": "application/json" } : {}),
        body: body ? JSON.stringify(body) : undefined
      });
    } catch {
      throw new ApiError(0, "offline");
    }
    if (res.status === 401) { unpair(t("revoked")); throw new ApiError(401, "revoked"); }
    const data = res.status === 204 ? null : await res.json().catch(() => null);
    if (!res.ok) throw new ApiError(res.status, data && data.error);
    return data;
  }

  const errorText = e => {
    const key = "err_" + (e && e.code);
    return STR.en[key] ? t(key) : t("err_generic", e && (e.code || e.status || e.message));
  };

  let toastTimer = 0;
  function toast(text) {
    const box = $("toast");
    box.textContent = text;
    box.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { box.hidden = true; }, 4000);
  }

  // ---------- WebSocket ----------
  function connect() {
    clearTimeout(retryTimer);
    if (!token || (ws && ws.readyState <= WebSocket.OPEN)) return;

    const socket = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/ws");
    ws = socket;
    socket.onopen = () => socket.send(JSON.stringify({ type: "auth", token }));
    socket.onmessage = e => {
      let msg;
      try { msg = JSON.parse(e.data); } catch { return; }
      onMessage(msg);
    };
    socket.onclose = e => {
      if (ws !== socket) return;
      ws = null;
      setOnline(false);
      if (e.code === 1008 && (e.reason === "revoked" || e.reason === "unauthorized")) {
        unpair(t("revoked"));
        return;
      }
      retryTimer = setTimeout(connect, retryMs);
      retryMs = Math.min(retryMs * 2, 10000);
    };
  }

  function onMessage(msg) {
    switch (msg.type) {
      case "hello":
        retryMs = 1000;
        setSettings(msg.settings, true);
        setStatus(msg.status);
        setOnline(true);
        if (tab === "devices") loadDevices();
        break;
      case "settingsChanged":
        setSettings(msg.settings, false);
        break;
      case "alert": {
        setStatus(msg.status);
        const cd = $("countdown");
        cd.classList.remove("pulse"); void cd.offsetWidth; cd.classList.add("pulse"); // restart the animation
        if (navigator.vibrate) navigator.vibrate(200);
        break;
      }
      default:
        if (msg.status) setStatus(msg.status);
    }
  }

  function setOnline(value) {
    online = value;
    if (value) offlineSince = 0;
    else if (!offlineSince) offlineSince = performance.now();
    document.body.classList.toggle("offline", !value);
    renderBanner();
  }

  // After a while offline, the likely cause is a new PC address (router / DHCP): say how to fix it
  function renderBanner() {
    const banner = $("banner");
    banner.hidden = online || !token;
    if (banner.hidden) return;
    const long = offlineSince && performance.now() - offlineSince > OFFLINE_HINT_MS;
    banner.textContent = long ? t("offlineLong") : status ? t("offline") : t("connecting");
  }
  setInterval(renderBanner, 2000);

  function unpair(message) {
    token = null;
    store.del(KEY.token);
    if (ws) { const s = ws; ws = null; s.close(); }
    clearTimeout(retryTimer);
    status = null;
    setOnline(false);
    showPair(null, message);
  }

  // Phones freeze background tabs: reconnect right away when the page comes back
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && token && !ws) { retryMs = 1000; connect(); }
  });
  window.addEventListener("online", () => { if (token && !ws) { retryMs = 1000; connect(); } });

  // ---------- language / texts ----------
  function applyTexts() {
    document.documentElement.lang = lang;
    document.querySelectorAll("[data-t]").forEach(e => { e.textContent = t(e.dataset.t); });
    $("h-label").textContent = t(mode === "interval" ? "hours" : "hour");
    if (!$("banner").hidden) setOnline(online);
    renderInstall();
  }

  function setLang(value) {
    if (!STR[value] || value === lang) return;
    lang = value;
    store.set(KEY.lang, value);
    applyTexts();
    renderTimer();
    renderAlerts();
    if (tab === "devices") loadDevices();
  }

  // ---------- navigation ----------
  const screens = ["pair", "timer", "settings", "devices"];
  function show(name) {
    screens.forEach(s => { $("screen-" + s).hidden = s !== name; });
    $("tabs").hidden = name === "pair";
    if (name !== "pair") {
      tab = name;
      document.querySelectorAll(".tabs button").forEach(b =>
        b.setAttribute("aria-current", b.dataset.tab === name ? "page" : "false"));
    }
  }

  document.querySelectorAll(".tabs button").forEach(b => b.addEventListener("click", () => {
    show(b.dataset.tab);
    if (b.dataset.tab === "devices") loadDevices();
    if (b.dataset.tab === "settings" && !settingsDirty) fillSettings();
  }));

  // ---------- pairing ----------
  function showPair(code, message) {
    show("pair");
    $("pair-text").textContent = code ? t("pairWithCode") : t("pairIntro");
    $("pair-form").hidden = !code;
    $("pair-error").textContent = message || "";
    $("pair-btn").onclick = () => pair(code);
  }

  async function pair(code) {
    const btn = $("pair-btn");
    btn.disabled = true;
    $("pair-error").textContent = "";
    try {
      const res = await fetch("/pair", {
        method: "POST", cache: "no-store", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code, name: $("pair-name").value })
      });
      if (res.ok) {
        token = (await res.json()).token;
        store.set(KEY.token, token);
        show("timer");
        setOnline(false);
        connect();
        return;
      }
      $("pair-error").textContent = t(res.status === 429 ? "err_too_many" : "err_bad_code");
    } catch {
      $("pair-error").textContent = t("err_offline");
    } finally {
      btn.disabled = false;
    }
  }

  // ---------- timer screen ----------
  function setStatus(value) {
    status = value;
    statusAt = performance.now();
    renderTimer();
  }

  function remainingNow() {
    if (!status || status.state !== "running") return 0;
    return Math.max(0, status.remainingSeconds - (performance.now() - statusAt) / 1000);
  }

  const pad = n => String(n).padStart(2, "0");
  function formatDuration(seconds) {
    const s = Math.floor(seconds), h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60);
    return h > 0 ? `${pad(h)}:${pad(m)}:${pad(s % 60)}` : `${pad(m)}:${pad(s % 60)}`;
  }

  function zoneColor(seconds) {
    if (!settings) return "#fff";
    const minutes = seconds / 60;
    if (minutes <= settings.redThreshold) return settings.colorRed;
    if (minutes <= settings.yellowThreshold) return settings.colorYellow;
    return settings.colorGreen;
  }

  function renderCountdown() {
    const cd = $("countdown");
    if (!status) { cd.textContent = "00:00"; cd.className = "countdown idle"; cd.style.color = ""; return; }
    if (status.state === "running") {
      const left = remainingNow();
      cd.textContent = formatDuration(left);
      cd.classList.remove("idle");
      cd.style.color = zoneColor(left);
    } else if (status.state === "expired") {
      cd.textContent = "00:00";
      cd.classList.remove("idle");
      cd.style.color = settings ? settings.colorRed : "";
    } else {
      cd.textContent = "00:00";
      cd.classList.add("idle");
      cd.style.color = "";
    }
  }

  function renderTimer() {
    const running = !!status && status.state === "running";
    $("state").textContent = status ? t("state_" + status.state) : "";
    if (running && status.targetTime) {
      const end = new Date(status.targetTime).toLocaleTimeString(lang, { hour: "2-digit", minute: "2-digit" });
      $("target").textContent = t("endsThen", end, t("act_" + status.action));
    } else {
      $("target").textContent = "";
    }
    $("running-actions").hidden = !running;
    $("start-form").hidden = running || !status;
    renderCountdown();
  }
  setInterval(renderCountdown, 250);

  $("stop").addEventListener("click", async () => {
    if (!confirm(t("confirmStop"))) return;
    try { setStatus(await api("POST", "/api/timer/stop")); } catch (e) { if (e.status !== 401) toast(errorText(e)); }
  });

  async function extend(minutes) {
    if (minutes < 0 && remainingNow() <= -minutes * 60 &&
        !confirm(t("confirmEnd", t("act_" + status.action)))) return;
    try { setStatus(await api("POST", "/api/timer/extend", { minutes })); } catch (e) { if (e.status !== 401) toast(errorText(e)); }
  }
  $("minus").addEventListener("click", () => extend(-15));
  $("plus").addEventListener("click", () => extend(15));

  // start form
  function setMode(value) {
    mode = value;
    document.querySelectorAll(".segmented button").forEach(b =>
      b.setAttribute("aria-checked", String(b.dataset.mode === value)));
    $("h-label").textContent = t(value === "interval" ? "hours" : "hour");
    $("presets").hidden = value !== "interval";
    $("start-error").textContent = "";
    const last = loadLast()[value];
    $("start-h").value = last.h;
    $("start-m").value = pad(last.m);
  }

  function loadLast() {
    const fallback = { interval: { h: 1, m: 0 }, absolute: { h: 20, m: 0 }, mode: "interval" };
    try { return Object.assign(fallback, JSON.parse(store.get(KEY.last) || "{}")); } catch { return fallback; }
  }

  document.querySelectorAll(".segmented button").forEach(b => b.addEventListener("click", () => setMode(b.dataset.mode)));
  document.querySelectorAll("#presets .chip").forEach(c => c.addEventListener("click", () => {
    $("start-h").value = c.dataset.h;
    $("start-m").value = pad(c.dataset.m);
  }));

  $("start-form").addEventListener("submit", async e => {
    e.preventDefault();
    const h = parseInt($("start-h").value || "0", 10), m = parseInt($("start-m").value || "0", 10);
    const valid = Number.isInteger(h) && Number.isInteger(m) && h >= 0 && h <= 23 && m >= 0 && m <= 59
      && !(mode === "interval" && h === 0 && m === 0);
    if (!valid) { $("start-error").textContent = t("err_invalid_time"); return; }
    $("start-error").textContent = "";
    try {
      setStatus(await api("POST", "/api/timer/start", { mode, hours: h, minutes: m }));
      const last = loadLast();
      last[mode] = { h, m };
      last.mode = mode;
      store.set(KEY.last, JSON.stringify(last));
    } catch (err) {
      if (err.status !== 401) $("start-error").textContent = errorText(err);
    }
  });

  // ---------- settings screen ----------
  function setSettings(value, initial) {
    settings = value;
    setLang(value.language);
    if (!settingsDirty || initial) fillSettings();
    renderTimer();
  }

  function fillSettings() {
    if (!settings) return;
    settingsDirty = false;
    $("set-action").value = settings.action;
    $("set-language").value = settings.language;
    $("set-green").value = settings.colorGreen.toLowerCase();
    $("set-yellow").value = settings.colorYellow.toLowerCase();
    $("set-red").value = settings.colorRed.toLowerCase();
    $("set-yellow-min").value = settings.yellowThreshold;
    $("set-red-min").value = settings.redThreshold;
    editAlerts = settings.alertPoints.map(a => ({ minutes: a.minutes, enabled: a.enabled }));
    renderAlerts();
    $("settings-msg").textContent = "";
  }

  function renderAlerts() {
    const list = $("alerts");
    list.replaceChildren();
    if (editAlerts.length === 0) list.append(el("li", "empty", t("noAlerts")));
    editAlerts.forEach((alert, i) => {
      const li = el("li");
      const box = el("input");
      box.type = "checkbox";
      box.checked = alert.enabled;
      box.addEventListener("change", () => { alert.enabled = box.checked; settingsDirty = true; });
      const remove = el("button", "icon-btn", "✕");
      remove.type = "button";
      remove.setAttribute("aria-label", t("remove"));
      remove.addEventListener("click", () => { editAlerts.splice(i, 1); settingsDirty = true; renderAlerts(); });
      li.append(box, el("span", "grow", t("alertItem", alert.minutes)), remove);
      list.append(li);
    });
  }

  $("alert-add").addEventListener("click", () => {
    const m = parseInt($("alert-new").value, 10);
    if (!Number.isInteger(m) || m < 1 || m > 1440 || editAlerts.some(a => a.minutes === m)) {
      $("settings-msg").className = "msg err";
      $("settings-msg").textContent = t("err_invalid_alerts");
      return;
    }
    editAlerts.push({ minutes: m, enabled: true });
    editAlerts.sort((a, b) => b.minutes - a.minutes);
    $("alert-new").value = "";
    $("settings-msg").textContent = "";
    settingsDirty = true;
    renderAlerts();
  });

  $("settings-form").addEventListener("input", () => { settingsDirty = true; $("settings-msg").textContent = ""; });

  $("settings-form").addEventListener("submit", async e => {
    e.preventDefault();
    const msg = $("settings-msg");
    try {
      const saved = await api("PUT", "/api/settings", {
        action: $("set-action").value,
        language: $("set-language").value,
        colorGreen: $("set-green").value,
        colorYellow: $("set-yellow").value,
        colorRed: $("set-red").value,
        yellowThreshold: parseInt($("set-yellow-min").value, 10),
        redThreshold: parseInt($("set-red-min").value, 10),
        alertPoints: editAlerts
      });
      settingsDirty = false;
      setSettings(saved, true);
      msg.className = "msg ok";
      msg.textContent = t("saved");
    } catch (err) {
      if (err.status === 401) return;
      msg.className = "msg err";
      msg.textContent = errorText(err);
    }
  });

  // ---------- devices screen ----------
  let myDeviceId = null;

  async function loadDevices() {
    let devices;
    try { devices = await api("GET", "/api/devices"); } catch (e) { if (e.status !== 401) toast(errorText(e)); return; }
    const list = $("devices");
    list.replaceChildren();
    devices.forEach(d => {
      if (d.current) myDeviceId = d.id;
      const li = el("li");
      const info = el("div", "grow");
      const name = el("div", "name", d.name);
      if (d.current) name.append(el("span", "badge", t("thisPhone")));
      const seen = new Date(d.lastSeen).toLocaleString(lang, { dateStyle: "short", timeStyle: "short" });
      info.append(name, el("div", "sub", t("lastSeen", seen)));
      li.append(info);
      if (!d.current) {
        const remove = el("button", "icon-btn needs-online", "✕");
        remove.setAttribute("aria-label", t("remove"));
        remove.addEventListener("click", async () => {
          if (!confirm(t("confirmRemove", d.name))) return;
          try { await api("DELETE", "/api/devices/" + encodeURIComponent(d.id)); loadDevices(); }
          catch (e) { if (e.status !== 401) toast(errorText(e)); }
        });
        li.append(remove);
      }
      list.append(li);
    });
  }

  $("forget").addEventListener("click", async () => {
    if (!confirm(t("confirmDisconnect"))) return;
    try { if (myDeviceId) await api("DELETE", "/api/devices/" + encodeURIComponent(myDeviceId)); } catch { /* unpair anyway */ }
    unpair("");
  });

  // ---------- add to home screen ----------
  // Over plain HTTP there is no install API, so this card explains the browser menu. The beforeinstallprompt
  // button only appears if the app is ever served over HTTPS (e.g. a future relay); iOS never has one.
  let installPrompt = null;
  const isIos = /iPhone|iPad|iPod/.test(navigator.userAgent) ||
    (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  const isStandalone = () =>
    matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;

  function renderInstall() {
    const card = $("install-card");
    if (isStandalone()) { card.hidden = true; return; }
    card.hidden = false;
    $("install").hidden = !installPrompt;
    $("install-hint").textContent = installPrompt ? "" : t(isIos ? "installIos" : "installAndroid");
  }

  window.addEventListener("beforeinstallprompt", e => {
    e.preventDefault(); // show our own button instead of the mini-infobar
    installPrompt = e;
    renderInstall();
  });
  window.addEventListener("appinstalled", () => { installPrompt = null; renderInstall(); });
  $("install").addEventListener("click", async () => {
    if (!installPrompt) return;
    const prompt = installPrompt;
    installPrompt = null;
    prompt.prompt();
    await prompt.userChoice.catch(() => null);
    renderInstall();
  });

  // ---------- boot ----------
  const ua = navigator.userAgent;
  $("pair-name").value = /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) ? "iPad" : /Android/.test(ua) ? "Android" : "Phone";
  applyTexts();
  setMode(loadLast().mode === "absolute" ? "absolute" : "interval");

  const code = new URLSearchParams(location.search).get("code");
  if (code || location.pathname !== "/") history.replaceState(null, "", "/"); // keep the one-time code out of history
  if (code) {
    showPair(code);
  } else if (!token) {
    showPair(null);
  } else {
    show("timer");
    setOnline(false);
    renderTimer();
    connect();
  }
})();
