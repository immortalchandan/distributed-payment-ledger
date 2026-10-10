package com.chandan.payment.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String accountNumber;

    @Column(nullable = false, length = 64)
    private String ownerName;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency; // e.g. "INR", "USD"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletStatus status;

    /**
     * Optimistic locking mechanism: prevents concurrent modifications
     * and race conditions when multiple threads debit the same wallet.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public enum WalletStatus {
        ACTIVE,
        FROZEN,
        CLOSED
    }

    public boolean hasSufficientBalance(BigDecimal amount) {
        return this.balance != null && this.balance.compareTo(amount) >= 0;
    }

    public void debit(BigDecimal amount) {
        if (!hasSufficientBalance(amount)) {
            throw new IllegalStateException("Insufficient funds in wallet: " + this.id);
        }
        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }
}
