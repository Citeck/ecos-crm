package ru.citeck.ecos.crm.phonedigits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * phoneDigits is computed with storingType ON_MUTATE, so a record gets its value only when it is
 * saved. Records that already exist at deploy time would stay without the value - and therefore
 * unsearchable by phone - until somebody edits them. The patch fills them by running the standard
 * "update-calculated-atts" group action over every record of the type.
 *
 * <p>The patch is a plain YAML the app deploys, so nothing here executes it: what these tests can
 * catch is the silent mistake - a typo in the action type, a wrong typeRef, an accidentally copied
 * manual flag - that would make the patch apply successfully and change nothing.
 */
public class PhoneDigitsFillPatchTest {

    private static final String PATCHES_DIR = "src/main/resources/app/artifacts/app/patch";

    private static final String PATCH_ID = "fill-opportunity-phone-digits";
    /** The type the attribute is declared on; lead and deal are picked up as its children. */
    private static final String TYPE_REF = "emodel/type@opportunity";

    /** The types that inherit phoneDigits from TYPE_REF and are recalculated through it. */
    private static final List<String> CHILD_TYPES = List.of("deal", "lead");

    @Test
    @DisplayName("the patch runs the standard recalculation action over records of opportunity")
    void patchRunsRecalculationForTypeTest() {
        Map<?, ?> attributes = groupActionAttributes(patchFile(PATCH_ID));

        Map<?, ?> values = section(attributes, "values");
        assertEquals(
            "admin-action-records-of-type",
            values.get("type"),
            "records to process must be selected by type"
        );
        assertEquals(
            TYPE_REF,
            section(values, "config").get("typeRef"),
            "the patch must select records of the type phoneDigits is declared on"
        );

        assertEquals(
            "update-calculated-atts",
            section(attributes, "execution").get("type"),
            "the execution must be the standard 'update calculated attributes' action"
        );
    }

    @Test
    @DisplayName("the patch mutates emodel, where both the records and the group action live")
    void patchTargetsEmodelTest() {
        Map<String, Object> patch = PhoneDigitsHistoryExclusionTest.loadYaml(patchFile(PATCH_ID));
        assertEquals(PATCH_ID, patch.get("id"), "patch id must match its file name");
        assertEquals("mutate", patch.get("type"), "the patch creates a record, hence type 'mutate'");
        assertEquals("emodel", patch.get("targetApp"), "group actions are executed by emodel");
        assertNotNull(patch.get("date"), "a patch without a date can't be redeployed with a newer one");
    }

    @Test
    @DisplayName("the patch is applied automatically, it is not a manual one")
    void patchIsNotManualTest() {
        // The sample this patch is modelled on is manual: true, and copying that flag along with the
        // structure is the easy mistake. 'manual' means the patch stays PENDING until an operator
        // presses Apply in the patches journal, which would leave every stand - including the ones
        // acceptance runs on - with unfilled phoneDigits and a silently non-working phone lookup.
        Map<String, Object> patch = PhoneDigitsHistoryExclusionTest.loadYaml(patchFile(PATCH_ID));
        assertFalse(
            Boolean.TRUE.equals(patch.get("manual")),
            "the patch must be applied automatically on deploy, see the decision in the plan"
        );
    }

    @Test
    @DisplayName("the group action is described by the two attributes it needs and nothing else")
    void groupActionIsFullyDescribedTest() {
        // a group action record with a missing half - a selection without an execution or the other
        // way round - is created by the patch without an error and simply never processes anything
        assertEquals(
            Set.of("values", "execution"),
            new HashSet<>(groupActionAttributes(patchFile(PATCH_ID)).keySet()),
            "the group action must describe what to select ('values') and what to do ('execution')"
        );
    }

    @Test
    @DisplayName("the recalculated types inherit the attribute from the type the patch selects")
    void childTypesInheritFromTheSelectedTypeTest() {
        // the patch selects records of opportunity, which has no table of its own: it reaches lead
        // and deal only because they are its children. Re-parenting either of them makes the patch
        // silently skip its records and leaves them unsearchable by phone.
        String parentRef = TYPE_REF;
        for (String typeId : CHILD_TYPES) {
            assertEquals(
                parentRef,
                PhoneDigitsHistoryExclusionTest.loadYaml(ComputedScriptRunner.typeFile(typeId))
                    .get("parentRef"),
                typeId + ".yml must stay a child of " + parentRef + ", the patch reaches it through the parent"
            );
        }
    }

    @Test
    @DisplayName("the recalculated type is the one that declares phoneDigits")
    void typeRefDeclaresPhoneDigitsTest() {
        // a typeRef pointing at a type without the attribute would make the patch a no-op: the group
        // action would run, save every record and still leave phoneDigits empty
        String typeId = TYPE_REF.substring(TYPE_REF.indexOf('@') + 1);
        assertTrue(
            PhoneDigitsHistoryExclusionTest.attributeIds(ComputedScriptRunner.typeFile(typeId))
                .contains("phoneDigits"),
            "type " + typeId + " must declare the phoneDigits attribute the patch recalculates"
        );
    }

    private static Map<?, ?> groupActionAttributes(Path patchFile) {
        Map<String, Object> patch = PhoneDigitsHistoryExclusionTest.loadYaml(patchFile);
        Object records = section(patch, "config").get("records");
        if (!(records instanceof List) || ((List<?>) records).size() != 1) {
            throw new IllegalArgumentException("Patch " + patchFile + " must create exactly one record");
        }
        Map<?, ?> record = (Map<?, ?>) ((List<?>) records).get(0);
        assertEquals(
            "emodel/group-action@",
            record.get("id"),
            "the patch must create a new group action, hence an id without a local id"
        );
        return section(record, "attributes");
    }

    private static Map<?, ?> section(Map<?, ?> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Section '" + key + "' is missing or is not an object");
        }
        return (Map<?, ?>) value;
    }

    private static Path patchFile(String patchId) {
        return Paths.get(PATCHES_DIR, patchId + ".yml").toAbsolutePath();
    }
}
