# GameTimer Child — the agent on the child's phone

Phase C, steps C2 (connect) and C3 (limit). The child's Android phone becomes a device the parent app
controls, like the Windows PC: the same protocol (docs/protocol.md §7.4), the same parent app.

## Limits (C3)

- **Games**: in the parent app, open the phone → More → **Games** and tick the apps that count as games. Only
  these are limited; calls, messages and school apps always work.
- **Game time**: the timer counts down only while a game is open (the parent app shows "Paused" otherwise).
  When it runs out, the games show a block page until a parent adds time, starts a new timer or stops it.
- **Schedule**: as for the PC — game minutes per day and allowed hours; with the schedule on, each day's
  allowance starts by itself.
- **Ask for more time**: on the block page; the parent answers on their phone (+15 / +30 / No). A "yes" or a
  "+" from a parent is granted even past today's schedule.
- **Blocking needs** Usage access and Display over other apps (the app's Limits card; Android may first need
  App info → ⋮ → Allow restricted settings). Then a game is covered about a second after it opens.
- **Strict mode** (optional, behind the parent PIN): with Device admin and the accessibility service, a game is
  covered the moment it opens, and the settings screens that would switch the app off are closed. Pause it for
  10 minutes with the PIN to change settings.
- **Known gap until C4**: moving the phone's date or time does nothing, but a reboot after moving the date
  starts a fresh day. C4 will tell the parents about clock changes, reboots and switched-off permissions.

## What it does

- **A foreground service** (quiet notification "GameTimer is active") runs the agent: the Kotlin `AgentHost` from
  `shared`, started again after a reboot or an update.
