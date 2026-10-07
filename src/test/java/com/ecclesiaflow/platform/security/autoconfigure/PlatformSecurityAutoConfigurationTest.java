package com.ecclesiaflow.platform.security.autoconfigure;

import com.ecclesiaflow.platform.security.KeycloakJwtConverter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSecurityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformSecurityAutoConfiguration.class));

    @Test
    void registersTheKeycloakConverter() {
        runner.run(context -> assertThat(context).hasSingleBean(KeycloakJwtConverter.class));
    }

    @Test
    void aConsumerConverterTakesPrecedence() {
        KeycloakJwtConverter own = new KeycloakJwtConverter();
        runner.withBean(KeycloakJwtConverter.class, () -> own)
                .run(context -> assertThat(context.getBean(KeycloakJwtConverter.class)).isSameAs(own));
    }

    @Test
    void staysAwayFromAModuleWithoutTheResourceServer() {
        runner.withClassLoader(new FilteredClassLoader(JwtAuthenticationToken.class))
                .run(context -> assertThat(context).doesNotHaveBean(KeycloakJwtConverter.class));
    }
}
