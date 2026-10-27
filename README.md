# Дом рядом · Household Planner

Vue 3 + Vuetify, Scala 3 + ZIO HTTP и PostgreSQL. Приложение содержит серверную регистрацию и вход, JWT-сессии, личный кабинет, подтверждение аккаунтов, административный сброс пароля по email, аудит и создание домохозяйств.

Новый пользователь получает статус `PENDING`: может менять никнейм и пароль в личном кабинете. После подтверждения администратором открывается доступ к домохозяйствам. Первый администратор — `admin` / `admin`; до обязательной смены пароля остальные действия закрыты. Bootstrap выполняется один раз и не восстанавливает пароль после перезапуска.

## Разработка

Нужны JDK 21, sbt, Node.js 22.12+, Docker Compose, OpenSSL. Для helper-скриптов используется POSIX shell.

```sh
./scripts/dev-db-up.sh
./scripts/dev-api.sh
```

В другом терминале:

```sh
cd web
npm install
npm run dev
```

Открой `http://localhost:5173`. PostgreSQL доступна на `127.0.0.1:5433`. Письма разработческого окружения — в Mailpit: `http://localhost:8025`. Параметры БД по умолчанию: база `household_planner_dev`, пользователь `household_dev`, пароль `household_dev_local_only`.

`dev-db-up.sh` применяет SQL-миграции и идемпотентный базовый seed. `./scripts/dev-db-seed-demo.sh` добавляет демонстрационные записи доменной модели. Эти записи не создают работающие пользовательские пароли; зарегистрируй аккаунт через приложение. `./scripts/dev-db-down.sh` останавливает локальные сервисы, сохраняя БД. Ключи JWT генерируются в `.local/secrets/` и не попадают в Git.

## Проверки

```sh
npm run test:unit --prefix web
npm run build --prefix web
sbt "backend/test"
```

Backend unit-тесты проверяют JWT, Argon2, права доступа и rate limiting. PostgreSQL integration-тесты включаются при передаче `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD`; каждый запуск создаёт отдельную случайную схему и удаляет только её. Без `TEST_DB_URL` этот набор явно пропускается. Не используй production-БД для тестов.

```sh
TEST_DB_URL=jdbc:postgresql://localhost:5433/household_planner_dev \
TEST_DB_USER=household_dev TEST_DB_PASSWORD=household_dev_local_only sbt "backend/test"
```

E2E запускается на отдельной одноразовой базе и почтовом сервере:

```sh
cd web
npx playwright install chromium
cd ..
./scripts/e2e.sh
```

Скрипт создаёт и удаляет только Compose-проект `household-planner-tests`; dev/prod volumes не подключаются. В CI собираются frontend/backend, выполняются unit/integration-тесты и Playwright-сценарий без подмены API. Требуется доступ к Maven, npm, Docker Registry и Docker daemon. Если зависимости недоступны, наличие тестовых файлов не означает успешный прогон.

## Развёртывание

Docker Compose с Traefik и размещение Vue в Sites описаны в [docs/deployment.md](docs/deployment.md). Публикацию выполняет владелец проекта. Канонический frontend — `web/`; отдельной HTML-демоверсии больше нет.

Сценарии задач, запасов, графиков 5/2 и 2/2, отдыха и бюджета описаны в архитектуре и SQL-схеме; их прикладные API и интерфейсы пока не входят в реализованный модуль аккаунтов.

- [Архитектура и границы реализации](docs/architecture.md)
- [ERD: Mermaid, SVG и PNG](docs/database-erd.md)
- [OpenAPI-контракт](docs/openapi.json)
- [Выполненные проверки и ограничения среды](docs/validation.md)
