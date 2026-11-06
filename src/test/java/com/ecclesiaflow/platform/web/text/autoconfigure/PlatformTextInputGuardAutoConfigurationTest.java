package com.ecclesiaflow.platform.web.text.autoconfigure;

import com.ecclesiaflow.platform.error.autoconfigure.PlatformErrorAutoConfiguration;
import com.ecclesiaflow.platform.time.autoconfigure.PlatformClockAutoConfiguration;
import com.ecclesiaflow.platform.web.error.autoconfigure.PlatformWebErrorAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Boots the guard with the auto-configurations a service runs, so the request below crosses the real converters. */
class PlatformTextInputGuardAutoConfigurationTest {

    record Announcement(String title, String body) {
    }

    @RestController
    static class AnnouncementsController {

        @PostMapping(path = "/announcements", consumes = MediaType.APPLICATION_JSON_VALUE)
        Announcement create(@RequestBody Announcement announcement) {
            return announcement;
        }
    }

    @ImportAutoConfiguration({
            PlatformClockAutoConfiguration.class, PlatformErrorAutoConfiguration.class,
            PlatformWebErrorAutoConfiguration.class, PlatformTextInputGuardAutoConfiguration.class,
            WebMvcAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Configuration(proxyBeanMethods = false)
    @Import(AnnouncementsController.class)
    static class Service {
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner().withUserConfiguration(Service.class);

    private static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("a body's hidden characters are dropped before the controller sees it")
    void dropsHiddenCharacters() {
        runner.run(context -> mvc(context).perform(post("/announcements").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cul\\u200bte\",\"body\":\"Ve\\u2060nez\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Culte"))
                .andExpect(jsonPath("$.body").value("Venez")));
    }

    @Test
    @DisplayName("an unsafe character is a validation error naming the field, not a malformed body")
    void refusesUnsafeCharacters() {
        runner.run(context -> mvc(context).perform(post("/announcements").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ok\",\"body\":\"txt.exe\\u202egpj\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].path").value("body"))
                .andExpect(jsonPath("$.errors[0].code").value("UnsafeCharacters"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("gpj")))));
    }

    @Test
    @DisplayName("the application's own mapper, which reads Keycloak, the database and other services, is left as it was")
    void leavesTheSharedMapperAlone() {
        runner.run(context -> {
            ObjectMapper shared = context.getBean(ObjectMapper.class);
            assertThat(shared.getRegisteredModuleIds()).doesNotContain("ecclesiaflow-text-input-guard");
            assertThat(shared.readValue("{\"title\":\"a\\u202eb\",\"body\":\"\"}", Announcement.class).title())
                    .isEqualTo("a‮b");
        });
    }

    @Test
    @DisplayName("a module can turn the guard off by property")
    void canBeTurnedOff() {
        runner.withPropertyValues("ecclesiaflow.platform.web.text-input-guard.enabled=false")
                .run(context -> mvc(context).perform(post("/announcements").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"Cul\\u200bte\",\"body\":\"\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.title").value("Cul​te")));
    }

    @Test
    @DisplayName("every service gets it: the auto-configuration is registered")
    void isRegistered() {
        assertThat(ImportCandidates.load(org.springframework.boot.autoconfigure.AutoConfiguration.class,
                getClass().getClassLoader()).getCandidates())
                .contains(PlatformTextInputGuardAutoConfiguration.class.getName());
    }
}
