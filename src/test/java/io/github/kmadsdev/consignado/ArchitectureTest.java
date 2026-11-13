package io.github.kmadsdev.consignado;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

/**
 * The layering in docs/adr/0001-layering.md, checked on every build.
 *
 * <pre>
 * api -> application -> persistence, outbox, observability
 *  \__________\_____________\____________________-> domain (pure Java)
 * </pre>
 */
@AnalyzeClasses(packages = "io.github.kmadsdev.consignado", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layers = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Api").definedBy("..api..")
            .layer("Application").definedBy("..application..")
            .layer("Persistence").definedBy("..persistence..")
            .layer("Outbox").definedBy("..outbox..")
            .layer("Observability").definedBy("..observability..")
            .layer("Domain").definedBy("..domain..")
            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Api")
            .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Application")
            .whereLayer("Outbox").mayOnlyBeAccessedByLayers("Application")
            .whereLayer("Observability").mayOnlyBeAccessedByLayers("Application")
            .because("controllers talk to use cases, and only use cases touch storage and messaging");

    @ArchTest
    static final ArchRule domainIsPureJava = classes().that().resideInAPackage("..domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..domain..")
            .because("business rules must be testable without Spring, JPA or a database");

    @ArchTest
    static final ArchRule entitiesStayInStorageModules = classes().that().areAnnotatedWith(Entity.class)
            .should().resideInAnyPackage("..persistence..", "..outbox..")
            .because("the API returns read models, never entities");

    @ArchTest
    static final ArchRule controllersLiveInApi = classes().that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage("..api..")
            .andShould().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule constructorInjectionOnly = noFields().should().beAnnotatedWith(Autowired.class)
            .because("constructor injection keeps dependencies explicit and classes testable");

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
}
