package com.chandan.payment.service;

import com.chandan.payment.config.KafkaConfig;
import com.chandan.payment.domain.model.OutboxEvent;
import com.chandan.payment.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private static final int MAX_RETRIES = 5;

    @Scheduled(fixedDelayString = "${payment.outbox.poll-rate-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(PageRequest.of(0, 50));
        if (pendingEvents.isEmpty()) {
            return;
        }

        for (OutboxEvent event : pendingEvents) {
            try {
                kafkaTemplate.send(KafkaConfig.PAYMENT_EVENTS_TOPIC, event.getAggregateId(), event.getPayload())
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                event.setStatus(OutboxEvent.EventStatus.PROCESSED);
                                event.setProcessedAt(Instant.now());
                                outboxEventRepository.save(event);
                                log.debug("Emitted outbox event: {} to Kafka", event.getId());
                            } else {
                                handleFailure(event, ex);
                            }
                        });
            } catch (Exception ex) {
                handleFailure(event, ex);
            }
        }
    }

    private void handleFailure(OutboxEvent event, Throwable ex) {
        log.error("Failed to publish outbox event: {}", event.getId(), ex);
        int retries = event.getRetryCount() + 1;
        event.setRetryCount(retries);
        event.setLastError(ex.getMessage() != null ? ex.getMessage() : "Unknown error");

        if (retries >= MAX_RETRIES) {
            event.setStatus(OutboxEvent.EventStatus.FAILED);
            // Publish to Dead Letter Queue (DLQ)
            kafkaTemplate.send(KafkaConfig.PAYMENT_DLQ_TOPIC, event.getAggregateId(), event.getPayload());
            log.warn("Moved outbox event {} to DLQ after {} failed attempts", event.getId(), retries);
        }
        outboxEventRepository.save(event);
    }
}
