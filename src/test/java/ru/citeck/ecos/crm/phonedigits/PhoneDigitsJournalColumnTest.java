package ru.citeck.ecos.crm.phonedigits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The "Телефон" column of the deals and leads journals is bound to contacts.contactPhone. Loading
 * that path works - the cell has always shown the number - but a predicate over a path inside a
 * JSON attribute is cut to alwaysFalse in ecos-data, so the filter silently returned nothing.
 *
 * <p>The fix keeps the display attribute and redirects the filter to the indexed computed attribute
 * via searchConfig.searchAttribute (ecos-ui rewrites the predicate att in JournalsConverter).
 * These tests guard the pair: renaming phoneDigits or dropping the redirect brings the silent miss
 * back, and nothing in the journal artifact itself would fail on deploy.
 */
public class PhoneDigitsJournalColumnTest {

    private static final String JOURNALS_DIR = "src/main/resources/app/artifacts/ui/journal";
    private static final String PHONE_COLUMN = "phone";
    private static final String PHONE_DIGITS = "phoneDigits";
    private static final String CONTACTS_PHONE_PATH = "contacts.contactPhone";
    private static final List<String> JOURNALS = List.of("deals-journal", "leads-journal");

    @Test
    @DisplayName("both journals carry a separate searchable phoneDigits column")
    void separateDigitsColumnIsSearchableTest() {
        // The phone column and the search by number are deliberately two different columns. Until
        // 15.09.2026 they were one: the phone column displayed the formatted number while its
        // filter was silently redirected to phoneDigits, so the user saw one value and had to type
        // another - typing the number shown in the cell returned nothing. See ECOSCRM-106 Task 13.
        for (String journalId : JOURNALS) {
            Map<?, ?> column = digitsColumn(journalId);
            assertEquals(
                PHONE_DIGITS,
                column.get("attribute"),
                journalId + ".yml: the digits column must read " + PHONE_DIGITS
            );
            assertEquals(
                Boolean.TRUE,
                column.get("searchable"),
                journalId + ".yml: the digits column is the searchable half of the pair"
            );
            assertEquals(
                Boolean.TRUE,
                column.get("visible"),
                journalId + ".yml: hiding it would drop it from the quick find box too"
            );
        }
    }

    @Test
    @DisplayName("the old contacts.contactPhone column is gone")
    void oldPhoneColumnIsRemovedTest() {
        // It could neither be filtered - a predicate over a path inside a JSON attribute is cut to
        // alwaysFalse - nor show more than the first contact, because uiserv rejects the only path
        // shape that reads them all (contacts[].contactPhone) as a column attribute. Keeping it
        // beside the digits column meant showing the same number twice, one of the copies partial.
        for (String journalId : JOURNALS) {
            for (Map<?, ?> column : columns(journalId)) {
                assertNotEquals(
                    CONTACTS_PHONE_PATH,
                    column.get("attribute"),
                    journalId + ".yml: " + CONTACTS_PHONE_PATH + " must not be a column any more"
                );
            }
        }
    }

    @Test
    @DisplayName("the digits column renders through a formatter, not as raw digits")
    void digitsColumnHasAFormatterTest() {
        for (String journalId : JOURNALS) {
            Map<?, ?> formatter = (Map<?, ?>) digitsColumn(journalId).get("formatter");
            assertNotNull(formatter, journalId + ".yml: the digits column has no formatter");
            assertEquals("script", formatter.get("type"), journalId + ".yml: expected a script formatter");
            Object fn = ((Map<?, ?>) formatter.get("config")).get("fn");
            assertNotNull(fn, journalId + ".yml: the formatter has no fn");
            assertTrue(
                String.valueOf(fn).contains("'+7 ('"),
                journalId + ".yml: the formatter must render the russian shape readably"
            );
        }
    }

    @Test
    @DisplayName("the searched attribute is the one declared by the type")
    void searchAttributeIsDeclaredByTheTypeTest() {
        // a typo here would be silent: the journal deploys fine and the filter finds nothing,
        // which is exactly the defect being fixed
        List<String> declared = PhoneDigitsHistoryExclusionTest.attributeIds(
            ComputedScriptRunner.typeFile("opportunity")
        );
        assertTrue(
            declared.contains(PHONE_DIGITS),
            "opportunity.yml must declare " + PHONE_DIGITS + ", journals refer to it by this id"
        );
    }

