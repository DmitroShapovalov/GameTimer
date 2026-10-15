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
      tabTimer: "Timer", tabSettings: "Settings", tabDevices: "Phones", tabHistory: "History",
      historyEmpty: "No events yet",
      ev_started: "Started", ev_stopped: "Stopped", ev_reset: "Reset",
      ev_expired: "Time up", ev_resumed: "Resumed after restart", ev_closed: "App closed",
      ev_plus: "+{0} min", ev_minus: "−{0} min",
      who_pc: "PC", who_system: "automatic",
      mode_interval: "interval", mode_absolute: "until time",
      connecting: "Connecting to the PC…",
      offline: "PC unreachable. Reconnecting… Check that this phone is on the same Wi-Fi.",
      closedWhileRunning: "GameTimer was closed on the PC at {0} while a timer was running, and resumed when it started again.",
      gotIt: "Got it",
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
      err_invalid_schedule: "Check the schedule values (0–1440).",
      err_generic: "Something went wrong ({0}).",
      schedToday: "Schedule: {0} min left today",
      schedBlk_window: "Schedule: outside the allowed hours now",
      schedBlk_day: "Schedule: gaming is off today",
      schedBlk_limit: "Schedule: today's time budget is used up",
      schedTitle: "Daily schedule",
      schedHint: "Per weekday: a daily limit (minutes; 0 = none) and the allowed hours (24-hour, e.g. 15:00). Managed from the PC too.",
      schedEnable: "Enable schedule",
      schedCol_day: "Day", schedCol_on: "On", schedCol_min: "Min", schedCol_from: "From", schedCol_to: "To"
    },
    ru: {
      tabTimer: "Таймер", tabSettings: "Настройки", tabDevices: "Телефоны", tabHistory: "История",
      historyEmpty: "Пока нет событий",
      ev_started: "Запуск", ev_stopped: "Остановлен", ev_reset: "Сброс",
      ev_expired: "Время вышло", ev_resumed: "Продолжен после перезапуска", ev_closed: "Приложение закрыто",
      ev_plus: "+{0} мин", ev_minus: "−{0} мин",
      who_pc: "ПК", who_system: "автоматически",
      mode_interval: "интервал", mode_absolute: "до времени",
      connecting: "Подключение к ПК…",
      offline: "ПК недоступен. Переподключение… Проверьте, что телефон в той же сети Wi-Fi.",
      closedWhileRunning: "GameTimer на ПК был закрыт в {0}, пока шёл таймер, и продолжился при следующем запуске.",
      gotIt: "Понятно",
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
      err_invalid_schedule: "Проверьте значения расписания (0–1440).",
      err_generic: "Что-то пошло не так ({0}).",
      schedToday: "Расписание: сегодня осталось {0} мин",
      schedBlk_window: "Расписание: сейчас вне разрешённых часов",
      schedBlk_day: "Расписание: сегодня играть нельзя",
      schedBlk_limit: "Расписание: дневной лимит исчерпан",
      schedTitle: "Дневное расписание",
      schedHint: "Для каждого дня: дневной лимит (минуты; 0 = без лимита) и разрешённые часы (24-ч, напр. 15:00). Также настраивается на ПК.",
      schedEnable: "Включить расписание",
      schedCol_day: "День", schedCol_on: "Вкл", schedCol_min: "Мин", schedCol_from: "С", schedCol_to: "До"
    },
    nl: {
      tabTimer: "Timer",
      tabSettings: "Instellingen",
      tabDevices: "Telefoons",
      tabHistory: "Geschiedenis",
      historyEmpty: "Nog geen gebeurtenissen",
      ev_started: "Gestart",
      ev_stopped: "Gestopt",
      ev_reset: "Gereset",
      ev_expired: "Tijd om",
      ev_resumed: "Hervat na herstart",
      ev_closed: "App gesloten",
      ev_plus: "+{0} min",
      ev_minus: "−{0} min",
      who_pc: "Pc",
      who_system: "automatisch",
      mode_interval: "interval",
      mode_absolute: "tot tijdstip",
      connecting: "Verbinden met de pc…",
      offline: "Pc onbereikbaar. Opnieuw verbinden… Controleer of deze telefoon op dezelfde wifi zit.",
      closedWhileRunning: "GameTimer is op de pc om {0} gesloten terwijl een timer liep, en hervat bij de volgende start.",
      gotIt: "Begrepen",
      offlineLong: "Pc nog steeds onbereikbaar. Als hij aan staat en deze telefoon op dezelfde wifi zit, heeft de pc misschien een nieuw adres: open op de pc Instellingen → Telefoon verbinden en scan de nieuwe QR-code.",
      state_running: "Loopt",
      state_stopped: "Gestopt",
      state_notStarted: "Niet gestart",
      state_expired: "Tijd is om",
      endsThen: "Eindigt om {0}, daarna {1}",
      act_shutdown: "wordt de pc afgesloten",
      act_lock: "wordt het scherm vergrendeld",
      act_logoff: "wordt de gebruiker afgemeld",
      stop: "Stop",
      start: "Start",
      modeInterval: "Duur",
      modeAbsolute: "Tot tijdstip",
      hours: "Uren",
      hour: "Uur",
      minutes: "Minuten",
      confirmStop: "De timer stoppen?",
      confirmEnd: "Minder dan 15 minuten over: de timer eindigt nu en {0}. Doorgaan?",
      action: "Als de tijd om is",
      action_shutdown: "De pc afsluiten",
      action_lock: "Het scherm vergrendelen",
      action_logoff: "Afmelden",
      language: "Taal (ook op de pc)",
      colors: "Kleuren van het aftellen",
      colorsHint: "De kleur verandert als er nog zoveel minuten of minder over zijn.",
      zoneNormal: "Normaal",
      zoneWarning: "Waarschuwing",
      zoneAlert: "Alarm",
      minLeft: "min",
      alerts: "Geluidsmeldingen op de pc",
      minBeforeEnd: "min voor het einde",
      alertItem: "{0} min voor het einde",
      add: "Toevoegen",
      remove: "Verwijderen",
      noAlerts: "Geen meldingen",
      save: "Opslaan",
      saved: "Opgeslagen",
      thisPhone: "deze telefoon",
      lastSeen: "Laatst gezien {0}",
      confirmRemove: "“{0}” verwijderen? De telefoon moet dan opnieuw worden verbonden.",
      disconnectThis: "Deze telefoon loskoppelen",
      confirmDisconnect: "Deze telefoon loskoppelen van de pc?",
      installTitle: "Startscherm",
      install: "App installeren",
      installIos: "Zet GameTimer op het startscherm: Safari → Delen → Zet op beginscherm.",
      installAndroid: "Zet GameTimer op het startscherm: Chrome-menu ⋮ → Toevoegen aan startscherm.",
      pairIntro: "Open op de pc GameTimer → Instellingen → Telefoon verbinden en scan de QR-code met deze telefoon.",
      pairWithCode: "Geef deze telefoon een naam en verbind hem met de pc.",
      phoneName: "Naam van de telefoon",
      connect: "Verbinden",
      revoked: "Deze telefoon is op de pc losgekoppeld.",
      err_bad_code: "De code is ongeldig of verlopen. Maak een nieuwe op de pc.",
      err_too_many: "Te veel pogingen. Wacht een minuut.",
      err_offline: "Pc onbereikbaar.",
      err_invalid_time: "Controleer de tijd.",
      err_already_running: "De timer loopt al.",
      err_not_running: "De timer loopt niet.",
      err_invalid_minutes: "Ongeldig aantal minuten.",
      err_invalid_threshold: "Kleuren: minuten moeten 1–1440 zijn.",
      err_invalid_alerts: "Meldingen: 1–1440 minuten, geen duplicaten.",
      err_invalid_color: "Ongeldige kleur.",
      err_invalid_language: "Onbekende taal.",
      err_invalid_schedule: "Controleer de schemawaarden (0–1440).",
      err_generic: "Er ging iets mis ({0}).",
      schedToday: "Schema: vandaag nog {0} min",
      schedBlk_window: "Schema: nu buiten de toegestane uren",
      schedBlk_day: "Schema: vandaag mag er niet gespeeld worden",
      schedBlk_limit: "Schema: het tijdbudget van vandaag is op",
      schedTitle: "Dagschema",
      schedHint: "Per weekdag: een daglimiet (minuten; 0 = geen) en de toegestane uren (24-uurs, bijv. 15:00). Ook op de pc te beheren.",
      schedEnable: "Schema inschakelen",
      schedCol_day: "Dag",
      schedCol_on: "Aan",
      schedCol_min: "Min",
      schedCol_from: "Van",
      schedCol_to: "Tot"
    },
    uk: {
      tabTimer: "Таймер",
      tabSettings: "Налаштування",
      tabDevices: "Телефони",
      tabHistory: "Історія",
      historyEmpty: "Подій поки немає",
      ev_started: "Запущено",
      ev_stopped: "Зупинено",
      ev_reset: "Скинуто",
      ev_expired: "Час вийшов",
      ev_resumed: "Відновлено після перезапуску",
      ev_closed: "Програму закрито",
      ev_plus: "+{0} хв",
      ev_minus: "−{0} хв",
      who_pc: "ПК",
      who_system: "автоматично",
      mode_interval: "інтервал",
      mode_absolute: "до часу",
      connecting: "Підключення до ПК…",
      offline: "ПК недоступний. Повторне підключення… Перевірте, що телефон у тій самій мережі Wi-Fi.",
      closedWhileRunning: "GameTimer на ПК було закрито о {0}, поки працював таймер, і відновлено після наступного запуску.",
      gotIt: "Зрозуміло",
      offlineLong: "ПК усе ще недоступний. Якщо він увімкнений, а телефон у тій самій мережі Wi-Fi, можливо, у ПК змінилася адреса: на ПК відкрийте «Налаштування» → «Підключити телефон» і відскануйте новий QR-код.",
      state_running: "Працює",
      state_stopped: "Зупинено",
      state_notStarted: "Не запущено",
      state_expired: "Час вийшов",
      endsThen: "Закінчиться о {0}, потім {1}",
      act_shutdown: "ПК вимкнеться",
      act_lock: "екран заблокується",
      act_logoff: "буде виконано вихід із системи",
      stop: "Стоп",
      start: "Старт",
      modeInterval: "Тривалість",
      modeAbsolute: "До часу",
      hours: "Години",
      hour: "Година",
      minutes: "Хвилини",
      confirmStop: "Зупинити таймер?",
      confirmEnd: "Залишилося менше ніж 15 хвилин: таймер завершиться зараз, і {0}. Продовжити?",
      action: "Коли час вийде",
      action_shutdown: "Вимкнути ПК",
      action_lock: "Заблокувати екран",
      action_logoff: "Вийти з системи",
      language: "Мова (і на ПК)",
      colors: "Кольори відліку",
      colorsHint: "Колір змінюється, коли до кінця залишається стільки хвилин або менше.",
      zoneNormal: "Звичайний",
      zoneWarning: "Попередження",
      zoneAlert: "Тривога",
      minLeft: "хв",
      alerts: "Звукові сповіщення на ПК",
      minBeforeEnd: "хв до кінця",
      alertItem: "за {0} хв до кінця",
      add: "Додати",
      remove: "Видалити",
      noAlerts: "Немає сповіщень",
      save: "Зберегти",
      saved: "Збережено",
      thisPhone: "цей телефон",
      lastSeen: "Востаннє: {0}",
      confirmRemove: "Видалити «{0}»? Його доведеться підключити знову.",
      disconnectThis: "Відключити цей телефон",
      confirmDisconnect: "Відключити цей телефон від ПК?",
      installTitle: "Головний екран",
      install: "Встановити застосунок",
      installIos: "Додайте GameTimer на головний екран: Safari → «Поділитися» → «На екран „Домівка“».",
      installAndroid: "Додайте GameTimer на головний екран: меню Chrome ⋮ → «Додати на головний екран».",
      pairIntro: "На ПК відкрийте GameTimer → «Налаштування» → «Підключити телефон» і відскануйте QR-код цим телефоном.",
      pairWithCode: "Назвіть телефон і підключіть його до ПК.",
      phoneName: "Назва телефона",
      connect: "Підключити",
      revoked: "Цей телефон відключено на ПК.",
      err_bad_code: "Код неправильний або термін його дії минув. Створіть новий на ПК.",
      err_too_many: "Забагато спроб. Зачекайте хвилину.",
      err_offline: "ПК недоступний.",
      err_invalid_time: "Перевірте час.",
      err_already_running: "Таймер уже запущено.",
      err_not_running: "Таймер не запущено.",
      err_invalid_minutes: "Неправильна кількість хвилин.",
      err_invalid_threshold: "Кольори: хвилини мають бути 1–1440.",
      err_invalid_alerts: "Сповіщення: 1–1440 хвилин, без повторів.",
      err_invalid_color: "Неправильний колір.",
      err_invalid_language: "Невідома мова.",
      err_invalid_schedule: "Перевірте значення розкладу (0–1440).",
      err_generic: "Щось пішло не так ({0}).",
      schedToday: "Розклад: сьогодні залишилося {0} хв",
      schedBlk_window: "Розклад: зараз поза дозволеними годинами",
      schedBlk_day: "Розклад: сьогодні грати не можна",
      schedBlk_limit: "Розклад: денний ліміт часу вичерпано",
      schedTitle: "Денний розклад",
      schedHint: "Для кожного дня: денний ліміт (хвилини; 0 = без ліміту) і дозволені години (24-год, напр. 15:00). Також керується з ПК.",
      schedEnable: "Увімкнути розклад",
      schedCol_day: "День",
      schedCol_on: "Увімк",
      schedCol_min: "Хв",
      schedCol_from: "З",
      schedCol_to: "До"
    }
  };
  let lang = store.get(KEY.lang) || (() => {
    const p = (navigator.language || "").toLowerCase();
    return p.startsWith("ru") ? "ru" : p.startsWith("nl") ? "nl" : p.startsWith("uk") ? "uk" : "en";
  })();
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
  let editSched = null;        // { enabled, days[] } working copy for the editor (days indexed by weekday 0=Sun)
  let scheduleDirty = false;
  const SCHED_ORDER = [1, 2, 3, 4, 5, 6, 0]; // Mon..Sun, mapped to weekday index

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
      case "history":
        if (tab === "history") loadHistory();
        break;
      case "schedule":
        if (msg.status) setStatus(msg.status);
        if (tab === "settings" && !scheduleDirty) loadSchedule();
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
    if (editSched) renderSchedGrid();
    if (tab === "devices") loadDevices();
  }

  // ---------- navigation ----------
  const screens = ["pair", "timer", "settings", "devices", "history"];
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
    if (b.dataset.tab === "history") loadHistory();
    if (b.dataset.tab === "settings") { if (!settingsDirty) fillSettings(); if (!scheduleDirty) loadSchedule(); }
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

  function renderInterruption() {
    const box = $("interruption");
    const at = status && status.lastInterruption;
    if (!at) { box.hidden = true; return; }
    const when = new Date(at).toLocaleString(lang, { dateStyle: "short", timeStyle: "short" });
    $("interruption-text").textContent = t("closedWhileRunning", when);
    box.hidden = false;
  }
  $("interruption-ok").addEventListener("click", async () => {
    try { setStatus(await api("POST", "/api/interruption/ack")); }
    catch (e) { if (e.status !== 401) toast(errorText(e)); }
  });

  function renderTimer() {
    renderInterruption();
    renderSchedule();
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

  // ---------- schedule ----------
  const clampMin = m => Math.max(0, Math.min(1440, m | 0));
  function minutesToClock(m) { m = clampMin(m); return pad(Math.floor(m / 60)) + ":" + pad(m % 60); }
  function clockToMinutes(str, fb) {
    if (str == null) return fb;
    str = String(str).trim();
    if (!str) return fb;
    const parts = str.split(":");
    const h = parseInt(parts[0], 10);
    if (!Number.isInteger(h)) return fb;
    let mm = 0;
    if (parts.length > 1 && parts[1] !== "") {
      mm = parseInt(parts[1], 10);
      if (!Number.isInteger(mm)) return fb;
    }
    return clampMin(h * 60 + mm);
  }
  function dayName(weekday) {
    // 2024-01-07 is a Sunday; + weekday lands on the right day
    const d = new Date(2024, 0, 7 + weekday);
    const n = new Intl.DateTimeFormat(lang, { weekday: "short" }).format(d);
    return n.charAt(0).toUpperCase() + n.slice(1);
  }

  // Timer-screen status chip
  function renderSchedule() {
    const box = $("sched-status");
    const st = status && status.scheduleStatus;
    if (!st || st === "off") { box.hidden = true; return; }
    box.hidden = false;
    box.classList.remove("warn", "ok");
    switch (st) {
      case "allowed":
        box.textContent = t("schedToday", Math.floor((status.scheduleAvailableSeconds || 0) / 60));
        box.classList.add("ok");
        break;
      case "outsideWindow": box.textContent = t("schedBlk_window"); box.classList.add("warn"); break;
      case "dayDisabled": box.textContent = t("schedBlk_day"); box.classList.add("warn"); break;
      case "limitReached": box.textContent = t("schedBlk_limit"); box.classList.add("warn"); break;
      default: box.hidden = true;
    }
  }

  async function loadSchedule() {
    let sched;
    try { sched = await api("GET", "/api/schedule"); } catch (e) { if (e.status !== 401) toast(errorText(e)); return; }
    if (scheduleDirty) return; // don't clobber in-progress edits
    const week = sched.week || [];
    const days = [];
    for (let i = 0; i < 7; i++) {
      const d = week[i] || {};
      days[i] = {
        allowed: d.allowed !== false,
        limit: clampMin(d.limitMinutes | 0),
        from: minutesToClock(d.windowStartMinutes | 0),
        to: minutesToClock(d.windowEndMinutes == null ? 1440 : d.windowEndMinutes)
      };
    }
    editSched = { enabled: !!sched.enabled, days };
    $("sched-enabled").checked = editSched.enabled;
    $("schedule-msg").textContent = "";
    renderSchedGrid();
  }

  function renderSchedGrid() {
    const g = $("sched-grid");
    g.replaceChildren();
    if (!editSched) return;
    ["schedCol_day", "schedCol_on", "schedCol_min", "schedCol_from", "schedCol_to"].forEach(k =>
      g.append(el("span", "sched-h", t(k))));
    SCHED_ORDER.forEach(wd => {
      const day = editSched.days[wd];
      g.append(el("span", "sched-day", dayName(wd)));

      const on = el("input");
      on.type = "checkbox";
      on.checked = day.allowed;
      on.addEventListener("change", () => { day.allowed = on.checked; scheduleDirty = true; });
      g.append(on);

      const lim = el("input", "sched-num");
      lim.type = "number"; lim.inputMode = "numeric"; lim.min = 0; lim.max = 1440; lim.value = day.limit;
      lim.addEventListener("input", () => { day.limit = clampMin(parseInt(lim.value || "0", 10) || 0); scheduleDirty = true; });
      g.append(lim);

      const from = el("input", "sched-time");
      from.type = "text"; from.inputMode = "numeric"; from.value = day.from; from.placeholder = "00:00";
      from.addEventListener("input", () => { day.from = from.value; scheduleDirty = true; });
      g.append(from);

      const to = el("input", "sched-time");
      to.type = "text"; to.inputMode = "numeric"; to.value = day.to; to.placeholder = "24:00";
      to.addEventListener("input", () => { day.to = to.value; scheduleDirty = true; });
      g.append(to);
    });
  }

  $("sched-enabled").addEventListener("change", () => {
    if (editSched) editSched.enabled = $("sched-enabled").checked;
    scheduleDirty = true;
  });

  $("schedule-save").addEventListener("click", async () => {
    if (!editSched) return;
    const week = [];
    for (let i = 0; i < 7; i++) {
      const d = editSched.days[i];
      week[i] = {
        allowed: d.allowed,
        limitMinutes: clampMin(parseInt(d.limit, 10) || 0),
        windowStartMinutes: clockToMinutes(d.from, 0),
        windowEndMinutes: clockToMinutes(d.to, 1440)
      };
    }
    const msg = $("schedule-msg");
    try {
      const saved = await api("PUT", "/api/schedule", { enabled: $("sched-enabled").checked, week });
      scheduleDirty = false;
      await Promise.resolve();
      // refresh from the server's normalized values
      const days = [];
      (saved.week || []).forEach((d, i) => {
        days[i] = {
          allowed: d.allowed !== false,
          limit: clampMin(d.limitMinutes | 0),
          from: minutesToClock(d.windowStartMinutes | 0),
          to: minutesToClock(d.windowEndMinutes == null ? 1440 : d.windowEndMinutes)
        };
      });
      editSched = { enabled: !!saved.enabled, days };
      $("sched-enabled").checked = editSched.enabled;
      renderSchedGrid();
      msg.className = "msg ok";
      msg.textContent = t("saved");
    } catch (e) {
      if (e.status === 401) return;
      msg.className = "msg err";
      msg.textContent = errorText(e);
    }
  });

  // ---------- history screen ----------
  const pad2 = n => String(n).padStart(2, "0");
  function eventText(e) {
    switch (e.type) {
      case "started": {
        const m = e.minutes || 0;
        const dur = m >= 60 ? `${Math.floor(m / 60)}:${pad2(m % 60)}` : `${m} ${t("minLeft")}`;
        return `${t("ev_started")}: ${dur} (${t(e.mode === "absolute" ? "mode_absolute" : "mode_interval")})`;
      }
      case "stopped": return t("ev_stopped");
      case "reset": return t("ev_reset");
      case "extended": return t((e.minutes || 0) >= 0 ? "ev_plus" : "ev_minus", Math.abs(e.minutes || 0));
      case "expired": return `${t("ev_expired")}: ${t("act_" + e.action)}`;
      case "resumed": return t("ev_resumed");
      case "closed": return t("ev_closed");
      default: return e.type;
    }
  }
  const eventWho = e => e.source === "pc" ? t("who_pc") : e.source === "phone" ? (e.device || "") : t("who_system");

  async function loadHistory() {
    let events;
    try { events = await api("GET", "/api/history"); } catch (e) { if (e.status !== 401) toast(errorText(e)); return; }
    const list = $("history");
    list.replaceChildren();
    $("history-empty").hidden = events.length > 0;
    if (events.length === 0) { $("history-empty").textContent = t("historyEmpty"); return; }
    for (const e of events) {
      const li = el("li");
      const top = el("div", "ev-top");
      const when = new Date(e.time).toLocaleString(lang, { dateStyle: "short", timeStyle: "short" });
      top.append(el("span", "ev-when", when), el("span", "ev-who", eventWho(e)));
      li.append(top, el("div", "ev-text", eventText(e)));
      list.append(li);
    }
  }

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
