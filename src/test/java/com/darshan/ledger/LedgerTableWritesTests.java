package com.darshan.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// ArchUnit sees classes and calls, not the table names inside SQL strings, so this is the half of
// the "only PostingService writes the ledger" rule that reads the sources: no statement anywhere
// in production code writes journal_entry or ledger_entry (they are append-only, written through
// JPA inside the ledger package), and no code outside that package writes account_balance or
// hold. What neither test can see is SQL assembled at runtime or run from outside the
// application, which is why the database triggers and the end-of-day check exist.
class LedgerTableWritesTests {

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "darshan", "ledger");
    private static final Path LEDGER_PACKAGE = SOURCES.resolve("ledger");
    private static final Pattern APPEND_ONLY_WRITE = Pattern.compile(
            "(?i)\\b(insert\\s+into|update|delete\\s+from|truncate(\\s+table)?)\\s+(journal_entry|ledger_entry)\\b");
    private static final Pattern BALANCE_WRITE = Pattern.compile(
            "(?i)\\b(insert\\s+into|update|delete\\s+from|truncate(\\s+table)?)\\s+(account_balance|hold)\\b");
    private static final Pattern LINE_COMMENT = Pattern.compile("//.*");

    @Test
    void noProductionSqlWritesTheAppendOnlyLedgerTables() {
        assertThat(offenders(APPEND_ONLY_WRITE, path -> true)).isEmpty();
    }

    @Test
    void noSqlOutsideTheLedgerPackageWritesBalancesOrHolds() {
        assertThat(offenders(BALANCE_WRITE, path -> !path.startsWith(LEDGER_PACKAGE))).isEmpty();
    }

    @Test
    void theSourceTreeIsWhereThisTestExpectsIt() {
        assertThat(SOURCES.resolve("ledger/PostingService.java")).exists();
    }

    private static List<String> offenders(Pattern pattern, java.util.function.Predicate<Path> inScope) {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(inScope)
                    .flatMap(path -> matches(path, pattern))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Stream<String> matches(Path path, Pattern pattern) {
        try {
            String source = LINE_COMMENT.matcher(Files.readString(path)).replaceAll("");
            Matcher matcher = pattern.matcher(source);
            Stream.Builder<String> found = Stream.builder();
            while (matcher.find()) {
                found.add(path + ": " + matcher.group());
            }
            return found.build();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
