package com.ecclesiaflow.platform.rpc.events;

import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class S2sAuthEventListenerTest {

    private static final String METHOD = "ecclesiaflow.members.MembersService/GetMember";
    private static final String KEYCLOAK_TOKEN_URL =
            "http://keycloak:8080/realms/ecclesiaflow/protocol/openid-connect/token";

    private final S2sAuthEventListener listener = new S2sAuthEventListener();
    private final LogCaptor logs = LogCaptor.forClass(S2sAuthEventListener.class);

    @AfterEach
    void tearDown() {
        logs.close();
    }

    @Nested
    @DisplayName("reasons coming from exception messages are sanitized")
    class SanitizedReasons {

        @Test
        @DisplayName("outbound token failure: the Keycloak URL is not logged")
        void outboundTokenUnavailable() {
            listener.onOutboundTokenUnavailable(new S2sAuthEvents.OutboundTokenUnavailable(
                    METHOD, "Failed to contact token endpoint: " + KEYCLOAK_TOKEN_URL));

            assertThat(logs.getWarnLogs()).singleElement().asString()
                    .contains(METHOD)
                    .doesNotContain("keycloak")
                    .doesNotContain("http://");
        }

        @Test
        @DisplayName("invalid inbound token: the JWKS URL and host are not logged")
        void inboundInvalidToken() {
            String reason = "An error occurred while attempting to decode the Jwt: Couldn't retrieve remote JWK set: "
                    + "I/O error on GET request for \"http://keycloak:8080/realms/ecclesiaflow/protocol/openid-connect/certs\": "
                    + "Connection refused: keycloak/172.18.0.3:8080";

            listener.onInboundInvalidToken(new S2sAuthEvents.InboundInvalidToken(METHOD, reason));

            assertThat(logs.getWarnLogs()).singleElement().asString()
                    .contains(METHOD)
                    .contains("Couldn't retrieve remote JWK set")
                    .doesNotContain("keycloak")
                    .doesNotContain("172.18.0.3");
        }
    }

    @Nested
    @DisplayName("the inbound azp policy is stated once at startup")
    class AzpPolicy {

        @Test
        @DisplayName("an open plane is a warning")
        void anOpenPlaneIsAWarning() {
            listener.onInboundAzpPolicy(new S2sAuthEvents.InboundAzpPolicy(Set.of()));

            assertThat(logs.getWarnLogs()).singleElement().asString().contains("allowed-azp");
            assertThat(logs.getInfoLogs()).isEmpty();
        }

        @Test
        @DisplayName("an enforced list names the clients it lets in")
        void anEnforcedListNamesTheClientsItLetsIn() {
            listener.onInboundAzpPolicy(new S2sAuthEvents.InboundAzpPolicy(Set.of("ecclesiaflow-auth-backend")));

            assertThat(logs.getInfoLogs()).singleElement().asString().contains("ecclesiaflow-auth-backend");
            assertThat(logs.getWarnLogs()).isEmpty();
        }
    }
}