    @Test
    @DisplayName("the searched attribute is indexed - the filter must not become a full scan")
    void searchAttributeIsIndexedTest() {
        Map<?, ?> attribute = attributeDef(ComputedScriptRunner.typeFile("opportunity"), PHONE_DIGITS);
        Object index = attribute.get("index");
        assertNotNull(index, "opportunity.yml: " + PHONE_DIGITS + " has no index section");
        assertEquals(
            Boolean.TRUE,
            ((Map<?, ?>) index).get("enabled"),
            "the journal filter runs over " + PHONE_DIGITS + ", so the GIN index must stay enabled"
        );
    }

    @Test
    @DisplayName("the definition of the searched attribute keeps the shape the filter relies on")
    void searchAttributeDefinitionTest() {
        // every property here is load bearing and none of them fails on deploy if it is changed:
        // TEXT+multiple is what makes the predicate an array overlap over a varchar[] column,
        // SCRIPT+ON_MUTATE is what keeps the keys up to date on every save of the record. Dropping
        // any of them leaves a journal that deploys fine and finds nothing.
        Map<?, ?> attribute = attributeDef(ComputedScriptRunner.typeFile("opportunity"), PHONE_DIGITS);

        assertEquals("TEXT", attribute.get("type"), PHONE_DIGITS + " must stay a text attribute");
        assertEquals(
            Boolean.TRUE,
            attribute.get("multiple"),
            PHONE_DIGITS + " holds a key per phone, the filter is an overlap over the array"
        );

        Object computed = attribute.get("computed");
        assertNotNull(computed, PHONE_DIGITS + " has no computed section");
        assertEquals(
            "SCRIPT",
            ((Map<?, ?>) computed).get("type"),
            "the keys are produced by the normalization script"
        );
        assertEquals(
            "ON_MUTATE",
            ((Map<?, ?>) computed).get("storingType"),
            "the value must be stored and refreshed on every save, otherwise there is no column to filter"
        );
    }

    @Test
    @DisplayName("exactly one phone column is left")
    void exactlyOnePhoneColumnTest() {
        for (String journalId : JOURNALS) {
            long phoneColumns = columns(journalId).stream()
                .filter(c -> String.valueOf(c.get("id")).toLowerCase().contains("phone"))
                .count();
            assertEquals(
                1L,
                phoneColumns,
                journalId + ".yml: the displayed and the searchable column were merged into one"
            );
        }
    }

    private static Map<?, ?> digitsColumn(String journalId) {
        for (Map<?, ?> column : columns(journalId)) {
            if (PHONE_DIGITS.equals(String.valueOf(column.get("id")))) {
                return column;
            }
        }
        throw new IllegalArgumentException(
            "Journal " + journalId + " has no '" + PHONE_DIGITS + "' column"
        );
    }

    private static Map<?, ?> phoneColumn(String journalId) {
        for (Map<?, ?> column : columns(journalId)) {
            if (PHONE_COLUMN.equals(String.valueOf(column.get("id")))) {
                return column;
            }
        }
        throw new IllegalArgumentException("Journal " + journalId + " has no '" + PHONE_COLUMN + "' column");
    }

    private static List<Map<?, ?>> columns(String journalId) {
        Path file = Paths.get(JOURNALS_DIR, journalId + ".yml").toAbsolutePath();
        Object columns = PhoneDigitsHistoryExclusionTest.loadYaml(file).get("columns");
        if (!(columns instanceof List)) {
            throw new IllegalArgumentException("Journal " + file + " has no 'columns' list");
        }
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object column : (List<?>) columns) {
            if (!(column instanceof Map)) {
                throw new IllegalArgumentException("Journal " + file + " has a column which is not an object");
            }
            result.add((Map<?, ?>) column);
        }
        return result;
    }

    private static Map<?, ?> attributeDef(Path typeFile, String attId) {
        Object model = PhoneDigitsHistoryExclusionTest.loadYaml(typeFile).get("model");
        Object attributes = model instanceof Map ? ((Map<?, ?>) model).get("attributes") : null;
        if (!(attributes instanceof List)) {
            throw new IllegalArgumentException("Type " + typeFile + " has no 'model.attributes' list");
        }
        for (Object att : (List<?>) attributes) {
            if (att instanceof Map && attId.equals(String.valueOf(((Map<?, ?>) att).get("id")))) {
                return (Map<?, ?>) att;
            }
        }
        throw new IllegalArgumentException("Attribute '" + attId + "' is not found in " + typeFile);
    }
}
