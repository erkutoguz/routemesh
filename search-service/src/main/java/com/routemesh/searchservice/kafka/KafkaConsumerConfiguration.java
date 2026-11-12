package com.routemesh.searchservice.kafka;

import java.time.DateTimeException;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.routemesh.searchservice.config.SearchProperties;

import tools.jackson.core.JacksonException;

@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfiguration {

    /**
     * Geçici hatalarda üstel bekleme ile sınırlı sayıda dener, sonra olayı kaynak konunun DLT'sine yazıp sonrakine
     * geçer. Bozuk JSON, geçersiz kimlik/zaman ve veritabanı kısıtını çiğneyen veri hiç denenmeden DLT'ye gider.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, SearchProperties properties) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(properties.retryAttempts());
        backOff.setInitialInterval(properties.retryInitialInterval().toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(properties.retryMaxInterval().toMillis());
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, error) -> new TopicPartition(record.topic() + properties.dltSuffix(), -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class,
                DateTimeException.class, DataIntegrityViolationException.class);
        return handler;
    }
}
