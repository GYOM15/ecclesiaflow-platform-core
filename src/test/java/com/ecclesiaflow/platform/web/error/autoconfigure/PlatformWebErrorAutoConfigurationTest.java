package com.ecclesiaflow.platform.web.error.autoconfigure;

import com.ecclesiaflow.platform.error.ErrorCategoryResolver;
import com.ecclesiaflow.platform.error.autoconfigure.PlatformErrorAutoConfiguration;
import com.ecclesiaflow.platform.time.autoconfigure.PlatformClockAutoConfiguration;
import com.ecclesiaflow.platform.web.error.PlatformRestExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the advice the way a service does: the service's own beans first, the platform's
 * auto-configuration after them, so the ordering under test is the production one.
 */
class PlatformWebErrorAutoConfigurationTest {

    static class GroupNotFoundException extends RuntimeException {
    }

    @RestController
    static class GroupsController {

        @GetMapping("/groups/missing")
        String missing() {
            throw new GroupNotFoundException();
        }

        @GetMapping("/groups/broken")
        String broken() {
            throw new IllegalStateException("bug");
        }
    }

    /** A module handler with specific mappings and no catch-all. */
    @RestControllerAdvice
    static class ModuleExceptionHandler {

        @ExceptionHandler(GroupNotFoundException.class)
        ResponseEntity<Map<String, String>> notFound() {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("errorCode", "GROUP_NOT_FOUND"));
        }
    }

    @RestControllerAdvice
    static class ModuleCatchAllHandler {

        @ExceptionHandler(Exception.class)
        ResponseEntity<Map<String, String>> any() {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("errorCode", "MODULE_CATCH_ALL"));
        }
    }

    @ImportAutoConfiguration({
            PlatformClockAutoConfiguration.class, PlatformErrorAutoConfiguration.class,
            PlatformWebErrorAutoConfiguration.class, WebMvcAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class})
    @Configuration(proxyBeanMethods = false)
    @Import({GroupsController.class, ModuleExceptionHandler.class})
    static class ServiceWithSpecificHandlers {
    }

    @ImportAutoConfiguration({
            PlatformClockAutoConfiguration.class, PlatformErrorAutoConfiguration.class,
            PlatformWebErrorAutoConfiguration.class, WebMvcAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class})
    @Configuration(proxyBeanMethods = false)
    @Import({GroupsController.class, ModuleCatchAllHandler.class})
    static class ServiceWithCatchAll {
    }

    private static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("the service's own handler keeps priority over the platform fallback")
    void serviceHandlerWins() {
        new WebApplicationContextRunner().withUserConfiguration(ServiceWithSpecificHandlers.class)
                .run(context -> mvc(context).perform(get("/groups/missing"))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.errorCode").value("GROUP_NOT_FOUND")));
    }

    @Test
    @DisplayName("what the service does not handle reaches the platform fallback instead of a 500")
    void fallbackAnswersTheRest() {
        new WebApplicationContextRunner().withUserConfiguration(ServiceWithSpecificHandlers.class)
                .run(context -> {
                    MockMvc mvc = mvc(context);
                    mvc.perform(post("/groups/missing"))
                            .andExpect(status().isMethodNotAllowed())
                            .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
                    mvc.perform(get("/groups/broken"))
                            .andExpect(status().isInternalServerError())
                            .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                            .andExpect(jsonPath("$.timestamp").isString());
                });
    }

    @Test
    @DisplayName("a service catch-all still wins: the platform advice is the lowest-priority fallback")
    void serviceCatchAllStillWins() {
        new WebApplicationContextRunner().withUserConfiguration(ServiceWithCatchAll.class)
                .run(context -> mvc(context).perform(post("/groups/missing"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.errorCode").value("MODULE_CATCH_ALL")));
    }

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PlatformClockAutoConfiguration.class, PlatformErrorAutoConfiguration.class,
                    PlatformWebErrorAutoConfiguration.class));

    @Test
    @DisplayName("registered in a servlet application, with the shared clock and resolver")
    void registered() {
        web.run(context -> {
            assertThat(context).hasSingleBean(PlatformRestExceptionHandler.class);
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context).hasSingleBean(ErrorCategoryResolver.class);
        });
    }

    @Test
    @DisplayName("a service can switch it off")
    void switchedOff() {
        web.withPropertyValues("ecclesiaflow.platform.web.errors.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PlatformRestExceptionHandler.class));
    }

    @Test
    @DisplayName("a service can replace it")
    void replaced() {
        PlatformRestExceptionHandler own = new PlatformRestExceptionHandler(
                new ErrorCategoryResolver(List.of()), Clock.systemUTC()) {
        };
        web.withBean(PlatformRestExceptionHandler.class, () -> own)
                .run(context -> assertThat(context.getBean(PlatformRestExceptionHandler.class)).isSameAs(own));
    }

    @Test
    @DisplayName("absent outside a servlet application")
    void notInNonWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PlatformClockAutoConfiguration.class, PlatformErrorAutoConfiguration.class,
                        PlatformWebErrorAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformRestExceptionHandler.class));
    }

    /** Members scans com.ecclesiaflow, the library's packages included. */
    @Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(basePackageClasses = PlatformRestExceptionHandler.class,
            useDefaultFilters = false,
            includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = PlatformRestExceptionHandler.class))
    static class ServiceScanningTheLibrary {
    }

    @Test
    @DisplayName("a service whose component scan reaches the library still starts, with a single advice")
    void scannedByService() {
        web.withUserConfiguration(ServiceScanningTheLibrary.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PlatformRestExceptionHandler.class);
                });
    }

    @Test
    @DisplayName("a test slice whose scan reaches the library, without the platform auto-configuration, still starts")
    void scannedBySliceWithoutAutoConfiguration() {
        new WebApplicationContextRunner().withUserConfiguration(ServiceScanningTheLibrary.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformRestExceptionHandler.class);
                });
    }

    @Test
    @DisplayName("two Clock beans in the service do not stop the advice from starting")
    void ambiguousClock() {
        web.withBean("a", Clock.class, Clock::systemUTC).withBean("b", Clock.class, Clock::systemUTC)
                .run(context -> assertThat(context).hasSingleBean(PlatformRestExceptionHandler.class));
    }

    @Test
    @DisplayName("listed for Spring Boot's auto-configuration import")
    void listedForImport() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();

        assertThat(candidates).contains(
                PlatformWebErrorAutoConfiguration.class.getName(),
                PlatformErrorAutoConfiguration.class.getName(),
                PlatformClockAutoConfiguration.class.getName());
    }
}
