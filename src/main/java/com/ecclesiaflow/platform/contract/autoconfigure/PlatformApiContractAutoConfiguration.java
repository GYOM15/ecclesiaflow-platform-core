package com.ecclesiaflow.platform.contract.autoconfigure;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ApiContractLoader;
import com.ecclesiaflow.platform.contract.ApiContractProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;

/**
 * Reads the module's OpenAPI contract once, at startup, for every rule keyed on its operations. Servlet
 * applications only, the shape of every module: a module whose contract cannot be found does not start.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ApiContractProperties.class)
public class PlatformApiContractAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApiContract apiContract(ApiContractProperties properties, ResourceLoader resourceLoader) {
        return ApiContractLoader.load(ResourcePatternUtils.getResourcePatternResolver(resourceLoader),
                properties.getLocations());
    }
}
