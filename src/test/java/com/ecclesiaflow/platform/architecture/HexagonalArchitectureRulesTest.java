package com.ecclesiaflow.platform.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HexagonalArchitectureRulesTest {

    private static final String COMPLIANT = "com.ecclesiaflow.platform.architecture.fixture.compliant";
    private static final String VIOLATING = "com.ecclesiaflow.platform.architecture.fixture.violating";

    private static final JavaClasses COMPLIANT_CLASSES = new ClassFileImporter().importPackages(COMPLIANT);
    private static final JavaClasses VIOLATING_CLASSES = new ClassFileImporter().importPackages(VIOLATING);

    private static EvaluationResult evaluate(Function<String, ArchRule> rule, String base, JavaClasses classes) {
        return rule.apply(base).evaluate(classes);
    }

    @Test
    @DisplayName("a module laid out as the skill describes passes every rule")
    void compliantModulePasses() {
        HexagonalArchitectureRules.all(COMPLIANT)
                .forEach(rule -> assertThat(rule.evaluate(COMPLIANT_CLASSES).hasViolation())
                        .as(rule.getDescription()).isFalse());
    }

    @Test
    @DisplayName("web must not reach into io")
    void webDoesNotDependOnIo() {
        EvaluationResult result = evaluate(HexagonalArchitectureRules::webDoesNotDependOnIo, VIOLATING, VIOLATING_CLASSES);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).anyMatch(line -> line.contains("LeakyController"));
    }

    @Test
    @DisplayName("business must not reach into io, web or application")
    void businessDoesNotDependOnOuterLayers() {
        EvaluationResult result = evaluate(HexagonalArchitectureRules::businessDoesNotDependOnOuterLayers,
                VIOLATING, VIOLATING_CLASSES);

        assertThat(result.getFailureReport().getDetails())
                .anyMatch(line -> line.contains("MemberStore"))
                .anyMatch(line -> line.contains("AuditActorResolver"))
                .anyMatch(line -> line.contains("LeakyController"));
    }

    @Test
    @DisplayName("business must not use Spring Web, gRPC or generated protobuf types")
    void businessDoesNotDependOnTransportOrPersistence() {
        EvaluationResult result = evaluate(HexagonalArchitectureRules::businessDoesNotDependOnTransportOrPersistence,
                VIOLATING, VIOLATING_CLASSES);

        assertThat(result.getFailureReport().getDetails())
                .anyMatch(line -> line.contains("org.springframework.http.HttpStatus"))
                .anyMatch(line -> line.contains("io.grpc.Status"))
                .anyMatch(line -> line.contains("ActivationStatus"));
    }

    @Test
    @DisplayName("an interface implemented by an io adapter must live in business.domain")
    void portsLiveInBusinessDomain() {
        EvaluationResult result = evaluate(HexagonalArchitectureRules::portsLiveInBusinessDomain,
                VIOLATING, VIOLATING_CLASSES);

        assertThat(result.getFailureReport().getDetails()).anyMatch(line -> line.contains("MemberLookup"));
    }

    @Test
    @DisplayName("business.domain carries no Spring or framework type")
    void domainIsFrameworkFree() {
        EvaluationResult result = evaluate(HexagonalArchitectureRules::domainIsFrameworkFree,
                VIOLATING, VIOLATING_CLASSES);

        assertThat(result.getFailureReport().getDetails()).anyMatch(line -> line.contains("Church"));
    }

    @Test
    @DisplayName("a rule with nothing to check does not fail a module that lacks the layer")
    void emptyLayersAreAllowed() {
        JavaClasses nothing = new ClassFileImporter().importPackages(COMPLIANT + ".absent");

        HexagonalArchitectureRules.all(COMPLIANT + ".absent")
                .forEach(rule -> assertThat(rule.evaluate(nothing).hasViolation()).isFalse());
    }

    @Test
    @DisplayName("the one-line check reports every broken rule at once")
    void checkReportsAllRules() {
        assertThatThrownBy(() -> HexagonalArchitectureRules.check(VIOLATING, VIOLATING_CLASSES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("LeakyController")
                .hasMessageContaining("MemberLookup")
                .hasMessageContaining("Church");
    }

    @Test
    @DisplayName("the one-line check imports production classes only")
    void checkImportsProductionClasses() {
        JavaClasses production = HexagonalArchitectureRules.importProductionClasses("com.ecclesiaflow.platform");

        assertThat(production.contain(HexagonalArchitectureRules.class)).isTrue();
        assertThat(production.stream()).noneMatch(type -> type.getPackageName().startsWith(VIOLATING));
        assertThatCode(() -> HexagonalArchitectureRules.check("com.ecclesiaflow.platform")).doesNotThrowAnyException();
    }
}
