<div align="center">

# Simple FTP

### Минималистичный FTP-сервер для Android-читалок, адаптированный для PocketBook и eBookSender

[![Релизы](https://img.shields.io/badge/%D0%A0%D0%B5%D0%BB%D0%B8%D0%B7%D1%8B-%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C-2F6FED.svg)](../../releases/latest)
[![Android](https://img.shields.io/badge/Android-6.0%2B-00B0FF.svg?logo=android&logoColor=white)](https://developer.android.com/about/versions/marshmallow)
[![PocketBook](https://img.shields.io/badge/PocketBook-e--ink-2F6FED.svg)](../../releases)

</div>

---

**Simple FTP** запускает локальный FTP-сервер прямо на Android-читалке и показывает крупный QR-код с адресом подключения. Сервер рассчитан на простой сценарий: открыть приложение на читалке, отсканировать QR-код в **eBookSender** и отправить книги в выбранную папку.

GitHub description:

```text
Minimal FTP server for Android-based e-readers, optimized for PocketBook and eBookSender
```

---

## Возможности

- FTP-сервер на порту `2121`.
- Подключение без пароля по адресу вида `ftp://anonymous@<ip>:2121/`.
- Крупный QR-код и текстовый FTP-адрес на экране.
- Выбор папки, которая будет доступна по FTP.
- Запоминание выбранной папки между запусками.
- Пассивный FTP-режим `PASV` и `EPSV`.
- Базовые FTP-команды для работы eBookSender и обычных FTP-клиентов: просмотр каталогов, загрузка файлов, скачивание, переименование, удаление, создание и удаление папок.
- Ограничение доступа выбранной папкой: пути с выходом наружу отбрасываются.
- Монохромный интерфейс без анимаций, рассчитанный на e-ink экран.
- Русский и английский интерфейс.

---

## Как это работает

```text
Android-читалка
    |
    | запуск Simple FTP
    v
FTP-сервер на :2121
    |
    | QR-код с ftp://anonymous@<ip>:2121/
    v
eBookSender или другой FTP-клиент
    |
    v
Файлы загружаются в выбранную папку
```

---

## Установка

### 1. Скачайте APK

Откройте страницу [последнего релиза](../../releases/latest) и скачайте:

```text
simple-ftp-vX.Y.Z.apk
```

### 2. Установите на читалку

Скопируйте APK на Android-читалку и установите его обычным способом. На некоторых устройствах нужно заранее разрешить установку приложений из внешних источников.

### 3. Выдайте доступ к файлам

При первом запуске нажмите **Grant file access** / **Дать доступ к файлам**, если приложение попросит разрешение. Без доступа к файлам сервер не сможет показать папки и принимать книги.

---

## Использование с eBookSender

1. Подключите телефон с eBookSender и читалку к одной Wi-Fi сети.
2. Запустите **Simple FTP** на читалке.
3. Убедитесь, что на экране появился QR-код и FTP-адрес.
4. В eBookSender добавьте FTP-устройство по QR-коду или вручную.
5. Отправьте книги на читалку.

Адрес для ручного подключения выглядит так:

```text
ftp://anonymous@<ip>:2121/
```

Пароль не нужен.

---

## Выбор папки

Кнопка **Path** / **Путь** открывает простой выбор папки. Выбранная папка становится корнем FTP-сервера:

- клиент видит её как `/`;
- файлы не могут быть записаны выше выбранного корня;
- путь сохраняется после закрытия приложения.

Если оставить корнем всё хранилище, FTP-клиент сможет работать со всеми доступными приложению файлами. Для обычного использования лучше выбрать папку книг.

---

## Диагностика

- Если QR-код не появился, проверьте, что Wi-Fi включён и устройство получило локальный IP-адрес.
- Если eBookSender не подключается, убедитесь, что телефон и читалка находятся в одной сети.
- Если используется VPN, отключите его или разрешите локальные подключения.
- Если сеть гостевая, проверьте, не запрещает ли роутер обмен между устройствами.
- Если файлы не записываются, проверьте разрешение на доступ к файлам и выбранную папку.
- Если Android выгружает приложение в фоне, держите Simple FTP открытым на экране во время передачи.

---

## Сборка для разработчиков

### Требования

| Компонент | Версия |
| --- | --- |
| Android SDK Platform | 36 |
| JDK | 17+ |
| Gradle | через wrapper |

### Debug-сборка

```sh
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew :app:assembleDebug
```

APK появится здесь:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Release-сборка

Локальная release-сборка использует параметры подписи из `local.properties`:

```properties
RELEASE_STORE_FILE=release.keystore
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

Команда:

```sh
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew :app:assembleRelease
```

Версию можно переопределить параметрами Gradle:

```sh
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew :app:assembleRelease \
  -PVERSION_NAME=0.1.0 \
  -PVERSION_CODE=1
```

---

## Релизы

При пуше тега вида `vX.Y.Z` workflow `.github/workflows/ci-cd.yml`:

- проверяет Kotlin debug-компиляцию;
- собирает release APK с версией из тега;
- переименовывает APK в `simple-ftp-vX.Y.Z.apk`;
- публикует APK на GitHub Release Page;
- добавляет `CHANGELOG.md` в release notes;
- публикует `updates/latest.json` и changelog-файлы на GitHub Pages для проверки обновлений в приложении.

Приложение проверяет обновления только при запуске. Manifest обновлений публикуется по адресу:

```text
https://cybercat2033.github.io/SimpleFTP/updates/latest.json
```

Для работы автообновления в репозитории должна быть включена GitHub Pages публикация через GitHub Actions.

Для подписанной release-сборки в GitHub Actions нужны secrets:

| Secret | Назначение |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | keystore в base64 |
| `RELEASE_STORE_PASSWORD` | пароль keystore |
| `RELEASE_KEY_ALIAS` | alias ключа |
| `RELEASE_KEY_PASSWORD` | пароль ключа |

---

## Архитектура проекта

| Путь | Назначение |
| --- | --- |
| `app/src/main/java/com/cybercat/simpleftp/MainActivity.kt` | Android entry point и e-ink UI на Jetpack Compose |
| `app/src/main/java/com/cybercat/simpleftp/AppUpdateManager.kt` | проверка manifest обновлений, загрузка APK и запуск системного установщика |
| `app/src/main/java/com/cybercat/simpleftp/MinimalFtpServer.kt` | минимальная реализация FTP-сервера |
| `app/src/main/java/com/cybercat/simpleftp/NetworkAddress.kt` | выбор локального IPv4-адреса для QR-кода |
| `app/src/main/java/com/cybercat/simpleftp/PathRepository.kt` | хранение выбранной FTP-папки через DataStore |
| `app/src/main/java/com/cybercat/simpleftp/QrCodeGenerator.kt` | генерация QR-кода |
| `app/src/main/res/values*/strings.xml` | русские и английские строки интерфейса |

---

## Технологии

| Технология | Для чего используется |
| --- | --- |
| Kotlin | основной язык приложения |
| Jetpack Compose | интерфейс |
| Material 3 | базовые UI-компоненты |
| AndroidX DataStore | хранение выбранной папки |
| ZXing | QR-код FTP-адреса |

---

## Связанные проекты

- **[eBookSender](https://github.com/CyberCat2033/eBookSender)** — Android-приложение для отправки книг, документов и манги на FTP-устройства.
- **[pb-ftp](https://github.com/CyberCat2033/pb-ftp)** — PocketBook launcher/server для устройств PocketBook с Linux-прошивкой.

---

## Ограничения

Simple FTP намеренно остаётся маленьким сервером для локальной сети:

- нет пользователей и паролей;
- нет TLS/FTPS/SFTP;
- нет фонового foreground service;
- нет встроенной индексации библиотеки;
- нет OPDS, скачивания книг или управления eBookSender.

Используйте приложение только в доверенной локальной сети.
