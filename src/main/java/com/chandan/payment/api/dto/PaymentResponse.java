package com.chandan.payment.api.dto;

import com.chandan.payment.domain.model.PaymentTransaction;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentResponse {

    private UUID transactionId;
    private String idempotencyKey;
    private UUID sourceWalletId;
    private UUID destinationWalletId;
    private BigDecimal amount;
    private String currency;
    private PaymentTransaction.TransactionStatus status;
    private boolean idempotentReplayed;
    private Instant createdAt;

    public static PaymentResponse from(PaymentTransaction tx, boolean replayed) {
        return PaymentResponse.builder()
                .transactionId(tx.getId())
                .idempotencyKey(tx.getIdempotencyKey())
                .sourceWalletId(tx.getSourceWalletId())
                .destinationWalletId(tx.getDestinationWalletId())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .status(tx.getStatus())
                .idempotentReplayed(replayed)
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
