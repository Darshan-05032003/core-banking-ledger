package com.darshan.ledger.statement;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountService;
import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// A statement is a report over an append-only history, so it never locks anything. The reads run
// at REPEATABLE READ: the balances and the lines then come from one snapshot of the ledger, and a
// deposit that lands between the two queries cannot show up in the closing balance but not in
// the lines (or the other way round).
@Service
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    private final AccountService accountService;
    private final StatementRepository repository;
    private final StatementProperties properties;
    private final Clock clock;

    public StatementService(AccountService accountService, StatementRepository repository,
            StatementProperties properties, Clock clock) {
        this.accountService = accountService;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatementResponse page(Long accountId, LocalDate from, LocalDate to, String cursor, int size) {
        StatementPeriod period = period(from, to);
        StatementCursor after = cursor == null ? null : StatementCursor.decode(cursor);
        Account account = accountService.requireAccount(accountId);

        PeriodBalances balances = repository.balances(accountId, period);
        List<StatementLine> fetched = repository.linesAfter(accountId, period, after, size + 1);
        boolean hasMore = fetched.size() > size;
        List<StatementLine> lines = hasMore ? List.copyOf(fetched.subList(0, size)) : fetched;
        String nextCursor = hasMore ? StatementCursor.after(lines.getLast()).encode() : null;

        log.info("Statement page for account {} from {} to {} returned {} lines", account.getAccountNumber(),
                period.from(), period.to(), lines.size());
        return new StatementResponse(accountId, account.getAccountNumber().masked(),
                account.getCurrency().getCurrencyCode(), period.from(), period.to(), balances.opening(),
                balances.closing(), lines, nextCursor);
    }

    @Transactional(readOnly = true)
    public StatementDownload prepareDownload(Long accountId, LocalDate from, LocalDate to) {
        StatementPeriod period = period(from, to);
        Account account = accountService.requireAccount(accountId);
        return new StatementDownload(accountId, account.getAccountNumber().masked(), account.getCurrency(), period);
    }

    // Runs on the thread that writes the response, after the headers have gone out. The
    // transaction stays open for the whole write because the stream reads from a database
    // cursor in batches; closing it early would leave the cursor with nothing to read from.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void writeCsv(StatementDownload download, OutputStream out) {
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        StatementCsvWriter csv = new StatementCsvWriter(writer, download.currency());
        long written = 0;
        csv.writeHeader();
        try (Stream<StatementLine> lines = repository.streamLines(download.accountId(), download.period())) {
            for (StatementLine line : (Iterable<StatementLine>) lines::iterator) {
                csv.writeLine(line);
                if (++written % StatementRepository.STREAM_FETCH_SIZE == 0) {
                    csv.flush();
                }
            }
        }
        csv.flush();
        log.info("Statement CSV for account {} from {} to {} streamed {} lines", download.maskedAccountNumber(),
                download.period().from(), download.period().to(), written);
    }

    private StatementPeriod period(LocalDate from, LocalDate to) {
        return StatementPeriod.of(from, to, properties.maxPeriod(), clock.getZone());
    }
}
