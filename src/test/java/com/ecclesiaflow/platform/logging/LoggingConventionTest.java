package com.ecclesiaflow.platform.logging;

import org.aopalliance.intercept.MethodInterceptor;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The aop-logging rule, enforced on the library itself: only cross-cutting code
 * may hold a logger. Adapters, sanitizers and limiters throw, return or publish.
 */
class LoggingConventionTest {

    @Test
    @DisplayName("only aspects, AOP advices, event listeners, controller advices and auto-configurations declare a logger")
    void onlyCrossCuttingClassesDeclareALogger() throws Exception {
        List<String> offenders = libraryClasses()
                .filter(LoggingConventionTest::declaresLogger)
                .filter(type -> !isCrossCutting(type))
                .map(Class::getName)
                .toList();

        assertThat(offenders).isEmpty();
    }

    private static boolean declaresLogger(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .anyMatch(Logger.class::equals);
    }

    // Auto-configurations may announce what they activated at startup (aop-logging rule 1).
    private static boolean isCrossCutting(Class<?> type) {
        return type.isAnnotationPresent(Aspect.class)
                || MethodInterceptor.class.isAssignableFrom(type)
                || AnnotatedElementUtils.hasAnnotation(type, Configuration.class)
                || Arrays.stream(type.getDeclaredMethods()).anyMatch(m -> m.isAnnotationPresent(EventListener.class))
                // A controller advice is where an unexpected failure is logged, once.
                || AnnotatedElementUtils.hasAnnotation(type, ControllerAdvice.class);
    }

    private static Stream<Class<?>> libraryClasses() throws IOException, URISyntaxException {
        Path root = Path.of(SecurityMaskingUtils.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (Stream<Path> files = Files.walk(root)) {
            List<Class<?>> classes = files
                    .filter(file -> file.toString().endsWith(".class"))
                    .map(file -> root.relativize(file).toString())
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                    .filter(name -> name.startsWith("com.ecclesiaflow.platform."))
                    .<Class<?>>map(LoggingConventionTest::load)
                    .toList();
            return classes.stream();
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, LoggingConventionTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(name, e);
        }
    }
}
