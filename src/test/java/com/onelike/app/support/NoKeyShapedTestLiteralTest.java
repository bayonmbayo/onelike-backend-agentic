package com.onelike.app.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No Spring context and no database: a source scan over src/test/java that
 * refuses any line holding a key-shaped run, so a fixture cannot be mistaken
 * for a pasted secret by anyone reviewing a diff.
 *
 * Shape: a run of 28 or more characters from the base64 and base64url
 * alphabets together (letters, digits, + / _ -), directly preceded by a quote,
 * an equals sign, a colon or a space, and followed by zero, one or two = and
 * then a closing quote. That covers a quoted key, base64url keys with - and _,
 * keys of any length past 28 (the issuer's jwt.secret is 94 bytes), padded
 * standard base64 (openssl rand -base64 32 and Base64.getEncoder() both end a
 * 32-byte key in one =), and a value inside a "prop=VALUE" or "prop: VALUE"
 * property string. Every match on a line is tested, so an exempt fixture
 * cannot shield a key that shares its line.
 *
 * A match passes only by exemption, applied with matches() to the whole run
 * (the padding is outside the captured run, so it neither helps nor hinders):
 * - the convention test-fixture-SEGMENT(-SEGMENT)*-not-a-real-secret, where
 *   every SEGMENT is 1 to 16 lowercase letters or digits. The cap is what stops
 *   the convention being used as a wrapper around a real 32-hex or base64 key;
 * - a lowercase-hex UUID, 8-4-4-4-12.
 *
 * Blind spots - this sees none of these:
 * - a literal assembled by concatenation, or built at runtime (this class's
 *   own samples are built that way on purpose, which is how they stay out of
 *   its own scan);
 * - a value inside a text block, since its lines carry no closing quote;
 * - anything under src/test/resources;
 * - a run shorter than 28 characters;
 * - a value containing a character outside the class, such as . (a JWT, for
 *   one) - the run stops at it and is then too short or not quote-terminated;
 * - a lead character outside quote, =, : and space (a tab, an opening paren);
 * - a secret that happens to be UUID-shaped, which the UUID exemption passes;
 * - a run followed by three or more = (no base64 encoder emits that padding).
 *
 * Padding of one or two = is no longer a blind spot (samples I and J).
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
 * scan must have read this class's own source file. The sample cases below
 * test the line check itself, so a pattern that silently matches nothing
 * fails them rather than passing the scan.
 */
class NoKeyShapedTestLiteralTest {

    // Character-class syntax, so this source line cannot match the pattern itself.
    private static final Pattern KEY_SHAPED = Pattern.compile("([\"=: ])([A-Za-z0-9+/_-]{28,})={0,2}\"");

    private static final Pattern FIXTURE_CONVENTION =
            Pattern.compile("^test-fixture-[a-z0-9]{1,16}(-[a-z0-9]{1,16})*-not-a-real-secret$");

    private static final Pattern LOWERCASE_UUID =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final Path ROOT = Paths.get("src", "test", "java");

    private static final Path SELF = ROOT.resolve(Paths.get("com", "onelike", "app", "support",
            "NoKeyShapedTestLiteralTest.java"));

    /**
     * The 1-based numbers of every line holding at least one non-exempt
     * key-shaped run. A line passes only if every match on it is exempt.
     */
    static List<Integer> keyShapedLineNumbers(List<String> lines) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = KEY_SHAPED.matcher(lines.get(i));
            while (matcher.find()) {
                if (!isExempt(matcher.group(2))) {
                    hits.add(i + 1);
                    break;
                }
            }
        }
        return hits;
    }

    private static boolean isExempt(String run) {
        return FIXTURE_CONVENTION.matcher(run).matches() || LOWERCASE_UUID.matcher(run).matches();
    }

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
            for (int lineNumber : keyShapedLineNumbers(lines)) {
                hits.add(ROOT.relativize(source).toString().replace('\\', '/') + ":" + lineNumber);
            }
        }

        assertTrue(hits.isEmpty(), "key-shaped literal in test source (text withheld, see the line): " + hits);
    }

    // ---- self-test: every sample below is built at runtime, never a literal ----

    private static final String QUOTE = "\"";

    private static String quotedOnALine(String value) {
        return "    private static final String SAMPLE = " + QUOTE + value + QUOTE + ";";
    }

    private static boolean flagged(String line) {
        return keyShapedLineNumbers(List.of(line)).equals(List.of(1));
    }

    private static byte[] bytes(int length, int... prefix) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (i < prefix.length ? prefix[i] : 0x41 + (i % 26));
        }
        return b;
    }

    /** 0xFB 0xEF 0xBE encodes to four index-62 characters, 0xFF 0xFF 0xFF to four index-63. */
    private static final int[] INDEX_62_AND_63 = {0xFB, 0xEF, 0xBE, 0xFF, 0xFF, 0xFF};

    private static String hex32() {
        return HexFormat.of().formatHex(bytes(16, 0xDE, 0xAD, 0xBE, 0xEF));
    }

    private static String conventionFixture() {
        return "test-fixture-" + "sample-" + "not-a-real-secret";
    }

    @Test
    void sampleA_base64UrlKeyWithHyphenAndUnderscoreIsFlagged() {
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(32, INDEX_62_AND_63));
        assertEquals(43, key.length());
        assertTrue(key.contains("-") && key.contains("_"), "sample (a) must hold both - and _");

        assertTrue(flagged(quotedOnALine(key)), "sample (a) not flagged");
    }

    @Test
    void sampleB_longStandardBase64KeyIsFlagged() {
        String key = Base64.getEncoder().withoutPadding().encodeToString(bytes(70, INDEX_62_AND_63));
        assertEquals(94, key.length());
        assertTrue(key.contains("+") && key.contains("/"), "sample (b) must hold both + and /");

        assertTrue(flagged(quotedOnALine(key)), "sample (b) not flagged");
    }

    @Test
    void sampleC_valueInsideAPropertyStringIsFlagged() {
        String value = HexFormat.of().withUpperCase().formatHex(bytes(20, 0x12, 0x34));
        assertEquals(40, value.length());

        assertTrue(flagged(quotedOnALine("k=" + value)), "sample (c) not flagged");
    }

    @Test
    void sampleD_lowercaseHexKeyIsFlagged() {
        String key = hex32();
        assertEquals(32, key.length());

        assertTrue(flagged(quotedOnALine(key)), "sample (d) not flagged");
    }

    @Test
    void sampleE_conventionWrapperWithUppercaseIsFlagged() {
        String value = "test-fixture-" + "Agent-Key-" + "not-a-real-secret";

        assertTrue(flagged(quotedOnALine(value)), "sample (e) not flagged");
    }

    @Test
    void sampleF_exemptFixtureDoesNotShieldAKeyOnTheSameLine() {
        String line = "    call(" + QUOTE + conventionFixture() + QUOTE + ", " + QUOTE + hex32() + QUOTE + ");";

        assertTrue(flagged(line), "sample (f) not flagged");
    }

    @Test
    void sampleG_uuidAndConventionFixturePass() {
        String uuid = UUID.nameUUIDFromBytes(bytes(8)).toString();
        assertEquals(36, uuid.length());

        assertEquals(List.of(), keyShapedLineNumbers(List.of(quotedOnALine(uuid))), "sample (g) UUID flagged");
        assertEquals(List.of(), keyShapedLineNumbers(List.of(quotedOnALine(conventionFixture()))),
                "sample (g) convention fixture flagged");
    }

    @Test
    void sampleH_conventionWrapperAroundAHexKeyIsFlagged() {
        String value = "test-fixture-" + hex32() + "-not-a-real-secret";

        assertTrue(flagged(quotedOnALine(value)), "sample (h) not flagged");
    }

    @Test
    void sampleI_paddedStandardBase64KeyWithOneEqualsIsFlagged() {
        String key = Base64.getEncoder().encodeToString(bytes(32, INDEX_62_AND_63));
        assertEquals(44, key.length());
        assertTrue(key.endsWith("=") && !key.endsWith("=="), "sample (i) must end in exactly one =");

        assertTrue(flagged(quotedOnALine(key)), "sample (i) not flagged");
    }

    @Test
    void sampleJ_paddedStandardBase64KeyWithTwoEqualsIsFlagged() {
        String key = Base64.getEncoder().encodeToString(bytes(64, INDEX_62_AND_63));
        assertEquals(88, key.length());
        assertTrue(key.endsWith("==") && !key.endsWith("==="), "sample (j) must end in exactly two =");

        assertTrue(flagged(quotedOnALine(key)), "sample (j) not flagged");
    }

    @Test
    void sampleK_sixtyFourCharacterLowercaseHexKeyIsFlagged() {
        String key = HexFormat.of().formatHex(bytes(32, 0xDE, 0xAD, 0xBE, 0xEF));
        assertEquals(64, key.length());
        assertEquals(key.toLowerCase(), key, "sample (k) must be lowercase");

        assertTrue(flagged(quotedOnALine(key)), "sample (k) not flagged");
    }
}
