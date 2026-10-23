# core-banking-ledger

A core banking backend built with Java 21 and Spring Boot that records customers, accounts, deposits, withdrawals and transfers in a double-entry ledger backed by PostgreSQL, and publishes every posted journal to Kafka through a transactional outbox.

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
| `app.ledger` | Lock timeout applied to every transaction that locks balance rows, and the cron of the end-of-day check |
| `app.idempotency` | How long an unfinished `Idempotency-Key` may block before a retry takes it over |
| `app.limits` | Per-transaction and daily transfer limits |
| `app.approval` | Maker-checker threshold, how long a pending transfer waits before it expires, and the cron of the expiry sweep |
| `app.statement` | The longest period one statement request may cover (default one year) |
| `app.outbox` | Relay delay and batch size, the publish acknowledgement timeout, the partition count and replication factor of the event topics, and how long published rows are kept before the nightly purge |
| `app.notification` | Consumer group of the notification consumer, delivery attempts before a record is dead-lettered, and the backoff between attempts |
| `app.interest` | Annual savings interest rate and the cron of the daily accrual |
| `app.security` | JWT issuer, token lifetime, signing key pair and transfer rate limit |
| `app.scheduling` | `enabled` switches the `@Scheduled` jobs off for an instance that should only serve requests (the test profile does this) |

## Authentication and roles

The application is an OAuth2 resource server for tokens it issues itself. `POST /api/v1/auth/token` with `{"username", "password"}` checks the password against the BCrypt hash in `app_user` and returns a short-lived JWT signed with RS256:

```json
{ "accessToken": "eyJ...", "tokenType": "Bearer", "expiresIn": 900, "expiresAt": "2026-10-22T10:30:30Z" }
```

Every other endpoint expects `Authorization: Bearer <token>`. The token carries the username, the roles and, for a customer login, the linked customer id, so no request after login touches the user table. Sessions and CSRF protection are off: there is no cookie for a cross-site request to ride on. A missing or invalid token is answered with 401 and a `WWW-Authenticate: Bearer` header; a valid token without the required role gets 403. Both carry the same problem body as every other error.

The signing key pair comes from `APP_SECURITY_JWT_PRIVATE_KEY` and `APP_SECURITY_JWT_PUBLIC_KEY` (PEM text or a `file:` location). In the `prod` profile the application refuses to start without them; `dev` and `test` generate a throwaway pair at start-up, so tokens do not survive a restart there. Keys are never committed. The `dev` profile also seeds one ADMIN user from `APP_ADMIN_USERNAME` and `APP_ADMIN_PASSWORD`, which is how the first users of a fresh database are created.

| Role | May |
|---|---|
| `CUSTOMER` | Read its own customer record, accounts and balances; send transfers from its own accounts and read them. A customer login is linked to exactly one customer id by an admin. |
| `TELLER` | Onboard customers, decide KYC, open accounts, deposit and withdraw cash, read accounts, journals and transfers |
| `CHECKER` | Read customers, accounts, journals and transfers; list, approve and reject transfers waiting for approval |
| `ADMIN` | Everything above, plus freeze, unfreeze and close accounts, reverse journals, manage users and read all actuator endpoints. An admin may decide approvals, but never one of its own transfers |

Role rules live on each controller method with `@PreAuthorize`. Ownership is decided by small beans referenced from the expression, for example `@accountAccess.isOwner(#accountId, authentication)`, which run one existence query against the customer id in the token; an account that is not the caller's is reported as 403 whether or not it exists.

## Audit trail

Every money movement (deposit, withdrawal, reversal, transfer), every approval decision, every statement view or download and every administrative action (KYC decision, freeze, unfreeze, close, user management) writes a row to `audit_log`: actor, roles, action, target type and id, outcome `SUCCESS` or `FAILURE`, the error code of a failure, client IP, correlation id, timestamp and a JSON `details` column with the business values of the request (amounts, masked account numbers, reasons; never passwords or full account numbers). The controller methods are marked `@Audited`, and an aspect writes the row after the method has returned or thrown, in its own transaction, so a withdrawal refused for insufficient funds or a freeze attempted by a teller without the right role is on record just like a successful one. A pending transfer that expires is recorded the same way by the job that expires it, with `system` as the actor and no client IP, as are the nightly interest accrual and end-of-day check runs and every discrepancy the check finds. Database triggers make `audit_log` append-only, as for the ledger tables.

