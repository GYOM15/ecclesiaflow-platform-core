package com.ecclesiaflow.platform.security.surface;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ContractOperation;
import com.ecclesiaflow.platform.contract.InvalidApiContractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SurfaceIndexTest {

    private static ContractOperation operation(String method, String template, boolean tokenless, String surface) {
        Map<String, Object> extensions = surface == null ? Map.of() : Map.of(Surface.EXTENSION, surface);
        return new ContractOperation(method, template, null, tokenless, extensions, List.of(), "test");
    }

    private final SurfaceIndex index = new SurfaceIndex(ApiContract.of(List.of(
            operation("GET", "/members/me", false, "member"),
            operation("GET", "/members", false, null),
            operation("GET", "/moderation/reports", false, "platform"),
            operation("POST", "/members", true, null),
            operation("DELETE", "/push/devices/{id}", true, "member"),
            operation("GET", "/ws/contract-declared", false, "member"))));

    @Test
    @DisplayName("each operation that takes a token resolves to its surface")
    void ruledOperations() {
        assertThat(index.resolve("GET", "/members/me").surface()).isEqualTo(Surface.MEMBER);
        assertThat(index.resolve("GET", "/members").surface()).isEqualTo(Surface.CONSOLE);
        assertThat(index.resolve("GET", "/moderation/reports").surface()).isEqualTo(Surface.PLATFORM);
        assertThat(index.resolve("GET", "/members/me").operation().pathTemplate()).isEqualTo("/members/me");
    }

    @Test
    @DisplayName("a security: [] operation is not ruled, even one marked member")
    void tokenlessNotRuled() {
        SurfaceIndex.Resolution signup = index.resolve("POST", "/members");

        assertThat(signup.ruled()).isFalse();
        assertThat(signup.operation()).isNotNull();
        assertThat(index.resolve("DELETE", "/push/devices/abc").ruled()).isFalse();
    }

    @Test
    @DisplayName("outside the contract a technical path is not ruled, any other path is console only")
    void outsideTheContract() {
        assertThat(index.resolve("GET", "/actuator/health").ruled()).isFalse();
        assertThat(index.resolve("GET", "/actuator/health").operation()).isNull();
        assertThat(index.resolve("GET", "/unlisted").surface()).isEqualTo(Surface.CONSOLE);
        assertThat(index.resolve("PUT", "/members/me").surface()).isEqualTo(Surface.CONSOLE);
    }

    @Test
    @DisplayName("the contract wins over the technical list for a path it declares")
    void contractBeforeTechnicalList() {
        assertThat(index.resolve("GET", "/ws/contract-declared").surface()).isEqualTo(Surface.MEMBER);
    }

    @Test
    @DisplayName("counts the operations of each surface, and those without a token apart")
    void counts() {
        assertThat(index.count(Surface.MEMBER)).isEqualTo(2);
        assertThat(index.count(Surface.CONSOLE)).isEqualTo(1);
        assertThat(index.count(Surface.PLATFORM)).isEqualTo(1);
        assertThat(index.tokenlessCount()).isEqualTo(2);
        assertThat(new SurfaceIndex(ApiContract.of(List.of())).count(Surface.MEMBER)).isZero();
    }

    @Test
    @DisplayName("a misdeclared surface stops the module when the index is built")
    void misdeclared() {
        ApiContract contract = ApiContract.of(List.of(operation("GET", "/a", false, "anonymous")));

        assertThatThrownBy(() -> new SurfaceIndex(contract)).isInstanceOf(InvalidApiContractException.class);
    }
}
