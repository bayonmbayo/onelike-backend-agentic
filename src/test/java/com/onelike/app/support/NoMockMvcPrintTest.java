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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No Spring context and no database: a source scan over src/test/java that
 * refuses a MockMvc print handler. A test that calls .andDo(print()) writes
 * the full request - headers included - into target/surefire-reports/, and
 * FilterNewsAgentControllerAuthTest sends the real configured API key as a
 * header. So the reflex when a MockMvc test fails would copy the key to disk.
 *
 * Refused, on any non-comment line: an andDo call whose argument starts with
 * print( or with the handler class's qualified print(, and any import of that
 * handler class. The class name is assembled by concatenation below so this
 * file does not import or name it on a code line.
 *
 * A line counts as a comment when, trimmed, it starts with //, /* or *. That
 * is a line rule, not a parser, and these are its blind spots:
 * - a call split across lines, e.g. andDo( on one line and print() on the next;
 * - a handler held in a variable and passed later, e.g. andDo(handler), when
 *   the class is reached through a wildcard import of its package;
 * - alwaysDo(print()) on the builder, and other handlers such as log();
 * - code following a block comment on the same line, which is exempted;
 * - a code line that itself starts with * (an operator continuation), which
 *   is exempted;
 * - anything outside src/test/java, and anything inside a text block.
 *
 * Three refusals keep the scan from passing vacuously: the root must exist,
 * the scan must have read this class's own source file, and it must have seen
 * a comment-exempted occurrence in this javadoc - which it has, here:
 * .andDo(print()) - while classifying this class's own declaration line as
 * code. The last half is what stops "everything is a comment" from passing.
 *
 * The failure message names relative/path:line ONLY.
 */
class NoMockMvcPrintTest {

    private static final String HANDLER_CLASS = "MockMvc" + "ResultHandlers";

    private static final Pattern PRINT_CALL =
            Pattern.compile("andDo\\s*\\(\\s*(" + HANDLER_CLASS + "\\s*\\.\\s*)?print\\s*\\(");

    private static final Pattern HANDLER_IMPORT =
            Pattern.compile("^\\s*import\\s+(static\\s+)?[\\w.]*\\b" + HANDLER_CLASS + "\\b");

    private static final Path ROOT = Paths.get("src", "test", "java");

    private static final Path SELF = ROOT.resolve(Paths.get("com", "onelike", "app", "support",
            "NoMockMvcPrintTest.java"));

    static boolean isComment(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*");
    }

    private static boolean matchesRaw(String line) {
        return PRINT_CALL.matcher(line).find() || HANDLER_IMPORT.matcher(line).find();
    }

    /** The 1-based numbers of every non-comment line holding a print call or a handler import. */
    static List<Integer> printLineNumbers(List<String> lines) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!isComment(line) && matchesRaw(line)) {
                hits.add(i + 1);
            }
        }
        return hits;
    }

    @Test
    void noTestSourceLinePrintsAMockMvcExchange() throws IOException {
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
            for (int lineNumber : printLineNumbers(lines)) {
                hits.add(ROOT.relativize(source).toString().replace('\\', '/') + ":" + lineNumber);
            }
        }

        List<String> self = Files.readAllLines(SELF, StandardCharsets.UTF_8);
        long exemptedInOwnJavadoc = self.stream().filter(l -> isComment(l) && matchesRaw(l)).count();
        assertTrue(exemptedInOwnJavadoc > 0, "control: no comment-exempted occurrence seen in " + SELF);
        String ownDeclaration = self.stream()
                .filter(l -> l.startsWith("class NoMockMvcPrintTest"))
                .findFirst()
                .orElse("");
        assertFalse(ownDeclaration.isEmpty() || isComment(ownDeclaration),
                "control: this class's own declaration line was not classified as code");

        assertTrue(hits.isEmpty(), "MockMvc print handler in test source (text withheld, see the line): " + hits);
    }

    @Test
    void aCodeLineIsFlaggedAndCommentLinesPass() {
        String call = ".andDo(" + "print())";
        String codeLine = "                " + call;
        String starLine = "     * " + call;
        String slashLine = "        // " + call;

        assertEquals(List.of(1), printLineNumbers(List.of(codeLine)), "code line not flagged");
        assertEquals(List.of(1), printLineNumbers(List.of("import static org.springframework.test.web.servlet.result."
                + HANDLER_CLASS + ".print;")), "handler import not flagged");
        assertEquals(List.of(), printLineNumbers(List.of(starLine)), "javadoc line flagged");
        assertEquals(List.of(), printLineNumbers(List.of(slashLine)), "line comment flagged");
    }
}
