package com.onelike.app.support;

import com.onelike.app.exception.FilterNewsException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boundary between application.properties and AgentApiKey, with no web
 * layer and no database: a minimal context holding only the placeholder
 * auto-configuration and the AgentApiKey bean.
 *
 * The property value is read from each real classpath property file - both
 * application.properties and application.prod.properties, every case runs
 * once per file - and asserted to be exactly the bare placeholder, then fed
 * into the context. So this tests both shipped lines, and an inline default
 * added to either (which would let a missing variable boot with that default)
 * fails here first. Only that one key is read from each file; no other value
 * is touched.
 *
 * The process environment and the JVM system properties are removed before
 * the context refreshes, and the initializer refuses if either is still
 * present. Without that, a developer machine or CI runner that exports
 * ONELIKE_NEWS_FILTER_APIKEY would turn the absent case into a context that
 * starts, and the case would be testing the machine instead of the code.
 * spring-dotenv is not involved: it registers only a SpringApplication run
 * listener, which ApplicationContextRunner never invokes.
 *
 * The three cases:
 * - absent: the context must refuse to start, naming the variable;
 * - set but empty: the context starts - a placeholder resolving to "" is not
 *   missing - so the bean itself has to reject every presented key;
 * - control: a convention fixture key is accepted, so the refusals above are
 *   not the bean rejecting everything.
 */
class AgentApiKeyPropertyTest {

    private static final String PROPERTY = "onelike.news.filter.apikey";

    private static final String VARIABLE = "ONELIKE_NEWS_FILTER_APIKEY";

    private static final String CODE_INVALID = "filternews.apikey.invalid";

    private static final String FIXTURE_KEY = "test-fixture-property-key-not-a-real-secret";

    private static String shippedPropertyValue(String file) throws IOException {
        Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource(file));
        String value = properties.getProperty(PROPERTY);
        assertNotNull(value, PROPERTY + " is missing from classpath " + file);
        return value;
    }

    /** Strips the process environment and system properties, and refuses if either survives. */
    static class WithoutSystemSources implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            MutablePropertySources sources = context.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                    || sources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
                throw new IllegalStateException("system property sources still present; refusing to run");
            }
        }
    }

    private static ApplicationContextRunner runner(String file) throws IOException {
        String value = shippedPropertyValue(file);
        assertEquals("${" + VARIABLE + "}", value,
                PROPERTY + " in " + file + " is no longer the bare placeholder");

        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AgentApiKey.class)
                .withInitializer(new WithoutSystemSources())
                .withPropertyValues(PROPERTY + "=" + value);
    }

    private static boolean chainMentions(Throwable failure, String text) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.properties", "application.prod.properties"})
    void absentVariableRefusesToStartAndNamesIt(String file) throws IOException {
        runner(file).run(context -> {
            assertNotNull(context.getStartupFailure(), "context started with " + VARIABLE + " absent");
            assertTrue(chainMentions(context.getStartupFailure(), VARIABLE),
                    "startup failure chain does not name " + VARIABLE);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.properties", "application.prod.properties"})
    void emptyVariableStartsAndTheBeanRejectsEveryKey(String file) throws IOException {
        runner(file).withPropertyValues(VARIABLE + "=").run(context -> {
            assertEquals(null, context.getStartupFailure(), "context failed to start with " + VARIABLE + " empty");
            AgentApiKey agentApiKey = context.getBean(AgentApiKey.class);

            assertEquals(CODE_INVALID,
                    assertThrows(FilterNewsException.class, () -> agentApiKey.requireValidKey("")).getMessage());
            assertEquals(CODE_INVALID,
                    assertThrows(FilterNewsException.class, () -> agentApiKey.requireValidKey("   ")).getMessage());
            assertEquals(CODE_INVALID,
                    assertThrows(FilterNewsException.class, () -> agentApiKey.requireValidKey(FIXTURE_KEY)).getMessage());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.properties", "application.prod.properties"})
    void controlConfiguredVariableStartsAndTheBeanAcceptsIt(String file) throws IOException {
        runner(file).withPropertyValues(VARIABLE + "=" + FIXTURE_KEY).run(context -> {
            assertEquals(null, context.getStartupFailure(), "context failed to start with " + VARIABLE + " set");
            AgentApiKey agentApiKey = context.getBean(AgentApiKey.class);

            assertDoesNotThrow(() -> agentApiKey.requireValidKey(FIXTURE_KEY));
        });
    }
}
