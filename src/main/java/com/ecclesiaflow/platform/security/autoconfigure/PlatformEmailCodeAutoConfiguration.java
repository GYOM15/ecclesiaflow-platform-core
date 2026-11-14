package com.ecclesiaflow.platform.security.autoconfigure;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.autoconfigure.PlatformApiContractAutoConfiguration;
import com.ecclesiaflow.platform.idempotency.autoconfigure.PlatformIdempotencyAutoConfiguration;
import com.ecclesiaflow.platform.idempotency.web.IdempotencyKeyResolver;
import com.ecclesiaflow.platform.security.reauth.code.ConfirmableGestures;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeMailer;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeProperties;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeSecrets;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeService;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeStore;
import com.ecclesiaflow.platform.security.reauth.code.events.EmailCodeEventListener;
import com.ecclesiaflow.platform.security.reauth.code.events.EmailCodeMetrics;
import com.ecclesiaflow.platform.security.reauth.code.jdbc.JdbcEmailCodeStore;
import com.ecclesiaflow.platform.security.reauth.code.web.EmailCodeConfirmation;
import com.ecclesiaflow.platform.security.reauth.code.web.EmailCodeExceptionHandler;
import com.ecclesiaflow.platform.security.reauth.code.web.ReauthLinkedIndex;
import com.ecclesiaflow.platform.time.autoconfigure.PlatformClockAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Set;
import java.util.TreeSet;

/**
 * The e-mail code that confirms the gestures of linked accounts marked
 * {@code @RecentAuthRequired(linkedAccounts = SECOND_FACTOR_OR_EMAIL_CODE)}, for a servlet module that sets
 * {@code ecclesiaflow.security.reauth.email-code.enabled=true}. Once enabled it fails the startup rather than run
 * half-wired: a key under 32 bytes, no {@link EmailCodeMailer}, no {@link ConfirmableGestures}, no single
 * transaction manager, or gestures that differ from the contract's {@code x-ef-reauth-linked} operations.
 *
 * <p>The secrets, the service, the confirmation and the contract check are not {@code @ConditionalOnMissingBean}:
 * a module bean of the same type must not silently replace them. The store is a port, JDBC by default.</p>
 */
@AutoConfiguration(after = {
        PlatformClockAutoConfiguration.class,
        PlatformApiContractAutoConfiguration.class,
        PlatformIdempotencyAutoConfiguration.class,
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.springframework.jdbc.core.JdbcTemplate",
        "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken",
        "org.springframework.web.servlet.DispatcherServlet"})
@ConditionalOnProperty(prefix = EmailCodeProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(EmailCodeProperties.class)
public class PlatformEmailCodeAutoConfiguration {

    static final String KEY_SETTING = EmailCodeProperties.PREFIX + ".key";

    @Bean
    public EmailCodeSecrets emailCodeSecrets(EmailCodeProperties properties) {
        return new EmailCodeSecrets(EmailCodeSecrets.decodeKey(properties.getKey(), KEY_SETTING), new SecureRandom());
    }

    // A private JdbcTemplate: a JdbcTemplate bean would make Boot's own back off in the module.
    @Bean
    @ConditionalOnMissingBean
    public EmailCodeStore emailCodeStore(ObjectProvider<DataSource> dataSource) {
        return new JdbcEmailCodeStore(new JdbcTemplate(required(dataSource.getIfUnique(),
                "a single DataSource, whose database holds reauth_email_code")));
    }

    @Bean
    public EmailCodeService emailCodeService(EmailCodeStore emailCodeStore, EmailCodeSecrets emailCodeSecrets,
                                             ObjectProvider<EmailCodeMailer> mailer,
                                             ObjectProvider<PlatformTransactionManager> transactionManager,
                                             ApplicationEventPublisher events, ObjectProvider<Clock> clock) {
        return new EmailCodeService(emailCodeStore, emailCodeSecrets,
                required(mailer.getIfUnique(), "an EmailCodeMailer bean that sends the codes"),
                required(transactionManager.getIfUnique(), "a single PlatformTransactionManager"), events,
                clock.getIfUnique(Clock::systemUTC));
    }

    @Bean
    public ReauthLinkedIndex reauthLinkedIndex(ObjectProvider<ApiContract> contract) {
        return new ReauthLinkedIndex(required(contract.getIfUnique(), "the module's OpenAPI contract"));
    }

    @Bean
    public EmailCodeConfirmation emailCodeConfirmation(EmailCodeService emailCodeService,
                                                       EmailCodeSecrets emailCodeSecrets,
                                                       ObjectProvider<ConfirmableGestures> gestures,
                                                       ReauthLinkedIndex reauthLinkedIndex,
                                                       ObjectProvider<IdempotencyKeyResolver> idempotencyKeys,
                                                       ApplicationEventPublisher events) {
        ConfirmableGestures confirmable = required(gestures.getIfUnique(),
                "a ConfirmableGestures bean that binds, checks and names each gesture");
        refuseUnlessContractAgrees(confirmable.actions(), reauthLinkedIndex.labels());
        return new EmailCodeConfirmation(emailCodeService, emailCodeSecrets, confirmable,
                idempotencyKeys.getIfUnique(), events);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmailCodeEventListener emailCodeEventListener() {
        return new EmailCodeEventListener();
    }

    @Bean
    @ConditionalOnMissingBean
    public EmailCodeExceptionHandler emailCodeExceptionHandler(ObjectProvider<Clock> clock) {
        return new EmailCodeExceptionHandler(clock.getIfUnique(Clock::systemUTC)) {
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class EmailCodeMetricsConfiguration {

        /** Without a registry bean the counter is inert rather than absent, so nothing else changes. */
        @Bean
        @ConditionalOnMissingBean
        EmailCodeMetrics emailCodeMetrics(ObjectProvider<MeterRegistry> registry) {
            return new EmailCodeMetrics(registry.getIfUnique());
        }
    }

    // Clients read the contract's mark to expect the 202 and its dialog: it must name exactly these gestures.
    private static void refuseUnlessContractAgrees(Set<String> gestures, Set<String> marked) {
        Set<String> unmarked = new TreeSet<>(gestures);
        unmarked.removeAll(marked);
        Set<String> unknown = new TreeSet<>(marked);
        unknown.removeAll(gestures);
        if (!unmarked.isEmpty() || !unknown.isEmpty()) {
            throw new IllegalStateException("The e-mail code would not hold: ConfirmableGestures and the contract's "
                    + "x-ef-reauth-linked operations differ; not marked in the contract: " + unmarked
                    + "; unknown to ConfirmableGestures: " + unknown);
        }
    }

    private static <T> T required(T bean, String what) {
        if (bean == null) {
            throw new IllegalStateException(EmailCodeProperties.PREFIX + ".enabled is true but the module lacks "
                    + what);
        }
        return bean;
    }
}
