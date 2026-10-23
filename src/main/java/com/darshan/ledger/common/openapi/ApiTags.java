package com.darshan.ledger.common.openapi;

import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;

// One tag per module, in the order a reader of the documentation meets them: sign in, then the
// customer's life cycle, then money movements, then what only staff and operators use.
public final class ApiTags {

    public static final String AUTH = "Auth";
    public static final String CUSTOMERS = "Customers";
    public static final String ACCOUNTS = "Accounts";
    public static final String TELLER = "Teller";
    public static final String TRANSFERS = "Transfers";
    public static final String APPROVALS = "Approvals";
    public static final String STATEMENTS = "Statements";
    public static final String JOURNALS = "Journals";
    public static final String ADMIN = "Admin";
    public static final String JOBS = "Jobs";

    private ApiTags() {
    }

    static List<Tag> ordered() {
        return List.of(
                tag(AUTH, "Exchange a username and password for a short-lived bearer token"),
                tag(CUSTOMERS, "Onboarding and KYC decisions"),
                tag(ACCOUNTS, "Account opening, status changes and balances"),
                tag(TELLER, "Cash deposits and withdrawals at the counter"),
                tag(TRANSFERS, "Idempotent transfers between customer accounts"),
                tag(APPROVALS, "Maker-checker queue for high-value transfers"),
                tag(STATEMENTS, "Ledger lines with running balances, as pages or CSV"),
                tag(JOURNALS, "Posted journals and their reversals"),
                tag(ADMIN, "Login management"),
                tag(JOBS, "Interest accrual and end-of-day checks run on demand"));
    }

    private static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }
}