## Rate limiting

`POST /api/v1/transfers` is limited per user to `app.security.rate-limit.transfers-per-minute` (default 20) with a token bucket that refills continuously. A request over the limit is answered with 429, `errorCode` `RATE_LIMIT_EXCEEDED`, a `Retry-After` header in seconds and the same value as `retryAfterSeconds` in the body. The buckets are in memory, so each instance of the application counts on its own.

## API

All endpoints are under `/api/v1`. The last column lists who may call the endpoint; "owner" means a `CUSTOMER` login linked to the customer the record belongs to.

| Method and path | Purpose | Roles |
|---|---|---|
| `POST /auth/token` | Exchange username and password for an access token | anyone |
| `POST /customers` | Onboard a customer (full name, email, phone, date of birth); KYC starts as `PENDING` | TELLER, ADMIN |
| `GET /customers/{id}` | Fetch a customer | staff, owner |
| `POST /customers/{id}/kyc/verify` | Mark KYC as `VERIFIED` | TELLER, ADMIN |
| `POST /customers/{id}/kyc/reject` | Mark KYC as `REJECTED` with a reason | TELLER, ADMIN |
| `GET /customers/{id}/accounts` | List the customer's accounts | staff, owner |
| `POST /accounts` | Open a `SAVINGS` or `CURRENT` account for a KYC-verified customer; currency defaults to `INR` | TELLER, ADMIN |
| `GET /accounts/{id}` | Fetch an account | staff, owner |
| `POST /accounts/{id}/freeze`, `/unfreeze` | Block or unblock an account | ADMIN |
| `POST /accounts/{id}/close` | Close an account; allowed only when the balance is exactly zero and nothing is on hold | ADMIN |
| `GET /accounts/{id}/balance` | Ledger balance, available balance and the amount on hold | staff, owner |
| `GET /accounts/{id}/statement?from=&to=&cursor=&size=` | The account's ledger lines for an inclusive date range with a running balance, plus the opening and closing balance of the period; pages continue through `nextCursor` (`size` at most 200) | staff, owner |
| `GET /accounts/{id}/statement.csv?from=&to=` | The same lines for the whole range as a streamed CSV download | staff, owner |
| `POST /accounts/{id}/deposits` | Teller cash deposit (`amount`, `currency`, optional `remarks`) into an `ACTIVE` account | TELLER, ADMIN |
| `POST /accounts/{id}/withdrawals` | Teller cash withdrawal; refused with `INSUFFICIENT_FUNDS` when the available balance is too low | TELLER, ADMIN |
| `GET /journals/{id}` | A posted journal with its ledger lines | staff |
| `POST /journals/{id}/reversal` | Correct a journal by posting the opposite legs (`reason` required); each journal can be reversed once | ADMIN |
| `POST /transfers` | Move money between two customer accounts (`fromAccount`, `toAccount`, `amount`, `currency`, optional `remarks`); requires an `Idempotency-Key` header. 201 when posted, 202 when parked for approval | owner of `fromAccount`, ADMIN |
| `GET /transfers/{id}` | A transfer attempt in any state: completed, failed, pending approval, rejected or expired | staff, owner of the sending account |
| `GET /approvals?status=&page=&size=` | The approval queue, oldest first (`status` defaults to `PENDING_APPROVAL`, `size` at most 100) | CHECKER, ADMIN |
| `POST /approvals/{transferId}/approve` | Post a pending transfer | CHECKER, ADMIN |
| `POST /approvals/{transferId}/reject` | Refuse a pending transfer (`reason` required) and release its hold | CHECKER, ADMIN |
| `POST /admin/users` | Create a login (`username`, `password`, `roles`, optional `customerId` for a `CUSTOMER`) | ADMIN |
| `GET /admin/users/{id}` | Fetch a login; the password hash is never returned | ADMIN |
| `PUT /admin/users/{id}/customer` | Link a `CUSTOMER` login to a customer id | ADMIN |
| `POST /admin/users/{id}/disable`, `/enable` | Block or restore a login; a disabled user cannot obtain a token | ADMIN |
| `POST /admin/jobs/interest-accrual?date=` | Accrue savings interest for a day that has ended (and pay it out when the day is a month-end); reports how many accounts were processed, newly accrued, already accrued and posted | ADMIN |
| `POST /admin/jobs/eod-check?date=` | Reconcile every balance row with its ledger lines and holds, every journal with its legs and the totals per currency; `date` defaults to today | ADMIN |

