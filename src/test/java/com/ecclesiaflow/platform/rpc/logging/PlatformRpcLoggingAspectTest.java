package com.ecclesiaflow.platform.rpc.logging;

import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenCache;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenClient;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenException;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenProvider;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformRpcLoggingAspectTest {

    private static final String FAILURE =
            "Failed to contact token endpoint: http://keycloak:8080/realms/ecclesiaflow/protocol/openid-connect/token";

    private final LogCaptor logs = LogCaptor.forClass(PlatformRpcLoggingAspect.class);
    private S2sTokenClient client;
    private S2sTokenProvider provider;

    @BeforeEach
    void setUp() {
        S2sProperties props = new S2sProperties();
        props.setRefreshLeewaySeconds(5);
        S2sTokenClient target = mock(S2sTokenClient.class);
        client = advised(target);
        provider = advised(new S2sTokenProvider(client, new S2sTokenCache(), props));
        when(target.fetchToken()).thenThrow(new S2sTokenException(FAILURE));
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    @Test
    @DisplayName("a failed token fetch is logged without the Keycloak URL")
    void fetchFailureIsSanitized() {
        assertThatThrownBy(client::fetchToken).isInstanceOf(S2sTokenException.class);

        assertThat(logs.getErrorLogs()).singleElement().asString()
                .contains("S2sTokenException")
                .doesNotContain("keycloak")
                .doesNotContain("http://");
    }

    @Test
    @DisplayName("a caller's failed getToken() is logged without the Keycloak URL")
    void providerFailureIsSanitized() {
        assertThatThrownBy(provider::getToken).isInstanceOf(S2sTokenException.class);

        assertThat(logs.getWarnLogs()).singleElement().asString()
                .doesNotContain("keycloak")
                .doesNotContain("http://");
    }

    @Test
    @DisplayName("a successful fetch is announced")
    void fetchSuccessIsAnnounced() {
        S2sTokenClient target = mock(S2sTokenClient.class);
        when(target.fetchToken()).thenReturn(new com.ecclesiaflow.platform.rpc.s2s.token.S2sToken(
                "jwt", Instant.now().plusSeconds(300)));

        advised(target).fetchToken();

        assertThat(logs.getInfoLogs()).singleElement().asString().contains("Obtained fresh s2s token");
    }

    @SuppressWarnings("unchecked")
    private static <T> T advised(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new PlatformRpcLoggingAspect());
        return (T) factory.getProxy();
    }
}
