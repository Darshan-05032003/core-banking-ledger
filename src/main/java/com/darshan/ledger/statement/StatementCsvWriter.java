package com.darshan.ledger.statement;

import com.darshan.ledger.ledger.EntryDirection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.Currency;

// RFC 4180 output: comma separated, CRLF line ends, fields quoted when they need it. Debits and
// credits get their own columns, as on a printed statement. Free text that starts with a formula
// character is prefixed with an apostrophe so a spreadsheet shows it instead of evaluating it.
final class StatementCsvWriter {

    static final String HEADER =
            "posted_at,journal_reference,type,description,counterparty,debit,credit,balance,currency";

    private static final String LINE_END = "\r\n";
    private static final String FORMULA_PREFIXES = "=+-@\t\r";

    private final Writer writer;
    private final Currency currency;

    StatementCsvWriter(Writer writer, Currency currency) {
        this.writer = writer;
        this.currency = currency;
    }

    void writeHeader() {
        write(HEADER + LINE_END);
    }

    void writeLine(StatementLine line) {
        boolean debit = line.direction() == EntryDirection.DEBIT;
        write(String.join(",",
                line.postedAt().toString(),
                line.journalReference(),
                line.journalType().name(),
                text(line.description()),
                text(counterparty(line.counterparty())),
                amount(debit ? line.amount() : null),
                amount(debit ? null : line.amount()),
                amount(line.runningBalance()),
                currency.getCurrencyCode()) + LINE_END);
    }

    void flush() {
        try {
            writer.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void write(String content) {
        try {
            writer.write(content);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String counterparty(StatementCounterparty counterparty) {
        if (counterparty == null) {
            return "";
        }
        return counterparty.glAccount() != null ? counterparty.glAccount().name() : counterparty.maskedAccountNumber();
    }

    private static String amount(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = FORMULA_PREFIXES.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        boolean needsQuotes = safe.chars().anyMatch(c -> c == ',' || c == '"' || c == '\n' || c == '\r');
        return needsQuotes ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }
}
