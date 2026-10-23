package com.darshan.ledger;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameStartingWith;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.darshan.ledger.common.error.ErrorCode;
import com.darshan.ledger.ledger.AccountBalance;
import com.darshan.ledger.ledger.Hold;
import com.darshan.ledger.ledger.JournalEntry;
import com.darshan.ledger.ledger.LedgerEntry;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.repository.Repository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RestController;

// The module boundaries the design relies on, checked on every build against the compiled
// production classes. Each rule names the decision it protects; the test for raw SQL against the
// ledger tables lives in LedgerTableWritesTests because bytecode does not carry table names.
@AnalyzeClasses(packages = "com.darshan.ledger", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    private static final String LEDGER = "com.darshan.ledger.ledger";

    @ArchTest
    static final ArchRule featurePackagesAreFreeOfCycles = slices()
            .matching("com.darshan.ledger.(*)..")
            .should().beFreeOfCycles()
            .because("a cycle between feature packages means neither can be understood, tested or extracted alone");

    @ArchTest
    static final ArchRule sharedPackagesDoNotDependOnFeatures = noClasses()
            .that().resideInAnyPackage("..common..", "..security..")
            .should().dependOnClassesThat(resideInAPackage("com.darshan.ledger..")
                    .and(not(resideInAnyPackage("..common..", "..security.."))))
            .because("common and security are the shared kernel every feature builds on, not the other way round");

    @ArchTest
    static final ArchRule controllersDoNotTouchRepositories = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat(dataAccess())
            .because("a controller translates HTTP; what it may read or write is decided in a service");

    @ArchTest
    static final ArchRule onlyTheLedgerPackageUsesTheLedgerRepositories = noClasses()
            .that().resideOutsideOfPackage(LEDGER)
            .should().dependOnClassesThat(ledgerWriteRepositories())
            .because("journal_entry, ledger_entry, account_balance and hold are written only through PostingService"
                    + " and HoldService");

    @ArchTest
    static final ArchRule onlyTheLedgerPackageCreatesLedgerRows = noClasses()
            .that().resideOutsideOfPackage(LEDGER)
            .should().callConstructorWhere(target(owner(belongToAnyOf(JournalEntry.class, LedgerEntry.class,
                    AccountBalance.class, Hold.class))))
            .because("a journal, a leg, a balance row or a hold comes into existence only inside the ledger package");

    @ArchTest
    static final ArchRule onlyTheLedgerPackageMovesBalances = noClasses()
            .that().resideOutsideOfPackage(LEDGER)
            .should().callMethodWhere(target(owner(assignableTo(AccountBalance.class)))
                    .and(target(nameMatching("credit|debit|hold|releaseHold"))))
            .because("every balance change goes through a locked posting or hold inside the ledger package");

    @ArchTest
    static final ArchRule onlyTheWebTierDependsOnTheWebLayer = noClasses()
            .that(not(webTier()))
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
            .because("services, entities and jobs must work the same from a request, a scheduler or a Kafka thread");

    @ArchTest
    static final ArchRule scheduledJobsRunUnderShedLock = methods()
            .that().areAnnotatedWith(Scheduled.class)
            .should().beAnnotatedWith(SchedulerLock.class)
            .because("a schedule fires on every instance; the lock is what makes it one run per schedule");

    @ArchTest
    static final ArchRule errorCodesAreEnums = classes()
            .that().implement(ErrorCode.class)
            .should().beEnums()
            .because("error codes are an API contract and a metric tag: the set must be closed and known"
                    + " at compile time");

    // Named rather than referenced: HoldRepository is package-private, which is the point.
    private static DescribedPredicate<JavaClass> ledgerWriteRepositories() {
        return name(LEDGER + ".JournalEntryRepository")
                .or(name(LEDGER + ".AccountBalanceRepository"))
                .or(name(LEDGER + ".HoldRepository"))
                .forSubtype();
    }

    private static DescribedPredicate<JavaClass> dataAccess() {
        return assignableTo(Repository.class)
                .or(annotatedWith(org.springframework.stereotype.Repository.class))
                .or(simpleNameEndingWith("Repository"))
                .or(resideInAnyPackage("org.springframework.jdbc..", "jakarta.persistence.."));
    }

    // Controllers, the request plumbing in common.web, the error rendering in common.error and the
    // security filter-chain pieces that write 401 and 403 responses themselves.
    private static DescribedPredicate<JavaClass> webTier() {
        return resideInAnyPackage("..common.web..", "..common.error..")
                .or(annotatedWith(RestController.class))
                .or(resideInAPackage("..security..").and(simpleNameStartingWith("ProblemDetail")
                        .or(simpleName("ProblemResponseWriter"))
                        .or(simpleName("SecurityConfiguration"))));
    }
}
