package com.onelike.app.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refuses Tomcat server settings in the shipped property files that are either
 * silently ignored or hit a fixed access-log list: the tokens %r, %q, %h, %a
 * (bare or with a {...} argument) and any %{...}i request header, the aliases
 * "common" and "combined", a placeholder pattern, and an enabled log with no
 * pattern. That list is not "anything that could leak a credential":
 * %{...}c (a cookie), %S (the session id), %{...}s (a session attribute), %u
 * (the remote user) and %{remote}p are NOT refused (verifier N1, 2026-10-19 C;
 * backlog N1, the allowlist that would close it). No Spring, no database: the
 * two files are read from src/main/resources as plain java.util.Properties.
 *
 * WHY a refusal and not a comment: the symptom of each failure is remote.
 * - The access log pattern. Every request's query string can carry ?apiKey=
 *   or ?uuid=, and a request header can carry X-API-Key. A pattern holding %r
 *   (the request line, query included), %q (the query string), or a %{...}i
 *   header would write those into /logs on the production host, where no test
 *   and no reviewer reads it. %h/%a write the caller's IP, which the gate
 *   (2026-10-19 C, "Prod only, no IPs") ruled out. "common" and "combined"
 *   are Tomcat aliases that contain %r and %h. An enabled log with no explicit
 *   pattern falls back to Tomcat's default, which is "common".
 * - server.tomcat.max-threads. Boot 3.1.3 has no such property (0 entries in
 *   spring-configuration-metadata.json, no setMaxThreads on
 *   ServerProperties$Tomcat), so the line is ignored without a warning and
 *   Tomcat keeps its default of 200. Its replacement is server.tomcat.threads.max.
 *   Keys are compared in Boot's relaxed form (lowercase, '-' and '_' removed),
 *   so server.tomcat.maxThreads is refused too.
 * - server.tomcat.threads.max above MAX_THREADS_BOUND. Hikari's pool of 20
 *   bounds only the requests that borrow a database connection; with
 *   spring.cache.type=simple a cached request may never borrow one. That
 *   threads beyond the pool merely queue on it is unmeasured, and would hold
 *   at most for requests that need a connection.
 *
 * It must not pass by scanning nothing: a missing file, an empty file and a
 * file with no keys are refused, and the shipped-files case asserts that both
 * named files were read.
 */
class ServerPropertiesGuardTest {

    static final Path RESOURCES = Paths.get("src", "main", "resources");

    static final List<String> SHIPPED_FILES = List.of("application.properties", "application.prod.properties");

    static final int MAX_THREADS_BOUND = 400;

    /** The pattern approved at the 2026-10-19 C gate, verbatim. */
    static final String APPROVED_PROD_PATTERN = "%t %m %U %s %b %{ms}T";

    private static final String ENABLED = "server.tomcat.accesslog.enabled";
    private static final String PATTERN = "server.tomcat.accesslog.pattern";
    private static final String THREADS_MAX = "server.tomcat.threads.max";
    private static final String OLD_MAX_THREADS = "server.tomcat.max-threads";

    /** %r, %q, %h, %a, with or without a {...} argument. */
    private static final Pattern FORBIDDEN_TOKEN = Pattern.compile("%(\\{[^}]*\\})?[rqha]");

    /** Any request header, %{name}i. */
    private static final Pattern HEADER_TOKEN = Pattern.compile("%\\{[^}]*\\}i");

    /** Boot's relaxed-binding comparison form of a key. */
    static String canonical(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /** Reads one file, refusing a missing file, an empty file, or one with no keys. */
    static Properties load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("refusing: property file not found: " + file);
        }
        if (Files.size(file) == 0) {
            throw new IllegalStateException("refusing: property file is empty: " + file);
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        if (properties.isEmpty()) {
            throw new IllegalStateException("refusing: property file holds no keys: " + file);
        }
        return properties;
    }

    /** The value of the first key whose relaxed form matches, or null. */
    private static String get(Properties properties, String key) {
        String wanted = canonical(key);
        for (String name : properties.stringPropertyNames()) {
            if (canonical(name).equals(wanted)) {
                return properties.getProperty(name);
            }
        }
        return null;
    }

    /** Every refusal for one file's properties; empty means it passes. */
    static List<String> violations(Properties properties) {
        List<String> found = new ArrayList<>();

        if (get(properties, OLD_MAX_THREADS) != null) {
            found.add(OLD_MAX_THREADS + " is not a Boot 3 property and is ignored; use " + THREADS_MAX);
        }

        String threads = get(properties, THREADS_MAX);
        if (threads != null) {
            try {
                int max = Integer.parseInt(threads.trim());
                if (max < 1 || max > MAX_THREADS_BOUND) {
                    found.add(THREADS_MAX + "=" + max + " is outside 1.." + MAX_THREADS_BOUND);
                }
            } catch (NumberFormatException e) {
                found.add(THREADS_MAX + " is not a literal integer, so its bound cannot be checked");
            }
        }

        String enabled = get(properties, ENABLED);
        boolean logOn = enabled != null && !"false".equalsIgnoreCase(enabled.trim());
        String pattern = get(properties, PATTERN);

        if (logOn && (pattern == null || pattern.trim().isEmpty())) {
            found.add("access log enabled without an explicit " + PATTERN + " (Tomcat's default is common)");
        }
        if (pattern != null) {
            String p = pattern.trim();
            if ("common".equalsIgnoreCase(p) || "combined".equalsIgnoreCase(p)) {
                found.add(PATTERN + " is the alias " + p.toLowerCase(Locale.ROOT) + ", which logs the request line and the IP");
            }
            if (p.contains("${")) {
                found.add(PATTERN + " holds a placeholder, so its tokens cannot be checked");
            }
            if (FORBIDDEN_TOKEN.matcher(p).find()) {
                found.add(PATTERN + " holds %r, %q, %h or %a (query string or IP)");
            }
            if (HEADER_TOKEN.matcher(p).find()) {
                found.add(PATTERN + " holds a %{...}i request header");
            }
        }
        return found;
    }

