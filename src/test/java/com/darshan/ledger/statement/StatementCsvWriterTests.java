package com.darshan.ledger.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.darshan.ledger.ledger.EntryDirection;
import com.darshan.ledger.ledger.GlAccountCode;
import com.darshan.ledger.ledger.JournalType;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class StatementCsvWriterTests {

    private static final Instant POSTED_AT = Instant.parse("2026-10-22T10:15:30Z");
    private static final Currency INR = Currency.getInstance("INR");

    private final StringWriter out = new StringWriter();
    private final StatementCsvWriter writer = new StatementCsvWriter(out, INR);

    @Test
    void aDebitFillsTheDebitColumnAndACreditTheCreditColumn() {
        writer.writeHeader();
        writer.writeLine(line(EntryDirection.DEBIT, "250.50", "749.50", "Rent, October",
                new StatementCounterparty("XXXXXXXX0057", null)));
        writer.writeLine(line(EntryDirection.CREDIT, "1000.00", "1000.00", "Opening cash",
                new StatementCounterparty(null, GlAccountCode.CASH_VAULT)));
        writer.writeLine(line(EntryDirection.CREDIT, "1.00", "1001.00", null, null));
        writer.flush();

        assertThat(out.toString()).isEqualTo(
                "posted_at,journal_reference,type,description,counterparty,debit,credit,balance,currency\r\n"
                        + "2026-10-22T10:15:30Z,JRN-0000000001,TRANSFER,\"Rent, October\",XXXXXXXX0057,"
                        + "250.50,,749.50,INR\r\n"
                        + "2026-10-22T10:15:30Z,JRN-0000000001,TRANSFER,Opening cash,CASH_VAULT,,"
                        + "1000.00,1000.00,INR\r\n"
                        + "2026-10-22T10:15:30Z,JRN-0000000001,TRANSFER,,,,1.00,1001.00,INR\r\n");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingTextIsAnEmptyField(String value) {
        assertThat(StatementCsvWriter.text(value)).isEmpty();
    }

    static Stream<Arguments> text() {
        return Stream.of(
                arguments("Rent", "Rent"),
                arguments("Rent, October", "\"Rent, October\""),
                arguments("Say \"hi\"", "\"Say \"\"hi\"\"\""),
                arguments("=1+2", "'=1+2"),
                arguments("=HYPERLINK(\"http://evil\")", "\"'=HYPERLINK(\"\"http://evil\"\")\""),
                arguments("+1 234", "'+1 234"),
                arguments("-cmd", "'-cmd"),
                arguments("@SUM(A1)", "'@SUM(A1)"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("text")
    void textIsQuotedWhenItNeedsToBeAndFormulaPrefixesAreNeutralised(String value, String expected) {
        assertThat(StatementCsvWriter.text(value)).isEqualTo(expected);
    }

    @Test
    void lineBreaksAndTabsInsideTextAreQuotedAndCannotStartAFormula() {
        assertThat(StatementCsvWriter.text("line\nbreak")).isEqualTo("\"line\nbreak\"");
        assertThat(StatementCsvWriter.text("\tindented")).isEqualTo("'\tindented");
        assertThat(StatementCsvWriter.text("\rreturn")).isEqualTo("\"'\rreturn\"");
    }

    @Test
    void anOutputFailureSurfacesInsteadOfProducingATruncatedFile() {
        Writer broken = new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                throw new IOException("client went away");
            }

            @Override
            public void flush() throws IOException {
                throw new IOException("client went away");
            }

            @Override
            public void close() {
            }
        };
        StatementCsvWriter failing = new StatementCsvWriter(broken, INR);

        assertThatThrownBy(failing::writeHeader).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(failing::flush).isInstanceOf(UncheckedIOException.class);
    }

    private static StatementLine line(EntryDirection direction, String amount, String runningBalance,
            String description, StatementCounterparty counterparty) {
        return new StatementLine(1L, 1L, "JRN-0000000001", JournalType.TRANSFER, description, POSTED_AT, direction,
                new BigDecimal(amount), counterparty, new BigDecimal(runningBalance));
    }
}
