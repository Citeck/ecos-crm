package ru.citeck.ecos.crm.phonedigits;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("both journals filter the phone column by phoneDigits")
    void phoneColumnSearchesByPhoneDigitsTest() {
        for (String journalId : JOURNALS) {
            Map<?, ?> column = phoneColumn(journalId);
            Object searchConfig = column.get("searchConfig");
            assertNotNull(searchConfig, journalId + ".yml: the phone column has no searchConfig");
            assertEquals(
                PHONE_DIGITS,
                ((Map<?, ?>) searchConfig).get("searchAttribute"),
                journalId + ".yml: the phone column must be filtered by " + PHONE_DIGITS
            );
        }
    }

    @Test
    @DisplayName("the display attribute stays the human readable number, not the digit keys")
    void phoneColumnDisplaysContactPhoneTest() {
        for (String journalId : JOURNALS) {
            assertEquals(
                CONTACTS_PHONE_PATH,
                phoneColumn(journalId).get("attribute"),
                journalId + ".yml: the cell must keep showing the number as it is typed by the user"
            );
        }
    }

    @Test
    @DisplayName("the searched attribute is the one declared by opportunity")
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
    @DisplayName("the phone column is not turned into a separate visible digits column")
    void noDigitsColumnAddedTest() {
        // the keys are an internal lookup format - showing them to the user was deliberately
        // rejected, the column keeps the formatted number
        for (String journalId : JOURNALS) {
            for (Map<?, ?> column : columns(journalId)) {
                assertTrue(
                    !PHONE_DIGITS.equals(column.get("attribute")),
                    journalId + ".yml: " + PHONE_DIGITS + " must not be shown as a column"
                );
            }
        }
    }

    @Test
    @DisplayName("filtering of the phone column is not disabled")
    void phoneColumnStaysSearchableTest() {
        for (String journalId : JOURNALS) {
            Object searchable = phoneColumn(journalId).get("searchable");
            // null means the platform default, which is "searchable"; only an explicit false hides
            // the filter input and would make the redirect pointless
            if (searchable != null) {
                assertEquals(
                    Boolean.TRUE,
                    searchable,
                    journalId + ".yml: the phone column must stay searchable"
                );
            } else {
                assertNull(searchable);
            }
        }
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
