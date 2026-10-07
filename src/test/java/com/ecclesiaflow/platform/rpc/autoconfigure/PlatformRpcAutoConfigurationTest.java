package com.ecclesiaflow.platform.rpc.autoconfigure;

import com.ecclesiaflow.platform.rpc.events.S2sAuthEventListener;
import com.ecclesiaflow.platform.rpc.events.S2sAuthEvents;
import com.ecclesiaflow.platform.rpc.logging.PlatformRpcLoggingAspect;
import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sAuthClientInterceptor;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sAuthServerInterceptor;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sJwtDecoder;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sScopeRegistry;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenCache;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenClient;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenProvider;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformRpcAutoConfigurationTest {

    static final String[] S2S_PROPERTIES = {
            "ecclesiaflow.platform.rpc.s2s.client-id=ecclesiaflow-church-backend",
            "ecclesiaflow.platform.rpc.s2s.client-secret=test-secret",
            "ecclesiaflow.platform.rpc.s2s.token-url=http://localhost:1/realms/ecclesiaflow/protocol/openid-connect/token",
            "ecclesiaflow.platform.rpc.s2s.jwks-uri=http://localhost:1/realms/ecclesiaflow/protocol/openid-connect/certs",
            "ecclesiaflow.platform.rpc.s2s.issuer=http://localhost:1/realms/ecclesiaflow"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformRpcAutoConfiguration.class));

    @Test
    void staysInertWithoutAClientId() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(S2sProperties.class);
            assertThat(context).doesNotHaveBean(S2sTokenProvider.class);
            assertThat(context).doesNotHaveBean(S2sAuthClientInterceptor.class);
            assertThat(context).doesNotHaveBean(S2sAuthServerInterceptor.class);
        });
    }

    @Test
    void contributesTheS2sPipelineOnceAClientIdIsSet() {
        runner.withPropertyValues(S2S_PROPERTIES).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(S2sTokenClient.class);
            assertThat(context).hasSingleBean(S2sTokenCache.class);
            assertThat(context).hasSingleBean(S2sTokenProvider.class);
            assertThat(context).hasSingleBean(S2sAuthClientInterceptor.class);
            assertThat(context).hasSingleBean(S2sScopeRegistry.class);
            assertThat(context).hasSingleBean(S2sAuthServerInterceptor.class);
            assertThat(context).hasSingleBean(PlatformRpcLoggingAspect.class);
            assertThat(context).hasSingleBean(S2sAuthEventListener.class);
            assertThat(context.getBean(S2sScopeRegistry.class).size()).isZero();
        });
    }

    @Test
    void loggingCanBeSwitchedOff() {
        runner.withPropertyValues(S2S_PROPERTIES)
                .withPropertyValues("ecclesiaflow.platform.rpc.logging.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PlatformRpcLoggingAspect.class);
                    assertThat(context).doesNotHaveBean(S2sAuthEventListener.class);
                    assertThat(context).hasSingleBean(S2sAuthServerInterceptor.class);
                });
    }

    @Test
    void aConsumerServerInterceptorTakesPrecedence() {
        S2sAuthServerInterceptor own = mock(S2sAuthServerInterceptor.class);
        runner.withPropertyValues(S2S_PROPERTIES)
                .withBean(S2sAuthServerInterceptor.class, () -> own)
                .run(context -> assertThat(context.getBean(S2sAuthServerInterceptor.class)).isSameAs(own));
    }

    @Test
    void publishesNoBeanOfTypeJwtDecoder() {
        // A JwtDecoder bean here is what Spring Boot's resource server and every
        // by-type injection in the consuming module would pick up for the REST plane.
        runner.withPropertyValues(S2S_PROPERTIES).run(context ->
                assertThat(context.getBeansOfType(JwtDecoder.class)).isEmpty());
    }

    @Test
    void aConsumerResolvesItsOwnRestDecoderByTypeAlone() {
        runner.withPropertyValues(S2S_PROPERTIES)
                .withUserConfiguration(ConsumerWithRestDecoder.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RestDecoderUser.class).decoder())
                            .isSameAs(context.getBean("restJwtDecoder"));
                });
    }

    @Test
    void springBootStillBuildsTheRestDecoderOfAModuleThatDeclaresNone() {
        // The billing case: s2s configured, no JwtDecoder of its own, resource-server
        // properties set. The REST plane must get Boot's decoder, built from those properties.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PlatformRpcAutoConfiguration.class,
                        SecurityAutoConfiguration.class,
                        OAuth2ResourceServerAutoConfiguration.class))
                .withPropertyValues(S2S_PROPERTIES)
                .withPropertyValues("spring.security.oauth2.resourceserver.jwt.jwk-set-uri="
                        + "http://localhost:1/realms/ecclesiaflow/protocol/openid-connect/certs")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(JwtDecoder.class))
                            .containsExactly("jwtDecoderByJwkKeySetUri");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void theServerInterceptorDecodesWithTheS2sRulesNotTheRestDecoder() {
        // The REST decoder below accepts anything; if the interceptor ever resolved it,
        // a token refused by the s2s rules would reach the RPC.
        runner.withPropertyValues(S2S_PROPERTIES)
                .withUserConfiguration(ConsumerWithPermissiveRestDecoder.class)
                .withBean(S2sJwtDecoder.class, () -> new S2sJwtDecoder(token -> {
                    throw new BadJwtException("refused by the s2s rules");
                }))
                .run(context -> {
                    ServerCall<Object, Object> call = mock(ServerCall.class);
                    MethodDescriptor<Object, Object> method = mock(MethodDescriptor.class);
                    when(call.getMethodDescriptor()).thenReturn(method);
                    when(method.getFullMethodName()).thenReturn("ecclesiaflow.test.TestService/DoThing");
                    ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
                    Metadata headers = new Metadata();
                    headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer t");

                    context.getBean(S2sAuthServerInterceptor.class).interceptCall(call, headers, next);

                    ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
                    verify(call).close(status.capture(), any(Metadata.class));
                    assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    verify(next, never()).startCall(any(), any());
                });
    }

    @Test
    void statesAtStartupThatTheAzpAllowListIsOff() {
        runner.withPropertyValues(S2S_PROPERTIES)
                .withBean(AzpPolicyRecorder.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AzpPolicyRecorder.class).seen)
                            .singleElement()
                            .satisfies(policy -> assertThat(policy.enforced()).isFalse());
                });
    }

    @Test
    void statesAtStartupWhichClientsTheAzpAllowListLetsIn() {
        runner.withPropertyValues(S2S_PROPERTIES)
                .withPropertyValues("ecclesiaflow.platform.rpc.s2s.allowed-azp="
                        + "ecclesiaflow-auth-backend, ecclesiaflow-members-backend")
                .withBean(AzpPolicyRecorder.class)
                .run(context -> assertThat(context.getBean(AzpPolicyRecorder.class).seen)
                        .singleElement()
                        .satisfies(policy -> {
                            assertThat(policy.enforced()).isTrue();
                            assertThat(policy.allowedAzp()).containsExactlyInAnyOrder(
                                    "ecclesiaflow-auth-backend", "ecclesiaflow-members-backend");
                        }));
    }

    @Test
    void refusesToStartWhenTheAllowListIsRequiredButEmpty() {
        runner.withPropertyValues(S2S_PROPERTIES)
                .withPropertyValues("ecclesiaflow.platform.rpc.s2s.require-allowed-azp=true",
                        "ecclesiaflow.platform.rpc.s2s.allowed-azp=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("allowed-azp");
                });
    }

    @Test
    void startsWhenTheRequiredAllowListIsSet() {
        runner.withPropertyValues(S2S_PROPERTIES)
                .withPropertyValues("ecclesiaflow.platform.rpc.s2s.require-allowed-azp=true",
                        "ecclesiaflow.platform.rpc.s2s.allowed-azp=ecclesiaflow-auth-backend")
                .run(context -> assertThat(context).hasNotFailed());
    }

    static class AzpPolicyRecorder {

        final List<S2sAuthEvents.InboundAzpPolicy> seen = new CopyOnWriteArrayList<>();

        @EventListener
        void on(S2sAuthEvents.InboundAzpPolicy policy) {
            seen.add(policy);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConsumerWithPermissiveRestDecoder {

        @Bean
        JwtDecoder restJwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("scope", "ef:s2s ef:test:do")
                    .claim("azp", "ecclesiaflow-church-backend")
                    .subject("service-account")
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConsumerWithRestDecoder {

        @Bean
        JwtDecoder restJwtDecoder() {
            return token -> {
                throw new BadJwtException("not used");
            };
        }

        @Bean
        RestDecoderUser restDecoderUser(JwtDecoder decoder) {
            return new RestDecoderUser(decoder);
        }
    }

    record RestDecoderUser(JwtDecoder decoder) {
    }
}
