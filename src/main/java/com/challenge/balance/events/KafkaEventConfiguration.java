package com.challenge.balance.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(KafkaEventProperties.class)
@ConditionalOnProperty(prefix = "balance.kafka", name = "enabled", havingValue = "true")
public class KafkaEventConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "balance.kafka", name = "create-topic", havingValue = "true")
    NewTopic balanceChangedTopic(KafkaEventProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(properties.partitions())
                .replicas(properties.replicas())
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(properties.minInSyncReplicas()))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }
}
