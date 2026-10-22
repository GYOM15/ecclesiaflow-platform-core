package com.ecclesiaflow.platform.security.reauth;

import com.ecclesiaflow.platform.security.autoconfigure.PlatformRecentAuthAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.ecclesiaflow.platform.security.reauth.fixture.UnresolvableSignature;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecentAuthVerifierTest {

    private static final SignInMethodsClient SIGN_IN_METHODS = keycloakUserId -> new SignInMethods(true, List.of());
    private static final PendingConfirmationIssuer ISSUER = action -> "pending";

    private final WebApplicationContextRunner module = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                    PlatformRecentAuthAutoConfiguration.class))
            .withBean(SignInMethodsClient.class, () -> SIGN_IN_METHODS)
            .withBean(PendingConfirmationIssuer.class, () -> ISSUER);

    public static class Delegate {

        @RecentAuthRequired(action = "ACCOUNT_DELETION")
        public ResponseEntity<Void> deleteMyAccount() {
            return ResponseEntity.noContent().build();
        }
    }

    public static class StringDelegate {

        @RecentAuthRequired(action = "ACCOUNT_DELETION")
        public String deleteMyAccount() {
            return "deleted";
        }
    }

    public static class FinalDelegate {

        @RecentAuthRequired(action = "ACCOUNT_DELETION")
        public final ResponseEntity<Void> deleteMyAccount() {
            return ResponseEntity.noContent().build();
        }
    }

    public static class PrivateDelegate {

        public ResponseEntity<Void> deleteMyAccount() {
            return close();
        }

        @RecentAuthRequired(action = "ACCOUNT_DELETION")
        private ResponseEntity<Void> close() {
            return ResponseEntity.noContent().build();
        }
    }

    public static class MisconfiguredDelegate {

        @RecentAuthRequired(action = " ", maxAgeSeconds = 0)
        public ResponseEntity<Void> deleteMyAccount() {
            return ResponseEntity.noContent().build();
        }
    }

    public interface AccountApi {

        @RecentAuthRequired(action = "ACCOUNT_DELETION")
        ResponseEntity<Void> deleteMyAccount();
    }

    /** Annotated on the interface only, which the aspect does not see. */
    public static class InterfaceAnnotatedDelegate implements AccountApi {

        @Override
        public ResponseEntity<Void> deleteMyAccount() {
            return ResponseEntity.noContent().build();
        }
    }

    public interface Closing {

        ResponseEntity<Void> deleteMyAccount();
    }

    public static class ClosingDelegate implements Closing {

        @Override
        @RecentAuthRequired(action = "ACCOUNT_DELETION")
        public ResponseEntity<Void> deleteMyAccount() {
            return ResponseEntity.noContent().build();
        }

        @RecentAuthRequired(action = "EMAIL_CHANGE")
        public ResponseEntity<Void> changeEmail() {
            return ResponseEntity.accepted().build();
        }
    }

    public static class StaticDelegate {

        @RecentAuthRequired(action = "ACCOUNT_DELETION")
        public static ResponseEntity<Void> deleteMyAccount() {
            return ResponseEntity.noContent().build();
        }
    }

    /** Proxied for one gesture, while the other is annotated on its interface only. */
    public static class HalfAnnotatedDelegate implements AccountApi {

        @Override
        public ResponseEntity<Void> deleteMyAccount() {
            return ResponseEntity.noContent().build();
        }

        @RecentAuthRequired(action = "EMAIL_CHANGE")
        public ResponseEntity<Void> changeEmail() {
            return ResponseEntity.accepted().build();
        }
    }

    /** A factory whose product type cannot be known before it is asked for. */
    public static class OpaqueFactory implements FactoryBean<Object> {

        @Override
        public Object getObject() {
            return "opaque";
        }

        @Override
        public Class<?> getObjectType() {
            return null;
        }
    }

    public static class Unguarded {

        public ResponseEntity<Void> deleteMyAccount() {
            return ResponseEntity.noContent().build();
        }
    }

    @Test
    @DisplayName("lets a module start whose gestures are intercepted and wired")
    void wired() {
        module.withBean(Delegate.class).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("lets a module that guards nothing start without the ports")
    void nothingGuarded() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                        PlatformRecentAuthAutoConfiguration.class))
                .withBean(Unguarded.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("skips a bean whose type cannot be known")
    void opaqueBean() {
        module.withBean(OpaqueFactory.class).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("skips a class whose method signatures do not resolve, as a lazy bean's may not")
    void unresolvableSignature() throws Exception {
        Class<?> unresolvable = new IsolatingClassLoader(UnresolvableSignature.class).loadClass(
                UnresolvableSignature.class.getName());
        ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);
        when(beanFactory.getBeanNamesForType(Object.class, false, false)).thenReturn(new String[] {"lazyAdapter"});
        doReturn(unresolvable).when(beanFactory).getType("lazyAdapter");

        new RecentAuthVerifier(beanFactory).afterSingletonsInstantiated();

        verify(beanFactory, never()).getBean("lazyAdapter");
    }

    /** Loads one class from its bytes and nothing else, so the types its methods name cannot be found. */
    private static final class IsolatingClassLoader extends ClassLoader {

        private final Class<?> isolated;

        IsolatingClassLoader(Class<?> isolated) {
            super(null);
            this.isolated = isolated;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(isolated.getName())) {
                throw new ClassNotFoundException(name);
            }
            try (InputStream bytes = isolated.getResourceAsStream(isolated.getSimpleName() + ".class")) {
                byte[] code = bytes.readAllBytes();
                return defineClass(name, code, 0, code.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    @Nested
    @DisplayName("refuses to start a module")
    class Refuses {

        @Test
        @DisplayName("whose gesture is static, which no proxy reaches")
        void staticMethod() {
            module.withBean(StaticDelegate.class).run(context -> assertThat(context).getFailure()
                    .hasMessageContaining("StaticDelegate.deleteMyAccount is not intercepted"));
        }

        @Test
        @DisplayName("whose proxied bean leaves one gesture unchecked")
        void partlyIntercepted() {
            module.withBean(HalfAnnotatedDelegate.class).run(context -> assertThat(context).getFailure()
                    .hasMessageContaining("HalfAnnotatedDelegate.deleteMyAccount is not intercepted")
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain("HalfAnnotatedDelegate.changeEmail")));
        }

        @Test
        @DisplayName("without a SignInMethodsClient")
        void noSignInMethods() {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                            PlatformRecentAuthAutoConfiguration.class))
                    .withBean(PendingConfirmationIssuer.class, () -> ISSUER)
                    .withBean(Delegate.class)
                    .run(context -> assertThat(context).getFailure()
                            .hasMessageContaining("no SignInMethodsClient bean"));
        }

        @Test
        @DisplayName("without a PendingConfirmationIssuer")
        void noIssuer() {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                            PlatformRecentAuthAutoConfiguration.class))
                    .withBean(SignInMethodsClient.class, () -> SIGN_IN_METHODS)
                    .withBean(Delegate.class)
                    .run(context -> assertThat(context).getFailure()
                            .hasMessageContaining("no PendingConfirmationIssuer bean"));
        }

        @Test
        @DisplayName("whose gesture cannot answer 202")
        void notAResponseEntity() {
            module.withBean(StringDelegate.class).run(context -> assertThat(context).getFailure()
                    .hasMessageContaining("StringDelegate.deleteMyAccount does not return a ResponseEntity"));
        }

        @Test
        @DisplayName("whose gesture a proxy cannot intercept: final or private")
        void notInterceptable() {
            module.withBean(FinalDelegate.class).run(context -> assertThat(context).getFailure()
                    .hasMessageContaining("FinalDelegate.deleteMyAccount is not intercepted"));
            module.withBean(PrivateDelegate.class).run(context -> assertThat(context).getFailure()
                    .hasMessageContaining("PrivateDelegate.close is not intercepted"));
        }

        @Test
        @DisplayName("whose annotation sits on an interface only")
        void interfaceOnly() {
            module.withBean(InterfaceAnnotatedDelegate.class).run(context -> assertThat(context).getFailure()
                    .hasMessageContaining("InterfaceAnnotatedDelegate.deleteMyAccount is not intercepted"));
        }

        @Test
        @DisplayName("in which AOP does not run, so nothing would be checked")
        void noAop() {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PlatformRecentAuthAutoConfiguration.class))
                    .withBean(SignInMethodsClient.class, () -> SIGN_IN_METHODS)
                    .withBean(PendingConfirmationIssuer.class, () -> ISSUER)
                    .withBean(Delegate.class)
                    .run(context -> assertThat(context).getFailure()
                            .hasMessageContaining("Delegate.deleteMyAccount is not intercepted"));
        }

        @Test
        @DisplayName("whose annotation names no action or allows no sign-in age")
        void misconfigured() {
            module.withBean(MisconfiguredDelegate.class).run(context -> assertThat(context).getFailure()
                    .hasMessageContaining("names no action")
                    .hasMessageContaining("allows a sign-in age of 0 s"));
        }

        @Test
        @DisplayName("behind an interface-based proxy, whose gesture is not on the interface")
        void jdkProxy() {
            module.withPropertyValues("spring.aop.proxy-target-class=false")
                    .withBean(Closing.class, ClosingDelegate::new)
                    .run(context -> assertThat(context).getFailure()
                            .hasMessageContaining("ClosingDelegate.changeEmail is not intercepted")
                            .satisfies(failure -> assertThat(failure.getMessage())
                                    .doesNotContain("ClosingDelegate.deleteMyAccount")));
        }
    }
}