"staff" means `TELLER`, `CHECKER` or `ADMIN`.

Account numbers have twelve digits: a four-digit branch code (`app.account.branch-code`), a seven-digit sequence drawn from a database sequence and a Luhn check digit, so a mistyped digit is detected before any lookup. Responses and logs never contain full account numbers, phone numbers or email addresses; they are masked as `XXXXXXXX4821` and `a***@example.com`.

Business rule violations use 422 with a machine-readable code, for example `CUSTOMER_NOT_VERIFIED`, `BALANCE_NOT_ZERO`, `FUNDS_ON_HOLD`, `INSUFFICIENT_FUNDS`, `DAILY_LIMIT_EXCEEDED`, `SELF_APPROVAL_FORBIDDEN` or `CURRENCY_MISMATCH`; invalid state changes such as freezing an already frozen account, reversing a journal twice or deciding a transfer that is no longer pending use 409 (`ACCOUNT_NOT_ACTIVE`, `ACCOUNT_CLOSED`, `KYC_STATUS_UNCHANGED`, `JOURNAL_ALREADY_REVERSED`, `TRANSFER_NOT_PENDING`). A request that could not get a balance row within the lock timeout is answered with 409 `LOCK_TIMEOUT` and can be retried as it is.

## Transfers

`POST /api/v1/transfers` names both accounts by their full twelve-digit number, the way a customer fills in a payee form; the paths under `/accounts/{id}` stay id-based because they act on one account resource. A mistyped number fails the Luhn check and is rejected with `INVALID_ACCOUNT_NUMBER` before any lookup.

Every transfer request must carry an `Idempotency-Key` header (1 to 64 letters, digits, `.`, `_`, `:` or `-`), unique per requester. The first request with a key claims it by inserting a row into `idempotency_key`, whose unique constraint on (requester, key) decides between concurrent requests. The winner performs the transfer and stores the HTTP status and JSON body it produced in the same transaction as the transfer. A later request with the same key and the same payload gets that stored response back, with the header `Idempotent-Replayed: true`; the same key with a different payload is refused with 422 `IDEMPOTENCY_KEY_REUSED`; a request that arrives while the first is still running gets 409 `REQUEST_IN_PROGRESS`. Rejections are stored and replayed too, so a retried request can never turn a refusal into a second attempt. If the first request fails unexpectedly (for example on a lock timeout) the key is released and the client retries with the same key; a key left unfinished by a crashed instance can be taken over after `app.idempotency.in-progress-timeout`.

Validation is a chain of rules, each a Spring bean with an explicit order, evaluated until the first rejection: both accounts exist, they are distinct, both are `ACTIVE`, the accounts and the request share one currency, the sender's KYC is `VERIFIED`, the amount is positive and within `app.limits.per-transaction`, the sender's transfers of the current day plus this amount stay within `app.limits.daily`, and the available balance covers the amount. The rules run after both `account_balance` rows have been locked with `SELECT ... FOR UPDATE` in ascending account id order, so the balance and the daily total they see cannot change before the journal is posted, and two transfers in opposite directions between the same accounts cannot deadlock. The transaction runs at READ COMMITTED with `SET LOCAL lock_timeout` (`app.ledger.lock-timeout`), so a request never waits indefinitely for a row held by another transaction.

A completed transfer is a `TRANSFER` journal (debit sender, credit beneficiary) plus a row in `transfer` with status `COMPLETED` and a reference such as `TRF-0000000012`. A transfer the rules rejected is still written, in a separate transaction after the rollback, as a `FAILED` row carrying the error code; the 422 response includes its `transferId` and `transferReference`, and `GET /api/v1/transfers/{id}` shows it. A transfer above the approval threshold passes the same rules and is then parked instead of posted, as described next.

