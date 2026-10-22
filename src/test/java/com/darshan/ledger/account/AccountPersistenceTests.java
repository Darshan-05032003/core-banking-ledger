package com.darshan.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.ledger.TestcontainersConfiguration;
import com.darshan.ledger.customer.Customer;
import com.darshan.ledger.customer.CustomerRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestcontainersConfiguration.class)
class AccountPersistenceTests {

    private static final Currency INR = Currency.getInstance("INR");
    private static final Instant NOW = Instant.parse("2026-10-22T10:15:30Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void persistsAndReloadsAnAccountWithItsValueObjects() {
        Customer customer = entityManager.persist(newCustomer("reload@example.com"));
        AccountNumber number = AccountNumber.of("0001", 1);
        Account saved = entityManager.persistFlushFind(
                new Account(number, customer, AccountType.SAVINGS, INR, TODAY, NOW));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAccountNumber()).isEqualTo(number);
        assertThat(saved.getCurrency()).isEqualTo(INR);
        assertThat(saved.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(saved.getOpenedOn()).isEqualTo(TODAY);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getCustomer().getId()).isEqualTo(customer.getId());
    }

    @Test
    void storesTheCurrencyAsAThreeCharacterCode() {
        Customer customer = entityManager.persist(newCustomer("char@example.com"));
        Account account = entityManager.persistAndFlush(
                new Account(AccountNumber.of("0001", 2), customer, AccountType.CURRENT, INR, TODAY, NOW));

        Object columnType = entityManager.getEntityManager()
                .createNativeQuery("select pg_typeof(currency)::text from account where id = :id")
                .setParameter("id", account.getId())
                .getSingleResult();

        assertThat(columnType).isEqualTo("character");
    }

    @Test
    void accountNumbersAreUnique() {
        Customer customer = entityManager.persist(newCustomer("unique@example.com"));
        AccountNumber number = AccountNumber.of("0001", 3);
        entityManager.persistAndFlush(new Account(number, customer, AccountType.SAVINGS, INR, TODAY, NOW));

        assertThatThrownBy(() -> entityManager.persistAndFlush(
                new Account(number, customer, AccountType.CURRENT, INR, TODAY, NOW)))
                .isInstanceOf(ConstraintViolationException.class)
                .extracting("constraintName").isEqualTo("uk_account_number");
    }

    // Through the repository the database error arrives translated into Spring's hierarchy.
    @Test
    void customerEmailsAreUnique() {
        customerRepository.saveAndFlush(newCustomer("dup@example.com"));

        assertThatThrownBy(() -> customerRepository.saveAndFlush(newCustomer("dup@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_customer_email");
    }

    @Test
    void closedStatusAndClosedDateMustAgree() {
        Customer customer = entityManager.persist(newCustomer("closed@example.com"));
        Account account = entityManager.persistAndFlush(
                new Account(AccountNumber.of("0001", 4), customer, AccountType.SAVINGS, INR, TODAY, NOW));

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("update account set status = 'CLOSED' where id = :id")
                .setParameter("id", account.getId())
                .executeUpdate())
                .isInstanceOf(ConstraintViolationException.class)
                .extracting("constraintName").isEqualTo("ck_account_closed_on");
    }

    @Test
    void findsTheAccountsOfACustomerInOpeningOrder() {
        Customer owner = entityManager.persist(newCustomer("owner@example.com"));
        Customer other = entityManager.persist(newCustomer("other@example.com"));
        Account first = entityManager.persist(
                new Account(AccountNumber.of("0001", 5), owner, AccountType.SAVINGS, INR, TODAY, NOW));
        Account second = entityManager.persist(
                new Account(AccountNumber.of("0001", 6), owner, AccountType.CURRENT, INR, TODAY, NOW));
        entityManager.persist(new Account(AccountNumber.of("0001", 7), other, AccountType.SAVINGS, INR, TODAY, NOW));
        entityManager.flush();
        entityManager.clear();

        assertThat(accountRepository.findByCustomerIdOrderByIdAsc(owner.getId()))
                .extracting(Account::getId)
                .containsExactly(first.getId(), second.getId());
    }

    private static Customer newCustomer(String email) {
        return new Customer("Asha Rao", email, "+919876543210", LocalDate.of(1990, 5, 17), NOW);
    }
}
