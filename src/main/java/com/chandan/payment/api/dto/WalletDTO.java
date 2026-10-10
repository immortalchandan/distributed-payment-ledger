package com.chandan.payment.api.dto;

import com.chandan.payment.domain.model.Wallet;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletDTO {

    private UUID id;
    private String accountNumber;
    private String ownerName;
    private BigDecimal balance;
    private String currency;
    private Wallet.WalletStatus status;

    public static WalletDTO from(Wallet wallet) {
        return WalletDTO.builder()
                .id(wallet.getId())
                .accountNumber(wallet.getAccountNumber())
                .ownerName(wallet.getOwnerName())
                .balance(wallet.getBalance())
                .currency(wallet.getCurrency())
                .status(wallet.getStatus())
                .build();
    }
}