## Maker-checker and holds

A transfer whose amount is above `app.approval.threshold` (default 200000.00) is not posted by the person who raised it. It is saved as `PENDING_APPROVAL`, answered with 202 and a `Location`, and a **hold** for the amount is placed on the sender's account in the same transaction, under the same row lock the rules ran on. The hold lowers the account's *available* balance but not its *ledger* balance: the money is still the customer's and still on the books, it just cannot be spent twice while a checker decides. `GET /accounts/{id}/balance` shows all three figures (`ledgerBalance`, `availableBalance`, `onHold`), the balance rule of the next transfer sees the reduced available balance, a teller withdrawal is checked against it too, and an account with an active hold cannot be closed (`FUNDS_ON_HOLD`).

A `CHECKER` (or an admin) lists the queue with `GET /approvals` and decides with `POST /approvals/{transferId}/approve` or `/reject`. The four-eyes rule is enforced on the server: the user who raised the transfer cannot decide it, whatever roles they hold, and gets 422 `SELF_APPROVAL_FORBIDDEN`. Approval locks the transfer row, then the two balance rows in ascending account id, captures the hold and posts the `TRANSFER` journal in one transaction, so afterwards the balances are exactly what a direct transfer would have produced; the journal's `createdBy` is the checker, the transfer's `decidedBy` as well. Rejection releases the hold and records the reason as `decisionReason`; the transfer ends `REJECTED`. A decision on a transfer that is no longer pending is answered with 409 `TRANSFER_NOT_PENDING`, which is also what the loser of two simultaneous decisions gets, because both first lock the transfer row. A pending transfer whose sender has been frozen in the meantime cannot be approved (409 `ACCOUNT_NOT_ACTIVE`) and stays in the queue until it is rejected, the account is unfrozen or it expires.

The idempotency row of the original request keeps the 202 response it produced, so a retry of the same `Idempotency-Key` still returns `PENDING_APPROVAL` after the decision; the current state is on `GET /transfers/{id}`.

Pending transfers that nobody decides within `app.approval.pending-expiry` (default 24h) are expired by a scheduled sweep (`app.approval.expiry-cron`, default every minute): each one is handled in its own transaction with the same locks as a decision, its hold is released, the transfer ends `EXPIRED` with `decidedBy` `system`, and an audit row is written. The sweep is idempotent and works in batches of 100.

## Statements

`GET /api/v1/accounts/{id}/statement?from=2026-10-01&to=2026-10-31` returns the account's ledger lines for an inclusive date range, oldest first, each with the journal reference, type and description, the counterparty (another customer's account, masked, or one of the bank's own accounts such as `CASH_VAULT`) and the balance after the line. The response also carries the opening balance (everything posted before `from`) and the closing balance of the period. The running balance is computed by PostgreSQL with a window function, `SUM(signed_amount) OVER (ORDER BY posted_at, id)`, where a credit counts positive and a debit negative because a customer's balance is what the bank owes them. Days are interpreted in the application's time zone (`UTC` unless the clock is configured otherwise).

Pages use keyset pagination instead of `OFFSET`: the response contains an opaque `nextCursor` (null on the last page) that encodes the position of the last line returned, and the next page continues strictly after that position. Lines posted after the first page was fetched appear at the end, so a client walking the pages never sees a line twice or misses one that was already there, which `OFFSET` cannot promise on a table that keeps growing; and the database never scans and discards the rows of earlier pages. The running balance of a page that starts in the middle of the period is still right because the window runs over the whole period and the cursor is applied outside it. `size` defaults to 50 and is at most 200; `from` must not be after `to` and the range may not exceed `app.statement.max-period`; a cursor that was not issued by a previous page is rejected. All three are 400 responses (`INVALID_PERIOD`, `PERIOD_TOO_LONG`, `INVALID_CURSOR`).

