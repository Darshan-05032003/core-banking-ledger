# core-banking-ledger

A core banking backend built with Java 21 and Spring Boot that records customers, accounts, deposits, withdrawals and transfers in a double-entry ledger backed by PostgreSQL and Kafka.

## Requirements

- JDK 21
- Docker with Compose (for the local PostgreSQL and Kafka, and for the integration tests, which use Testcontainers)

## Running locally

```bash
cp .env.example .env          # adjust if needed
docker compose up -d          # PostgreSQL on localhost:5433, Kafka on localhost:9094
./mvnw spring-boot:run        # application on http://localhost:8082
```

The `dev` profile is active by default. Set `SPRING_PROFILES_ACTIVE=prod` for production-style logging and health output. Every setting is read from an environment variable with a local default; `.env.example` lists them all.

## Configuration

Application settings live under the `app.*` prefix and are bound to validated `@ConfigurationProperties` classes, so an invalid value stops the application at start-up instead of failing later.

| Prefix | Purpose |
|---|---|
| `app.limits` | Per-transaction and daily transfer limits |
| `app.approval` | Maker-checker threshold and expiry of pending transfers |
| `app.interest` | Savings interest rate and accrual schedule |
| `app.security` | JWT issuer, token lifetime and transfer rate limit |

## Error responses

Errors are returned as RFC 9457 `application/problem+json` documents. Every body carries a machine-readable `errorCode` and the `correlationId` of the request, which is also returned in the `X-Correlation-Id` response header and printed in every log line for that request. Send your own `X-Correlation-Id` to have it reused; otherwise one is generated.

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "Available balance is lower than the transfer amount",
  "instance": "/api/v1/transfers",
  "errorCode": "INSUFFICIENT_FUNDS",
  "correlationId": "7c1e9a5c-1a2b-4d3e-9f10-2b3c4d5e6f70"
}
```

Validation failures (400) add an `errors` array of `{ "field", "message" }` entries. Unexpected failures return 500 with a generic message and no internal details.

## Tests

```bash
./mvnw clean verify
```

Integration tests start PostgreSQL in a Testcontainers container on a random port, so Docker must be running.
