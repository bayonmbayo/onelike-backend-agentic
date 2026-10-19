package com.onelike.app.support;

import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.io.IOException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The effective Tomcat request-thread maximum, read from a RUNNING connector.
 *
 * Not the full application context: only Boot's own servlet-server and
 * server-customizer auto-configuration, on an ephemeral port bound to
 * 127.0.0.1. That is representative of the application because src/main holds
 * no WebServerFactoryCustomizer, TomcatConnectorCustomizer,
 * TomcatProtocolHandlerCustomizer or ServletWebServerFactory bean of its own
 * (git grep -F over src/main, 2026-10-19 C), so the shipped key reaches
 * Tomcat by exactly this path. It makes no outward request.
 *
 * 200 is also Tomcat's default, so a test that only reads 200 cannot tell a
 * bound property from an ignored one. The control case feeds an arbitrary
 * value and must read it back; the old-key case records that Boot 3.1.3
 * ignores server.tomcat.max-threads (the line this cycle removed).
 */
class TomcatThreadsMaxTest {

    private static final String THREADS_MAX = "server.tomcat.threads.max";

    private static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                .withConfiguration(AutoConfigurations.of(
                        ServletWebServerFactoryAutoConfiguration.class,
                        EmbeddedWebServerFactoryCustomizerAutoConfiguration.class))
                .withPropertyValues("server.port=0", "server.address=127.0.0.1");
    }

    private static int runningMaxThreads(WebApplicationContextRunner runner) {
        int[] max = {-1};
        runner.run(context -> {
            assertNull(context.getStartupFailure(), "the server context failed to start");
            ServletWebServerApplicationContext server =
                    context.getSourceApplicationContext(ServletWebServerApplicationContext.class);
            TomcatWebServer webServer = (TomcatWebServer) server.getWebServer();
            Connector connector = webServer.getTomcat().getConnector();
            assertTrue(webServer.getPort() > 0, "the connector is not bound");
            Executor executor = connector.getProtocolHandler().getExecutor();
            assertNotNull(executor, "the running connector has no executor");
            assertTrue(executor instanceof ThreadPoolExecutor,
                    "unexpected executor type " + executor.getClass().getName());
            max[0] = ((ThreadPoolExecutor) executor).getMaximumPoolSize();
        });
        return max[0];
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.properties", "application.prod.properties"})
    void theShippedValueIsWhatTheRunningConnectorUses(String file) throws IOException {
        String shipped = ServerPropertiesGuardTest
                .load(ServerPropertiesGuardTest.RESOURCES.resolve(file))
                .getProperty(THREADS_MAX);
        assertNotNull(shipped, THREADS_MAX + " is missing from " + file);
        assertEquals(200, runningMaxThreads(runner().withPropertyValues(THREADS_MAX + "=" + shipped.trim())));
    }

    @Test
    void controlAnArbitraryValueIsReadBack() {
        assertEquals(37, runningMaxThreads(runner().withPropertyValues(THREADS_MAX + "=37")));
    }

    @Test
    void theOldMaxThreadsKeyIsIgnored() {
        assertEquals(200, runningMaxThreads(runner().withPropertyValues("server.tomcat.max-threads=2500")));
    }
}
