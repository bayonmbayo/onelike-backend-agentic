package com.onelike.app.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No Spring context and no database: a source scan over src/test/java that
 * refuses any line holding a quoted run of 28 to 48 letters and digits - the
 * same shape the credential redactor in CLAUDE.md masks. A fixture of that
 * shape cannot be told apart from a pasted secret by anyone reviewing a diff,
 * and the redactor would hide it from them.
 *
 * Scope: this refuses that one shape and nothing else. It does not require
 * the test-fixture-...-not-a-real-secret convention (hyphens included); a
 * fixture can break the convention and still pass. It does not see a run
 * containing - or _ (the base64url alphabet), a run longer than 48 characters
 * (the issuer's jwt.secret is 94 bytes), a value inside a prop=VALUE property
 * string (the quote must sit directly against the run), anything under
 * src/test/resources, a literal assembled by concatenation, or a value inside
 * a text block.
 *
 * Only src/test/java is scanned. src/main is deliberately out of scope: it
 * holds a known false positive, and production code is not where fixtures
 * live.
 *
 * The failure message names relative/path:line ONLY, never the matched text.
 * Surefire writes assertion messages into target/surefire-reports/, so
 * echoing the line would copy the very literal this test exists to keep out
 * of files.
 *
 * Two guards keep the scan from passing vacuously: the root must exist (a
 * wrong working directory would otherwise find zero files and pass), and the
 * scan must have read this class's own source file.
 */
class NoKeyShapedTestLiteralTest {

    // Character-class syntax, so this source line cannot match the pattern itself.
    private static final Pattern KEY_SHAPED = Pattern.compile("\"[A-Za-z0-9]{28,48}\"");

    private static final Path ROOT = Paths.get("src", "test", "java");

    private static final Path SELF = ROOT.resolve(Paths.get("com", "onelike", "app", "support",
            "NoKeyShapedTestLiteralTest.java"));

    @Test
    void noTestSourceLineHoldsAKeyShapedLiteral() throws IOException {
        assertTrue(Files.isDirectory(ROOT), "test source root not found: " + ROOT.toAbsolutePath());

        List<Path> sources;
        try (Stream<Path> walk = Files.walk(ROOT)) {
            sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
        }

        assertTrue(sources.contains(SELF), "scan did not reach its own source file: " + SELF);

        List<String> hits = new ArrayList<>();
        for (Path source : sources) {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (KEY_SHAPED.matcher(lines.get(i)).find()) {
                    hits.add(ROOT.relativize(source).toString().replace('\\', '/') + ":" + (i + 1));
                }
            }
        }

        assertTrue(hits.isEmpty(), "key-shaped literal in test source (text withheld, see the line): " + hits);
    }
}
