package com.chandan.payment.api;

import com.chandan.payment.api.dto.PaymentRequest;
import com.chandan.payment.api.dto.PaymentResponse;
import com.chandan.payment.api.dto.StressTestResult;
import com.chandan.payment.domain.model.LedgerEntry;
import com.chandan.payment.domain.model.Wallet;
import com.chandan.payment.domain.repository.LedgerEntryRepository;
import com.chandan.payment.domain.repository.WalletRepository;
import com.chandan.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/v1/stress-test")
@RequiredArgsConstructor
public class StressTestController {

    private static final Logger log = LoggerFactory.getLogger(StressTestController.class);

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentService paymentService;

    /**
     * Executes a high-concurrency double-spend attack simulation.
     * Demonstrates that under high-throughput concurrent load, database row-locks
     * and Redisson distributed locks strictly enforce ACID invariants and eliminate ledger drift.
     */
    @PostMapping("/double-spend-attack")
    public ResponseEntity<StressTestResult> runDoubleSpendAttack(
            @RequestParam(defaultValue = "50") int concurrency,
            @RequestParam(defaultValue = "500.00") BigDecimal initialBalance,
            @RequestParam(defaultValue = "200.00") BigDecimal debitPerThread,
            @RequestParam(required = false) UUID sourceWalletId,
            @RequestParam(required = false) UUID destinationWalletId) {

        // Constrain concurrency between 5 and 100 to prevent local thread exhaustion
        int clampedConcurrency = Math.max(5, Math.min(concurrency, 100));

        String testId = UUID.randomUUID().toString().substring(0, 8);
        Wallet source;
        Wallet dest;
        BigDecimal initialSrcBalance;
        BigDecimal initialDstBalance;

        // 1. Determine target wallets: Use active UI wallets if provided, or create isolated ephemeral test wallets
        if (sourceWalletId != null && destinationWalletId != null) {
            source = walletRepository.findById(sourceWalletId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source wallet not found: " + sourceWalletId));
            dest = walletRepository.findById(destinationWalletId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Destination wallet not found: " + destinationWalletId));

            initialSrcBalance = source.getBalance();
            initialDstBalance = dest.getBalance();
        } else {
            source = Wallet.builder()
                    .ownerName("Chaos Target (" + testId + ")")
                    .accountNumber("CHAOS-SRC-" + testId)
                    .balance(initialBalance)
                    .currency("INR")
                    .status(Wallet.WalletStatus.ACTIVE)
                    .build();
            source = walletRepository.save(source);

            LedgerEntry opening = LedgerEntry.builder()
                    .transactionId(UUID.randomUUID())
                    .walletId(source.getId())
                    .entryType(LedgerEntry.EntryType.CREDIT)
                    .amount(initialBalance)
                    .currency("INR")
                    .balanceAfter(initialBalance)
                    .description("Chaos test initial balance")
                    .build();
            ledgerEntryRepository.save(opening);

            dest = Wallet.builder()
                    .ownerName("Chaos Vault (" + testId + ")")
                    .accountNumber("CHAOS-DST-" + testId)
                    .balance(BigDecimal.ZERO)
                    .currency("INR")
                    .status(Wallet.WalletStatus.ACTIVE)
                    .build();
            dest = walletRepository.save(dest);

            initialSrcBalance = initialBalance;
            initialDstBalance = BigDecimal.ZERO;
        }

        // 2. Prepare concurrent threads coordinated by CountDownLatch
        ExecutorService executor = Executors.newFixedThreadPool(clampedConcurrency);
        CountDownLatch readyLatch = new CountDownLatch(clampedConcurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(clampedConcurrency);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);
        AtomicInteger conflictsCount = new AtomicInteger(0);
        AtomicInteger otherFailuresCount = new AtomicInteger(0);

        List<Long> latenciesMs = Collections.synchronizedList(new ArrayList<>());
        List<String> executionTimeline = Collections.synchronizedList(new ArrayList<>());

        UUID srcId = source.getId();
        UUID dstId = dest.getId();

        long globalStart = System.currentTimeMillis();

        for (int i = 1; i <= clampedConcurrency; i++) {
            final int threadIdx = i;
            executor.submit(() -> {
                String threadKey = "attack-" + testId + "-t" + threadIdx + "-" + UUID.randomUUID().toString().substring(0, 6);
                readyLatch.countDown();
                try {
                    // Block until all threads are pre-warmed and ready, then fire simultaneously
                    startLatch.await();

                    long reqStart = System.currentTimeMillis();
                    try {
                        PaymentRequest req = PaymentRequest.builder()
                                .sourceWalletId(srcId)
                                .destinationWalletId(dstId)
                                .amount(debitPerThread)
                                .currency("INR")
                                .description("Concurrent double-spend probe #" + threadIdx)
                                .build();

                        paymentService.processPayment(threadKey, req);
                        successCount.incrementAndGet();
                        long reqLatency = System.currentTimeMillis() - reqStart;
                        latenciesMs.add(reqLatency);
                        if (executionTimeline.size() < 12) {
                            executionTimeline.add("Thread #" + threadIdx + " -> 201 CREATED (" + reqLatency + "ms): Atomic debit ₹" + debitPerThread + " committed");
                        }
                    } catch (ResponseStatusException rse) {
                        long reqLatency = System.currentTimeMillis() - reqStart;
                        latenciesMs.add(reqLatency);
                        if (rse.getStatusCode().value() == 422) {
                            insufficientFundsCount.incrementAndGet();
                            if (executionTimeline.size() < 12) {
                                executionTimeline.add("Thread #" + threadIdx + " -> 422 REJECTED (" + reqLatency + "ms): Balance check prevented overdraft");
                            }
                        } else if (rse.getStatusCode().value() == 409) {
                            conflictsCount.incrementAndGet();
                            if (executionTimeline.size() < 12) {
                                executionTimeline.add("Thread #" + threadIdx + " -> 409 CONFLICT (" + reqLatency + "ms): Lease lock blocked race condition");
                            }
                        } else {
                            otherFailuresCount.incrementAndGet();
                            if (executionTimeline.size() < 12) {
                                executionTimeline.add("Thread #" + threadIdx + " -> " + rse.getStatusCode().value() + " (" + reqLatency + "ms)");
                            }
                        }
                    } catch (Exception ex) {
                        long reqLatency = System.currentTimeMillis() - reqStart;
                        latenciesMs.add(reqLatency);
                        otherFailuresCount.incrementAndGet();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        try {
            // Wait for all threads to be spawned and ready
            readyLatch.await(10, TimeUnit.SECONDS);
            // Release the barrier: all threads burst at the exact same millisecond
            startLatch.countDown();
            // Wait for all threads to complete (60s buffer for heavy serialized load on cloud DB)
            doneLatch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }

        long totalDuration = System.currentTimeMillis() - globalStart;

        // 3. Post-Test Verification & Double-Entry Ledger Audit
        Wallet updatedSource = walletRepository.findById(srcId).orElse(source);
        Wallet updatedDest = walletRepository.findById(dstId).orElse(dest);

        BigDecimal totalDebited = debitPerThread.multiply(BigDecimal.valueOf(successCount.get()));
        BigDecimal totalCredited = totalDebited;

        // Compute Ledger Invariant: Initial Source Balance - Total Debited must equal Final Source Balance
        BigDecimal expectedSourceBalance = initialSrcBalance.subtract(totalDebited);
        BigDecimal ledgerDrift = updatedSource.getBalance().subtract(expectedSourceBalance).abs();

        BigDecimal expectedDestBalance = initialDstBalance.add(totalCredited);
        boolean zeroSumPreserved = (ledgerDrift.compareTo(BigDecimal.ZERO) == 0) &&
                (updatedDest.getBalance().compareTo(expectedDestBalance) == 0);

        boolean negativeBalancePrevented = updatedSource.getBalance().compareTo(BigDecimal.ZERO) >= 0;

        // Compute p99 latency
        double p99 = 0.0;
        if (!latenciesMs.isEmpty()) {
            List<Long> sorted = new ArrayList<>(latenciesMs);
            Collections.sort(sorted);
            int p99Idx = (int) Math.ceil(0.99 * sorted.size()) - 1;
            p99 = sorted.get(Math.max(0, Math.min(p99Idx, sorted.size() - 1)));
        }

        StressTestResult result = StressTestResult.builder()
                .scenarioName(clampedConcurrency + "-Thread Competing Double-Spend Attack")
                .sourceWalletId(srcId)
                .destinationWalletId(dstId)
                .concurrencyLevel(clampedConcurrency)
                .initialSourceBalance(initialSrcBalance)
                .transferAmountPerThread(debitPerThread)
                .totalRequestsSent(clampedConcurrency)
                .successfulTransfers(successCount.get())
                .blockedInsufficientFunds(insufficientFundsCount.get())
                .conflictsBlocked(conflictsCount.get())
                .otherFailures(otherFailuresCount.get())
                .finalSourceBalance(updatedSource.getBalance())
                .finalDestinationBalance(updatedDest.getBalance())
                .totalDebited(totalDebited)
                .totalCredited(totalCredited)
                .ledgerDrift(ledgerDrift)
                .zeroSumInvariantPreserved(zeroSumPreserved)
                .negativeBalancePrevented(negativeBalancePrevented)
                .totalDurationMs(totalDuration)
                .p99LatencyMs(p99)
                .executionTimelineSample(executionTimeline)
                .build();

        return ResponseEntity.ok(result);
    }
}
