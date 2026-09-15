package ru.citeck.ecos.crm.phonedigits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    /** First segment of a path of this repository, as the document spells it. */
    private static final List<String> IN_REPO_PREFIXES = List.of("ecos-crm", "docs", "src");
    /** The counterparty type: the second copy of the algorithm, in a repository cloned next to this one. */
    private static final Path COUNTERPARTY = Paths.get(
        "..", "ecos-datalist", "src", "main", "resources", "app", "artifacts", "model", "type",
        "ecos-counterparty.yml"
    ).toAbsolutePath().normalize();

    /** Repositories the contract points at, cloned next to this one. */
    private static final List<String> SIBLING_REPOS = List.of("ecos-datalist", "ecos-crm-citeck");

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

    @Test
    @DisplayName("the counterparty script carries the same collectPhoneKeys, character for character")
    void counterpartyScriptCarriesTheSameCollectPhoneKeys() {

        // the invariant the whole design rests on: if the two copies of the function diverge, the
        // types produce different keys for the same number and every lookup misses without an error.
        // Until now only a reviewer could notice that; the sibling clone is not guaranteed to be
        // checked out, so the comparison runs whenever it is - the policy the path check below uses
        assumeSiblingCloneIsPresent();

        String expected = sharedFunctionOf(ComputedScriptRunner.readAttributeScript(OPPORTUNITY, "phoneDigits"));
        String actual = sharedFunctionOf(ComputedScriptRunner.readAttributeScript(COUNTERPARTY, "phoneDigits"));

        assertLinesMatch(
            List.of(expected.split("\\R")),
            List.of(actual.split("\\R")),
            "collectPhoneKeys of " + COUNTERPARTY.getFileName() + " no longer matches the one of "
                + OPPORTUNITY.getFileName() + ". The copies must change together, and together with "
                + "the third one in the Mango route of COREDEV-510."
        );
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("the documented counterparty prologue is the one the counterparty type really runs")
    void documentedCounterpartyPrologueIsTheRealOne() {

        // the test above this one can only see what the document claims about the counterparty;
        // this one compares that claim with the artifact, so a prologue changed in ecos-datalist
        // alone can not leave the contract describing sources the type no longer reads
        assumeSiblingCloneIsPresent();

        String expected = prologueOf(ComputedScriptRunner.readAttributeScript(COUNTERPARTY, "phoneDigits"))
            .stripTrailing();
        String actual = jsBlocks().get(1).stripTrailing();

        assertLinesMatch(
            List.of(expected.split("\\R")),
            List.of(actual.split("\\R")),
            "The counterparty listing of " + DOC.getFileName() + " no longer matches "
                + COUNTERPARTY.getFileName() + ". Regenerate the document instead of editing the listing."
        );
        assertEquals(expected, actual);
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
        assertEquals(17, exampleTable().count(), "The example table lost or gained a row");
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
    @DisplayName("every file the document points to exists, in this repository and in the siblings")
    void everyFileTheDocumentPointsToExists() {

        // a path of this repository is spelled either prefixed with the project name, as the
        // cross-repository references are, or relative to the project root; a path of another
        // repository always starts with that repository name, and the clones are siblings on disk
        Pattern path = Pattern.compile("`([\\w.-]+(?:/[\\w.-]+)+\\.(?:yml|md))`");
        Matcher matcher = path.matcher(read(DOC));

        List<String> missing = new ArrayList<>();
        List<String> unknownRepo = new ArrayList<>();
        int inThisRepo = 0;
        int inSiblings = 0;
        while (matcher.find()) {
            String matched = matcher.group(1);
            String repo = matched.substring(0, matched.indexOf('/'));
            if (IN_REPO_PREFIXES.contains(repo)) {
                inThisRepo++;
                String relative = "ecos-crm".equals(repo)
                    ? matched.substring("ecos-crm/".length())
                    : matched;
                if (!Files.isRegularFile(Paths.get(relative))) {
                    missing.add(matched);
                }
                continue;
            }
            inSiblings++;
            // the name is checked even when the clone is absent: a typo in it sends the reader of
            // the contract to a repository that does not exist, and that must not pass silently
            if (!SIBLING_REPOS.contains(repo)) {
                unknownRepo.add(matched);
                continue;
            }
            Path sibling = Paths.get("..", repo);
            // only this repository is guaranteed to be checked out - a missing sibling clone is
            // not a defect of the document, so its files are verified when the clone is there
            if (Files.isDirectory(sibling) && !Files.isRegularFile(sibling.resolve(matched.substring(repo.length() + 1)))) {
                missing.add(matched);
            }
        }

        assertEquals(List.of(), unknownRepo, "The document points at repositories that do not exist");
        assertEquals(List.of(), missing, "The document refers to files that do not exist");
        // without these the test is a no-op as soon as the paths of the document are reformatted:
        // no match means an empty list, which equals the expectation
        assertTrue(inThisRepo >= 3, "The document must keep pointing at the artifacts it describes");
        assertTrue(
            inSiblings >= 3,
            "The document must keep pointing at the counterparty type, its patch and the community journal"
        );
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
        return jsBlocks().get(0).substring(0, prologueStart(jsBlocks().get(0)));
    }

    /** The part of a script that must be identical in every copy: the shared function itself. */
    private static String sharedFunctionOf(String script) {
        int start = script.indexOf("function collectPhoneKeys");
        assertTrue(start >= 0, "The script must declare collectPhoneKeys");
        return script.substring(start, prologueStart(script));
    }

    /** The part of a script that is allowed to differ per type: the prologue feeding the function. */
    private static String prologueOf(String script) {
        return script.substring(prologueStart(script) + 1);
    }

    /** Offset of the newline before the prologue - the single place the two parts are told apart. */
    private static int prologueStart(String script) {
        int prologue = script.indexOf("\nvar result = []");
        assertTrue(prologue > 0, "The script must end with a prologue starting with 'var result = []'");
        return prologue;
    }

    private static void assumeSiblingCloneIsPresent() {
        assumeTrue(
            Files.isRegularFile(COUNTERPARTY),
            "ecos-datalist is not checked out next to this repository, nothing to compare with: " + COUNTERPARTY
        );
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
