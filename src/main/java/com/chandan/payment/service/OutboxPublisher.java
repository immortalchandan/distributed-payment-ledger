package com.chandan.payment.service;

import com.chandan.payment.config.KafkaConfig;
import com.chandan.payment.domain.model.OutboxEvent;
import com.chandan.payment.domain.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final boolean isStandaloneDemo;

    private static final int MAX_RETRIES = 5;

    @Autowired
    public OutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate,
            @Value("${payment.standalone-demo.enabled:false}") boolean isStandaloneDemo) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.isStandaloneDemo = isStandaloneDemo;
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-rate-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(PageRequest.of(0, 50));
        if (pendingEvents.isEmpty()) {
            return;
        }

        for (OutboxEvent event : pendingEvents) {
            if (isStandaloneDemo || kafkaTemplate == null) {
                // In demo mode without Kafka, simulate instant broker acknowledgement
                event.setStatus(OutboxEvent.EventStatus.PROCESSED);
                event.setProcessedAt(Instant.now());
                outboxEventRepository.save(event);
                log.info("[Standalone Demo] Event {} (type: {}) acknowledged and settled locally",
                        event.getId(), event.getEventType());
                continue;
            }

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
            if (kafkaTemplate != null) {
                kafkaTemplate.send(KafkaConfig.PAYMENT_DLQ_TOPIC, event.getAggregateId(), event.getPayload());
            }
            log.warn("Moved outbox event {} to DLQ after {} failed attempts", event.getId(), retries);
        }
        outboxEventRepository.save(event);
    }
}
