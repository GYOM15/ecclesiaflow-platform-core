package com.ecclesiaflow.platform.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.PackageMatcher;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;

import java.util.List;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit is optional here so it never reaches a service's runtime: a service runs these from a
 * test, e.g. {@code check("com.ecclesiaflow.church")}, with ArchUnit in its own test scope.
 */
public final class HexagonalArchitectureRules {

    private static final String[] SPRING_WEB = {"org.springframework.web..", "org.springframework.http.."};
    private static final String[] PERSISTENCE = {
            "jakarta.persistence..", "javax.persistence..", "org.hibernate..", "org.springframework.data.jpa.."};
    private static final String[] GRPC = {"io.grpc..", "com.google.protobuf.."};
    private static final String[] FEIGN = {"feign..", "org.springframework.cloud.openfeign.."};

    private HexagonalArchitectureRules() {
    }

    public static List<ArchRule> all(String basePackage) {
        return List.of(
                webDoesNotDependOnIo(basePackage),
                businessDoesNotDependOnOuterLayers(basePackage),
                businessDoesNotDependOnTransportOrPersistence(basePackage),
                portsLiveInBusinessDomain(basePackage),
                domainIsFrameworkFree(basePackage));
    }

    public static void check(String basePackage) {
        check(basePackage, importProductionClasses(basePackage));
    }

    public static void check(String basePackage, JavaClasses classes) {
        CompositeArchRule.of(all(basePackage)).check(classes);
    }

    public static JavaClasses importProductionClasses(String basePackage) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages(basePackage);
    }

    public static ArchRule webDoesNotDependOnIo(String basePackage) {
        return noClasses().that().resideInAPackage(layer(basePackage, "web"))
                .should().dependOnClassesThat().resideInAPackage(layer(basePackage, "io"))
                .because("web reaches adapters only through business services and their ports")
                .allowEmptyShould(true);
    }

    public static ArchRule businessDoesNotDependOnOuterLayers(String basePackage) {
        return noClasses().that().resideInAPackage(layer(basePackage, "business"))
                .should().dependOnClassesThat().resideInAnyPackage(
                        layer(basePackage, "io"), layer(basePackage, "web"), layer(basePackage, "application"))
                .because("business depends only on itself and its own ports")
                .allowEmptyShould(true);
    }

    public static ArchRule businessDoesNotDependOnTransportOrPersistence(String basePackage) {
        return noClasses().that().resideInAPackage(layer(basePackage, "business"))
                .should().dependOnClassesThat(transportOrPersistenceTypes())
                .because("Spring Web, JPA, gRPC and Feign belong to the adapters, not to the business layer")
                .allowEmptyShould(true);
    }

    public static ArchRule portsLiveInBusinessDomain(String basePackage) {
        return classes().that().resideInAPackage(layer(basePackage, "business"))
                .and().areInterfaces()
                .and(implementedIn(layer(basePackage, "io")))
                .should().resideInAPackage(layer(basePackage, "business.domain"))
                .because("ports are declared in business.domain and implemented in io")
                .allowEmptyShould(true);
    }

    public static ArchRule domainIsFrameworkFree(String basePackage) {
        return noClasses().that().resideInAPackage(layer(basePackage, "business.domain"))
                .should().dependOnClassesThat(transportOrPersistenceTypes()
                        .or(resideInAnyPackage("org.springframework..", "com.fasterxml.jackson.."))
                        .as("Spring, Jackson, JPA, gRPC or Feign types"))
                .because("the domain and its ports stay plain Java")
                .allowEmptyShould(true);
    }

    // Generated protobuf classes live in the service's own packages, so the package names alone miss them.
    private static DescribedPredicate<JavaClass> transportOrPersistenceTypes() {
        return resideInAnyPackage(SPRING_WEB)
                .or(resideInAnyPackage(PERSISTENCE))
                .or(resideInAnyPackage(GRPC))
                .or(resideInAnyPackage(FEIGN))
                .or(assignableTo("com.google.protobuf.MessageLiteOrBuilder"))
                .or(assignableTo("com.google.protobuf.ProtocolMessageEnum"))
                .as("Spring Web, JPA, gRPC/protobuf or Feign types");
    }

    private static DescribedPredicate<JavaClass> implementedIn(String packageIdentifier) {
        PackageMatcher adapters = PackageMatcher.of(packageIdentifier);
        return DescribedPredicate.describe("implemented in " + packageIdentifier,
                type -> type.getAllSubclasses().stream().anyMatch(sub -> adapters.matches(sub.getPackageName())));
    }

    private static String layer(String basePackage, String layer) {
        return basePackage + "." + layer + "..";
    }
}
