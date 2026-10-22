package com.darshan.ledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Seeded by migration and read-only for the application; the bank's chart of accounts is not
// something a request should be able to change.
@Entity
@Table(name = "gl_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GlAccount {

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private GlAccountCode code;

    @Column(nullable = false, updatable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private GlAccountType type;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof GlAccount that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return GlAccount.class.hashCode();
    }
}
