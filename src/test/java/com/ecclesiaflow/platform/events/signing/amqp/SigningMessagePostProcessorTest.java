package com.ecclesiaflow.platform.events.signing.amqp;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SigningMessagePostProcessorTest {

    private static final String SECRET = "post-processor-test-secret";

    private final DomainEventSigner signer = new DomainEventSigner(SECRET);
    private final SigningMessagePostProcessor processor = new SigningMessagePostProcessor(signer);

    private static Message message(byte[] body) {
        return new Message(body, new MessageProperties());
    }

    @Test
    void stampsVerifiableSignatureHeader() {
        byte[] body = "body-bytes".getBytes(StandardCharsets.UTF_8);
        Message out = processor.postProcessMessage(message(body));

        String sig = (String) out.getMessageProperties().getHeader(DomainEventSigner.SIGNATURE_HEADER);
        assertThat(sig).isNotBlank();
        // header is verifiable against the same body
        assertThat(signer.matches(out.getBody(), sig)).isTrue();
    }

    @Test
    void doesNotMutateBody() {
        byte[] original = "body-bytes".getBytes(StandardCharsets.UTF_8);
        Message out = processor.postProcessMessage(message("body-bytes".getBytes(StandardCharsets.UTF_8)));
        assertThat(out.getBody()).isEqualTo(original);
    }

    @Test
    void noOpWhenSigningDisabled() {
        SigningMessagePostProcessor disabled =
                new SigningMessagePostProcessor(new DomainEventSigner(""));
        Message out = disabled.postProcessMessage(message("body".getBytes(StandardCharsets.UTF_8)));
        String header = out.getMessageProperties().getHeader(DomainEventSigner.SIGNATURE_HEADER);
        assertThat(header).isNull();
    }
}
