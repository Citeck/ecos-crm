package ru.citeck.ecos.crm.phonedigits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps docs/phone-digits.md - the phoneDigits contract for COREDEV-510 - in sync with the code.
 *
 * <p>The document is the only place an external consumer reads before writing its own copy of the
 * normalization, so a silent drift there is as damaging as a drift between the two type scripts:
 * the keys stop matching and the lookup misses without an error. Therefore the reference listing
 * of the document is compared character by character with opportunity.yml, and every row of its
 * example table is replayed against the real script on GraalJS.
 */
public class PhoneDigitsContractDocTest {

    private static final Path DOC = Paths.get("docs", "phone-digits.md").toAbsolutePath();
    private static final Path OPPORTUNITY = ComputedScriptRunner.typeFile("opportunity");
    private static final String CONTACT_PHONES = "contacts[].contactPhone";

    /** A fenced ```js block with its body, in the order the blocks appear in the document. */
    private static final Pattern JS_BLOCK = Pattern.compile("```js\\R(.*?)\\R```", Pattern.DOTALL);

    private static final String EXAMPLE_TABLE_HEADER = "| Вход | Ключи | Правило |";
    private static final String NO_KEYS = "— нет";
    /** The input cell of the empty-value row: an empty inline code span renders as two backticks. */
    private static final String EMPTY_INPUT = "(пустая строка)";

    @Test
    @DisplayName("the contract document exists")
    void contractDocumentExists() {
        assertTrue(Files.isRegularFile(DOC), "The contract document is missing: " + DOC);
    }

