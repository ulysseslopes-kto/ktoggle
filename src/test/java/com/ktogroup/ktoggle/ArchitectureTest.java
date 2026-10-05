package com.ktogroup.ktoggle;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Enforces the house layering (zin / zout / zdto) and keeps feature packages acyclic. */
@AnalyzeClasses(packages = "com.ktogroup.ktoggle", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule inbound_adapters_do_not_use_outbound_adapters = noClasses()
            .that().resideInAPackage("..zin..")
            .should().dependOnClassesThat().resideInAPackage("..zout..");

    @ArchTest
    static final ArchRule persistence_details_stay_in_outbound_adapters = noClasses()
            .that().resideOutsideOfPackages("..zout..", "..config..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.springframework.jdbc..",
                    "org.springframework.data.jpa..", "software.amazon.awssdk.services.s3..")
            .because("domain services talk to persistence through *PersistencePort interfaces");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_web = noClasses()
            .that().resideOutsideOfPackages("..zin..", "..config..", "..commons..", "..delivery..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "jakarta.servlet..");

    @ArchTest
    static final ArchRule feature_packages_are_free_of_cycles = slices()
            .matching("com.ktogroup.ktoggle.(*)..")
            .should().beFreeOfCycles();
}
