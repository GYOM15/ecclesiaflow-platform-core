package com.ecclesiaflow.platform.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RateLimitedHandlerValidatorTest {

    @SuppressWarnings("unused")
    static class Controller {
        @RateLimited("import")
        public void importMembers() { }

        @RateLimited("exprt")
        public void exportLedger() { }

        @RateLimited("statment")
        public void printStatement() { }

        public void readNotes() { }
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Controller(), Controller.class.getMethod(name));
    }

    private static RateLimitedHandlerValidator validator(Map<String, Object> beans) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory(beans);
        return new RateLimitedHandlerValidator(
                factory.getBeanProvider(RequestMappingHandlerMapping.class),
                factory.getBeanProvider(RateLimiter.class),
                factory.getBeanProvider(RateLimitRuleRegistry.class),
                factory.getBeanProvider(RateLimitSubjectResolver.class));
    }

    private static Map<String, Object> wired() {
        RateLimitRuleRegistry registry =
                () -> Map.of("import", RateLimitRule.perChurch("import", 10, Duration.ofHours(1)));
        RateLimitSubjectResolver subjects = scope -> Optional.of("church-1");
        return Map.of(
                "limiter", mock(RateLimiter.class),
                "registry", registry,
                "subjects", subjects);
    }

    @Test
    @DisplayName("every undeclared rule is named once, even for a handler mapped on several paths")
    void namesEveryUndeclaredRuleOnce() throws Exception {
        HandlerMethod export = handler("exportLedger");
        List<HandlerMethod> handlers = List.of(
                handler("importMembers"), export, export, handler("printStatement"));

        assertThatThrownBy(() -> validator(wired()).validate(handlers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'exprt'")
                .hasMessageContaining("'statment'")
                .hasMessageNotContaining("'import'")
                .satisfies(e -> assertThat(e.getMessage()).containsOnlyOnce("exportLedger"));
    }

    @Test
    @DisplayName("every missing collaborator is listed, so one restart fixes them all")
    void listsEveryMissingCollaborator() throws Exception {
        List<HandlerMethod> handlers = List.of(handler("importMembers"));

        assertThatThrownBy(() -> validator(Map.of()).validate(handlers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no RateLimiter, no RateLimitRuleRegistry, no RateLimitSubjectResolver bean");
    }

    @Test
    @DisplayName("a module with no limited handler needs none of the limiter beans")
    void ignoresUnlimitedHandlers() throws Exception {
        List<HandlerMethod> handlers = List.of(handler("readNotes"));

        assertThatCode(() -> validator(Map.of()).validate(handlers)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("declared rules on a wired module pass")
    void acceptsDeclaredRules() throws Exception {
        List<HandlerMethod> handlers = List.of(handler("importMembers"), handler("readNotes"));

        assertThatCode(() -> validator(wired()).validate(handlers)).doesNotThrowAnyException();
    }
}
