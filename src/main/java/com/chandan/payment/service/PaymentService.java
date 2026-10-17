package com.chandan.payment.service;

import com.chandan.payment.api.dto.PaymentRequest;
import com.chandan.payment.api.dto.PaymentResponse;
import com.chandan.payment.domain.model.LedgerEntry;
import com.chandan.payment.domain.model.OutboxEvent;
import com.chandan.payment.domain.model.PaymentTransaction;
import com.chandan.payment.domain.model.Wallet;
import com.chandan.payment.domain.repository.LedgerEntryRepository;
import com.chandan.payment.domain.repository.OutboxEventRepository;
import com.chandan.payment.domain.repository.PaymentTransactionRepository;
import com.chandan.payment.domain.repository.WalletRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyService idempotencyService;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    /**
     * Executes an idempotent payment transfer with distributed locking,
     * double-entry balanced ledgering, and transactional outbox event creation.
     */
    public PaymentResponse processPayment(String idempotencyKey, PaymentRequest request) {
        // Step 1: Check if this idempotency key was already processed
        Optional<PaymentResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            PaymentResponse replayed = cachedResponse.get();
            replayed.setIdempotentReplayed(true);
            log.info("Returning replayed cached response for idempotency key: {}", idempotencyKey);
            return replayed;
        }

        // Step 2: Check database in case Redis cache expired
        Optional<PaymentTransaction> existingTx = paymentTransactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTx.isPresent()) {
            PaymentResponse response = PaymentResponse.from(existingTx.get(), true);
            idempotencyService.recordSuccess(idempotencyKey, response);
            return response;
        }

        // Step 3: Acquire Idempotency lease
        boolean acquiredLease = idempotencyService.acquireLease(idempotencyKey);
        if (!acquiredLease) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Concurrent request in progress for idempotency key");
        }

        // Step 4: Deadlock-free Distributed Locking
        // Sort wallet IDs deterministically to eliminate distributed deadlocks
        List<UUID> orderedWalletIds = Arrays.asList(request.getSourceWalletId(), request.getDestinationWalletId());
        orderedWalletIds.sort(Comparator.comparing(UUID::toString));

        RLock lock1 = redissonClient.getLock("wallet:lock:" + orderedWalletIds.get(0));
        RLock lock2 = redissonClient.getLock("wallet:lock:" + orderedWalletIds.get(1));

        RLock multiLock = redissonClient.getMultiLock(lock1, lock2);

        try {
            // Wait up to 40 seconds to acquire lock under high concurrency bursts, hold for 15 seconds auto-lease
            boolean locked = multiLock.tryLock(40, 15, TimeUnit.SECONDS);
            if (!locked) {
                idempotencyService.releaseLease(idempotencyKey);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not acquire wallet locks, please retry");
            }

            try {
                PaymentResponse response = executeTransferInTransaction(idempotencyKey, request);
                idempotencyService.recordSuccess(idempotencyKey, response);
                return response;
            } catch (Exception e) {
                idempotencyService.releaseLease(idempotencyKey);
                throw e;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            idempotencyService.releaseLease(idempotencyKey);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Interrupted while locking");
        } finally {
            if (multiLock.isHeldByCurrentThread()) {
                multiLock.unlock();
            }
        }
    }

    /**
     * Atomic database transaction executing double-entry ledger debit & credit.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentResponse executeTransferInTransaction(String idempotencyKey, PaymentRequest request) {
        if (request.getSourceWalletId().equals(request.getDestinationWalletId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source and destination wallets must be different");
        }

        Wallet source = walletRepository.findById(request.getSourceWalletId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source wallet not found"));

        Wallet destination = walletRepository.findById(request.getDestinationWalletId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Destination wallet not found"));

        if (!source.getCurrency().equalsIgnoreCase(request.getCurrency()) ||
            !destination.getCurrency().equalsIgnoreCase(request.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Currency mismatch between wallets and transfer request");
        }

        if (source.getStatus() != Wallet.WalletStatus.ACTIVE || destination.getStatus() != Wallet.WalletStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both wallets must be in ACTIVE status");
        }

        BigDecimal amount = request.getAmount();
        if (!source.hasSufficientBalance(amount)) {
            // Record failed transaction for auditability
            PaymentTransaction failedTx = PaymentTransaction.builder()
                    .idempotencyKey(idempotencyKey)
                    .sourceWalletId(source.getId())
                    .destinationWalletId(destination.getId())
                    .amount(amount)
                    .currency(request.getCurrency())
                    .status(PaymentTransaction.TransactionStatus.FAILED)
                    .failureReason("Insufficient funds")
                    .build();
            paymentTransactionRepository.save(failedTx);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds in source wallet");
        }

        // 1. Debit Source & Credit Destination
        source.debit(amount);
        destination.credit(amount);

        walletRepository.save(source);
        walletRepository.save(destination);

        // 2. Persist Payment Transaction Record
        PaymentTransaction tx = PaymentTransaction.builder()
                .idempotencyKey(idempotencyKey)
                .sourceWalletId(source.getId())
                .destinationWalletId(destination.getId())
                .amount(amount)
                .currency(request.getCurrency())
                .status(PaymentTransaction.TransactionStatus.SUCCESS)
                .build();
        tx = paymentTransactionRepository.save(tx);

        // 3. Append Double-Entry Ledger Records (Debit entry and Credit entry)
        LedgerEntry debitEntry = LedgerEntry.builder()
                .transactionId(tx.getId())
                .walletId(source.getId())
                .entryType(LedgerEntry.EntryType.DEBIT)
                .amount(amount)
                .currency(request.getCurrency())
                .balanceAfter(source.getBalance())
                .description("Payment debit to wallet " + destination.getAccountNumber())
                .build();

        LedgerEntry creditEntry = LedgerEntry.builder()
                .transactionId(tx.getId())
                .walletId(destination.getId())
                .entryType(LedgerEntry.EntryType.CREDIT)
                .amount(amount)
                .currency(request.getCurrency())
                .balanceAfter(destination.getBalance())
                .description("Payment credit from wallet " + source.getAccountNumber())
                .build();

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        // 4. Transactional Outbox Pattern: Save event atomically in same DB transaction
        PaymentResponse response = PaymentResponse.from(tx, false);
        try {
            String payloadJson = objectMapper.writeValueAsString(response);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("PAYMENT")
                    .aggregateId(tx.getId().toString())
                    .eventType("PAYMENT_SETTLED")
                    .payload(payloadJson)
                    .status(OutboxEvent.EventStatus.PENDING)
                    .retryCount(0)
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event payload", e);
        }

        log.info("Successfully settled transaction: {} from {} to {} amount: {}",
                tx.getId(), source.getId(), destination.getId(), amount);

        return response;
    }
}
