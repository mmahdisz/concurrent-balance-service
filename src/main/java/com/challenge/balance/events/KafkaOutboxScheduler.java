package com.challenge.balance.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "balance.kafka", name = {"enabled", "publisher.enabled"}, havingValue = "true")
public class KafkaOutboxScheduler {
    private final KafkaOutboxPublisher publisher;

    public KafkaOutboxScheduler(KafkaOutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${balance.kafka.poll-interval:1000}")
    public void publish() {
        publisher.publishBatch();
    }
}
