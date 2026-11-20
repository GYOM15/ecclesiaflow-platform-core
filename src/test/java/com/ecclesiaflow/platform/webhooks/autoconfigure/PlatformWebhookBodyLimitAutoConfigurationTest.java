package com.ecclesiaflow.platform.webhooks.autoconfigure;

import com.ecclesiaflow.platform.webhooks.web.WebhookBodyLimitFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformWebhookBodyLimitAutoConfigurationTest {

    @RestController
    static class NotificationController {

        @PostMapping("/payments/webhooks/test")
        String receive(@RequestBody byte[] body) {
            return Integer.toString(body.length);
        }
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformWebhookBodyLimitAutoConfiguration.class));

    private final WebApplicationContextRunner enabled = runner.withPropertyValues(
            "ecclesiaflow.webhooks.body-limit.enabled=true",
            "ecclesiaflow.webhooks.body-limit.path-prefixes=/payments/webhooks/");

    @Test
    @DisplayName("is registered for Spring Boot to find")
    void registered() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();

        assertThat(candidates).contains(PlatformWebhookBodyLimitAutoConfiguration.class.getName());
    }

    @Test
    @DisplayName("contributes nothing to a module that does not enable it")
    void inertByDefault() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    @DisplayName("is not loaded outside a servlet application")
    void notOutsideAServlet() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformWebhookBodyLimitAutoConfiguration.class))
                .withPropertyValues("ecclesiaflow.webhooks.body-limit.enabled=true",
                        "ecclesiaflow.webhooks.body-limit.path-prefixes=/payments/webhooks/")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    @DisplayName("once enabled, registers the filter ahead of Spring Security's chain, 256 KiB by default")
    void registersTheFilter() {
        enabled.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FilterRegistrationBean.class);
            FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(WebhookBodyLimitFilter.class);
            assertThat(registration.getOrder())
                    .isEqualTo(WebhookBodyLimitFilter.ORDER)
                    .isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
            MockMvc mvc = mvc(registration);
            mvc.perform(post("/payments/webhooks/test").content(new byte[262_144]))
                    .andExpect(status().isOk()).andExpect(content().string("262144"));
            mvc.perform(post("/payments/webhooks/test").content(new byte[262_145]))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.errorCode").value(WebhookBodyLimitFilter.ERROR_CODE));
        });
    }

    @Test
    @DisplayName("bounds at the configured size")
    void configuredBound() {
        enabled.withPropertyValues("ecclesiaflow.webhooks.body-limit.max-bytes=16").run(context -> {
            MockMvc mvc = mvc(context.getBean(FilterRegistrationBean.class));
            byte[] seventeen = new byte[17];
            Arrays.fill(seventeen, (byte) 'x');

            mvc.perform(post("/payments/webhooks/test").content(seventeen)).andExpect(status().isPayloadTooLarge());
            mvc.perform(post("/payments/webhooks/test").content(new byte[16])).andExpect(status().isOk());
        });
    }

    @Test
    @DisplayName("fails the startup when enabled without a route to bound")
    void needsARoute() {
        runner.withPropertyValues("ecclesiaflow.webhooks.body-limit.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("path-prefixes"));
    }

    private static MockMvc mvc(FilterRegistrationBean<?> registration) {
        return MockMvcBuilders.standaloneSetup(new NotificationController()).addFilters(registration.getFilter())
                .build();
    }
}
