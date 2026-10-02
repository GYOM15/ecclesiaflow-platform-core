package com.ecclesiaflow.platform.ratelimit.autoconfigure;

import com.ecclesiaflow.platform.ratelimit.RateLimitRule;
import com.ecclesiaflow.platform.ratelimit.RateLimitRuleRegistry;
import com.ecclesiaflow.platform.ratelimit.RateLimitSubjectResolver;
import com.ecclesiaflow.platform.ratelimit.RateLimited;
import com.ecclesiaflow.platform.ratelimit.RateLimitedHandlerValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Whether an application whose {@link RateLimited} methods would not be limited is
 * allowed to start. The library's rate-limit auto-configurations are read from its
 * own imports file, the way a consuming service picks them up.
 */
class PlatformRateLimitValidationAutoConfigurationTest {

    private static final RateLimitRuleRegistry REGISTRY =
            () -> Map.of("import", RateLimitRule.perChurch("import", 10, Duration.ofHours(1)));

    private static final RateLimitSubjectResolver SUBJECTS = scope -> Optional.of("church-1");

    @RestController
    static class ImportController {
        @RateLimited("import")
        @PostMapping("/imports")
        public void importMembers() { }
    }

    @RestController
    static class MistypedController {
        @RateLimited("imprt")
        @PostMapping("/imports")
        public void importMembers() { }
    }

    @RestController
    static class UnlimitedController {
        @PostMapping("/notes")
        public void addNote() { }
    }

    private final WebApplicationContextRunner withoutRedis = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class, RedisAutoConfiguration.class))
            .withConfiguration(AutoConfigurations.of(declaredRateLimitAutoConfigurations()));

    private final WebApplicationContextRunner runner =
            withoutRedis.withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));

    @Test
    @DisplayName("a rule name nobody declared stops the application at startup")
    void refusesToStartOnAnUndeclaredRuleName() {
        runner.withBean(RateLimitRuleRegistry.class, () -> REGISTRY)
                .withBean(RateLimitSubjectResolver.class, () -> SUBJECTS)
                .withBean(MistypedController.class)
                .run(context -> assertThat(startupFailure(context))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("'imprt'")
                        .hasMessageContaining("importMembers"));
    }

    @Test
    @DisplayName("a limited method with no subject resolver stops the application at startup")
    void refusesToStartWhenTheLimiterCannotBeWired() {
        runner.withBean(RateLimitRuleRegistry.class, () -> REGISTRY)
                .withBean(ImportController.class)
                .run(context -> assertThat(startupFailure(context))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("RateLimitSubjectResolver")
                        .hasMessageContaining("importMembers"));
    }

    @Test
    @DisplayName("a limited method with no rule registry stops the application at startup")
    void refusesToStartWithoutARegistry() {
        runner.withBean(RateLimitSubjectResolver.class, () -> SUBJECTS)
                .withBean(ImportController.class)
                .run(context -> assertThat(startupFailure(context))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("RateLimitRuleRegistry"));
    }

    @Test
    @DisplayName("a limited method in a module without Redis stops the application at startup")
    void refusesToStartWithoutALimiter() {
        withoutRedis.withBean(RateLimitRuleRegistry.class, () -> REGISTRY)
                .withBean(RateLimitSubjectResolver.class, () -> SUBJECTS)
                .withBean(ImportController.class)
                .run(context -> assertThat(startupFailure(context))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("no RateLimiter bean")
                        .hasMessageContaining("importMembers"));
    }

    @Test
    @DisplayName("a fully wired application with declared rules starts")
    void startsWhenEveryLimitedMethodIsBacked() {
        runner.withBean(RateLimitRuleRegistry.class, () -> REGISTRY)
                .withBean(RateLimitSubjectResolver.class, () -> SUBJECTS)
                .withBean(ImportController.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(RateLimitedHandlerValidator.class));
    }

    @Test
    @DisplayName("an application that limits nothing starts without a registry or a resolver")
    void startsWhenNothingIsLimited() {
        runner.withBean(UnlimitedController.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("the kill switch turns the startup check off with the limiter")
    void theKillSwitchSkipsTheCheck() {
        runner.withPropertyValues("ecclesiaflow.rate-limit.enabled=false")
                .withBean(MistypedController.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static Throwable startupFailure(AssertableWebApplicationContext context) {
        assertThat(context).hasFailed();
        return NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure());
    }

    private static Class<?>[] declaredRateLimitAutoConfigurations() {
        ClassLoader loader = PlatformRateLimitValidationAutoConfigurationTest.class.getClassLoader();
        Stream<String> names = ImportCandidates.load(AutoConfiguration.class, loader).getCandidates().stream()
                .filter(name -> name.startsWith("com.ecclesiaflow.platform.ratelimit."));
        return names.map(name -> {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }).toArray(Class<?>[]::new);
    }
}
