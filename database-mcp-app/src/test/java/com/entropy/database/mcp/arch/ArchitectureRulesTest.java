/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.entropy.database.mcp.arch;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture rules for the MCP server, evaluated in <strong>warn-only + no-regression</strong> mode.
 *
 * <h2>Why warn-only</h2>
 * The layering described by these rules is the target state, and the codebase is on its way there
 * (the {@code facade/} capability-interface split and the {@code tools/} rewiring landed first).
 * Turning every rule into a hard build gate right now would either break the build or force us to
 * water the rules down until they say nothing. Instead each rule is evaluated explicitly via
 * {@link ArchRule#evaluate(JavaClasses)}: every violation is printed with rule name and full detail,
 * and the test itself never fails because of an existing violation.
 *
 * <h2>Why a baseline</h2>
 * Pure warning output is ignored within a week. So the <em>current</em> violation count of every rule
 * is pinned as a baseline constant here, and each rule asserts {@code violations <= baseline}. That
 * gives us:
 * <ul>
 *   <li>the status quo does not break the build;</li>
 *   <li>a newly introduced violation does break the build (count would exceed the baseline);</li>
 *   <li>rules whose baseline is already {@code 0} are effectively hard-enforced;</li>
 *   <li>as violations get fixed, the baseline is lowered - it is a ratchet, never a hiding place.</li>
 * </ul>
 * Baselines must only ever be <em>lowered</em>. Raising one is the same as deleting the rule, and the
 * legitimate exceptions below are expressed as explicit predicates on the rules, never as baseline
 * padding, so the reason for every allowance stays readable in code.
 *
 * <h2>What Maven already enforces, and what it does not</h2>
 * Since the repository was split into Maven modules the <em>layer</em> direction is a
 * compile-time constraint, so a rule that only restates it is dead weight — R3 ("lower layers must
 * not depend back on tools") was removed for exactly that reason: {@code gateway} lives in
 * {@code features}, {@code repository}/{@code byok} in {@code infra}, {@code dialect} in its own
 * module, and none of them can see {@code tools} at all.
 *
 * <p>R1 and R2 are <strong>not</strong> covered by the module split and stay here. Both forbid
 * dependencies that are perfectly visible to {@code tools} at compile time: {@code JdbcTemplate}
 * arrives transitively via {@code features}' {@code spring-boot-starter-jdbc}, and the BYOK registry
 * is a public type in {@code infra}. Maven has no opinion on either; only these rules do.
 */
class ArchitectureRulesTest {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureRulesTest.class);

    private static final String BASE_PACKAGE = "com.entropy.database.mcp";

    /** Synthetic lambda body, e.g. {@code lambda$6}. */
    private static final Pattern SYNTHETIC_LAMBDA = Pattern.compile("lambda\\$\\d+");

    private static final String TOOLS_PACKAGE = BASE_PACKAGE + ".tools..";
    private static final String FACADE_PACKAGE = BASE_PACKAGE + ".facade";

    /** JDBC infrastructure types that MCP tools must reach only through a facade capability interface. */
    private static final Set<String> JDBC_INFRASTRUCTURE_TYPES = Set.of(
            "org.springframework.jdbc.core.JdbcTemplate",
            "javax.sql.DataSource",
            "java.sql.Connection");

    /** The BYOK connection registry and its per-connection context. */
    private static final String DYNAMIC_DATA_SOURCE_MANAGER = BASE_PACKAGE + ".byok.DynamicDataSourceManager";
    private static final String BYOK_DATA_SOURCE_CONTEXT = BASE_PACKAGE + ".byok.ByokDataSourceContext";
    private static final Set<String> CONNECTION_REGISTRY_TYPES =
            Set.of(DYNAMIC_DATA_SOURCE_MANAGER, BYOK_DATA_SOURCE_CONTEXT);

    private static final String ETL_TOOLS = BASE_PACKAGE + ".tools.EtlTools";
    private static final String POOL_MONITOR_TOOLS = BASE_PACKAGE + ".tools.PoolMonitorTools";
    private static final String CONNECTION_ADMIN_TOOLS = BASE_PACKAGE + ".tools.ConnectionAdminTools";

    /**
     * Documented, narrowly scoped exceptions to R1/R2. Each entry pins the origin class, the exact
     * target types, the origin methods and the target members that are allowed. Anything wider than
     * these tuples is reported as a violation, so an exception cannot silently grow.
     */
    private static final List<Exemption> R1_R2_EXEMPTIONS = List.of(
            new Exemption(
                    ETL_TOOLS,
                    Set.of(DYNAMIC_DATA_SOURCE_MANAGER,
                            BYOK_DATA_SOURCE_CONTEXT,
                            "org.springframework.jdbc.core.JdbcTemplate"),
                    Set.of("<init>", "createNamedConnection"),
                    Set.of("acquire", "getJdbcTemplate", "getDialect", "queryForList"),
                    "createNamedConnection registers a BYOK connection and smoke-tests it: registration is "
                            + "connection management, not a database operation, so there is no facade seam for it"),
            new Exemption(
                    POOL_MONITOR_TOOLS,
                    CONNECTION_REGISTRY_TYPES,
                    Set.of(),
                    Set.of("getPoolStats"),
                    "read-only management plane: only reads HikariCP pool statistics from the registry"),
            new Exemption(
                    CONNECTION_ADMIN_TOOLS,
                    CONNECTION_REGISTRY_TYPES,
                    Set.of(),
                    Set.of("listConnectionKeys", "getConnectionMetadata",
                            "getConnectionCount", "getActiveConnectionCount"),
                    "read-only management plane: only lists connection keys and reads connection metadata/counts"));

    // ---------------------------------------------------------------------------------------------
    // Baselines: current violation counts. Lower them as violations are fixed; never raise them.
    // ---------------------------------------------------------------------------------------------

    /**
     * R1 - tools depending on JDBC infrastructure. Enforced at 0: the last violation was
     * {@code BatchInsertHelper}, which has moved to {@code repository} where a {@code JdbcTemplate}
     * dependency belongs.
     */
    private static final int R1_BASELINE = 0;
    /**
     * R2 - tools depending on the BYOK connection registry. Zero outside the two documented
     * management-plane exceptions, so this rule is effectively enforced.
     */
    private static final int R2_BASELINE = 0;
    /** R3 was removed: the Maven module split enforces it at compile time. See the class Javadoc. */
    /** R4 - facade contract interfaces depending on Spring JDBC / pool types. Enforced at 0. */
    private static final int R4_BASELINE = 0;
    /**
     * R5 - package cycles. Enforced at 0. Getting here took three steps: moving
     * {@code BatchInsertHelper} out of {@code tools} (25 -> 10), moving the {@code CacheConfig} /
     * {@code EtlConfig} / {@code QueryConfig} records out of {@code config} into the leaf package
     * {@code properties} so that {@code config} is only a wiring layer (10 -> 1), and letting the
     * connection registry implement {@code monitor.PoolStatsSource} instead of {@code monitor}
     * depending back on it (1 -> 0).
     */
    private static final int R5_BASELINE = 0;

    /**
     * Rejects test code that {@link ImportOption.Predefined#DO_NOT_INCLUDE_TESTS} misses.
     *
     * <p>That predefined option matches on the path {@code /target/test-classes/}, which only
     * covers test classes still sitting in their output directory. {@code database-mcp-tools} also
     * publishes a {@code -tests.jar} (app's tests reuse its fixtures), and once jars are imported
     * that archive comes in as production code: the first measurement after enabling jar import
     * reported two R2 violations that were both {@code PoolMonitorToolsTest} touching
     * {@code DynamicDataSourceManager} — a test doing exactly what a test should do.
     */
    private static final ImportOption DO_NOT_INCLUDE_TEST_JARS =
            location -> !location.contains("-tests.jar");

    /**
     * Lower bound on the imported class count, guarding against a vacuous import. The full import is
     * currently 274 classes (core 57 / dialect 24 / infra 52 / features 90 / tools 45 / app 6); this
     * bound only has to be high enough that "app module only" (6 classes) cannot satisfy it, so
     * there is no need to keep it in step with the real number as the codebase grows.
     */
    private static final int MINIMUM_IMPORTED_CLASSES = 200;

    private static JavaClasses productionClasses;

    /**
     * Imports every production class of this repository, <strong>including the ones that arrive as
     * jars</strong>.
     *
     * <p>{@code DO_NOT_INCLUDE_JARS} used to be set here, and after the Maven module split that
     * quietly emptied R1/R2/R4: {@code tools}, {@code features}, {@code infra} and {@code dialect}
     * reach this test as jars on the classpath, so the importer skipped them and only app's own
     * {@code config}/{@code init}/{@code arch} classes remained. Every rule then reported 0
     * violations against 0 candidate classes — and {@code allowEmptyShould(true)} kept that silent.
     * Dropping the option is safe because {@code importPackages(BASE_PACKAGE)} already restricts the
     * import to {@code com.entropy.database.mcp}, a package only this repository's own modules use.
     *
     * <p><strong>Run this in a full reactor.</strong> Because the other layers now arrive as jars,
     * {@code mvn -pl database-mcp-app test} resolves them from {@code ~/.m2} and evaluates the rules
     * against whatever was last {@code install}ed — a green run then says nothing about the working
     * tree. Use {@code mvn verify} (or {@code -am}) when the verdict matters.
     */
    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(DO_NOT_INCLUDE_TEST_JARS)
                .importPackages(BASE_PACKAGE);
    }

    /**
     * The rules below are all {@code noClasses().that().resideInAPackage(...)}, which passes
     * trivially when nothing matches. So assert first that the import actually saw the layers the
     * rules are about; without this, a classpath or packaging change turns the whole class into a
     * no-op that still reports green.
     */
    @Test
    void importIsNotVacuous() {
        assertThat(productionClasses.size())
                .withFailMessage("Only %d classes imported: the importer is not seeing the module jars, "
                                + "so every rule below would pass against zero candidates.",
                        productionClasses.size())
                .isGreaterThanOrEqualTo(MINIMUM_IMPORTED_CLASSES);

        assertThat(productionClasses.stream()
                .anyMatch(javaClass -> javaClass.getPackageName().startsWith(BASE_PACKAGE + ".tools")))
                .withFailMessage("No class from the tools package was imported, so R1 and R2 "
                        + "have nothing to check.")
                .isTrue();
        assertThat(productionClasses.stream()
                .anyMatch(javaClass -> javaClass.getPackageName().equals(FACADE_PACKAGE)))
                .withFailMessage("No class from the facade package was imported, so R4 "
                        + "has nothing to check.")
                .isTrue();
    }

    /**
     * Test code must never reach the rules; it legitimately touches what production may not.
     *
     * <p>Asserted on where each class came from rather than on its name: a name filter such as
     * {@code endsWith("Test")} would miss the fixtures and helpers that a {@code -tests.jar} carries
     * alongside the obvious {@code *Test} classes ({@code McpTestHttp}, for one), and those are the
     * classes most likely to slip through unnoticed.
     */
    @Test
    void importExcludesTestCode() {
        assertThat(productionClasses.stream()
                .filter(javaClass -> javaClass.getSource()
                        .map(source -> source.getUri().toString())
                        .filter(uri -> uri.contains("-tests.jar") || uri.contains("/test-classes/"))
                        .isPresent())
                .map(JavaClass::getName)
                .toList())
                .as("classes from a test artifact leaked into the production import")
                .isEmpty();
    }

    @Test
    void r1_toolsMustNotDependOnJdbcInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(TOOLS_PACKAGE)
                .should(dependOnAnyOf(JDBC_INFRASTRUCTURE_TYPES,
                        "JDBC infrastructure (JdbcTemplate / javax.sql.DataSource / java.sql.Connection)"))
                .as("R1: classes in tools must not depend on JDBC infrastructure directly")
                .allowEmptyShould(true);

        assertWithinBaseline("R1", rule, R1_BASELINE);
    }

    @Test
    void r2_toolsMustNotDependOnConnectionRegistry() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(TOOLS_PACKAGE)
                .should(dependOnAnyOf(CONNECTION_REGISTRY_TYPES,
                        "the BYOK connection registry (DynamicDataSourceManager / ByokDataSourceContext)"))
                .as("R2: classes in tools must not depend on the BYOK connection registry directly")
                .allowEmptyShould(true);

        assertWithinBaseline("R2", rule, R2_BASELINE);
    }

    @Test
    void r4_facadeContractsMustNotDependOnSpringJdbc() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(FACADE_PACKAGE).and().areInterfaces()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.jdbc..", "com.zaxxer.hikari..")
                .as("R4: facade contract interfaces must stay free of Spring JDBC / pool types")
                .allowEmptyShould(true);

        assertWithinBaseline("R4", rule, R4_BASELINE);
    }

    @Test
    void r5_packagesMustBeFreeOfCycles() {
        ArchRule rule = slices()
                .matching(BASE_PACKAGE + ".(*)..")
                .should().beFreeOfCycles()
                .as("R5: top-level packages must be free of cycles");

        assertWithinBaseline("R5", rule, R5_BASELINE);
    }

    // ---------------------------------------------------------------------------------------------
    // warn-only plumbing
    // ---------------------------------------------------------------------------------------------

    /**
     * Evaluates a rule without letting existing violations fail the build: violations are logged in
     * full, and only a count above the pinned baseline fails the test.
     */
    private static void assertWithinBaseline(String ruleId, ArchRule rule, int baseline) {
        EvaluationResult result = rule.evaluate(productionClasses);
        List<String> violations = result.getFailureReport().getDetails();

        log.warn("[ArchUnit warn-only] {} -> {} violation(s), baseline {} | {}",
                ruleId, violations.size(), baseline, rule.getDescription());
        for (int i = 0; i < violations.size(); i++) {
            log.warn("[ArchUnit warn-only] {} violation {}/{}: {}",
                    ruleId, i + 1, violations.size(), violations.get(i));
        }

        assertThat(violations.size())
                .withFailMessage("%s regressed: %d violation(s) exceeds the pinned baseline of %d.%n"
                                + "Fix the new violation(s) instead of raising the baseline.%n%s",
                        ruleId, violations.size(), baseline, String.join(System.lineSeparator(), violations))
                .isLessThanOrEqualTo(baseline);
    }

    /**
     * Reports every dependency on one of {@code forbiddenTypes} that is not covered by
     * {@link #R1_R2_EXEMPTIONS}. Used with {@code noClasses()}, so a satisfied event is a violation.
     */
    private static ArchCondition<JavaClass> dependOnAnyOf(Set<String> forbiddenTypes, String targetDescription) {
        return new ArchCondition<>("depend on " + targetDescription) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getDirectDependenciesFromSelf().stream()
                        .filter(dependency -> forbiddenTypes.contains(baseTypeName(dependency.getTargetClass())))
                        .filter(dependency -> !isExempt(dependency))
                        .forEach(dependency -> events.add(
                                SimpleConditionEvent.satisfied(dependency, dependency.getDescription())));
            }
        };
    }

    private static boolean isExempt(Dependency dependency) {
        return R1_R2_EXEMPTIONS.stream().anyMatch(exemption -> exemption.covers(dependency));
    }

    private static String baseTypeName(JavaClass javaClass) {
        return javaClass.getBaseComponentType().getName();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Set<JavaAccess<?>> accessesOf(Dependency dependency) {
        return (Set<JavaAccess<?>>) (Set) dependency.convertTo(JavaAccess.class);
    }

    /**
     * A single documented allowance for R1/R2.
     *
     * @param originClass          fully qualified name of the class that is allowed the dependency
     * @param targetClasses        fully qualified names of the types it may reach
     * @param allowedOriginMethods method names (plus their lambdas) the dependency may originate from;
     *                             empty means "any method of the origin class"
     * @param allowedTargetMembers members of the target type that may be accessed; empty means "any"
     * @param reason               why this is architecturally legitimate
     */
    private record Exemption(String originClass,
                             Set<String> targetClasses,
                             Set<String> allowedOriginMethods,
                             Set<String> allowedTargetMembers,
                             String reason) {

        boolean covers(Dependency dependency) {
            if (!originClass.equals(dependency.getOriginClass().getName())) {
                return false;
            }
            if (!targetClasses.contains(baseTypeName(dependency.getTargetClass()))) {
                return false;
            }
            Set<JavaAccess<?>> accesses = accessesOf(dependency);
            if (accesses.isEmpty()) {
                // Structural dependency (field declaration, constructor parameter, ...): no code unit
                // to narrow on, the origin-class + target-type tuple above is the whole allowance.
                return true;
            }
            return accesses.stream().allMatch(this::isAllowedAccess);
        }

        private boolean isAllowedAccess(JavaAccess<?> access) {
            return originMethodAllowed(access.getOrigin().getName())
                    && targetMemberAllowed(access.getTarget().getName());
        }

        private boolean originMethodAllowed(String originMethod) {
            if (allowedOriginMethods.isEmpty()) {
                return true;
            }
            if (allowedOriginMethods.stream().anyMatch(allowed ->
                    allowed.equals(originMethod)
                            || originMethod.startsWith("lambda$" + allowed + "$"))) {
                return true;
            }
            // This toolchain compiles lambda bodies to synthetic methods named lambda$<n>, i.e. without
            // the enclosing method name, so a lambda body cannot be attributed to its method here. Such
            // accesses stay allowed only because allowedTargetMembers pins exactly which members of the
            // target type may be touched.
            return SYNTHETIC_LAMBDA.matcher(originMethod).matches() && !allowedTargetMembers.isEmpty();
        }

        private boolean targetMemberAllowed(String targetMember) {
            return allowedTargetMembers.isEmpty() || allowedTargetMembers.contains(targetMember);
        }
    }
}
