package com.ecclesiaflow.platform.rpc.error.autoconfigure;

import com.ecclesiaflow.platform.error.ErrorCategoryResolver;
import com.ecclesiaflow.platform.error.autoconfigure.PlatformErrorAutoConfiguration;
import com.ecclesiaflow.platform.rpc.error.GrpcExceptionServerInterceptor;
import com.ecclesiaflow.platform.rpc.error.GrpcStatusMapper;
import com.ecclesiaflow.platform.rpc.events.RpcCallFailedListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Independent of the s2s properties, and inert until a service adds the interceptor to its
 * ServerBuilder, so a module that has not adopted it is unaffected.
 */
@AutoConfiguration(after = PlatformErrorAutoConfiguration.class)
public class PlatformRpcErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GrpcStatusMapper grpcStatusMapper(ErrorCategoryResolver errorCategoryResolver) {
        return new GrpcStatusMapper(errorCategoryResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcExceptionServerInterceptor grpcExceptionServerInterceptor(GrpcStatusMapper grpcStatusMapper,
                                                                         ApplicationEventPublisher events) {
        return new GrpcExceptionServerInterceptor(grpcStatusMapper, events);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ecclesiaflow.platform.rpc.logging", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public RpcCallFailedListener rpcCallFailedListener() {
        return new RpcCallFailedListener();
    }
}
