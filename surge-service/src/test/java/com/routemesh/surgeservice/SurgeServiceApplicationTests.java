package com.routemesh.surgeservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import com.routemesh.surgeservice.publish.RedisSurgePublisher;
import com.routemesh.surgeservice.publish.SurgePublisher;
import com.routemesh.surgeservice.zone.PricingZoneLocator;
import com.routemesh.surgeservice.zone.ZoneLocator;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "routemesh.surge.streams-enabled=false")
class SurgeServiceApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithoutStartingTheStreams() {
        assertThat(context.getBean(SurgePublisher.class)).isInstanceOf(RedisSurgePublisher.class);
        assertThat(context.getBean(ZoneLocator.class)).isInstanceOf(PricingZoneLocator.class);
        assertThat(context.getBeansOfType(org.springframework.context.SmartLifecycle.class).keySet())
                .noneMatch(name -> name.equals("surgeStreams"));
    }
}
