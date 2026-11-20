# MarathonBot

**A Telegram bot for running group habit challenges.** Give each participant daily goals, collect check-ins, and follow the group's progress from one chat.

Java 21 · Spring Boot · PostgreSQL · Liquibase · Telegram Bot API

## What you can do

| Participant | Organizer |
| --- | --- |
| See personal goals and submit daily reports | Create and configure multiple challenges |
| Take scheduled days off and review missed goals | Manage members, goals, reports and reminders |
| Earn medals and streaks and join a team expedition | Review statistics and send updates from Telegram |

### A typical challenge

An organizer creates a 14-day challenge and invites the group. Participants open the bot to see their goals and check in each day. At the end, the organizer gets a final summary. Rewards and the team expedition can be enabled per challenge.

## Run your own bot

Use **JDK 21** and **Docker Compose**. Create a separate Telegram bot for testing, then create a local environment file.

```bash
cp .env.example .env
```

Fill in the bot username and token, set a local database password, and set `SUPERADMIN_TELEGRAM_ID` to the Telegram user ID that should have administrator access. Keep `.env` local.

```bash
bash ./gradlew bootJar
docker compose up --build
```

Open your bot in Telegram and send `/start`. Liquibase applies the PostgreSQL schema at startup. `docker compose down` stops the services and retains the local database volume.

## Configuration

| Setting | Purpose |
| --- | --- |
| `TELEGRAM_BOT_USERNAME`, `TELEGRAM_BOT_TOKEN` | Credentials for your Telegram bot |
| `SUPERADMIN_TELEGRAM_ID` | Administrator's Telegram user ID |
| `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | Local PostgreSQL database settings |
| `GAMIFICATION_MANAGER_ENABLE_AVAILABLE` | Lets organizers enable rewards and expedition features |
| `YANDEX_API_KEY`, `YANDEX_FOLDER_ID` | Optional configuration for AI-generated motivation |

## Develop and test

```bash
bash ./gradlew test
```

Integration tests use PostgreSQL through Testcontainers and require Docker. To debug the bot from your IDE, start only the database with `docker compose up -d db`, then use the values from `.env` as environment variables for the Java process.

| Code | Responsibility |
| --- | --- |
| [`bot`](src/main/java/com/nekitvp/marathonbot/bot) and [`handler`](src/main/java/com/nekitvp/marathonbot/handler) | Telegram update routing and chat flows |
| [`service`](src/main/java/com/nekitvp/marathonbot/service) | Challenges, user goals and daily reporting |
| [`gamification`](src/main/java/com/nekitvp/marathonbot/gamification) | Reward rules, streaks and expedition progress |
| [`db`](src/main/resources/db) | Liquibase migrations |

## Version 7.14

This release adds configurable challenge management, weekly days off, reward history, and the team expedition to the publicly available version. The repository contains the source, tests and a local Compose setup.
