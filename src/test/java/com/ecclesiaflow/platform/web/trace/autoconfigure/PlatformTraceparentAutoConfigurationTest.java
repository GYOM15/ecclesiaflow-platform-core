package com.ecclesiaflow.platform.web.trace.autoconfigure;

import com.ecclesiaflow.platform.web.trace.TraceparentFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformTraceparentAutoConfigurationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String HEADER = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    @RestController
    static class WhoAmIController {

        @GetMapping("/trace")
        String trace() {
            String traceId = MDC.get(TraceparentFilter.MDC_KEY);
            return traceId == null ? "none" : traceId;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(WhoAmIController.class)
    static class Module {
    }

    @Configuration(proxyBeanMethods = false)
    static class ModuleWithItsOwnRegistration {

        static final FilterRegistrationBean<TraceparentFilter> OWN = new FilterRegistrationBean<>(new TraceparentFilter());

        @Bean
        FilterRegistrationBean<TraceparentFilter> moduleTraceparentFilter() {
            return OWN;
        }
    }

    static class OtherFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            chain.doFilter(request, response);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ModuleWithAnotherFilter {

        @Bean
        FilterRegistrationBean<OtherFilter> otherFilter() {
            return new FilterRegistrationBean<>(new OtherFilter());
        }
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PlatformTraceparentAutoConfiguration.class, WebMvcAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class))
            .withUserConfiguration(Module.class);

    @Test
    @DisplayName("is registered for Spring Boot to find")
    void registered() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();

        assertThat(candidates).contains(PlatformTraceparentAutoConfiguration.class.getName());
    }

    @Test
    @DisplayName("registers the filter by default, ahead of Spring Security's chain")
    void registersTheFilter() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FilterRegistrationBean.class);
            FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(TraceparentFilter.class);
            assertThat(registration.getOrder())
                    .isEqualTo(TraceparentFilter.ORDER)
                    .isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
            assertThat(registration.isEnabled()).isTrue();
        });
    }

    @Test
    @DisplayName("exposes the trace id to the controller of a request that carries a valid traceparent")
    void reachesTheController() {
        runner.run(context -> {
            MockMvc mvc = mvc(context.getBean(FilterRegistrationBean.class));

            mvc.perform(get("/trace").header("traceparent", HEADER))
                    .andExpect(status().isOk())
                    .andExpect(content().string(TRACE_ID));
            mvc.perform(get("/trace").header("traceparent", "garbage"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("none"));
            mvc.perform(get("/trace"))
                    .andExpect(content().string("none"));
            assertThat(MDC.get(TraceparentFilter.MDC_KEY)).isNull();
        });
    }

    @Test
    @DisplayName("can be switched off")
    void canBeSwitchedOff() {
        runner.withPropertyValues("ecclesiaflow.platform.web.traceparent.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    @DisplayName("stays on when the switch is set to true")
    void explicitlyOn() {
        runner.withPropertyValues("ecclesiaflow.platform.web.traceparent.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(FilterRegistrationBean.class));
    }

    @Test
    @DisplayName("is not loaded outside a servlet application")
    void notOutsideAServlet() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformTraceparentAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    @DisplayName("is registered beside the registrations of other filters, the surface filter's among them")
    void otherFiltersDoNotCount() {
        runner.withUserConfiguration(ModuleWithAnotherFilter.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(FilterRegistrationBean.class)).hasSize(2);
            assertThat(context.getBeansOfType(FilterRegistrationBean.class).values())
                    .extracting(FilterRegistrationBean::getFilter)
                    .hasAtLeastOneElementOfType(TraceparentFilter.class);
        });
    }

    @Test
    @DisplayName("backs off for a registration of the filter the module declares")
    void moduleRegistrationWins() {
        runner.withUserConfiguration(ModuleWithItsOwnRegistration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FilterRegistrationBean.class);
            assertThat(context.getBean(FilterRegistrationBean.class)).isSameAs(ModuleWithItsOwnRegistration.OWN);
        });
    }

    private static MockMvc mvc(FilterRegistrationBean<?> registration) {
        return MockMvcBuilders.standaloneSetup(new WhoAmIController()).addFilters(registration.getFilter()).build();
    }
}
