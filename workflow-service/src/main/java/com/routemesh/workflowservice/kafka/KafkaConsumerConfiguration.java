package com.routemesh.workflowservice.kafka;

import java.time.Duration;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;

@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfiguration {

    /**
     * Temporal ya da fleet geçici erişilemezse aynı olayı üstel bekleme ile (1 sn'den 60 sn'ye, toplam ~10 dk) dener;
     * bütçe biterse ya da olay bozuksa (hiç denenmeden) kaynak konunun DLT'sine yazıp sonrakine geçer. Olay asla
     * sessizce düşmez: DLT yazımı da başarısız olursa kayıt yeniden teslim edilir.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, MeterRegistry meters,
            @Value("${routemesh.kafka.retry-initial-interval:1s}") Duration initialInterval,
            @Value("${routemesh.kafka.retry-max-interval:60s}") Duration maxInterval,
            @Value("${routemesh.kafka.retry-max-elapsed:10m}") Duration maxElapsed,
            @Value("${routemesh.kafka.dlt-suffix:.workflow-service.dlt}") String dltSuffix) {
        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval.toMillis(), 2.0);
        backOff.setMaxInterval(maxInterval.toMillis());
        backOff.setMaxElapsedTime(maxElapsed.toMillis());
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, error) -> new TopicPartition(record.topic() + dltSuffix, -1));
        // topic dinlenen kaynak konudur (sabit küme)
        DefaultErrorHandler handler = new DefaultErrorHandler((record, error) -> {
            recoverer.accept(record, error);
            meters.counter("routemesh.workflow.dlt.messages", "topic", record.topic()).increment();
        }, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class);
        return handler;
    }
}
