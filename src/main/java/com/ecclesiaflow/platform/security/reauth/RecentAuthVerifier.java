package com.ecclesiaflow.platform.security.reauth;

import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Refuses to start a module in which a {@link RecentAuthRequired} method would run unchecked or could not answer:
 * no {@link SignInMethodsClient} or {@link PendingConfirmationIssuer}, a method the aspect does not intercept
 * (private, static or final, annotated only on an interface, on a bean that is not proxied, AOP turned off), or
 * one that does not return a {@code ResponseEntity}, in which a 202 cannot be answered. An unenforced sensitive
 * gesture looks exactly like an enforced one until it is abused.
 */
public class RecentAuthVerifier implements SmartInitializingSingleton {

    private final ConfigurableListableBeanFactory beanFactory;

    public RecentAuthVerifier(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = new ArrayList<>();
        boolean annotated = false;
        for (String name : beanFactory.getBeanNamesForType(Object.class, false, false)) {
            Class<?> type = AutoProxyUtils.determineTargetClass(beanFactory, name);
            if (type == null || !AnnotationUtils.isCandidateClass(type, RecentAuthRequired.class)) {
                continue;
            }
            Map<Method, RecentAuthRequired> methods = annotatedMethods(type);
            if (methods.isEmpty()) {
                continue;
            }
            annotated = true;
            Object bean = beanFactory.getBean(name);
            methods.forEach((method, recentAuth) -> problems.addAll(problemsOf(bean, type, method, recentAuth)));
        }
        if (annotated) {
            problems.addAll(missingCollaborators());
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("@RecentAuthRequired would not hold: " + String.join("; ", problems));
        }
    }

    // Skipped as Spring's own @EventListener scan skips it: a class whose method signatures do not resolve, a
    // lazy bean's typically, cannot be proxied either, so creating it fails before any gesture of it runs.
    private static Map<Method, RecentAuthRequired> annotatedMethods(Class<?> type) {
        try {
            return MethodIntrospector.selectMethods(type,
                    (MethodIntrospector.MetadataLookup<RecentAuthRequired>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, RecentAuthRequired.class));
        } catch (IllegalStateException unresolvable) {
            return Map.of();
        }
    }

    private static List<String> problemsOf(Object bean, Class<?> type, Method method, RecentAuthRequired recentAuth) {
        String where = ClassUtils.getQualifiedMethodName(method, type);
        List<String> problems = new ArrayList<>();
        if (!ResponseEntity.class.isAssignableFrom(method.getReturnType())) {
            problems.add(where + " does not return a ResponseEntity, so it cannot answer 202");
        }
        if (!StringUtils.hasText(recentAuth.action())) {
            problems.add(where + " names no action for the confirmation");
        }
        if (recentAuth.maxAgeSeconds() <= 0) {
            problems.add(where + " allows a sign-in age of " + recentAuth.maxAgeSeconds() + " s");
        }
        if (!intercepted(bean, type, method)) {
            problems.add(where + " is not intercepted: it must be a public, non-final method annotated on the class"
                    + " of a proxied bean");
        }
        return problems;
    }

    private static boolean intercepted(Object bean, Class<?> type, Method method) {
        int modifiers = method.getModifiers();
        if (!(bean instanceof Advised advised) || Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers)
                || Modifier.isFinal(modifiers)) {
            return false;
        }
        if (AopUtils.isJdkDynamicProxy(bean) && !declaredOnProxiedInterface(advised, method)) {
            return false;
        }
        return Arrays.stream(advised.getAdvisors())
                .filter(PointcutAdvisor.class::isInstance)
                .map(PointcutAdvisor.class::cast)
                .filter(RecentAuthVerifier::isRecentAuthAdvice)
                .anyMatch(advisor -> advisor.getPointcut().getMethodMatcher().matches(method, type));
    }

    private static boolean declaredOnProxiedInterface(Advised advised, Method method) {
        return Arrays.stream(advised.getProxiedInterfaces())
                .anyMatch(proxied -> ClassUtils.hasMethod(proxied, method.getName(), method.getParameterTypes()));
    }

    private static boolean isRecentAuthAdvice(PointcutAdvisor advisor) {
        return advisor.getAdvice() instanceof AbstractAspectJAdvice advice
                && advice.getAspectJAdviceMethod().getDeclaringClass() == RecentAuthAspect.class;
    }

    private List<String> missingCollaborators() {
        List<String> missing = new ArrayList<>();
        // The policy exists only beside a SignInMethodsClient, which is what the module has to declare.
        if (beanFactory.getBeanNamesForType(RecentAuthPolicy.class, true, false).length == 0) {
            missing.add("no " + SignInMethodsClient.class.getSimpleName()
                    + " bean reads the account's sign-in methods");
        }
        if (beanFactory.getBeanNamesForType(PendingConfirmationIssuer.class, true, false).length == 0) {
            missing.add("no " + PendingConfirmationIssuer.class.getSimpleName()
                    + " bean holds the gestures of linked accounts");
        }
        return missing;
    }
}
