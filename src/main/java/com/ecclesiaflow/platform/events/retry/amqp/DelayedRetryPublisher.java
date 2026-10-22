package com.ecclesiaflow.platform.events.retry.amqp;

import com.ecclesiaflow.platform.events.retry.DelayedRetryQueue;
import com.ecclesiaflow.platform.events.retry.DelayedRetryStamp;
import com.ecclesiaflow.platform.events.retry.RetryHop;
import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes a copy of a failed delivery into a retry stage and waits for the broker's confirm, so the
 * caller acknowledges the original only once the copy is safe. The copy keeps the event's body and
 * signature headers untouched; a signing post-processor would re-sign it for the stage's queue, so
 * this publisher has a template of its own.
 */
public class DelayedRetryPublisher {

    static final String CORRELATION_PREFIX = "retry-";
    private static final String DEFAULT_EXCHANGE = "";

    // The death headers are the broker's (RabbitMQ 3.13 would fold a republished x-death into its death
    // history); the hop headers are rewritten for the new stage.
    private static final Set<String> NOT_COPIED = Set.of("x-death",
            "x-first-death-queue", "x-first-death-reason", "x-first-death-exchange",
            "x-last-death-queue", "x-last-death-reason", "x-last-death-exchange",
            RetryHop.QUEUE_HEADER, RetryHop.ATTEMPT_HEADER, RetryHop.RETRIED_AT_HEADER,
            RetryHop.ORIGINAL_EXCHANGE_HEADER, RetryHop.ORIGINAL_ROUTING_KEY_HEADER, RetryHop.STAMP_HEADER);

    private final RabbitTemplate template;
    private final DelayedRetryStamp stamp;
    private final Duration confirmTimeout;
    private final Clock clock;

    /** The template must publish mandatory on a connection factory with correlated confirms and returns. */
    public DelayedRetryPublisher(RabbitTemplate template, DelayedRetryStamp stamp, Duration confirmTimeout,
                                 Clock clock) {
        if (confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.isZero()) {
            throw new IllegalArgumentException("ecclesiaflow.events.delayed-retry.confirm-timeout must be positive");
        }
        this.template = template;
        this.stamp = stamp;
        this.confirmTimeout = confirmTimeout;
        this.clock = clock;
    }

    /**
     * Builds its template on the module's connection factory. Throws {@link IllegalStateException} when
     * the factory cannot confirm or return: the original would be acknowledged while its copy is lost.
     */
    public static DelayedRetryPublisher on(ConnectionFactory connectionFactory, DelayedRetryStamp stamp,
                                           Duration confirmTimeout, Clock clock) {
        if (!connectionFactory.isPublisherConfirms()) {
            throw new IllegalStateException("Delayed retries need correlated publisher confirms "
                    + "(spring.rabbitmq.publisher-confirm-type=correlated)");
        }
        if (!connectionFactory.isPublisherReturns()) {
            throw new IllegalStateException("Delayed retries need publisher returns to detect a missing stage "
                    + "(spring.rabbitmq.publisher-returns=true)");
        }
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setReturnsCallback(returned -> { });
        return new DelayedRetryPublisher(template, stamp, confirmTimeout, clock);
    }

    /**
     * Returns the hop the copy carries. Throws {@link RetryPublishFailedException} when the broker did not
     * confirm the copy into the stage.
     */
    public RetryHop publish(Message failed, DelayedRetryQueue stage, String originalExchange,
                            String originalRoutingKey) {
        RetryHop hop = new RetryHop(stage.returnQueue(), stage.attempt(), clock.millis(), originalExchange,
                originalRoutingKey);
        CorrelationData correlation = new CorrelationData(CORRELATION_PREFIX + UUID.randomUUID());
        try {
            template.send(DEFAULT_EXCHANGE, stage.getName(), copyOf(failed, hop), correlation);
        } catch (AmqpException e) {
            throw new RetryPublishFailedException(stage.getName(), describe(e), e);
        }
        awaitConfirm(correlation, stage.getName());
        return hop;
    }

    private Message copyOf(Message failed, RetryHop hop) {
        MessageProperties source = failed.getMessageProperties();
        MessageProperties copy = new MessageProperties();
        copy.setContentType(source.getContentType());
        copy.setContentEncoding(source.getContentEncoding());
        copy.setMessageId(source.getMessageId());
        copy.setTimestamp(source.getTimestamp());
        copy.setType(source.getType());
        copy.setCorrelationId(source.getCorrelationId());
        copy.setAppId(source.getAppId());
        copy.setPriority(source.getPriority());
        // Never inherited: a transient copy would not survive a broker restart during a 2 h stage.
        copy.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        source.getHeaders().forEach((name, value) -> {
            if (!NOT_COPIED.contains(name)) {
                copy.setHeader(name, value);
            }
        });
        hop.headers().forEach(copy::setHeader);
        if (stamp.isEnabled()) {
            copy.setHeader(RetryHop.STAMP_HEADER, stamp.sign(hop,
                    text(source.getHeader(DomainEventSigner.SIGNED_AT_HEADER)),
                    text(source.getHeader(DomainEventSigner.SIGNATURE_HEADER)), failed.getBody()));
        }
        return new Message(failed.getBody(), copy);
    }

    private void awaitConfirm(CorrelationData correlation, String retryQueue) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RetryPublishFailedException(retryQueue, "no broker confirm within " + confirmTimeout, e);
        } catch (ExecutionException e) {
            throw new RetryPublishFailedException(retryQueue, describe(e.getCause()), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryPublishFailedException(retryQueue, "interrupted before the broker confirmed", e);
        }
        if (!confirm.isAck()) {
            throw new RetryPublishFailedException(retryQueue,
                    confirm.getReason() == null ? "broker nack" : "broker nack: " + confirm.getReason(), null);
        }
        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            throw new RetryPublishFailedException(retryQueue, "unroutable, the stage is not declared: "
                    + returned.getReplyCode() + " " + returned.getReplyText(), null);
        }
    }

    private static String text(Object header) {
        return header == null ? null : header.toString();
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
}
