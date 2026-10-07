package com.ecclesiaflow.platform.events.deadletter.autoconfigure;

import com.ecclesiaflow.platform.events.deadletter.DeadLetterQueueMetrics;
import com.ecclesiaflow.platform.events.deadletter.DeadLetterQueues;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * Bound as a {@link MeterBinder} once all beans exist, and conditional on no other bean, so the order
 * auto-configurations run in cannot change what is measured.
 */
@AutoConfiguration
@ConditionalOnClass({AmqpAdmin.class, MeterBinder.class})
@ConditionalOnProperty(prefix = "ecclesiaflow.events.dead-letter-metrics", name = "enabled", matchIfMissing = true)
public class PlatformDeadLetterMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DeadLetterQueueMetrics deadLetterQueueMetrics(ObjectProvider<AmqpAdmin> amqpAdmin,
                                                         ObjectProvider<Declarable> declarables,
                                                         ObjectProvider<Declarables> declarableGroups) {
        List<Declarable> topology = new ArrayList<>();
        declarables.orderedStream().forEach(topology::add);
        declarableGroups.orderedStream().forEach(group -> topology.addAll(group.getDeclarables()));
        return new DeadLetterQueueMetrics(amqpAdmin.getIfUnique(), DeadLetterQueues.in(topology));
    }
}
