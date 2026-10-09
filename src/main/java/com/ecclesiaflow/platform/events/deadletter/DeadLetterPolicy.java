package com.ecclesiaflow.platform.events.deadletter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The broker policy that dead-letters one subscription queue, as {@code rabbitmqctl set_policy} takes it. */
public record DeadLetterPolicy(String queue, String deadLetterExchange, String deadLetterRoutingKey) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATTERN_SAFE = "-_";

    public String name() {
        return "dead-letter." + queue;
    }

    /** Anchored and escaped: RabbitMQ reads it as a regular expression, and a queue name is full of dots. */
    public String pattern() {
        StringBuilder pattern = new StringBuilder("^");
        queue.codePoints().forEach(c -> {
            if (!Character.isLetterOrDigit(c) && PATTERN_SAFE.indexOf(c) < 0) {
                pattern.append('\\');
            }
            pattern.appendCodePoint(c);
        });
        return pattern.append('$').toString();
    }

    public Map<String, String> definition() {
        Map<String, String> definition = new LinkedHashMap<>();
        definition.put("dead-letter-exchange", deadLetterExchange);
        definition.put("dead-letter-routing-key", deadLetterRoutingKey);
        return definition;
    }

    /** Queues only: an exchange matching the pattern must not pick the policy up. */
    public List<String> rabbitmqctlArguments() {
        return List.of("set_policy", "--apply-to", "queues", name(), pattern(), definitionJson());
    }

    private String definitionJson() {
        try {
            return JSON.writeValueAsString(definition());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A map of strings always writes as JSON", e);
        }
    }
}
