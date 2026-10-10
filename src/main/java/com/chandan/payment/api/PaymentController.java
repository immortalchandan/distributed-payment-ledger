package com.chandan.payment.api;

import com.chandan.payment.api.dto.PaymentRequest;
import com.chandan.payment.api.dto.PaymentResponse;
import com.chandan.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse> initiatePayment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required 'Idempotency-Key' HTTP header");
        }

        PaymentResponse response = paymentService.processPayment(idempotencyKey.trim(), request);

        HttpHeaders headers = new HttpHeaders();
        if (response.isIdempotentReplayed()) {
            headers.add("Idempotent-Replayed", "true");
            return ResponseEntity.status(HttpStatus.OK).headers(headers).body(response);
        }

        return ResponseEntity.status(HttpStatus.CREATED).headers(headers).body(response);
    }
}
