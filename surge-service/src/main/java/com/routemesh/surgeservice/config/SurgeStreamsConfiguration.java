package com.routemesh.surgeservice.config;

import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.routemesh.contracts.pricing.PricingServiceGrpc;
import com.routemesh.surgeservice.publish.RedisSurgePublisher;
import com.routemesh.surgeservice.publish.SurgePublisher;
import com.routemesh.surgeservice.topology.SurgeTopology;
import com.routemesh.surgeservice.zone.PricingZoneLocator;
import com.routemesh.surgeservice.zone.ZoneLocator;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaStreamsMetrics;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class SurgeStreamsConfiguration {

    @Bean
    ZoneLocator zoneLocator(PricingServiceGrpc.PricingServiceBlockingStub pricing, SurgeProperties properties) {
        return new PricingZoneLocator(pricing, properties, System::currentTimeMillis);
    }

    @Bean
    SurgePublisher surgePublisher(StringRedisTemplate redis, SurgeProperties properties, MeterRegistry meters) {
        return new RedisSurgePublisher(redis, properties, meters);
    }

    @Bean
    @ConditionalOnProperty(name = "routemesh.surge.streams-enabled", havingValue = "true")
    SmartLifecycle surgeStreams(SurgeProperties properties, ZoneLocator zones, SurgePublisher publisher,
            JsonMapper mapper, MeterRegistry meters,
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "surge-service");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        config.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1);
        config.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 1);
        config.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 5_000);
        config.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");

        KafkaStreams streams = new KafkaStreams(SurgeTopology.build(properties, zones, publisher, mapper), config);
        streams.setUncaughtExceptionHandler(error -> StreamThreadExceptionResponse.REPLACE_THREAD);
        KafkaStreamsMetrics streamsMetrics = new KafkaStreamsMetrics(streams);
        return new SmartLifecycle() {
            @Override
            public void start() {
                streamsMetrics.bindTo(meters);
                streams.start();
            }

            @Override
            public void stop() {
                streams.close(java.time.Duration.ofSeconds(10));
                streamsMetrics.close();
            }

            @Override
            public boolean isRunning() {
                return streams.state().isRunningOrRebalancing();
            }
        };
    }
}
