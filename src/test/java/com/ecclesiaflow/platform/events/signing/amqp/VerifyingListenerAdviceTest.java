package com.ecclesiaflow.platform.events.signing.amqp;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerifyingListenerAdviceTest {

    private static final String SECRET = "advice-test-secret";
    private static final String ROUTING_KEY = "auth.setup-token.issued.v1";

    private final DomainEventSigner signer = new DomainEventSigner(SECRET);

    private static byte[] body() {
        return "wire".getBytes(StandardCharsets.UTF_8);
    }

    private Message message(String routingKey, String signature) {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey(routingKey);
        if (signature != null) {
            props.setHeader(DomainEventSigner.SIGNATURE_HEADER, signature);
        }
        return new Message(body(), props);
    }

    private static MethodInvocation invocationWith(Object... args) throws Throwable {
        MethodInvocation inv = mock(MethodInvocation.class);
        when(inv.getArguments()).thenReturn(args);
        when(inv.proceed()).thenReturn("listener-result");
        return inv;
    }

    private VerifyingListenerAdvice advice(boolean strict) {
        return new VerifyingListenerAdvice(new DomainEventVerifier(signer, strict));
    }

    @Test
    void validSignatureProceeds() throws Throwable {
        String sig = signer.sign(body());
        MethodInvocation inv = invocationWith(message(ROUTING_KEY, sig));

        Object result = advice(true).invoke(inv);

        assertThat(result).isEqualTo("listener-result");
        verify(inv, times(1)).proceed();
    }

    @Test
    void lenientUnsignedProceeds() throws Throwable {
        MethodInvocation inv = invocationWith(message(ROUTING_KEY, null));

        advice(false).invoke(inv);

        verify(inv, times(1)).proceed();
    }

    @Test
    void strictUnsignedRejectedWithoutRequeue() throws Throwable {
        MethodInvocation inv = invocationWith(message(ROUTING_KEY, null));

        assertThatThrownBy(() -> advice(true).invoke(inv))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("Unsigned");
        verify(inv, never()).proceed();
    }

    @Test
    void strictInvalidSignatureRejectedWithoutRequeue() throws Throwable {
        MethodInvocation inv = invocationWith(message(ROUTING_KEY, "AAAA"));

        assertThatThrownBy(() -> advice(true).invoke(inv))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("invalid signature");
        verify(inv, never()).proceed();
    }

    @Test
    void noMessageArgumentIsTransparentPassThrough() throws Throwable {
        // an invocation with no AMQP Message argument must not be blocked
        MethodInvocation inv = invocationWith("not-a-message", 42);

        Object result = advice(true).invoke(inv);

        assertThat(result).isEqualTo("listener-result");
        verify(inv, times(1)).proceed();
    }

    @Test
    void disabledSignerAlwaysProceedsEvenStrict() throws Throwable {
        VerifyingListenerAdvice disabled =
                new VerifyingListenerAdvice(new DomainEventVerifier(new DomainEventSigner(""), true));
        MethodInvocation inv = invocationWith(message(ROUTING_KEY, null));

        disabled.invoke(inv);

        verify(inv, times(1)).proceed();
    }
}
