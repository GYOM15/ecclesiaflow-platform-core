package com.ecclesiaflow.platform.events.retry.amqp;

import com.ecclesiaflow.platform.events.retry.DelayedRetryQueue;
import com.ecclesiaflow.platform.events.retry.DelayedRetryTopology;
import com.ecclesiaflow.platform.events.retry.RetryHop;
import com.ecclesiaflow.platform.events.retry.events.DelayedRetryEvents;
import com.ecclesiaflow.platform.events.retry.events.DelayedRetryEvents.DeadLetterCause;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.handler.invocation.MethodArgumentResolutionException;

import java.util.Optional;

/**
 * The recoverer of a listener container whose consumers have an external effect, in place of
 * {@code RejectAndDontRequeueRecoverer}: once the container's in-memory retries are spent, a delivery
 * from a queue with a {@link DelayedRetryTopology} moves to its next stage, and only the failure of the
 * last stage reaches the dead-letter queue. A queue without a topology, and a failure no wait can fix,
 * are dead-lettered at once, as before.
 *
 * <p>Returning normally lets the container acknowledge the original: the copy is confirmed by then.</p>
 */
public class DelayedRetryMessageRecoverer implements MessageRecoverer {

    private final DelayedRetryTopologies topologies;
    private final DelayedRetryPublisher publisher;
    private final ApplicationEventPublisher events;

    public DelayedRetryMessageRecoverer(DelayedRetryTopologies topologies, DelayedRetryPublisher publisher,
                                        ApplicationEventPublisher events) {
        this.topologies = topologies;
        this.publisher = publisher;
        this.events = events;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        MessageProperties properties = message.getMessageProperties();
        String queue = properties.getConsumerQueue();
        Optional<DelayedRetryTopology> topology = topologies.forQueue(queue);
        if (topology.isEmpty()) {
            throw deadLetter(message, cause, new DelayedRetryEvents.DeadLettered(queue, DeadLetterCause.EXHAUSTED,
                    0, describe(cause)));
        }
        Optional<RetryHop> previous = topologies.claimedHop(message);
        int retries = previous.map(RetryHop::attempt).orElse(0);
        if (!isRetryable(cause)) {
            throw deadLetter(message, cause, new DelayedRetryEvents.DeadLettered(queue,
                    DeadLetterCause.NOT_RETRYABLE, retries, describe(cause)));
        }
        int attempt = retries + 1;
        if (!topology.get().schedule().hasStage(attempt)) {
            throw deadLetter(message, cause, new DelayedRetryEvents.DeadLettered(queue, DeadLetterCause.EXHAUSTED,
                    retries, describe(cause)));
        }
        DelayedRetryQueue stage = topology.get().retryQueue(attempt);
        // The first failure is on the published route; later ones keep the route the hop carried back.
        String exchange = previous.map(RetryHop::originalExchange).orElse(properties.getReceivedExchange());
        String routingKey = previous.map(RetryHop::originalRoutingKey).orElse(properties.getReceivedRoutingKey());
        try {
            publisher.publish(message, stage, exchange, routingKey);
        } catch (RetryPublishFailedException e) {
            throw deadLetter(message, e, new DelayedRetryEvents.DeadLettered(queue, DeadLetterCause.PUBLISH_FAILED,
                    retries, describe(e) + " after " + describe(cause)));
        }
        events.publishEvent(new DelayedRetryEvents.RetryScheduled(queue, attempt,
                topology.get().schedule().stages(), stage.delay(), stage.getName(), describe(cause)));
    }

    private ListenerExecutionFailedException deadLetter(Message message, Throwable cause,
                                                        DelayedRetryEvents.DeadLettered event) {
        events.publishEvent(event);
        return new ListenerExecutionFailedException("Delivery dead-lettered (" + event.cause() + ")",
                new AmqpRejectAndDontRequeueException(cause), message);
    }

    // Spring's fatal causes (ConditionalRejectingErrorHandler) and an explicit reject anywhere in the chain.
    private static boolean isRetryable(Throwable cause) {
        for (Throwable current = cause; current != null; current = next(current)) {
            if (current instanceof AmqpRejectAndDontRequeueException
                    || current instanceof MessageConversionException
                    || current instanceof org.springframework.messaging.converter.MessageConversionException
                    || current instanceof MethodArgumentResolutionException
                    || current instanceof NoSuchMethodException
                    || current instanceof ClassCastException) {
                return false;
            }
        }
        return true;
    }

    private static Throwable next(Throwable current) {
        return current.getCause() == current ? null : current.getCause();
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "no cause";
        }
        Throwable root = error;
        while (next(root) != null) {
            root = next(root);
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
