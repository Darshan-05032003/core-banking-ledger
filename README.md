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
| `app.ledger` | Lock timeout applied to every transaction that locks balance rows |
| `app.idempotency` | How long an unfinished `Idempotency-Key` may block before a retry takes it over |
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
| `GET /accounts/{id}/balance` | Ledger and available balance of an account |
| `POST /accounts/{id}/deposits` | Teller cash deposit (`amount`, `currency`, optional `remarks`) into an `ACTIVE` account |
| `POST /accounts/{id}/withdrawals` | Teller cash withdrawal; refused with `INSUFFICIENT_FUNDS` when the available balance is too low |
| `GET /journals/{id}` | A posted journal with its ledger lines |
| `POST /journals/{id}/reversal` | Correct a journal by posting the opposite legs (`reason` required); each journal can be reversed once |
| `POST /transfers` | Move money between two customer accounts (`fromAccount`, `toAccount`, `amount`, `currency`, optional `remarks`); requires an `Idempotency-Key` header |
| `GET /transfers/{id}` | A transfer attempt, completed or failed |

Account numbers have twelve digits: a four-digit branch code (`app.account.branch-code`), a seven-digit sequence drawn from a database sequence and a Luhn check digit, so a mistyped digit is detected before any lookup. Responses and logs never contain full account numbers, phone numbers or email addresses; they are masked as `XXXXXXXX4821` and `a***@example.com`.

Business rule violations use 422 with a machine-readable code, for example `CUSTOMER_NOT_VERIFIED`, `BALANCE_NOT_ZERO`, `INSUFFICIENT_FUNDS`, `DAILY_LIMIT_EXCEEDED` or `CURRENCY_MISMATCH`; invalid state changes such as freezing an already frozen account or reversing a journal twice use 409 (`ACCOUNT_NOT_ACTIVE`, `ACCOUNT_CLOSED`, `KYC_STATUS_UNCHANGED`, `JOURNAL_ALREADY_REVERSED`). A request that could not get a balance row within the lock timeout is answered with 409 `LOCK_TIMEOUT` and can be retried as it is.

## Transfers

`POST /api/v1/transfers` names both accounts by their full twelve-digit number, the way a customer fills in a payee form; the paths under `/accounts/{id}` stay id-based because they act on one account resource. A mistyped number fails the Luhn check and is rejected with `INVALID_ACCOUNT_NUMBER` before any lookup.

Every transfer request must carry an `Idempotency-Key` header (1 to 64 letters, digits, `.`, `_`, `:` or `-`), unique per requester. The first request with a key claims it by inserting a row into `idempotency_key`, whose unique constraint on (requester, key) decides between concurrent requests. The winner performs the transfer and stores the HTTP status and JSON body it produced in the same transaction as the transfer. A later request with the same key and the same payload gets that stored response back, with the header `Idempotent-Replayed: true`; the same key with a different payload is refused with 422 `IDEMPOTENCY_KEY_REUSED`; a request that arrives while the first is still running gets 409 `REQUEST_IN_PROGRESS`. Rejections are stored and replayed too, so a retried request can never turn a refusal into a second attempt. If the first request fails unexpectedly (for example on a lock timeout) the key is released and the client retries with the same key; a key left unfinished by a crashed instance can be taken over after `app.idempotency.in-progress-timeout`.

Validation is a chain of rules, each a Spring bean with an explicit order, evaluated until the first rejection: both accounts exist, they are distinct, both are `ACTIVE`, the accounts and the request share one currency, the sender's KYC is `VERIFIED`, the amount is positive and within `app.limits.per-transaction`, the sender's transfers of the current day plus this amount stay within `app.limits.daily`, and the available balance covers the amount. The rules run after both `account_balance` rows have been locked with `SELECT ... FOR UPDATE` in ascending account id order, so the balance and the daily total they see cannot change before the journal is posted, and two transfers in opposite directions between the same accounts cannot deadlock. The transaction runs at READ COMMITTED with `SET LOCAL lock_timeout` (`app.ledger.lock-timeout`), so a request never waits indefinitely for a row held by another transaction.

A completed transfer is a `TRANSFER` journal (debit sender, credit beneficiary) plus a row in `transfer` with status `COMPLETED` and a reference such as `TRF-0000000012`. A transfer the rules rejected is still written, in a separate transaction after the rollback, as a `FAILED` row carrying the error code; the 422 response includes its `transferId` and `transferReference`, and `GET /api/v1/transfers/{id}` shows it. `PENDING_APPROVAL` is reserved for high-value transfers that need a second pair of eyes.

## Ledger

Money moves only through `PostingService`, which records every business event as a journal with two or more ledger lines. Each line debits or credits either a customer account or one of the bank's general ledger accounts (`CASH_VAULT`, `INTEREST_EXPENSE`, `SUSPENSE`). A journal is accepted only when all its lines are in one currency and the debits equal the credits; the journal, its lines and the affected balances are then written in a single transaction, with the balance rows locked in ascending account id order.

A cash deposit debits `CASH_VAULT` and credits the customer account; a withdrawal does the opposite and is refused when the available balance is lower than the amount. Customer balances are stored in `account_balance` (ledger balance and available balance, which differ only when money is on hold) and must always agree with the sum of the account's ledger lines.

`journal_entry` and `ledger_entry` are append-only: database triggers reject any `UPDATE`, `DELETE` or `TRUNCATE`. A mistake is corrected with `POST /api/v1/journals/{id}/reversal`, which posts a new journal with every leg flipped and linked to the original through `reversal_of`; a unique constraint on that column guarantees a journal is reversed at most once even under concurrent requests.

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

Integration tests start PostgreSQL in a Testcontainers container on a random port, so Docker must be running. They include an invariant test that runs a random sequence of deposits, withdrawals and reversals and then checks, with SQL, that every journal balances, that each account balance equals the sum of its ledger lines and that the cash in the vault equals what the bank owes its customers. The transfer tests run 100 concurrent transfers between two accounts and check that the total is conserved, run transfers in both directions at once to show the lock ordering prevents deadlocks, race 50 transfers for a balance that covers ten of them and expect exactly ten to succeed, send concurrent requests with one `Idempotency-Key` and expect a single transfer, and hold a balance row from another transaction to show the lock timeout fires with SQLSTATE `55P03` and that the retry then succeeds.
