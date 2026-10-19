package com.onelike.app.controller;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

import java.util.Collections;
import java.util.List;

/**
 * Gives every {@link IsolatedH2Test} class its own in-memory H2 database,
 * named after the class, with no placeholder and nothing for the class to
 * declare. Registered in src/test/resources/META-INF/spring.factories.
 *
 * - A class without the annotation gets no customizer (null), so the
 *   MySQL-profile test classes are untouched.
 * - An annotated class gets spring.datasource.url =
 *   jdbc:h2:mem:&lt;its fully qualified name, non-alphanumerics as _&gt;;MODE=MySQL;NON_KEYWORDS=USER
 *   as the FIRST property source, above the annotation's sentinel literal.
 * - The customizer's equals/hashCode is the class name. That is load-bearing:
 *   the customizer is part of the context cache key, and two classes whose
 *   configuration is otherwise identical (UserNewsContainerQuotaTest and
 *   UserNewsContainerFrontInsertTest are) would otherwise share ONE cached
 *   context, and with it one database.
 *
 * If this factory does not run, the context still sees the sentinel URL, and
 * {@link IsolatedH2Extension.RefuseUnlessIsolatedH2} refuses to start it.
 */
public class IsolatedH2ContextCustomizerFactory implements ContextCustomizerFactory {

    static final String PROPERTY_SOURCE_NAME = "isolatedH2PerClassDatabase";

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                     List<ContextConfigurationAttributes> configAttributes) {
        if (!AnnotatedElementUtils.hasAnnotation(testClass, IsolatedH2Test.class)) {
            return null;
        }
        return new PerClassDatabase(testClass.getName());
    }

    /** The in-memory database name for a test class: its name with every non-alphanumeric as '_'. */
    static String databaseNameOf(String className) {
        return className.replaceAll("[^A-Za-z0-9]", "_");
    }

    static String urlOf(String className) {
        return "jdbc:h2:mem:" + databaseNameOf(className) + ";MODE=MySQL;NON_KEYWORDS=USER";
    }

    static final class PerClassDatabase implements ContextCustomizer {
        private final String className;

        PerClassDatabase(String className) {
            this.className = className;
        }

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME,
                    Collections.singletonMap("spring.datasource.url", urlOf(className))));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PerClassDatabase && className.equals(((PerClassDatabase) other).className);
        }

        @Override
        public int hashCode() {
            return className.hashCode();
        }

        @Override
        public String toString() {
            return "IsolatedH2 database for " + className;
        }
    }
}