    @Test
    @DisplayName("the reference listing is the opportunity script character by character")
    void referenceListingIsTheOpportunityScriptCharacterByCharacter() {

        String expected = ComputedScriptRunner.readAttributeScript(OPPORTUNITY, "phoneDigits").stripTrailing();
        String actual = jsBlocks().get(0);

        // line by line first: a mismatch in one line is readable, a mismatch of two 45-line
        // strings is not
        assertLinesMatch(
            List.of(expected.split("\\R")),
            List.of(actual.split("\\R")),
            "The listing of " + DOC.getFileName() + " no longer matches the script of "
                + OPPORTUNITY.getFileName() + ". Regenerate the document instead of editing the listing."
        );
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("the counterparty listing differs from the opportunity one only in the prologue")
    void counterpartyListingDiffersFromTheOpportunityOneOnlyInThePrologue() {

        // the counterparty type lives in another repository, so this test can not read it - what it
        // can check is that the document shows the prologue, and only the prologue, as different
        String counterpartyBlock = jsBlocks().get(1);

        assertFalse(
            counterpartyBlock.contains("function collectPhoneKeys"),
            "The second listing must show the prologue only - the function is already listed above"
        );
        for (String source : List.of("phone", "cellPhone", CONTACT_PHONES)) {
            assertTrue(
                counterpartyBlock.contains("value.load('" + source + "')"),
                "The counterparty prologue must read " + source
            );
        }
        assertTrue(
            counterpartyBlock.contains("collectPhoneKeys(phones[i], result)"),
            "The counterparty prologue must feed every source into the shared function"
        );
    }

    @ParameterizedTest(name = "[{index}] {0} = \"{1}\" -> {2}")
    @MethodSource("counterpartySources")
    @DisplayName("the documented counterparty prologue really produces the documented keys")
    void documentedCounterpartyPrologueProducesTheDocumentedKeys(
        String source,
        String phone,
        List<String> expected
    ) {
        // grepping the listing above only proves the sources are mentioned - a wrong order, a lost
        // string guard around the scalar phone/cellPhone or a missing dedup would pass it. The
        // prologue is what the Mango route of COREDEV-510 copies, so it is executed here for real.
        String script = sharedFunction() + "\n" + jsBlocks().get(1);

        Map<String, Object> atts = new HashMap<>();
        atts.put("phone", null);
        atts.put("cellPhone", null);
        atts.put(CONTACT_PHONES, List.of());
        atts.put(source, CONTACT_PHONES.equals(source) ? List.of(phone) : phone);

        assertEquals(expected, ComputedScriptRunner.ofScript(script).executeToStringList(atts));
    }

    /** Every source of the counterparty prologue, each carrying the same number in turn. */
    static Stream<Arguments> counterpartySources() {
        return Stream.of(
            Arguments.of("phone", "+7 (917) 582-92-99", List.of("9175829299")),
            Arguments.of("cellPhone", "+7 (917) 582-92-99", List.of("9175829299")),
            Arguments.of(CONTACT_PHONES, "+7 (917) 582-92-99", List.of("9175829299")),
            Arguments.of("phone", "630-20-10", List.of()),
            Arguments.of("cellPhone", "", List.of())
        );
    }

    @Test
    @DisplayName("the example table of the document is not empty and lists every documented rule")
    void exampleTableOfTheDocumentIsNotEmpty() {
        assertEquals(15, exampleTable().count(), "The example table lost or gained a row");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @MethodSource("exampleTable")
    @DisplayName("example table of the document, replayed against the real script")
    void documentedExampleProducesTheDocumentedKeys(String phone, List<String> expected) {

        List<String> keys = ComputedScriptRunner.ofTypeAttribute(OPPORTUNITY, "phoneDigits")
            .executeToStringList(contacts(phone));

        assertEquals(expected, keys, "The document promises keys the script does not produce");
    }

    @Test
    @DisplayName("every file the document points to exists in this repository")
    void everyFileTheDocumentPointsToExistsInThisRepository() {

        // both spellings the document uses for a file of this repository: prefixed with the
        // project name, as the cross-repository references are, and repo relative
        Pattern path = Pattern.compile("`((?:ecos-crm/|docs/|src/)[\\w./-]+\\.(?:yml|md))`");
        Matcher matcher = path.matcher(read(DOC));

        List<String> missing = new ArrayList<>();
        int checked = 0;
        while (matcher.find()) {
            checked++;
            String matched = matcher.group(1);
            String relative = matched.startsWith("ecos-crm/")
                ? matched.substring("ecos-crm/".length())
                : matched;
            if (!Files.isRegularFile(Paths.get(relative))) {
                missing.add(matched);
            }
        }
        assertEquals(List.of(), missing, "The document refers to files that do not exist");
        // without this the test is a no-op as soon as the paths of the document are reformatted:
        // no match means an empty list, which equals the expectation
        assertTrue(checked >= 3, "The document must keep pointing at the artifacts it describes");
    }

    /** Rows of the example table of the document: the input and the keys it must produce. */
    static Stream<Arguments> exampleTable() {

        List<Arguments> rows = new ArrayList<>();
        boolean insideTable = false;

        for (String line : read(DOC).split("\\R")) {
            if (line.equals(EXAMPLE_TABLE_HEADER)) {
                insideTable = true;
                continue;
            }
            if (!insideTable) {
                continue;
            }
            if (!line.startsWith("|")) {
                break;
            }
            if (line.startsWith("|---")) {
                continue;
            }
            String[] cells = line.split("\\|", -1);
            rows.add(Arguments.of(unwrapCode(cells[1]), parseKeys(cells[2])));
        }

        if (rows.isEmpty()) {
            throw new IllegalStateException("The example table is not found in " + DOC);
        }
        return rows.stream();
    }

    private static List<String> parseKeys(String cell) {
        String value = cell.trim();
        if (value.equals(NO_KEYS)) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String key : value.split(",")) {
            keys.add(unwrapCode(key));
        }
        return Collections.unmodifiableList(keys);
    }

    /**
     * Content of an inline code span of a table cell. The surrounding spaces of the cell are
     * dropped, the ones inside the backticks are kept - a trailing space is one of the documented
     * cases.
     */
    private static String unwrapCode(String cell) {
        String value = cell.trim();
        if (value.equals(EMPTY_INPUT)) {
            return "";
        }
        if (!value.startsWith("`") || !value.endsWith("`") || value.length() < 2) {
            throw new IllegalStateException("Table cell is not an inline code span: '" + cell + "'");
        }
        return value.substring(1, value.length() - 1);
    }

    /**
     * The first listing without its opportunity prologue: the shared collectPhoneKeys only, so the
     * prologue of another type can be appended to it and executed.
     */
    private static String sharedFunction() {
        String block = jsBlocks().get(0);
        int prologue = block.indexOf("\nvar result = []");
        assertTrue(prologue > 0, "The first listing must end with the prologue of opportunity");
        return block.substring(0, prologue);
    }

    private static List<String> jsBlocks() {
        List<String> blocks = new ArrayList<>();
        Matcher matcher = JS_BLOCK.matcher(read(DOC));
        while (matcher.find()) {
            blocks.add(matcher.group(1));
        }
        assertTrue(blocks.size() >= 2, "The document must list the script and the counterparty prologue");
        return blocks;
    }

    private static Map<String, Object> contacts(String phone) {
        Map<String, Object> atts = new HashMap<>();
        atts.put(CONTACT_PHONES, List.of(phone));
        return atts;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read " + file, e);
        }
    }
}