    static List<String> violations(Path file) throws IOException {
        return violations(load(file));
    }

    // ---------------------------------------------------------------- shipped files

    @Test
    void bothShippedFilesAreReadAndPassTheGuard() throws IOException {
        Set<String> read = new LinkedHashSet<>();
        for (String name : SHIPPED_FILES) {
            Path file = RESOURCES.resolve(name);
            List<String> found = violations(file);
            assertEquals(List.of(), found, name + " is refused");
            read.add(name);
        }
        assertEquals(Set.of("application.properties", "application.prod.properties"), read,
                "the guard did not read both shipped property files");
    }

    @Test
    void devFileKeepsTheAccessLogOff() throws IOException {
        Properties dev = load(RESOURCES.resolve("application.properties"));
        String enabled = get(dev, ENABLED);
        assertTrue(enabled == null || "false".equalsIgnoreCase(enabled.trim()),
                "the access log is enabled in application.properties; the gate ruled prod only");
    }

    @Test
    void prodFileLogsWithTheApprovedPattern() throws IOException {
        Properties prod = load(RESOURCES.resolve("application.prod.properties"));
        assertEquals("true", get(prod, ENABLED));
        assertEquals("/logs", get(prod, "server.tomcat.accesslog.directory"));
        assertEquals("14", get(prod, "server.tomcat.accesslog.max-days"));
        assertEquals(APPROVED_PROD_PATTERN, get(prod, PATTERN));
    }

    // ---------------------------------------------------------------- refusals, on synthetic files

    static Stream<Arguments> refusedFiles() {
        String on = ENABLED + "=true\n";
        return Stream.of(
                Arguments.of("enabled, no pattern", on, "without an explicit"),
                Arguments.of("enabled, blank pattern", on + PATTERN + "=\n", "without an explicit"),
                Arguments.of("alias common", on + PATTERN + "=common\n", "alias common"),
                Arguments.of("alias combined", on + PATTERN + "=combined\n", "alias combined"),
                Arguments.of("alias, any case", on + PATTERN + "=COMMON\n", "alias common"),
                Arguments.of("request line", on + PATTERN + "=%t %r %s\n", "%r, %q"),
                Arguments.of("query string", on + PATTERN + "=%t %U%q %s\n", "%r, %q"),
                Arguments.of("remote host", on + PATTERN + "=%h %t %U\n", "%r, %q"),
                Arguments.of("remote address", on + PATTERN + "=%a %t %U\n", "%r, %q"),
                Arguments.of("request header", on + PATTERN + "=%t %U %{X-API-Key}i\n", "request header"),
                Arguments.of("pattern placeholder", on + PATTERN + "=${LOG_PATTERN}\n", "placeholder"),
                Arguments.of("bad pattern while disabled", ENABLED + "=false\n" + PATTERN + "=combined\n", "alias combined"),
                Arguments.of("old max-threads key", OLD_MAX_THREADS + "=200\n", "not a Boot 3 property"),
                Arguments.of("old key, relaxed form", "server.tomcat.maxThreads=200\n", "not a Boot 3 property"),
                Arguments.of("threads above bound", THREADS_MAX + "=2500\n", "outside 1.."),
                Arguments.of("threads zero", THREADS_MAX + "=0\n", "outside 1.."),
                Arguments.of("threads placeholder", THREADS_MAX + "=${THREADS}\n", "not a literal integer"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedFiles")
    void refuses(String label, String content, String expectedFragment, @TempDir Path dir) throws IOException {
        Path file = dir.resolve("refused.properties");
        Files.write(file, content.getBytes(StandardCharsets.ISO_8859_1));
        List<String> found = violations(file);
        assertFalse(found.isEmpty(), label + " was not refused");
        assertTrue(found.stream().anyMatch(v -> v.contains(expectedFragment)),
                label + " was refused for the wrong reason: " + found);
    }

    @Test
    void controlTheApprovedShapePasses(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("approved.properties");
        String content = THREADS_MAX + "=200\n" + ENABLED + "=true\n" + PATTERN + "=" + APPROVED_PROD_PATTERN + "\n";
        Files.write(file, content.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(List.of(), violations(file));
    }

    @Test
    void refusesAMissingFile(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> violations(dir.resolve("absent.properties")));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void refusesAnEmptyFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.properties");
        Files.write(file, new byte[0]);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> violations(file));
        assertTrue(e.getMessage().contains("empty"));
    }

    @Test
    void refusesAFileWithNoKeys(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("comments.properties");
        Files.write(file, "# only a comment\n\n".getBytes(StandardCharsets.ISO_8859_1));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> violations(file));
        assertTrue(e.getMessage().contains("no keys"));
    }
}
