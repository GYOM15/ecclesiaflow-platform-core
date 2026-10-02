package com.ecclesiaflow.platform.error.autoconfigure;

import com.ecclesiaflow.platform.error.ErrorCategoryResolver;
import com.ecclesiaflow.platform.error.ExceptionClassifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class PlatformErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ErrorCategoryResolver errorCategoryResolver(ObjectProvider<ExceptionClassifier> classifiers) {
        return new ErrorCategoryResolver(classifiers.orderedStream().toList());
    }
}
