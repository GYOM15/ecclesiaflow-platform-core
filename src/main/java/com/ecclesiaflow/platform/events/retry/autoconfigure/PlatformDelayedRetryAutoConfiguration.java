package com.ecclesiaflow.platform.events.retry.autoconfigure;

import com.ecclesiaflow.platform.events.deadletter.DeadLetterRoute;
import com.ecclesiaflow.platform.events.retry.DelayedRetryProperties;
import com.ecclesiaflow.platform.events.retry.DelayedRetryQueue;
import com.ecclesiaflow.platform.events.retry.DelayedRetryStamp;
import com.ecclesiaflow.platform.events.retry.DelayedRetryTopology;
import com.ecclesiaflow.platform.events.retry.amqp.DelayedRetryMessageRecoverer;
import com.ecclesiaflow.platform.events.retry.amqp.DelayedRetryPublisher;
import com.ecclesiaflow.platform.events.retry.amqp.DelayedRetryQueueMetrics;
import com.ecclesiaflow.platform.events.retry.amqp.DelayedRetryTopologies;
import com.ecclesiaflow.platform.events.retry.amqp.DelayedRetryVerifier;
import com.ecclesiaflow.platform.events.retry.events.DelayedRetryEventListener;
import com.ecclesiaflow.platform.events.retry.events.DelayedRetryMetrics;
import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import com.ecclesiaflow.platform.events.signing.EventSigningProperties;
import com.ecclesiaflow.platform.events.signing.autoconfigure.PlatformEventSigningAmqpAutoConfiguration;
import com.ecclesiaflow.platform.events.signing.autoconfigure.PlatformEventSigningAutoConfiguration;
import com.ecclesiaflow.platform.events.signing.amqp.VerifyingListenerAdvice;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Active only once the module declares a {@link DelayedRetryTopology}: a module that does not opt in
 * is untouched and keeps the signing auto-configuration's {@link VerifyingListenerAdvice}. Once opted
 * in, it fails the startup rather than run half-wired: a topology on a queue that dead-letters nowhere
 * would drop the last failure, a factory without confirms could lose a copy, and a signing module
 * without stamps would refuse every redelivery.
 *
 * <p>Ordered before the signing AMQP auto-configuration so its retry-aware advice is the one bean of
 * that type.</p>
 */
@AutoConfiguration(after = PlatformEventSigningAutoConfiguration.class,
        before = PlatformEventSigningAmqpAutoConfiguration.class)
@ConditionalOnClass({RabbitTemplate.class, MessageRecoverer.class})
@ConditionalOnBean(DelayedRetryTopology.class)
@EnableConfigurationProperties(DelayedRetryProperties.class)
public class PlatformDelayedRetryAutoConfiguration {

    private static final String DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    private static final String DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";

    @Bean
    @ConditionalOnMissingBean
    public DelayedRetryTopologies delayedRetryTopologies(ObjectProvider<DelayedRetryTopology> topologies,
                                                         ObjectProvider<Queue> queues,
                                                         ObjectProvider<Declarables> declarableGroups,
                                                         ObjectProvider<DeadLetterRoute> routes) {
        DelayedRetryTopologies registry = new DelayedRetryTopologies(topologies.orderedStream().toList());
        List<Queue> declared = new ArrayList<>();
        queues.orderedStream().forEach(declared::add);
        declarableGroups.orderedStream().forEach(group -> declared.addAll(group.getDeclarablesByType(Queue.class)));
        requireDeadLetterRoutes(registry, declared, routes.orderedStream().toList());
        return registry;
    }

    /** Without a secret the stamp is disabled, like signing. */
    @Bean
    @ConditionalOnMissingBean
    public DelayedRetryStamp delayedRetryStamp(ObjectProvider<EventSigningProperties> signing) {
        EventSigningProperties properties = signing.getIfAvailable();
        return new DelayedRetryStamp(properties == null ? null : properties.getHmacSecret());
    }

