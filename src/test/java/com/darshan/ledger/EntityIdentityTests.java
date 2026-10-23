package com.darshan.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountNumber;
import com.darshan.ledger.account.AccountType;
import com.darshan.ledger.auth.AppUser;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.customer.Customer;
import com.darshan.ledger.security.Role;
import com.darshan.ledger.transfer.Transfer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

// Entities are equal when they are the same row: ids are compared once assigned, an unsaved entity
// equals only itself, and the hash code is a constant so an entity put in a set before it is saved
// can still be found afterwards. Every entity follows the same contract; this checks the ones with
// public constructors, the ledger-side entities have the same check next to their own tests.
class EntityIdentityTests {

    private static final Instant NOW = Instant.parse("2026-10-22T10:15:30Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private static final Currency INR = Currency.getInstance("INR");

    static Stream<Arguments> entities() {
        return Stream.of(
                arguments("Customer", (Supplier<Object>) EntityIdentityTests::customer),
                arguments("Account", (Supplier<Object>) () -> account(null)),
                arguments("AppUser", (Supplier<Object>) () -> new AppUser("asha", "{bcrypt}hash", Set.of(Role.CUSTOMER),
                        null, "admin", NOW)),
                arguments("Transfer", (Supplier<Object>) () -> Transfer.pendingApproval("TRF-0000000001", account(1L),
                        account(2L), Money.of("250000.00", INR), "Car", "asha", NOW)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entities")
    void anUnsavedEntityEqualsOnlyItself(String name, Supplier<Object> factory) {
        Object entity = factory.get();

        assertThat(entity).isEqualTo(entity)
                .isNotEqualTo(factory.get())
                .isNotEqualTo(null)
                .isNotEqualTo("something else");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entities")
    void savedEntitiesWithTheSameIdAreEqualAndStayFindableInASet(String name, Supplier<Object> factory) {
        Object first = factory.get();
        Object second = factory.get();
        Set<Object> set = new HashSet<>();
        set.add(first);

        ReflectionTestUtils.setField(first, "id", 7L);
        ReflectionTestUtils.setField(second, "id", 7L);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(set).contains(first);

        ReflectionTestUtils.setField(second, "id", 8L);
        assertThat(first).isNotEqualTo(second);
    }

    private static Customer customer() {
        return new Customer("Asha Rao", "asha@example.com", "+919876543210", LocalDate.of(1990, 5, 17), NOW);
    }

    private static Account account(Long id) {
        Account account = new Account(AccountNumber.of("0001", id == null ? 1 : id), customer(), AccountType.SAVINGS,
                INR, TODAY, NOW);
        if (id != null) {
            ReflectionTestUtils.setField(account, "id", id);
        }
        return account;
    }
}
