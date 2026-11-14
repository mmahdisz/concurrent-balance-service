package com.challenge.balance.events;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("balance.kafka")
public record KafkaEventProperties(
        @DefaultValue("balance.account-changed.v1") @NotBlank String topic,
        @DefaultValue("100") @Min(1) int batchSize,
        @DefaultValue("15s") Duration sendTimeout,
        @DefaultValue("6") @Min(1) int partitions,
        @DefaultValue("3") @Min(1) short replicas,
        @DefaultValue("2") @Min(1) int minInSyncReplicas) {
    @AssertTrue(message = "send-timeout must be positive")
    public boolean isSendTimeoutValid() {
        return sendTimeout != null && sendTimeout.toMillis() > 0;
    }

    @AssertTrue(message = "min-in-sync-replicas must not exceed replicas")
    public boolean isReplicationValid() {
        return minInSyncReplicas <= replicas;
    }
}
