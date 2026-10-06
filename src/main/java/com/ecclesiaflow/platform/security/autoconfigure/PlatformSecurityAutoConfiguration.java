package com.ecclesiaflow.platform.security.autoconfigure;

import com.ecclesiaflow.platform.security.KeycloakJwtConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@AutoConfiguration
@ConditionalOnClass(JwtAuthenticationToken.class)
public class PlatformSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KeycloakJwtConverter keycloakJwtConverter() {
        return new KeycloakJwtConverter();
    }
}
