package com.ecclesiaflow.platform.events.signing.autoconfigure;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import com.ecclesiaflow.platform.events.signing.amqp.SigningMessagePostProcessor;
import com.ecclesiaflow.platform.events.signing.amqp.VerifyingListenerAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformEventSigningAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PlatformEventSigningAutoConfiguration.class,
                    PlatformEventSigningAmqpAutoConfiguration.class));

    @Test
    void inertWhenSecretUnset() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(DomainEventSigner.class);
            assertThat(context).doesNotHaveBean(DomainEventVerifier.class);
            assertThat(context).doesNotHaveBean(SigningMessagePostProcessor.class);
            assertThat(context).doesNotHaveBean(VerifyingListenerAdvice.class);
        });
    }

    @Test
    void signerPresentButDisabledWhenSecretBlank() {
        // A blank secret satisfies @ConditionalOnProperty, so the beans wire, but the signer is disabled.
        runner.withPropertyValues("ecclesiaflow.events.hmac-secret=")
                .run(context -> {
                    assertThat(context).hasSingleBean(DomainEventSigner.class);
                    assertThat(context.getBean(DomainEventSigner.class).isEnabled()).isFalse();
                    assertThat(context.getBean(DomainEventVerifier.class)
                            .verify("ex", "rk", new byte[]{1}, null, null).isAccepted()).isTrue();
                });
    }

    @Test
    void wiresAllBeansWhenSecretPresent() {
        runner.withPropertyValues("ecclesiaflow.events.hmac-secret=my-shared-secret")
                .run(context -> {
                    assertThat(context).hasSingleBean(DomainEventSigner.class);
                    assertThat(context).hasSingleBean(DomainEventVerifier.class);
                    assertThat(context).hasSingleBean(SigningMessagePostProcessor.class);
                    assertThat(context).hasSingleBean(VerifyingListenerAdvice.class);
                    assertThat(context.getBean(DomainEventSigner.class).isEnabled()).isTrue();
                });
    }

    @Test
    void verifierHonoursVerifyFlag() {
        runner.withPropertyValues(
                        "ecclesiaflow.events.hmac-secret=my-shared-secret",
                        "ecclesiaflow.events.verify-signatures=true")
                .run(context -> {
                    DomainEventVerifier verifier = context.getBean(DomainEventVerifier.class);
                    assertThat(verifier.verify("ex", "rk", new byte[]{1}, null, null).isAccepted()).isFalse();
                });
    }

    @Test
    void verifierLenientByDefault() {
        runner.withPropertyValues("ecclesiaflow.events.hmac-secret=my-shared-secret")
                .run(context -> {
                    DomainEventVerifier verifier = context.getBean(DomainEventVerifier.class);
                    assertThat(verifier.verify("ex", "rk", new byte[]{1}, null, null).isAccepted()).isTrue();
                });
    }

    @Test
    void respectsUserOverrideBean() {
        DomainEventSigner custom = new DomainEventSigner("override");
        runner.withPropertyValues("ecclesiaflow.events.hmac-secret=ignored")
                .withBean(DomainEventSigner.class, () -> custom)
                .run(context -> assertThat(context.getBean(DomainEventSigner.class)).isSameAs(custom));
    }
}
