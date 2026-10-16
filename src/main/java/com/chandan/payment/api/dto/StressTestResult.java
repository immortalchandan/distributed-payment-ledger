package com.chandan.payment.api.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StressTestResult {
    private String scenarioName;
    private int concurrencyLevel;
    private BigDecimal initialSourceBalance;
    private BigDecimal transferAmountPerThread;
    private int totalRequestsSent;
    private int successfulTransfers;
    private int blockedInsufficientFunds;
    private int conflictsBlocked;
    private int otherFailures;
    private BigDecimal finalSourceBalance;
    private BigDecimal finalDestinationBalance;
    private BigDecimal totalDebited;
    private BigDecimal totalCredited;
    private BigDecimal ledgerDrift;
    private boolean zeroSumInvariantPreserved;
    private boolean negativeBalancePrevented;
    private long totalDurationMs;
    private double p99LatencyMs;
    private List<String> executionTimelineSample;
}
