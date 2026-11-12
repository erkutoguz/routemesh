package com.routemesh.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;

class ActuatorObservationPredicateTest {

    private final ActuatorObservationPredicate predicate = new ActuatorObservationPredicate();

    @Test
    void actuatorRequestsAreNotObserved() {
        assertThat(observed("/actuator/health")).isFalse();
        assertThat(observed("/actuator/prometheus")).isFalse();
        assertThat(observed("/actuator")).isFalse();
    }

    @Test
    void businessRequestsAndLookalikePathsAreObserved() {
        assertThat(observed("/api/v1/shipments")).isTrue();
        assertThat(observed("/actuators/health")).isTrue();
        assertThat(observed("/api/v1/actuator/health")).isTrue();
    }

    @Test
    void nonHttpObservationsAreUntouched() {
        assertThat(predicate.test("spring.kafka.listener", new Observation.Context())).isTrue();
    }

    @Test
    void theAutoConfigurationRegistersThePredicateWhenSpringWebIsPresent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformObservabilityAutoConfiguration.class))
                .run(context -> assertThat(context).hasSingleBean(ObservationPredicate.class)
                        .hasSingleBean(ActuatorObservationPredicate.class));
    }

    private boolean observed(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        return predicate.test("http.server.requests",
                new ServerRequestObservationContext(request, new MockHttpServletResponse()));
    }
}
