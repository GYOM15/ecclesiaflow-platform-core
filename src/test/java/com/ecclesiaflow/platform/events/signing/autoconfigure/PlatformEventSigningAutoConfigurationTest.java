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
                    PlatformEventSigningAutoConfiguration.AmqpHelpers.class));

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
        // An explicitly-blank secret satisfies @ConditionalOnProperty (the property
        // is present), so the beans wire — but the signer reports disabled, which is
        // the real escape hatch: signing/verification is inert despite the wiring.
        runner.withPropertyValues("ecclesiaflow.events.hmac-secret=")
                .run(context -> {
                    assertThat(context).hasSingleBean(DomainEventSigner.class);
                    assertThat(context.getBean(DomainEventSigner.class).isEnabled()).isFalse();
                    // disabled verifier accepts everything regardless of the strict flag
                    assertThat(context.getBean(DomainEventVerifier.class)
                            .verify(new byte[]{1}, null).isAccepted()).isTrue();
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
                    // strict mode → unsigned rejected
                    assertThat(verifier.verify(new byte[]{1}, null).isAccepted()).isFalse();
                });
    }

    @Test
    void verifierLenientByDefault() {
        runner.withPropertyValues("ecclesiaflow.events.hmac-secret=my-shared-secret")
                .run(context -> {
                    DomainEventVerifier verifier = context.getBean(DomainEventVerifier.class);
                    // default lenient → unsigned accepted (unverified)
                    assertThat(verifier.verify(new byte[]{1}, null).isAccepted()).isTrue();
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
