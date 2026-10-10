package com.chandan.payment;

import com.chandan.payment.api.dto.PaymentRequest;
import com.chandan.payment.api.dto.PaymentResponse;
import com.chandan.payment.domain.model.LedgerEntry;
import com.chandan.payment.domain.model.PaymentTransaction;
import com.chandan.payment.domain.model.Wallet;
import com.chandan.payment.domain.repository.LedgerEntryRepository;
import com.chandan.payment.domain.repository.PaymentTransactionRepository;
import com.chandan.payment.domain.repository.WalletRepository;
import com.chandan.payment.service.IdempotencyService;
import com.chandan.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private WalletRepository walletRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private com.chandan.payment.domain.repository.OutboxEventRepository outboxEventRepository;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock1;
    @Mock
    private RLock lock2;
    @Mock
    private RLock multiLock;

    private PaymentService paymentService;

    private UUID sourceId;
    private UUID destId;
    private Wallet sourceWallet;
    private Wallet destWallet;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                walletRepository,
                ledgerEntryRepository,
                paymentTransactionRepository,
                outboxEventRepository,
                idempotencyService,
                redissonClient,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );

        sourceId = UUID.randomUUID();
        destId = UUID.randomUUID();

        sourceWallet = Wallet.builder()
                .id(sourceId)
                .accountNumber("ACC-100")
                .ownerName("Alice")
                .balance(new BigDecimal("1000.00"))
                .currency("INR")
                .status(Wallet.WalletStatus.ACTIVE)
                .version(1L)
                .build();

        destWallet = Wallet.builder()
                .id(destId)
                .accountNumber("ACC-200")
                .ownerName("Bob")
                .balance(new BigDecimal("500.00"))
                .currency("INR")
                .status(Wallet.WalletStatus.ACTIVE)
                .version(1L)
                .build();
    }

    @Test
    @DisplayName("Should return cached response with replayed flag when idempotency key exists")
    void shouldReturnCachedResponse_WhenIdempotencyKeyExists() {
        String key = "test-idem-key-123";
        PaymentResponse cached = PaymentResponse.builder()
                .transactionId(UUID.randomUUID())
                .idempotencyKey(key)
                .status(PaymentTransaction.TransactionStatus.SUCCESS)
                .build();

        when(idempotencyService.getCachedResponse(key)).thenReturn(Optional.of(cached));

        PaymentRequest request = PaymentRequest.builder()
                .sourceWalletId(sourceId)
                .destinationWalletId(destId)
                .amount(new BigDecimal("100.00"))
                .currency("INR")
                .build();

        PaymentResponse result = paymentService.processPayment(key, request);

        assertThat(result).isNotNull();
        assertThat(result.isIdempotentReplayed()).isTrue();
        verifyNoInteractions(redissonClient);
    }

    @Test
    @DisplayName("Should reject concurrent duplicate request when lease acquisition fails")
    void shouldThrowConflict_WhenConcurrentLeaseCannotBeAcquired() {
        String key = "test-idem-key-conflict";
        when(idempotencyService.getCachedResponse(key)).thenReturn(Optional.empty());
        when(paymentTransactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLease(key)).thenReturn(false);

        PaymentRequest request = PaymentRequest.builder()
                .sourceWalletId(sourceId)
                .destinationWalletId(destId)
                .amount(new BigDecimal("100.00"))
                .currency("INR")
                .build();

        assertThatThrownBy(() -> paymentService.processPayment(key, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Concurrent request in progress");
    }

    @Test
    @DisplayName("Should execute balanced double-entry transfer and produce ledger entries")
    void shouldExecuteBalancedDoubleEntryTransfer() throws Exception {
        String key = "test-idem-key-success";
        when(idempotencyService.getCachedResponse(key)).thenReturn(Optional.empty());
        when(paymentTransactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLease(key)).thenReturn(true);

        when(redissonClient.getLock(anyString())).thenReturn(lock1);
        when(redissonClient.getMultiLock(any(RLock.class), any(RLock.class))).thenReturn(multiLock);
        when(multiLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(multiLock.isHeldByCurrentThread()).thenReturn(true);

        when(walletRepository.findById(sourceId)).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findById(destId)).thenReturn(Optional.of(destWallet));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class))).thenAnswer(i -> {
            PaymentTransaction tx = i.getArgument(0);
            tx.setId(UUID.randomUUID());
            return tx;
        });

        PaymentRequest request = PaymentRequest.builder()
                .sourceWalletId(sourceId)
                .destinationWalletId(destId)
                .amount(new BigDecimal("250.00"))
                .currency("INR")
                .build();

        PaymentResponse response = paymentService.processPayment(key, request);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(PaymentTransaction.TransactionStatus.SUCCESS);
        assertThat(response.getAmount()).isEqualByComparingTo("250.00");

        // Verify balance arithmetic
        assertThat(sourceWallet.getBalance()).isEqualByComparingTo("750.00");
        assertThat(destWallet.getBalance()).isEqualByComparingTo("750.00");

        // Verify double-entry ledger entries persisted
        verify(ledgerEntryRepository, times(2)).save(any(LedgerEntry.class));

        // Verify outbox event persisted
        verify(outboxEventRepository, times(1)).save(any());

        // Verify idempotency cache updated
        verify(idempotencyService, times(1)).recordSuccess(eq(key), any(PaymentResponse.class));
    }
}
