# UPI Transaction Tracker

An automated personal finance system that captures Google Pay UPI transactions from iPhone bank SMS notifications and delivers real-time Telegram alerts with running balance tracking.

## How It Works

```
Google Pay Payment
       ↓
Kotak Bank sends SMS
       ↓
iOS Shortcuts detects SMS containing "Sent Rs" / "Received Rs"
       ↓
Shortcuts POSTs raw SMS text as JSON to Spring Boot server
       ↓
Server parses SMS → updates running balance → sends Telegram notification
```

## What It Tracks

| SMS Type | Example | Server Action |
|----------|---------|---------------|
| UPI Debit | `Sent Rs.90.00 from Kotak Bank AC X8671 to gpay-xyz...` | Deducts from balance, sends Telegram alert |
| UPI Credit | `Received Rs.60.00 in your Kotak Bank AC X8671 from...` | Adds to balance, sends Telegram alert |
| Cash Deposit | `INR 50000.00 is credited...Combined Available Balance is INR 50025.97` | Syncs balance to actual bank value (no notification) |

## Tech Stack

- **Backend:** Java 17, Spring Boot 3.2
- **SMS Capture:** iOS Shortcuts automation (webhook trigger on bank SMS)
- **Notifications:** Telegram Bot API via Spring WebFlux WebClient
- **Deployment:** Docker, GitHub Actions CI/CD, AWS EC2
- **Configuration:** Externalized regex patterns and secrets via environment variables

## Project Structure

```
src/main/java/com/upi/transaction/
├── TransactionApplication.java        # Entry point
├── config/
│   └── AppConfig.java                 # Loads env vars (balance, telegram, SMS patterns)
├── controller/
│   └── SmsController.java             # POST /api/sms endpoint
├── dto/
│   └── ParsedTransaction.java         # Parsed SMS data record
└── service/
    ├── SmsParserService.java           # Regex-based SMS parser (3 formats)
    ├── BalanceService.java             # In-memory running balance
    └── TelegramService.java           # Sends formatted Telegram messages
```

## Setup

### 1. Create a Telegram Bot

1. Open Telegram, search for **@BotFather**
2. Send `/newbot`, follow the prompts
3. Copy the **bot token**
4. Message your bot, then visit `https://api.telegram.org/bot<TOKEN>/getUpdates` to find your **chat ID**

### 2. Configure Environment Variables

Set these in your `docker-compose.yml` or CI/CD secrets:

| Variable | Description |
|----------|-------------|
| `INITIAL_BALANCE` | Your current bank balance at time of deployment |
| `TELEGRAM_BOT_TOKEN` | Bot token from BotFather |
| `TELEGRAM_CHAT_ID` | Your Telegram chat ID |

### 3. Deploy

```bash
docker compose up -d --build
```

### 4. iOS Shortcuts Setup

Create SMS automations in the iPhone Shortcuts app:

**Trigger:**
- Automation → Message
- Sender: Any Sender
- Message Contains: `Sent Rs` (create a second one for `Received Rs`, third for `Combined Available Balance`)
- Run Immediately: ✓

**Action:**
- Get Contents of URL → `http://your-server-ip:8080/api/sms`
- Method: POST
- Header: `Content-Type: application/json`
- Request Body: JSON → key `smsBody`, value: Shortcut Input

## CI/CD

The project uses GitHub Actions for continuous deployment. On push to `main`:

1. GitHub Actions builds the Docker image
2. Pushes to the EC2 instance
3. Restarts the container with updated config

To correct balance drift, update `INITIAL_BALANCE` in the pipeline config and redeploy.

## API

```
POST /api/sms
```

Request:
```json
{
  "smsBody": "Sent Rs.90.00 from Kotak Bank AC X8671 to gpay-11256398113;okbizaxis on 11-12-25.UPI Ref 115487570744. Not you, https://kotak.com/KBANKT/Fraud"
}
```

Response:
```json
{
  "status": "processed",
  "type": "UPI_SENT",
  "amount": "90.00",
  "balance": "49910.00"
}
```

## Design Decisions

- **No database** — the only state is a single balance number held in memory. A database would be overkill for tracking one value.
- **Externalized regex patterns** — Kotak Bank could change their SMS format anytime. Patterns live in `application.properties`, loaded via `@ConfigurationProperties`. Format change = config update, not a code change.
- **Cash deposit SMS syncs balance** — these messages include the actual bank balance (`Combined Available Balance is INR ...`), so the server uses this to correct any drift rather than just adding the deposit amount.
- **Multi-stage Docker build** — build stage uses full Maven image, runtime uses lightweight JRE Alpine. Keeps the final image small.