- **Reachable from anywhere**: as soon as a parent's phone is connected, the agent keeps a connection to the relay
  (`gametimer-relay.fly.dev`). "Control from anywhere" is always on for a phone (owner's decision).
- **At home** (Wi-Fi or Ethernet) it also listens on the LAN, port 5123, like the PC. Connecting a parent's phone
  needs both phones on the same Wi-Fi (pairing is LAN-only, protocol §8.2).
- **Parent PIN** (owner's decision): chosen at first start. Connecting or removing a parent's phone, changing the
  PIN and starting over need it — not the phone's own PIN, which the child knows. After the PIN, these stay open for
  5 minutes while the app is on screen. Wrong PINs: 5 tries, then a wait from 30 s up to an hour.
- **Keys stay on the phone**: the agent's private key and the paired parents are sealed with an Android Keystore key
  in app-private storage; backups are off.
- The parent app shows the phone with only the Parents page for now (it hides what the phone doesn't offer yet).
- Interface in English, Russian, Dutch and Ukrainian (the phone's language, or Settings → Apps → GameTimer Child →
  Language on Android 13+).

## Try it (owner's check for C2 + C3)

1. Download `gametimer-child-debug-apk` from the PR's "Android app" CI run (a zip: unzip it) and install it on the
   test phone. Steps 1–6 (connecting) work with the released parent app. Steps 7–9 (limits) need the parent
   app from the same CI run (`gametimer-parent-debug-apk`, it has the Games page): it can't be installed over the
   released one (a different signing key), so uninstall that first and pair the PC again afterwards — or use
   another phone as the parent's.
2. On the test phone: open GameTimer Child, choose a parent PIN, allow notifications, and tap **Allow** for running
   without battery restrictions.
3. With both phones on the same Wi-Fi: **Connect a parent's phone** → PIN → a QR code. In the parent app: **Add** →
   scan → it shows a 6-digit code → type it on the test phone. The test phone lists the parent's phone.
4. In the parent app the phone is online. Then turn Wi-Fi off on the test phone (mobile data): after a few seconds it
   is online again through the relay. And the other way round: the parent's phone on mobile data.
5. Reboot the test phone without opening the app: the parent app sees it online again.
6. Remove the parent's phone on the test phone (PIN): the parent app says it was disconnected.

7. Limits: in the parent app → the phone → More → Games, tick a game; Status → start 2 minutes. On the test
   phone grant the two permissions (Limits card, PIN). Open the game: the parent app shows "Running"; leave
   it: "Paused". After 2 minutes of play the game shows "Time's up"; tap "Ask for more time" and answer +15 on
   the parent's phone: the block page closes.
8. Schedule: set today's limit to a few minutes and check it starts by itself and blocks when used up.
9. Strict mode: grant Device admin and the accessibility service, turn it on (PIN), then try as the child to
   open App info → Force stop / Uninstall: you're sent to the home screen. Pause (PIN) to change settings.

Things to note in the report: anything that didn't connect, how long it took to come back online after a network
change or a reboot, and whether the phone stays reachable after an hour in the pocket (battery manager).

## Ways back

- Forgotten PIN: Settings → Apps → GameTimer Child → Storage → Clear data (the parents' phones then have to connect
  again; a child can do the same, which C4 will report to the parents).
- Each CI run signs with its own debug key: uninstall before installing a newer build.

---

# GameTimer Child — агент на телефоне ребёнка (перевод)

Фаза C, шаги C2 (подключение) и C3 (ограничения). Телефон ребёнка становится устройством, которым управляет
родительское приложение, как ПК: тот же протокол и то же приложение.

## Ограничения (C3)

- **Игры**: в родительском приложении откройте телефон → «Ещё» → **«Игры»** и отметьте приложения, которые считаются
  играми. Ограничиваются только они; звонки, сообщения и школьные приложения работают всегда.
- **Игровое время**: таймер идёт только пока открыта игра (иначе родительское приложение показывает «На паузе»).
  Когда время вышло, игры закрываются страницей блокировки, пока родитель не добавит время, не запустит новый таймер или
  не остановит его.
- **Расписание**: как для ПК — минуты игр на день и разрешённые часы; при включённом расписании дневной лимит
  запускается сам.
- **Попросить ещё времени**: на странице блокировки; родитель отвечает на своём телефоне (+15 / +30 / Нет). «Да» или
  «+» от родителя даётся даже сверх сегодняшнего расписания.
- **Для блокировки нужны** доступ к истории использования и показ поверх других приложений (карточка «Ограничения»;
  Android может сначала потребовать «О приложении» → ⋮ → «Разрешить ограниченные настройки»). Тогда игра закрывается
  примерно через секунду после открытия.
- **Строгий режим** (по желанию, под родительским PIN): с администратором устройства и спецвозможностями игра
  закрывается в момент открытия, а экраны настроек, которыми можно отключить приложение, закрываются. Чтобы поменять
  настройки, поставьте его на паузу на 10 минут (PIN).
- **Известная дыра до C4**: перевод даты или времени на телефоне ничего не даёт, но перезагрузка после перевода
  даты начинает новый день. C4 будет сообщать родителям о смене часов, перезагрузках и отключённых разрешениях.

## Что умеет

- **Служба на переднем плане** (тихое уведомление «GameTimer работает на этом телефоне») держит агента; после
  перезагрузки или обновления запускается сама.
- **Доступен отовсюду**: как только подключён телефон родителя, агент держит соединение с relay. «Управление из любого
  места» на телефоне всегда включено (твоё решение).
- **Дома** (Wi-Fi или Ethernet) он ещё и слушает домашнюю сеть, порт 5123, как ПК. Для подключения телефона родителя
  оба телефона должны быть в одной сети Wi-Fi.
- **Родительский PIN** (твоё решение): задаётся при первом запуске. Он нужен, чтобы подключить или удалить телефон
  родителя, сменить PIN и начать заново — не PIN самого телефона, который ребёнок знает. После ввода всё это открыто
  5 минут, пока приложение на экране. Неверный PIN: 5 попыток, потом ожидание от 30 с до часа.
- **Ключи остаются на телефоне**: закрытый ключ агента и список родителей зашифрованы ключом Android Keystore,
  резервные копии отключены.
- Родительское приложение пока показывает для телефона только страницу родителей (скрывает то, чего телефон ещё не умеет).
- Интерфейс на английском, русском, нидерландском и украинском.

## Как проверить (твоя проверка C2 + C3)

1. Скачай `gametimer-child-debug-apk` из CI-прогона «Android app» в PR (это zip — распакуй) и установи на тестовый
   телефон. Шаги 1–6 (подключение) работают с выпущенным родительским приложением. Для шагов 7–9 (ограничения) нужно
   родительское приложение из того же прогона (`gametimer-parent-debug-apk`, в нём есть страница «Игры»): поверх
   выпущенного оно не ставится (другой ключ подписи), поэтому сначала удали выпущенное и потом снова подключи ПК — или
   возьми другой телефон в роли родительского.
2. На тестовом телефоне: открой GameTimer Child, задай родительский PIN, разреши уведомления и нажми **Разрешить** для
   работы без ограничений батареи.
3. Оба телефона в одной сети Wi-Fi: **Подключить телефон родителя** → PIN → QR-код. В родительском приложении:
   **Добавить** → отсканировать → оно покажет 6-значный код → введи его на тестовом телефоне. Тестовый телефон покажет
   телефон родителя в списке.
4. В родительском приложении телефон в сети. Выключи Wi-Fi на тестовом телефоне (мобильный интернет): через несколько
   секунд он снова в сети, уже через relay. И наоборот: телефон родителя на мобильном интернете.
5. Перезагрузи тестовый телефон, не открывая приложение: родительское приложение снова видит его в сети.
6. Удали телефон родителя на тестовом телефоне (PIN): родительское приложение сообщит, что отключено.

7. Ограничения: в родительском приложении → телефон → «Ещё» → «Игры» отметь игру; «Статус» → запустить 2 минуты. На
   тестовом телефоне выдай два разрешения (карточка «Ограничения», PIN). Открой игру: родительское приложение
   показывает «Идёт»; выйди из неё — «На паузе». После 2 минут игры появится «Время вышло»; нажми «Попросить ещё
   времени» и ответь +15 на телефоне родителя — страница блокировки закроется.
8. Расписание: поставь на сегодня лимит в несколько минут и проверь, что он запускается сам и блокирует, когда
   израсходован.
9. Строгий режим: выдай администратора устройства и спецвозможности, включи его (PIN), затем как ребёнок попробуй
   открыть «О приложении» → «Остановить» / «Удалить»: тебя выкинет на домашний экран. Пауза (PIN) — чтобы поменять
   настройки.

Что записать в отчёт: что не подключилось, сколько времени занимает возврат в сеть после смены сети или перезагрузки,
и остаётся ли телефон доступен через час в кармане (менеджер батареи).

## Как откатиться

- Забыт PIN: Настройки → Приложения → GameTimer Child → Память → Очистить данные (телефоны родителей придётся подключить
  заново; ребёнок может сделать то же самое — об этом C4 будет сообщать родителям).
- Каждый CI-прогон подписывает своим отладочным ключом: перед установкой новой сборки удали старую.
