# Развёртывание

## Docker Compose и Traefik

1. Скопируй `.env.example` в `.env`. Укажи домен, ACME email, пароль PostgreSQL, действующий SMTP STARTTLS и адрес отправителя. `BOOTSTRAP_ADMIN_EMAIL` должен быть твоим рабочим email, если администратору потребуется восстановление доступа.
2. Выполни `./scripts/generate-jwt-keys.sh`. Ключи сохраняются в `.local/secrets`, повторный запуск их не заменяет. Сохраняй пару ключей и PostgreSQL volume между обновлениями.
3. Запусти `docker compose up -d --build`. Flyway применит миграции до старта API.
4. В доверенном окружении войди `admin` / `admin` и задай постоянный пароль. Перезапуск не возвращает стандартный пароль.
5. Перед публичным доступом настрой DNS, входящие 80/443, SMTP и проверь резервное копирование PostgreSQL. БД наружу не публикуется.

Runtime JVM работает от пользователя `app`. Entrypoint читает Compose secrets и помещает закрытую копию ключа в tmpfs, чтобы не зависеть от uid/прав файла на хосте. Нельзя помещать ключи в Docker image, frontend env или Git. Production PostgreSQL на внешнем хосте подключается с `sslmode=verify-full` и доверенным сертификатом; внутренний Docker transport не публикуется.

Проверки готовности: `/api/health` и `/api/ready`. Настоящее письмо тестируется через административный сброс аккаунта с контролируемым email. Успешный ответ означает, что SMTP принял отправку, а не гарантированную доставку в почтовый ящик.

## Frontend в Sites

Публикацию в Sites выполняет владелец. Канонический frontend находится в `web/`; Site ID сохранён в `web/.openai/hosting.json`. Старый безсборочный HTML-прототип удалён.

Sites размещает статический Vue frontend; JVM/Scala и контейнер PostgreSQL должны работать на отдельном сервере. Данные и секреты не переносятся в браузерный проект. Само размещение страницы в Sites не запускает backend.

Для отдельного публичного API:

```sh
cd web
npm install
VITE_API_BASE_URL=https://api.example.com npm run build
```

Публикуется каталог `web/dist` через штатный Sites source workflow. На backend настрой:

```text
APP_PUBLIC_URL=https://your-site.example
ALLOWED_ORIGINS=https://your-site.example
COOKIE_SECURE=true
COOKIE_SAME_SITE=None
```

`APP_PUBLIC_URL` должен совпадать с публичным origin frontend, без пути; он используется также для reset-ссылок. В allowlist разрешены только точные доверенные origins. `VITE_API_BASE_URL` — публичный адрес без секрета; все переменные `VITE_*` видны посетителю.

При размещении frontend и API на разных сайтах браузер может блокировать third-party cookies независимо от `SameSite=None`. Для стабильной сессии используй собственные домены одного site либо server proxy для `/api` на origin frontend. Прокси должен передавать `Authorization`, cookie и `Set-Cookie`, не кэшировать auth-ответы; его origin включается в `ALLOWED_ORIGINS`. В Docker-развёртывании на одном домене Traefik уже обеспечивает этот маршрут.

Для тестирования собственного Sites deployment проверь: регистрацию, вход, reload страницы с refresh-cookie, доступ только к профилю до approve, отказ `/api/admin/users` обычному пользователю и сброс по ссылке из письма. Нельзя заменять эти проверки демонстрационным переключателем ролей.

## Локальная разработка и тесты

`compose.dev.yaml`: PostgreSQL `127.0.0.1:5433`, SMTP `1025`, Mailpit UI `8025`. Скрипт `dev-db-up.sh` ожидает готовности БД и применяет миграции. Для нестандартных параметров экспортируй `DEV_DB_*` перед запуском DB и API helper; `.env` автоматически читается Compose, но не выполняется как shell-код.

`./scripts/e2e.sh` запускает проект `household-planner-tests`: PostgreSQL `5544`, API `8081`, SMTP `1026`, Mailpit UI `8026`. Контейнеры удаляются после завершения. Он предполагает установленные npm-зависимости и Chromium (`cd web && npx playwright install chromium`). При ошибке Playwright сохраняет screenshot/trace; SMTP-письма можно изучить в Mailpit во время прогона.

Интеграционные Scala-тесты используют `TEST_DB_URL` и создают отдельную схему. Без этой переменной набор явно пропускается; CI передаёт её и запускает PostgreSQL. Не направляй тесты на production.

Пакетные lock-файлы нужно сохранять после успешной установки зависимостей. В среде без npm/Maven доступа нельзя подтверждать сборку или прохождение тестов по одной статической проверке.
