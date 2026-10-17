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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        // If wallet already exists for this account number, return it idempotently
        Optional<Wallet> existing = walletRepository.findByAccountNumber(accountNumber);
        if (existing.isPresent()) {
            return ResponseEntity.ok(WalletDTO.from(existing.get()));
        }

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

    @PostMapping("/reset")
    public ResponseEntity<Map<String, WalletDTO>> resetDemoWallets() {
        // Reset or create Alice (ACC-001) with 5000.00
        Wallet alice = walletRepository.findByAccountNumber("ACC-001")
                .orElseGet(() -> Wallet.builder().accountNumber("ACC-001").ownerName("Alice").currency("INR").status(Wallet.WalletStatus.ACTIVE).build());
        alice.setBalance(new BigDecimal("5000.00"));
        alice = walletRepository.save(alice);

        // Reset or create Bob (ACC-002) with 1000.00
        Wallet bob = walletRepository.findByAccountNumber("ACC-002")
                .orElseGet(() -> Wallet.builder().accountNumber("ACC-002").ownerName("Bob").currency("INR").status(Wallet.WalletStatus.ACTIVE).build());
        bob.setBalance(new BigDecimal("1000.00"));
        bob = walletRepository.save(bob);

        // Clean existing ledger entries for clean slate
        ledgerEntryRepository.deleteAll(ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(alice.getId()));
        ledgerEntryRepository.deleteAll(ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(bob.getId()));

        LedgerEntry aliceEntry = LedgerEntry.builder()
                .transactionId(UUID.randomUUID())
                .walletId(alice.getId())
                .entryType(LedgerEntry.EntryType.CREDIT)
                .amount(new BigDecimal("5000.00"))
                .currency("INR")
                .balanceAfter(new BigDecimal("5000.00"))
                .description("Initial opening balance")
                .build();
        ledgerEntryRepository.save(aliceEntry);

        LedgerEntry bobEntry = LedgerEntry.builder()
                .transactionId(UUID.randomUUID())
                .walletId(bob.getId())
                .entryType(LedgerEntry.EntryType.CREDIT)
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .balanceAfter(new BigDecimal("1000.00"))
                .description("Initial opening balance")
                .build();
        ledgerEntryRepository.save(bobEntry);

        Map<String, WalletDTO> response = new HashMap<>();
        response.put("alice", WalletDTO.from(alice));
        response.put("bob", WalletDTO.from(bob));
        return ResponseEntity.ok(response);
    }
}
