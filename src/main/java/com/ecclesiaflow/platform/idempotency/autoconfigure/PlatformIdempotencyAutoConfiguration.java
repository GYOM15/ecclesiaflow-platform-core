package com.ecclesiaflow.platform.idempotency.autoconfigure;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.autoconfigure.PlatformApiContractAutoConfiguration;
import com.ecclesiaflow.platform.idempotency.IdempotentCreator;
import com.ecclesiaflow.platform.idempotency.events.IdempotencyEventListener;
import com.ecclesiaflow.platform.idempotency.web.IdempotencyIndex;
import com.ecclesiaflow.platform.idempotency.web.IdempotencyKeyResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The idempotency primitive: the contract check and key resolver of a servlet module, and the creator wherever
 * one transaction manager exists. Nothing runs until a module marks an operation {@code x-ef-idempotency} and
 * calls the creator from it.
 *
 * <p>The index is not {@code @ConditionalOnMissingBean}: a module bean of the same type must not silently
 * replace the startup check of its contract.</p>
 */
@AutoConfiguration(after = {
        PlatformApiContractAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class})
public class PlatformIdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyEventListener idempotencyEventListener() {
        return new IdempotencyEventListener();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.transaction.PlatformTransactionManager")
    @ConditionalOnSingleCandidate(PlatformTransactionManager.class)
    static class CreatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public IdempotentCreator idempotentCreator(PlatformTransactionManager transactionManager,
                                                   ApplicationEventPublisher events) {
            return new IdempotentCreator(transactionManager, events);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ContractConfiguration {

        @Bean
        public IdempotencyIndex idempotencyIndex(ApiContract contract) {
            return new IdempotencyIndex(contract);
        }

        @Bean
        @ConditionalOnMissingBean
        public IdempotencyKeyResolver idempotencyKeyResolver(IdempotencyIndex idempotencyIndex) {
            return new IdempotencyKeyResolver(idempotencyIndex);
        }
    }
}
