# GameTimer Child — the agent on the child's phone

Phase C, step C2. The child's Android phone becomes a device the parent app controls, like the Windows PC:
the same protocol (docs/protocol.md), the same parent app. In this step the phone **connects and reports**;
limiting games comes in C3 (the C0 spike's blocking code is kept in `spike/` for that).

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

## Try it (owner's check for C2)

1. Download `gametimer-child-debug-apk` from the PR's "Android app" CI run (a zip: unzip it) and install it on the
   test phone. The parent app needs no update: the released one pairs with the phone too (it just labels things
   "PC" and shows tabs the phone doesn't support yet). The debug parent app from the same CI run shows it properly,
   but it can't be installed over the released one (a different signing key).
2. On the test phone: open GameTimer Child, choose a parent PIN, allow notifications, and tap **Allow** for running
   without battery restrictions.
3. With both phones on the same Wi-Fi: **Connect a parent's phone** → PIN → a QR code. In the parent app: **Add** →
   scan → it shows a 6-digit code → type it on the test phone. The test phone lists the parent's phone.
4. In the parent app the phone is online. Then turn Wi-Fi off on the test phone (mobile data): after a few seconds it
   is online again through the relay. And the other way round: the parent's phone on mobile data.
5. Reboot the test phone without opening the app: the parent app sees it online again.
6. Remove the parent's phone on the test phone (PIN): the parent app says it was disconnected.

Things to note in the report: anything that didn't connect, how long it took to come back online after a network
change or a reboot, and whether the phone stays reachable after an hour in the pocket (battery manager).

## Ways back

- Forgotten PIN: Settings → Apps → GameTimer Child → Storage → Clear data (the parents' phones then have to connect
  again; a child can do the same, which C4 will report to the parents).
- Each CI run signs with its own debug key: uninstall before installing a newer build.

---

# GameTimer Child — агент на телефоне ребёнка (перевод)

Фаза C, шаг C2. Телефон ребёнка становится устройством, которым управляет родительское приложение, как ПК: тот же
протокол и то же приложение. В этом шаге телефон **подключается и сообщает о себе**; ограничение игр появится в C3
(код блокировки из спайка C0 сохранён в `spike/`).

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

## Как проверить (твоя проверка C2)

1. Скачай `gametimer-child-debug-apk` из CI-прогона «Android app» в PR (это zip — распакуй) и установи на тестовый
   телефон. Родительское приложение обновлять не обязательно: выпущенная версия тоже подключается к телефону (только
   называет его «ПК» и показывает вкладки, которых телефон пока не поддерживает). Отладочное родительское приложение из
   того же прогона показывает всё правильно, но поверх выпущенного не ставится (другой ключ подписи).
2. На тестовом телефоне: открой GameTimer Child, задай родительский PIN, разреши уведомления и нажми **Разрешить** для
   работы без ограничений батареи.
3. Оба телефона в одной сети Wi-Fi: **Подключить телефон родителя** → PIN → QR-код. В родительском приложении:
   **Добавить** → отсканировать → оно покажет 6-значный код → введи его на тестовом телефоне. Тестовый телефон покажет
   телефон родителя в списке.
4. В родительском приложении телефон в сети. Выключи Wi-Fi на тестовом телефоне (мобильный интернет): через несколько
   секунд он снова в сети, уже через relay. И наоборот: телефон родителя на мобильном интернете.
5. Перезагрузи тестовый телефон, не открывая приложение: родительское приложение снова видит его в сети.
6. Удали телефон родителя на тестовом телефоне (PIN): родительское приложение сообщит, что отключено.

Что записать в отчёт: что не подключилось, сколько времени занимает возврат в сеть после смены сети или перезагрузки,
и остаётся ли телефон доступен через час в кармане (менеджер батареи).

## Как откатиться

- Забыт PIN: Настройки → Приложения → GameTimer Child → Память → Очистить данные (телефоны родителей придётся подключить
  заново; ребёнок может сделать то же самое — об этом C4 будет сообщать родителям).
- Каждый CI-прогон подписывает своим отладочным ключом: перед установкой новой сборки удали старую.