`GET /api/v1/accounts/{id}/statement.csv?from=&to=` streams the same lines as a CSV attachment (`posted_at, journal_reference, type, description, counterparty, debit, credit, balance, currency`). The rows are read through a JDBC cursor with a fetch size of 500 inside a read-only transaction that stays open until the last row is written, and are passed to the response as they arrive, so the memory needed does not grow with the length of the statement. Both endpoints read at `REPEATABLE READ`, so the balances and the lines of one response come from the same snapshot of the ledger. `ledger_entry` carries a copy of its journal's `posted_at`, kept consistent by a composite foreign key, and an index on `(account_id, posted_at, id)` serves the date range, the ordering and the cursor of these queries. Every view and every download is written to the audit log.

## Interest

Savings accounts earn interest daily and receive it monthly, which is how most banks do it: the daily figure is what the bank owes for one more day of holding the money, the monthly credit is when that debt is paid. Every night (`app.interest.accrual-cron`, default 00:30) the accrual job takes each savings account that is not closed, computes the balance it held at the end of the previous day from its ledger lines (`posted_at` before the next midnight in the application's time zone), and writes one `interest_accrual` row with that balance, the annual rate in force (`app.interest.savings-annual-rate`), and the day's interest: balance x rate / 365, rounded `HALF_EVEN` to four decimals. Four decimals rather than two because a small balance earns fractions of a paisa a day and a month of them must add up before anything is rounded to money. The arithmetic is done in Java rather than SQL because PostgreSQL's `round()` rounds half up and the ledger's rule is half even. The rate is copied onto each row, so changing the configuration later changes future accruals only. A frozen account keeps accruing: a freeze stops the customer from transacting, not the bank from owing interest. Current accounts earn nothing.

The table has a unique key on (account, date) and the job inserts with `ON CONFLICT DO NOTHING`, so running a day twice, or two instances running it at once, cannot accrue twice; a repeated run reports the accounts as already accrued. A day can be accrued only once it has ended, because the balance of a running day is not final. Because the balance is read from the ledger lines as of the end of the accrual date and not from the balance row, a run that happens late, after further deposits, still produces the right figure.

On the last day of a month the same run also pays out: for each account with accrual rows not yet posted, it locks those rows, sums them, rounds the sum `HALF_EVEN` to two decimals and posts one `INTEREST` journal through `PostingService`, debiting the bank's `INTEREST_EXPENSE` account and crediting the customer, then stamps the rows with the journal id. The rows are selected by "not yet posted" rather than by month, so a sum below one paisa is carried forward instead of being lost, and a month-end that was missed is caught up by the next one. A re-run finds no unposted rows and posts nothing. Each account is posted in its own transaction, so one account whose balance row is locked does not hold up the rest; the interest journal reaches Kafka through the outbox like every other posting.

## End-of-day check

The balance rows in `account_balance` are a copy of what the ledger lines say, maintained inside the posting transaction. The end-of-day check is the independent proof that the copy is still right and that nothing reached the ledger tables behind the posting service's back. It runs nightly (`app.ledger.eod-check-cron`, default 01:00, after the accrual) and on demand through `POST /api/v1/admin/jobs/eod-check`, and verifies three things in one `REPEATABLE READ` snapshot, so a posting that commits during the check cannot appear as a false difference between two queries: for every account, `ledger_balance` equals the signed sum of its ledger lines and `available_balance` equals `ledger_balance` minus its active holds; every journal has at least two legs whose debits equal its credits; and, per currency, the sum of all debits ever posted equals the sum of all credits.

Each discrepancy is reported three ways: an `ERROR` log line with the masked account number or the journal reference, an increment of the Micrometer counter `ledger.eod.mismatches` (tagged `type` = `balance`, `journal` or `totals`, visible on `/actuator/prometheus`), and an `audit_log` row with outcome `FAILURE` naming the account or journal and the expected and actual figures. The scheduled run adds a summary row (`EOD_CHECK`, `SUCCESS` or `FAILURE`, with the counts) under the business date it closed; an administrator's run is recorded by the audit aspect with the same counts. The report lists at most 100 offenders of each kind alongside the full counts.

## Scheduled jobs

Jobs are plain `@Scheduled` methods guarded by ShedLock's `@SchedulerLock`, with the JDBC lock provider and the `shedlock` table. When several instances of the application share the database, each schedule still fires once: the instance that claims the lock row runs the job, the others skip that run. Lock expiry uses the database clock, so instances with drifting clocks cannot both hold a lock. `app.scheduling.enabled=false` turns the schedules off for an instance; the job beans remain and are called directly by the tests.

| Job | Lock name | Default schedule | What it does |
|---|---|---|---|
| Pending transfer expiry | `expirePendingTransfers` | every minute | Releases the holds of pending transfers nobody decided in time |
| Outbox relay | `relayOutbox` | every second | Publishes outbox rows to Kafka |
| Interest accrual | `accrueInterest` | 00:30 daily | Accrues the previous day and, at a month-end, posts the interest |
| End-of-day check | `checkEndOfDay` | 01:00 daily | Reconciles balances, journals and totals for the previous business day |
| Outbox purge | `purgeOutbox` | 02:00 daily | Deletes published outbox rows older than `app.outbox.retention` (default 7 days), in slices of 1 000; unpublished rows are never deleted |

Every job is safe to run twice: the expiry re-checks each transfer under its lock, the relay stamps rows it has published, the accrual relies on a unique key, the payout takes only rows no journal has claimed, the check only reads, and the purge only removes what is already gone from the topic's point of view. Scheduled runs write their audit rows as `system`; the two jobs that can be triggered by an administrator are audited under that person's name.

## Events

Every journal that `PostingService` posts (deposit, withdrawal, transfer, approved transfer, reversal) is published to the Kafka topic `ledger.transactions.v1` as a `LedgerTransactionPosted` event, version 1: one event per customer account the journal touched, keyed by that account's id, carrying the journal id, reference and type, the posting time, the direction and amount for that account, the masked account number, and every leg of the journal (customer legs by id with a masked number, bank legs by GL code). The payload is JSON with an explicit `schemaVersion`.

The event is not sent from the posting code. It is written as a row of the `outbox_event` table inside the posting transaction (a synchronous listener on the `JournalPosted` application event, which runs with `Propagation.MANDATORY`), so a journal and its events are committed or rolled back together; writing to the database and to Kafka from the same code path could leave one without the other. A relay scheduled every `app.outbox.relay-delay` (and guarded by ShedLock, so one instance at a time) selects unpublished rows in batches with `SELECT ... FOR UPDATE SKIP LOCKED`, oldest first, sends each to Kafka, waits for the broker's acknowledgement (`acks=all`, idempotent producer) and stamps `published_at` in the same database transaction. A row whose publish fails keeps a count of attempts and the last error and is retried on the next run; later rows of the same account wait behind it, so the order of one account's events on its partition is always the posting order. If the relay dies between the acknowledgement and the commit the row is published again: delivery is at least once, and consumers must tolerate a duplicate.

The notification consumer in the same application (`@KafkaListener`, consumer group `app.notification.group-id`, offsets committed per record) parses each record, refuses anything that is not a version-1 event, and writes one simulated message per event: an SMS to the masked phone number for a debit, an email to the masked address for a credit, both as rows of the `notification` table. Before it writes, it inserts the event id into `processed_event` (`ON CONFLICT DO NOTHING`) in the same transaction; a redelivered event finds its id there and is skipped, which makes the consumer idempotent. A record that fails for a reason that may pass (the database is briefly unavailable) is retried `app.notification.retry-attempts` times with a fixed backoff; a record that can never be processed (not JSON, an unknown schema version, an account that does not exist) and a record whose retries are exhausted are republished to `ledger.transactions.v1.DLT` with the original key, value and headers plus the exception details, and the consumer continues. Both topics are declared by the application with `app.outbox.topic-partitions` partitions.

## Ledger

Money moves only through `PostingService`, which records every business event (`DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `INTEREST`, `REVERSAL`) as a journal with two or more ledger lines. Each line debits or credits either a customer account or one of the bank's general ledger accounts (`CASH_VAULT`, `INTEREST_EXPENSE`, `SUSPENSE`). A journal is accepted only when all its lines are in one currency and the debits equal the credits; the journal, its lines and the affected balances are then written in a single transaction, with the balance rows locked in ascending account id order.

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

Validation failures (400) add an `errors` array of `{ "field", "message" }` entries; other malformed requests use 400 with a specific code such as `IDEMPOTENCY_KEY_REQUIRED`, `INVALID_PERIOD`, `PERIOD_TOO_LONG` or `INVALID_CURSOR`. Authentication failures use 401 (`AUTHENTICATION_REQUIRED`, `INVALID_TOKEN`, `TOKEN_EXPIRED`, `INVALID_CREDENTIALS`, `USER_DISABLED`), authorisation failures 403 (`ACCESS_DENIED`) and the transfer rate limit 429 (`RATE_LIMIT_EXCEEDED`). Unexpected failures return 500 with a generic message and no internal details.

## Tests

```bash
./mvnw clean verify
```

Integration tests start PostgreSQL and Kafka in Testcontainers containers on random ports, so Docker must be running. The security tests obtain real tokens from the token endpoint and run every endpoint against an anonymous caller and each of the four roles, check that a customer is refused on another customer's accounts and transfers, that expired and tampered tokens are rejected, and that audit rows are written for successful, refused and denied attempts and cannot be updated or deleted. They include an invariant test that runs a random sequence of deposits, withdrawals and reversals and then checks, with SQL, that every journal balances, that each account balance equals the sum of its ledger lines and that the cash in the vault equals what the bank owes its customers. The transfer tests run 100 concurrent transfers between two accounts and check that the total is conserved, run transfers in both directions at once to show the lock ordering prevents deadlocks, race 50 transfers for a balance that covers ten of them and expect exactly ten to succeed, send concurrent requests with one `Idempotency-Key` and expect a single transfer, and hold a balance row from another transaction to show the lock timeout fires with SQLSTATE `55P03` and that the retry then succeeds. The approval tests cover the 202 and the hold, a second transfer refused because held money is not available, the maker refused as checker, the approve, reject and expiry flows with their balances, hold states, journals and audit rows, a concurrent approve and reject that end in exactly one outcome, the ShedLock row taken by the scheduled run, closure refused while a hold is active, and a SQL check that for every account the gap between ledger and available balance equals the sum of its active holds. The statement tests compare the running balance across pages with an in-memory computation over the same postings, check the opening and closing balance of a period that starts in the middle of an account's history, post further entries after a first page was fetched and verify that the following pages neither shift nor repeat, reject malformed cursors and periods, check the CSV header and rows, and load 100 000 balanced journals (200 000 ledger lines) through one SQL statement to verify with `EXPLAIN (ANALYZE, BUFFERS)` that a statement page is served by the `(account_id, posted_at, id)` index and that a year of lines streams completely as CSV. The event tests run against the Kafka container: a transfer leaves one outbox row per account and, once relayed, one notification per account; a deposit inside a transaction that rolls back leaves no outbox row, a transfer the rules refuse leaves none either, and the outbox writer refuses to run without a transaction; the relay publishes nothing twice and takes the ShedLock row; a relay whose broker rejects every send records the attempt and the error and the next run publishes the rows; a failed event holds back later events of the same account only; six transfers on one account produce notifications in posting order; the same event delivered twice produces one notification and one `processed_event` row; and malformed JSON, an unknown schema version and an unknown account each end up in the dead-letter topic with their original headers while the consumer keeps processing. The interest tests move the fixed clock forward to accrue known balances and check the stored four-decimal amounts (including the half-way cases that separate `HALF_EVEN` from half-up), that a late run uses the balance of the accrual date rather than the current one, that a repeated day inserts nothing, that a month-end posts one `INTEREST` journal with the rounded sum and a re-run posts nothing more, that interest below one paisa is carried forward, and that current and closed accounts are skipped while frozen ones accrue. The end-of-day tests pass on a healthy ledger, then corrupt a balance row directly and insert a one-sided ledger line inside a transaction that is rolled back, and verify the report, the `ERROR` lines with masked numbers, the counter increments and the audit rows; the purge tests show that only published rows older than the retention disappear; and each scheduled entry point is called once to show it takes its ShedLock row.
