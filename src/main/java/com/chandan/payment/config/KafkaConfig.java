package com.chandan.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    public static final String PAYMENT_EVENTS_TOPIC = "payment.events.v1";
    public static final String PAYMENT_DLQ_TOPIC = "payment.events.v1.dlq";

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(PAYMENT_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentDlqTopic() {
        return TopicBuilder.name(PAYMENT_DLQ_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
