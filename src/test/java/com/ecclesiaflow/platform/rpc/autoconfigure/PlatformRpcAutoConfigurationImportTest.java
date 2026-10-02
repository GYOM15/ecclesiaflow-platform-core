package com.ecclesiaflow.platform.rpc.autoconfigure;

import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sAuthClientInterceptor;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sAuthServerInterceptor;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots through {@code @EnableAutoConfiguration}, the path a consuming service takes, in a
 * servlet context so Spring Boot's resource-server auto-configuration competes with this
 * library's exactly as it does in a module.
 */
class PlatformRpcAutoConfigurationImportTest {

    private static final String JWK_SET_URI =
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/realms/ecclesiaflow/protocol/openid-connect/certs";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumingApplication {
    }

    static ConfigurableApplicationContext boot(String... properties) {
        return new SpringApplicationBuilder(ConsumingApplication.class)
                .web(WebApplicationType.SERVLET)
                .contextFactory(webApplicationType -> {
                    AnnotationConfigServletWebApplicationContext context = new AnnotationConfigServletWebApplicationContext();
                    context.setServletContext(new MockServletContext());
                    return context;
                })
                .properties(properties)
                .properties("spring.main.banner-mode=off")
                .run();
    }

    @Test
    void theRestPlaneKeepsSpringBootsDecoder() {
        try (ConfigurableApplicationContext context = boot(withS2s(JWK_SET_URI))) {
            assertThat(context.getBeanNamesForType(JwtDecoder.class)).containsExactly("jwtDecoderByJwkKeySetUri");
        }
    }

    @Test
    void wiresTheS2sPipeline() {
        try (ConfigurableApplicationContext context = boot(withS2s(JWK_SET_URI))) {
            assertThat(context.getBeansOfType(S2sTokenProvider.class)).hasSize(1);
            assertThat(context.getBeansOfType(S2sAuthClientInterceptor.class)).hasSize(1);
            assertThat(context.getBeansOfType(S2sAuthServerInterceptor.class)).hasSize(1);
        }
    }

    @Test
    void staysInertWithoutAClientId() {
        try (ConfigurableApplicationContext context = boot(JWK_SET_URI)) {
            assertThat(context.getBeansOfType(S2sAuthServerInterceptor.class)).isEmpty();
            assertThat(context.getBeanNamesForType(JwtDecoder.class)).containsExactly("jwtDecoderByJwkKeySetUri");
        }
    }

    @Test
    void statesTheAzpPostureOnceAtStartup() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                ConsumingApplication.class, PlatformRpcAutoConfigurationTest.AzpPolicyRecorder.class)
                .web(WebApplicationType.NONE)
                .properties(withS2s("ecclesiaflow.platform.rpc.s2s.allowed-azp=ecclesiaflow-auth-backend"))
                .properties("spring.main.banner-mode=off")
                .run()) {
            assertThat(context.getBean(PlatformRpcAutoConfigurationTest.AzpPolicyRecorder.class).seen)
                    .singleElement()
                    .satisfies(policy -> assertThat(policy.allowedAzp()).containsExactly("ecclesiaflow-auth-backend"));
        }
    }

    static String[] withS2s(String... extra) {
        String[] all = new String[PlatformRpcAutoConfigurationTest.S2S_PROPERTIES.length + extra.length];
        System.arraycopy(PlatformRpcAutoConfigurationTest.S2S_PROPERTIES, 0, all, 0,
                PlatformRpcAutoConfigurationTest.S2S_PROPERTIES.length);
        System.arraycopy(extra, 0, all, PlatformRpcAutoConfigurationTest.S2S_PROPERTIES.length, extra.length);
        return all;
    }
}
