package com.darshan.ledger.customer;

import com.darshan.ledger.common.error.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "customer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 16)
    private String phone;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 20)
    private KycStatus kycStatus;

    @Column(name = "kyc_reason")
    private String kycReason;

    @Column(name = "kyc_decided_at")
    private Instant kycDecidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Customer(String fullName, String email, String phone, LocalDate dateOfBirth, Instant createdAt) {
        this.fullName = Objects.requireNonNull(fullName);
        this.email = Objects.requireNonNull(email);
        this.phone = Objects.requireNonNull(phone);
        this.dateOfBirth = Objects.requireNonNull(dateOfBirth);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.kycStatus = KycStatus.PENDING;
    }

    public void verifyKyc(Instant decidedAt) {
        requireStatusChange(KycStatus.VERIFIED);
        kycStatus = KycStatus.VERIFIED;
        kycReason = null;
        kycDecidedAt = decidedAt;
    }

    public void rejectKyc(String reason, Instant decidedAt) {
        requireStatusChange(KycStatus.REJECTED);
        kycStatus = KycStatus.REJECTED;
        kycReason = Objects.requireNonNull(reason);
        kycDecidedAt = decidedAt;
    }

    public boolean isKycVerified() {
        return kycStatus == KycStatus.VERIFIED;
    }

    private void requireStatusChange(KycStatus target) {
        if (kycStatus == target) {
            throw new ConflictException(CustomerErrorCode.KYC_STATUS_UNCHANGED,
                    "KYC status of customer " + id + " is already " + target);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Customer that && id != null && id.equals(that.id);
    }

    // A constant hash keeps an entity findable in a HashSet before and after it receives its id.
    @Override
    public int hashCode() {
        return Customer.class.hashCode();
    }
}
