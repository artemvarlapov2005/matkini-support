.# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
./gradlew build          # Compile and package
./gradlew bootRun        # Run locally (requires env vars below)
./gradlew bootJar        # Build executable JAR
./gradlew test           # Run tests

docker-compose up        # Start PostgreSQL + bot container
```

**Required environment variables for local development:**
- `BOT_TOKEN` — Telegram bot token
- `ADMIN_IDS` — Comma-separated admin Telegram chat IDs
- `BOT_USERNAME` — (optional, defaults to `MatkiniSupportBot`)
- `DB_URL`, `DB_USER`, `DB_PASSWORD` — (optional, defaults to local PostgreSQL)

## Architecture

**Stack:** Kotlin 2.1, Spring Boot 3.4, Spring Data JPA, Telegram Bots 6.9.7, PostgreSQL 17, Java 21.

The bot uses `TelegramLongPollingBot` (polling, not webhooks). All message routing is handled in `SupportBot.kt` which dispatches based on message type (text command, callback query, or user/admin media message).

**Flow:**
1. User sends a message → bot creates a `Ticket` (OPEN), broadcasts inline-button notifications to all admins
2. Admin clicks "Take ticket" → `TicketService.assignAdmin()` (pessimistic lock) → status → IN_PROGRESS, notification buttons are removed from all other admins' messages using stored message IDs in `Ticket.adminNotifications`
3. Messages between the user and assigned admin are forwarded via `SupportBot` (text + all media types)
4. Admin uses `/leave` (returns to OPEN, re-notifies admins) or `/close` (status → CLOSED)

**Key source files:**
- `SupportBot.kt` — all Telegram message handling, callback routing, and UI (inline keyboards, message formatting)
- `TicketService.kt` — transactional business logic and state transitions
- `Ticket.kt` — JPA entity; stores `adminNotifications: Map<Long, Int>` (adminChatId → messageId) for post-assignment cleanup
- `BotProperties.kt` — configuration from env vars (`BOT_TOKEN`, `ADMIN_IDS`, `BOT_USERNAME`)

**Database:** `spring.jpa.hibernate.ddl-auto=update` — schema is auto-managed. Default datasource is `jdbc:postgresql://localhost:5432/matkini_support`.

**Timezones:** All timestamps are stored as `Instant` (UTC) in the DB; displayed to admins in Moscow time (`Europe/Moscow`).