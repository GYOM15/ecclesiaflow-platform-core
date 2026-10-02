package com.ecclesiaflow.platform.rpc.error.autoconfigure;

import com.ecclesiaflow.platform.error.ErrorCategory;
import com.ecclesiaflow.platform.error.ErrorCategoryResolver;
import com.ecclesiaflow.platform.error.ExceptionClassifier;
import com.ecclesiaflow.platform.error.autoconfigure.PlatformErrorAutoConfiguration;
import com.ecclesiaflow.platform.rpc.error.GrpcExceptionServerInterceptor;
import com.ecclesiaflow.platform.rpc.error.GrpcStatusMapper;
import com.ecclesiaflow.platform.rpc.events.RpcCallFailedListener;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformRpcErrorAutoConfigurationTest {

    static class ChurchNotFoundException extends RuntimeException {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PlatformErrorAutoConfiguration.class, PlatformRpcErrorAutoConfiguration.class));

    @Test
    @DisplayName("the interceptor is available without the s2s client being configured")
    void wiredWithoutS2s() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GrpcExceptionServerInterceptor.class);
            assertThat(context).hasSingleBean(GrpcStatusMapper.class);
            assertThat(context).hasSingleBean(RpcCallFailedListener.class);
        });
    }

    @Test
    @DisplayName("the service's classifier beans reach the status mapping")
    void usesServiceClassifiers() {
        runner.withBean("churchErrors", ExceptionClassifier.class,
                        () -> ExceptionClassifier.byType(Map.of(ChurchNotFoundException.class, ErrorCategory.NOT_FOUND)))
                .run(context -> assertThat(context.getBean(GrpcStatusMapper.class)
                        .toStatus(new ChurchNotFoundException()).getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    @DisplayName("the platform RPC logging switch also silences the failure listener")
    void loggingSwitch() {
        runner.withPropertyValues("ecclesiaflow.platform.rpc.logging.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RpcCallFailedListener.class);
                    assertThat(context).hasSingleBean(GrpcExceptionServerInterceptor.class);
                });
    }

    @Test
    @DisplayName("a service can replace the resolver")
    void serviceResolverWins() {
        ErrorCategoryResolver own = new ErrorCategoryResolver(java.util.List.of());
        runner.withBean(ErrorCategoryResolver.class, () -> own)
                .run(context -> assertThat(context.getBean(ErrorCategoryResolver.class)).isSameAs(own));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumingApplication {
    }

    @Test
    @DisplayName("everything is registered through the real auto-configuration import path")
    void registeredThroughImports() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ConsumingApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .run()) {
            assertThat(context.getBeansOfType(ErrorCategoryResolver.class)).hasSize(1);
            assertThat(context.getBeansOfType(GrpcExceptionServerInterceptor.class)).hasSize(1);
        }
    }
}
