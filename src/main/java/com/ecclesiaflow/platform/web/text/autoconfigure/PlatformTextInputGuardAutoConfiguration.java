package com.ecclesiaflow.platform.web.text.autoconfigure;

import com.ecclesiaflow.platform.web.text.TextInputGuardModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Guards the bodies the API reads, and only them: the guard lives in the MVC converters' own copy of the mapper,
 * so what a module reads from Keycloak, its database or another service, older rows included, is never refused.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({ObjectMapper.class, WebMvcConfigurer.class})
@ConditionalOnProperty(prefix = "ecclesiaflow.platform.web.text-input-guard", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PlatformTextInputGuardAutoConfiguration {

    @Bean
    public WebMvcConfigurer textInputGuardConverters() {
        return new WebMvcConfigurer() {
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                // A new converter in the same place: the shared one also serves the HTTP clients built from it.
                converters.replaceAll(converter -> converter instanceof MappingJackson2HttpMessageConverter json
                        ? guarded(json)
                        : converter);
            }
        };
    }

    private static MappingJackson2HttpMessageConverter guarded(MappingJackson2HttpMessageConverter json) {
        ObjectMapper mapper = json.getObjectMapper().copy().registerModule(new TextInputGuardModule());
        MappingJackson2HttpMessageConverter guarded = new MappingJackson2HttpMessageConverter(mapper);
        guarded.setSupportedMediaTypes(json.getSupportedMediaTypes());
        return guarded;
    }
}
