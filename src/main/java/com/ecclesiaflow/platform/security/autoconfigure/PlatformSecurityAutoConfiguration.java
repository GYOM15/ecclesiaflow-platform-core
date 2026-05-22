package com.ecclesiaflow.platform.security.autoconfigure;

import com.ecclesiaflow.platform.security.KeycloakJwtConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Spring Boot auto-configuration for the web-security helpers exposed by
 * platform-core. Currently registers the shared {@link KeycloakJwtConverter}
 * so any module pulling in platform-core picks it up without an explicit
 * {@code @Import} or component scan extension.
 *
 * <p>Activates only when Spring Security's resource-server JWT classes are on
 * the classpath. Consumers that do not use OAuth2 Resource Server stay
 * unaffected.
 */
@AutoConfiguration
@ConditionalOnClass(JwtAuthenticationToken.class)
public class PlatformSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KeycloakJwtConverter keycloakJwtConverter() {
        return new KeycloakJwtConverter();
    }
}
