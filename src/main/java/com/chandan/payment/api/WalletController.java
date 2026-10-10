package com.chandan.payment.api;

import com.chandan.payment.api.dto.WalletDTO;
import com.chandan.payment.domain.model.LedgerEntry;
import com.chandan.payment.domain.model.Wallet;
import com.chandan.payment.domain.repository.LedgerEntryRepository;
import com.chandan.payment.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @PostMapping
    public ResponseEntity<WalletDTO> createWallet(
            @RequestParam String ownerName,
            @RequestParam String accountNumber,
            @RequestParam(defaultValue = "1000.00") BigDecimal initialBalance,
            @RequestParam(defaultValue = "INR") String currency) {

        Wallet wallet = Wallet.builder()
                .ownerName(ownerName)
                .accountNumber(accountNumber)
                .balance(initialBalance)
                .currency(currency.toUpperCase())
                .status(Wallet.WalletStatus.ACTIVE)
                .build();

        wallet = walletRepository.save(wallet);

        // Record opening balance ledger entry
        LedgerEntry openingEntry = LedgerEntry.builder()
                .transactionId(UUID.randomUUID())
                .walletId(wallet.getId())
                .entryType(LedgerEntry.EntryType.CREDIT)
                .amount(initialBalance)
                .currency(wallet.getCurrency())
                .balanceAfter(wallet.getBalance())
                .description("Initial opening balance")
                .build();
        ledgerEntryRepository.save(openingEntry);

        return ResponseEntity.status(HttpStatus.CREATED).body(WalletDTO.from(wallet));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WalletDTO> getWallet(@PathVariable UUID id) {
        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found"));
        return ResponseEntity.ok(WalletDTO.from(wallet));
    }

    @GetMapping("/{id}/ledger")
    public ResponseEntity<List<LedgerEntry>> getWalletLedger(@PathVariable UUID id) {
        if (!walletRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found");
        }
        return ResponseEntity.ok(ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(id));
    }
}
