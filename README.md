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
| `app.account` | Branch code used in account numbers and the default account currency |
| `app.limits` | Per-transaction and daily transfer limits |
| `app.approval` | Maker-checker threshold and expiry of pending transfers |
| `app.interest` | Savings interest rate and accrual schedule |
| `app.security` | JWT issuer, token lifetime and transfer rate limit |

## API

All endpoints are under `/api/v1`. Role-based access rules are added together with JWT authentication; until then the endpoints sit behind Spring Security's default authentication.

| Method and path | Purpose |
|---|---|
| `POST /customers` | Onboard a customer (full name, email, phone, date of birth); KYC starts as `PENDING` |
| `GET /customers/{id}` | Fetch a customer |
| `POST /customers/{id}/kyc/verify` | Mark KYC as `VERIFIED` |
| `POST /customers/{id}/kyc/reject` | Mark KYC as `REJECTED` with a reason |
| `GET /customers/{id}/accounts` | List the customer's accounts |
| `POST /accounts` | Open a `SAVINGS` or `CURRENT` account for a KYC-verified customer; currency defaults to `INR` |
| `GET /accounts/{id}` | Fetch an account |
| `POST /accounts/{id}/freeze`, `/unfreeze` | Block or unblock an account |
| `POST /accounts/{id}/close` | Close an account; allowed only when the balance is exactly zero and nothing is on hold |

Account numbers have twelve digits: a four-digit branch code (`app.account.branch-code`), a seven-digit sequence drawn from a database sequence and a Luhn check digit, so a mistyped digit is detected before any lookup. Responses and logs never contain full account numbers, phone numbers or email addresses; they are masked as `XXXXXXXX4821` and `a***@example.com`.

Business rule violations use 422 with a machine-readable code, for example `CUSTOMER_NOT_VERIFIED`, `BALANCE_NOT_ZERO` or `FUNDS_ON_HOLD`; invalid state changes such as freezing an already frozen account use 409 (`ACCOUNT_NOT_ACTIVE`, `ACCOUNT_CLOSED`, `KYC_STATUS_UNCHANGED`).

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
