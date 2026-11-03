# Додатковий Android/AirPlay ресерч · 28.10.2026

## 1. Рішення, які порівнювалися

| Рішення | Перевага | Обмеження / висновок |
|---|---|---|
| centuryplay v2.1.2 | Kotlin/Android, AirPlay 2, локальний мережевий пошук, MediaRoute2 і QS tile, доступний код | Неофіційний AirPlay 2; можливі збої після прошивки HomePod; обраний рушій |
| Airvia (jazzy1133) | Альтернативний відкритий AirPlay 2 клієнт | Потрібні окреме портування UX і тести; резервний рушій |
| AirMusic | Підтверджено роботу на цьому Samsung із HomePod; підтримує YouTube Music | Пропрієтарний код, не можна просто скопіювати рушій; наш орієнтир у поведінці |
| OwnTone/AirConnect | Стабільний шлях для домашніх серверів у деяких інсталяціях | Не відповідає вимозі без додаткового сервера |

## 2. Прогалини v0.5, які виправлені

1. **Зупинка:** v0.5 не мала кнопки Stop на головному екрані. Додано керування через `StreamController.stopAll()`.
2. **Гучність:** додано SeekBar, збереження значення та керування гучністю активного HomePod через наявний StreamController.
3. **Перепошук:** є явна кнопка `repository.refresh()` без перевстановлення застосунку.
4. **Необов'язкові сповіщення:** у вихідному centuryplay дозволи `POST_NOTIFICATIONS` входили до `hasRuntimePermissions` і до шлюзу MediaProjection. На Android 13+ сповіщення не є необхідною умовою запуску Foreground Service. Вилучено з блокувальних умов, але залишено оголошеним у manifest.
5. **Повторне натискання:** коли вибраний HomePod уже відтворює, запуск лише відкриває YouTube Music, не запускаючи другий дозвіл захоплення.
6. **Безпечне обрання:** якщо обраний HomePod не знайдено, інша колонка не використовується замість нього.
7. **Дозвіл Android:** оминаємо привілейований шлях Shizuku; використовуємо системний MediaProjection і його звичайне підтвердження.
8. **Регресії UI:** додано Android UI smoke-тести для майбутнього CI-прогону на емуляторі, крім локальних JVM тестів.

## 3. Обмеження та ризики, які не знімаються зміною UI

- **Google Cast ≠ AirPlay:** HomePod не стане звичайним Google Cast-приймачем у внутрішньому меню YouTube Music без принципово іншого bridge (і його сумісність не гарантована). MediaRouter2 може показувати маршрут в Android системному перемикачі аудіо, але не у Google Cast списку.
- **Політика захоплення:** Android AudioPlaybackCapture потребує RECORD_AUDIO, MediaProjection з підтвердженням, однакового профілю користувача, `USAGE_MEDIA/GAME/UNKNOWN` та дозволу захоплення з боку YouTube Music. Фільтр по UID працює як додаткове обмеження; не обходить DRM чи політики застосунку.
- **Foreground service:** Android 14+ вимагає оголошеного типу `mediaProjection` і спеціального дозволу; Android 15+ обмежує автозапуск з BOOT_COMPLETED. Підключення після завантаження телефона не повинно стартувати непомітно.
- **Samsung One UI:** sleeping/deep sleeping може блокувати тло, потрібно додати застосунок до Never sleeping apps, якщо є обриви; це впливає на батарею.
- **LAN/mDNS:** телефон і HomePod повинні бачити один одного через локальну Wi-Fi мережу. Гостьова Wi-Fi з client isolation, різні VLAN, VPN чи суворий multicast-фільтр можуть блокувати пошук. Сервіс використовує mDNS/Bonjour.
- **Android 17:** при targetSdk 37+ з'являється окремий дозвіл ACCESS_LOCAL_NETWORK для широкого доступу до LAN. Поточний проєкт використовує targetSdk 35; при майбутньому підвищенні target SDK потрібен новий аудит.
- **Затримка й розсинхронізація:** AirPlay 2 зазвичай буферизує звук. Відео/ігри з малою затримкою не є метою цього продукту.
- **Коди PIN/підтвердження:** під час pairing HomePod може потребувати коду або підтвердження. Використовуємо розширену сторінку рушія, а не вигаданий автоматичний обхід.
- **Фізична перевірка:** мова лише про підготовлений код і тести без реального HomePod; очікується проба аудіо, Wi-Fi roaming, блокування екрана та оновлень HomePod.

## 4. Першоджерела

- Google Android Playback Capture: https://developer.android.com/media/platform/av-capture
- Android AudioPlaybackCaptureConfiguration: https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration
- Google Android MediaProjection: https://developer.android.com/media/grow/media-projection
- Android Foreground Service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android notification permission: https://developer.android.com/develop/ui/compose/notifications/notification-permission
- Android routing: https://developer.android.com/media/routing
- Android NsdManager: https://developer.android.com/reference/android/net/nsd/NsdManager
- Android local network permission: https://developer.android.com/privacy-and-security/local-network-permission
- Samsung sleeping apps: https://developer.samsung.com/mobile/app-management.html
- AirMusic: https://www.airmusic.app/
- centuryplay v2.1.2 source and license: https://github.com/g8row/centuryplay/releases/tag/v2.1.2
- Airvia: https://github.com/jazzy1133/airvia
