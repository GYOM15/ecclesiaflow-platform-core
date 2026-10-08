package com.ecclesiaflow.platform.events.deadletter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

@DisplayName("DeadLetterPolicy")
class DeadLetterPolicyTest {

    private final DeadLetterPolicy policy = new DeadLetterPolicy("comm.subscriber.setup-token-issued",
            "ecclesiaflow.domain-events", "auth.setup-token.issued-dead");

    @Test
    @DisplayName("is named after the queue it applies to")
    void namedAfterTheQueue() {
        assertThat(policy.name()).isEqualTo("dead-letter.comm.subscriber.setup-token-issued");
    }

    @Test
    @DisplayName("matches its queue and no other, dots included")
    void patternMatchesOnlyTheQueue() {
        Pattern pattern = Pattern.compile(policy.pattern());

        assertThat(policy.pattern()).isEqualTo("^comm\\.subscriber\\.setup-token-issued$");
        assertThat(pattern.matcher("comm.subscriber.setup-token-issued").find()).isTrue();
        assertThat(pattern.matcher("comm.subscriber.setup-token-issued.dlq").find()).isFalse();
        assertThat(pattern.matcher("commXsubscriber.setup-token-issued").find()).isFalse();
        assertThat(pattern.matcher("x.comm.subscriber.setup-token-issued").find()).isFalse();
    }

    @Test
    @DisplayName("escapes every pattern character a queue name may hold")
    void escapesPatternCharacters() {
        DeadLetterPolicy odd = new DeadLetterPolicy("a+b(c)[d]{e}|f?g*h^i$j\\k", "x", "y");

        assertThat(Pattern.compile(odd.pattern()).matcher("a+b(c)[d]{e}|f?g*h^i$j\\k").matches()).isTrue();
    }

    @Test
    @DisplayName("routes dead letters to the exchange and key of the dead-letter queue")
    void definitionNamesExchangeAndKey() {
        assertThat(policy.definition()).containsExactly(
                entry("dead-letter-exchange", "ecclesiaflow.domain-events"),
                entry("dead-letter-routing-key", "auth.setup-token.issued-dead"));
    }

    @Test
    @DisplayName("renders the rabbitmqctl arguments that set it, on queues only")
    void rendersRabbitmqctlArguments() {
        assertThat(policy.rabbitmqctlArguments()).containsExactly(
                "set_policy", "--apply-to", "queues",
                "dead-letter.comm.subscriber.setup-token-issued",
                "^comm\\.subscriber\\.setup-token-issued$",
                "{\"dead-letter-exchange\":\"ecclesiaflow.domain-events\","
                        + "\"dead-letter-routing-key\":\"auth.setup-token.issued-dead\"}");
    }

    @Test
    @DisplayName("writes the default exchange as an empty name")
    void defaultExchangeIsEmpty() {
        DeadLetterPolicy viaDefault = new DeadLetterPolicy("q", "", "q.dlq");

        assertThat(viaDefault.rabbitmqctlArguments()).last()
                .isEqualTo("{\"dead-letter-exchange\":\"\",\"dead-letter-routing-key\":\"q.dlq\"}");
    }
}