    /** Hand it to the listener container's retry interceptor, in place of RejectAndDontRequeueRecoverer. */
    @Bean
    @ConditionalOnMissingBean
    public DelayedRetryMessageRecoverer delayedRetryMessageRecoverer(DelayedRetryTopologies topologies,
                                                                     DelayedRetryStamp stamp,
                                                                     ObjectProvider<DomainEventSigner> signer,
                                                                     ConnectionFactory connectionFactory,
                                                                     ApplicationEventPublisher events,
                                                                     ObjectProvider<Clock> clock,
                                                                     DelayedRetryProperties properties) {
        DomainEventSigner eventSigner = signer.getIfUnique();
        if (eventSigner != null && eventSigner.isEnabled() && !stamp.isEnabled()) {
            throw new IllegalStateException("Domain events are signed but delayed retries cannot be stamped: "
                    + "set ecclesiaflow.events.hmac-secret, or every redelivery is refused as invalid");
        }
        DelayedRetryPublisher publisher = DelayedRetryPublisher.on(connectionFactory, stamp,
                properties.getConfirmTimeout(), clockOf(clock));
        return new DelayedRetryMessageRecoverer(topologies, publisher, events);
    }

    /** Replaces the signing auto-configuration's advice, which then backs off: same checks, plus redeliveries. */
    @Bean
    @ConditionalOnMissingBean(VerifyingListenerAdvice.class)
    @ConditionalOnBean({DomainEventVerifier.class, DomainEventSigner.class})
    public VerifyingListenerAdvice retryAwareVerifyingListenerAdvice(DomainEventVerifier verifier,
                                                                     DomainEventSigner signer,
                                                                     DelayedRetryStamp stamp,
                                                                     DelayedRetryTopologies topologies,
                                                                     ObjectProvider<MeterRegistry> meterRegistry,
                                                                     ObjectProvider<Clock> clock) {
        DelayedRetryVerifier redeliveries = new DelayedRetryVerifier(signer, stamp, topologies,
                verifier.isVerifySignatures(), verifier.getFreshnessWindow(), clockOf(clock));
        return new VerifyingListenerAdvice(verifier, meterRegistry.getIfAvailable(), redeliveries);
    }

    @Bean
    @ConditionalOnMissingBean
    public DelayedRetryEventListener delayedRetryEventListener() {
        return new DelayedRetryEventListener();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class DelayedRetryMetricsConfiguration {

        /** Without a registry bean the counters are inert rather than absent, so nothing else changes. */
        @Bean
        @ConditionalOnMissingBean
        DelayedRetryMetrics delayedRetryMetrics(ObjectProvider<MeterRegistry> registry,
                                                DelayedRetryTopologies topologies) {
            return new DelayedRetryMetrics(registry.getIfUnique(),
                    topologies.all().stream().map(DelayedRetryTopology::queue).toList());
        }

        @Bean
        @ConditionalOnMissingBean
        DelayedRetryQueueMetrics delayedRetryQueueMetrics(ObjectProvider<AmqpAdmin> amqpAdmin,
                                                          DelayedRetryTopologies topologies) {
            return new DelayedRetryQueueMetrics(amqpAdmin.getIfUnique(), topologies.all().stream()
                    .flatMap(topology -> topology.retryQueues().stream())
                    .map(DelayedRetryQueue::getName)
                    .toList());
        }
    }

    static void requireDeadLetterRoutes(DelayedRetryTopologies topologies, Collection<Queue> declared,
                                        Collection<DeadLetterRoute> routes) {
        Set<String> deadLettering = declared.stream()
                .filter(queue -> !(queue instanceof DelayedRetryQueue))
                .filter(queue -> queue.getArguments().get(DEAD_LETTER_EXCHANGE) != null
                        && queue.getArguments().get(DEAD_LETTER_ROUTING_KEY) != null)
                .map(Queue::getName)
                .collect(Collectors.toSet());
        routes.forEach(route -> deadLettering.add(route.queue()));
        for (DelayedRetryTopology topology : topologies.all()) {
            if (!deadLettering.contains(topology.queue())) {
                throw new IllegalStateException("Queue '" + topology.queue() + "' has delayed retries but no "
                        + "dead-letter route: declare a DeadLetterRoute for it, or its last failure is dropped");
            }
        }
    }

    private static Clock clockOf(ObjectProvider<Clock> clock) {
        return clock.getIfUnique(Clock::systemUTC);
    }
}
