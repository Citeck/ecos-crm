package ru.citeck.ecos.crm.phonedigits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
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

    private static final String TYPES_DIR = "src/main/resources/app/artifacts/model/type";

    private static final String PATCH_ID = "fill-opportunity-phone-digits";
    /** The type the attribute is declared on; lead and deal are picked up as its children. */
    private static final String TYPE_REF = "emodel/type@opportunity";

    /** Local id of TYPE_REF - the type file the guard below is checked against. */
    private static final String TYPE_ID = "opportunity";

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
    @DisplayName("the selection excludes records the recalculation would damage")
    void selectionSkipsRecordsWithAnEmptyOnEmptyAttributeTest() {
        // "update-calculated-atts" recomputes every computed attribute of the type, not only
        // phoneDigits. ON_CREATE atts and counters are skipped for an existing record, but an
        // ON_EMPTY one is evaluated whenever its current value is empty - and opportunity declares
        // dateReceived as ON_EMPTY `return new Date()`. Without a guard the patch would overwrite
        // the creation date of every such record with its own run time, irreversibly and
        // automatically on every stand it reaches. The selector must therefore leave those records
        // out; they keep an empty phoneDigits, which is recoverable, instead of losing a date,
        // which is not.
        //
        // The scan covers the children too: opportunity has no table of its own, the group action
        // reaches lead and deal by recursing into the children of the selected type and
        // recalculates each of them with its OWN model. An ON_EMPTY attribute declared on deal.yml
        // or lead.yml is therefore just as exposed as one on the parent, and looking only at
        // opportunity.yml would leave two of the three recalculated types unguarded.
        List<String> onEmptyAtts = guardedOnEmptyAttributeIds();
        assertFalse(
            onEmptyAtts.isEmpty(),
            "opportunity or one of its children is expected to declare at least one ON_EMPTY "
                + "attribute (dateReceived); if that is no longer true, the guard predicate below "
                + "can be dropped deliberately"
        );

        Object predicate = section(
            section(groupActionAttributes(patchFile(PATCH_ID)), "values"),
            "config"
        ).get("predicate");

        assertEquals(
            expectedGuard(onEmptyAtts),
            predicate,
            "every ON_EMPTY attribute of " + TYPE_ID + " and of its recalculated children "
                + CHILD_TYPES + " must be guarded by a not-empty condition in the patch selector, "
                + "otherwise the recalculation rewrites it with a fresh value. Found " + onEmptyAtts
                + " - if an attribute here is declared on a child only, decide deliberately whether "
                + "the guard belongs in the shared selector: the predicate is applied to every "
                + "recalculated type, including the ones that do not declare that attribute"
        );
    }

    @Test
    @DisplayName("the guarded attribute really is the ON_EMPTY one the guard names")
    void guardedAttributeIsDeclaredOnEmptyTest() {
        // the guard is written against dateReceived by name; renaming the attribute or changing its
        // storingType without touching the patch would leave a predicate that filters on nothing
        assertTrue(
            onEmptyAttributeIds(ComputedScriptRunner.typeFile(TYPE_ID)).contains("dateReceived"),
            "dateReceived must stay an ON_EMPTY computed attribute of " + TYPE_ID
                + " - the patch selector excludes records where it is empty"
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
    @DisplayName("CHILD_TYPES lists every type the patch recalculates through the parent")
    void childTypesListIsCompleteTest() {
        // the ON_EMPTY guard above is only as complete as this list: the group action recurses into
        // every child of the selected type, so a third child added to the project without being
        // added here would be recalculated with a guard that never looked at its model.
        Set<String> declaredChildren = new HashSet<>();
        try (Stream<Path> typeFiles = Files.list(Paths.get(TYPES_DIR))) {
            typeFiles.filter(file -> file.getFileName().toString().endsWith(".yml"))
                .forEach(file -> {
                    Object parentRef = PhoneDigitsHistoryExclusionTest.loadYaml(file.toAbsolutePath())
                        .get("parentRef");
                    if (TYPE_REF.equals(parentRef)) {
                        String fileName = file.getFileName().toString();
                        declaredChildren.add(fileName.substring(0, fileName.length() - ".yml".length()));
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException("Can't list type files in " + TYPES_DIR, e);
        }

        assertEquals(
            declaredChildren,
            new HashSet<>(CHILD_TYPES),
            "CHILD_TYPES must list exactly the types whose parentRef is " + TYPE_REF
                + " - the patch recalculates all of them and the ON_EMPTY guard is derived from them"
        );
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

    /** {@code not(empty(att))} for one attribute, an {@code and} of those for several. */
    private static Map<String, Object> expectedGuard(List<String> atts) {
        List<Map<String, Object>> conditions = new ArrayList<>();
        for (String att : atts) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("t", "empty");
            empty.put("att", att);
            Map<String, Object> notEmpty = new LinkedHashMap<>();
            notEmpty.put("t", "not");
            notEmpty.put("val", empty);
            conditions.add(notEmpty);
        }
        if (conditions.size() == 1) {
            return conditions.get(0);
        }
        Map<String, Object> and = new LinkedHashMap<>();
        and.put("t", "and");
        and.put("val", conditions);
        return and;
    }

    /**
     * Ids of the ON_EMPTY attributes of every type the patch recalculates - the selected type and
     * the children it reaches through it - in declaration order, without duplicates.
     */
    private static List<String> guardedOnEmptyAttributeIds() {
        List<String> typeIds = new ArrayList<>();
        typeIds.add(TYPE_ID);
        typeIds.addAll(CHILD_TYPES);

        List<String> result = new ArrayList<>();
        for (String typeId : typeIds) {
            for (String att : onEmptyAttributeIds(ComputedScriptRunner.typeFile(typeId))) {
                if (!result.contains(att)) {
                    result.add(att);
                }
            }
        }
        return result;
    }

    /** Ids of the attributes the type declares as {@code computed.storingType: ON_EMPTY}. */
    private static List<String> onEmptyAttributeIds(Path typeFile) {
        List<String> result = new ArrayList<>();
        Map<String, Object> type = PhoneDigitsHistoryExclusionTest.loadYaml(typeFile);
        Object model = type.get("model");
        if (!(model instanceof Map)) {
            return result;
        }
        Object attributes = ((Map<?, ?>) model).get("attributes");
        if (!(attributes instanceof List)) {
            return result;
        }
        for (Object attribute : (List<?>) attributes) {
            if (!(attribute instanceof Map)) {
                continue;
            }
            Object computed = ((Map<?, ?>) attribute).get("computed");
            if (!(computed instanceof Map)) {
                continue;
            }
            if ("ON_EMPTY".equals(((Map<?, ?>) computed).get("storingType"))) {
                result.add(String.valueOf(((Map<?, ?>) attribute).get("id")));
            }
        }
        return result;
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
