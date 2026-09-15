package ru.citeck.ecos.crm.phonedigits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * phoneDigits is recalculated on every mutation, so every save would otherwise add a
 * "phoneDigits changed" line to the event history widget of the record.
 *
 * <p>The exclusion is declared by the history-config aspect of the concrete types: deal has such an
 * aspect already, lead had none and got one here. Checked empirically on the stand before writing
 * the aspect: history is recorded by default, leads without the aspect do have node.updated events
 * (19 of them in citeck_history), so an omitted aspect would mean an unfiltered history.
 */
public class PhoneDigitsHistoryExclusionTest {

    private static final String PHONE_DIGITS = "phoneDigits";
    private static final String HISTORY_ASPECT = "emodel/aspect@history-config";
    /** Was already excluded before this change and must stay excluded. */
    private static final String BITRIX_SYNC_DATE = "crm-bitrix24:bitrixSyncDate";

    @Test
    @DisplayName("deal excludes phoneDigits from the history")
    void dealExcludesPhoneDigitsTest() {
        assertTrue(
            excludedAtts(ComputedScriptRunner.typeFile("deal")).contains(PHONE_DIGITS),
            "phoneDigits must be listed in excludedAtts of the history-config aspect of deal.yml"
        );
    }

    @Test
    @DisplayName("deal keeps the exclusion it had before")
    void dealKeepsPreviousExclusionTest() {
        assertEquals(
            List.of(BITRIX_SYNC_DATE, PHONE_DIGITS),
            excludedAtts(ComputedScriptRunner.typeFile("deal")),
            "the new exclusion must be added to the existing list, not replace it"
        );
    }

    @Test
    @DisplayName("lead excludes phoneDigits from the history")
    void leadExcludesPhoneDigitsTest() {
        assertTrue(
            excludedAtts(ComputedScriptRunner.typeFile("lead")).contains(PHONE_DIGITS),
            "phoneDigits must be listed in excludedAtts of the history-config aspect of lead.yml"
        );
    }

    @Test
    @DisplayName("the history stays enabled for both types - only one attribute is filtered out")
    void historyStaysEnabledTest() {
        for (String typeId : List.of("deal", "lead")) {
            Map<?, ?> config = historyConfig(ComputedScriptRunner.typeFile(typeId));
            assertEquals(
                Boolean.FALSE,
                config.get("disableHistory"),
                "history of " + typeId + " must stay enabled"
            );
        }
    }

    @Test
    @DisplayName("the excluded id is the id of the computed attribute itself")
    void excludedIdMatchesAttributeIdTest() {
        // the attribute is declared on the parent type, both children exclude it by the same id -
        // a typo here would be silent: history would simply keep recording the attribute
        assertTrue(
            attributeIds(ComputedScriptRunner.typeFile("opportunity")).contains(PHONE_DIGITS),
            "opportunity.yml must declare the phoneDigits attribute the exclusions refer to"
        );
        for (String typeId : List.of("deal", "lead")) {
            assertTrue(
                excludedAtts(ComputedScriptRunner.typeFile(typeId)).contains(PHONE_DIGITS),
                typeId + " must exclude exactly the id declared by opportunity.yml"
            );
        }
    }

    @Test
    @DisplayName("lead declares the aspect explicitly - it inherits none from opportunity")
    void leadDeclaresAspectItselfTest() {
        assertFalse(
            aspectRefs(ComputedScriptRunner.typeFile("opportunity")).contains(HISTORY_ASPECT),
            "opportunity declares no history-config aspect, so lead can't inherit the exclusion"
        );
        assertTrue(
            aspectRefs(ComputedScriptRunner.typeFile("lead")).contains(HISTORY_ASPECT),
            "lead must declare the history-config aspect itself"
        );
    }

    static List<String> excludedAtts(Path typeFile) {
        Object excluded = historyConfig(typeFile).get("excludedAtts");
        if (!(excluded instanceof List)) {
            throw new IllegalArgumentException(
                "history-config aspect of " + typeFile + " has no 'excludedAtts' list"
            );
        }
        List<String> result = new ArrayList<>();
        for (Object att : (List<?>) excluded) {
            result.add(String.valueOf(att));
        }
        return result;
    }

    static Map<?, ?> historyConfig(Path typeFile) {
        for (Object aspect : aspects(typeFile)) {
            Map<?, ?> aspectMap = (Map<?, ?>) aspect;
            if (!HISTORY_ASPECT.equals(String.valueOf(aspectMap.get("ref")))) {
                continue;
            }
            Object config = aspectMap.get("config");
            if (!(config instanceof Map)) {
                throw new IllegalArgumentException(
                    "history-config aspect of " + typeFile + " has no 'config' section"
                );
            }
            return (Map<?, ?>) config;
        }
        throw new IllegalArgumentException("Type " + typeFile + " has no history-config aspect");
    }

    static List<String> aspectRefs(Path typeFile) {
        List<String> refs = new ArrayList<>();
        for (Object aspect : aspects(typeFile)) {
            refs.add(String.valueOf(((Map<?, ?>) aspect).get("ref")));
        }
        return refs;
    }

    static List<String> attributeIds(Path typeFile) {
        Object model = loadYaml(typeFile).get("model");
        Object attributes = model instanceof Map ? ((Map<?, ?>) model).get("attributes") : null;
        if (!(attributes instanceof List)) {
            throw new IllegalArgumentException("Type " + typeFile + " has no 'model.attributes' list");
        }
        List<String> ids = new ArrayList<>();
        for (Object att : (List<?>) attributes) {
            if (att instanceof Map) {
                ids.add(String.valueOf(((Map<?, ?>) att).get("id")));
            }
        }
        return ids;
    }

    /** A type without an 'aspects' section simply declares no aspects - opportunity is one. */
    private static List<?> aspects(Path typeFile) {
        Object aspects = loadYaml(typeFile).get("aspects");
        if (aspects == null) {
            return List.of();
        }
        if (!(aspects instanceof List)) {
            throw new IllegalArgumentException("Type " + typeFile + " has an 'aspects' section which is not a list");
        }
        for (Object aspect : (List<?>) aspects) {
            if (!(aspect instanceof Map)) {
                throw new IllegalArgumentException(
                    "Type " + typeFile + " has an aspect which is not an object: " + aspect
                );
            }
        }
        return (List<?>) aspects;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadYaml(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Type file is not found: " + file);
        }
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) {
                throw new IllegalArgumentException("Type file is not a yaml object: " + file);
            }
            return (Map<String, Object>) loaded;
        } catch (IOException e) {
            throw new IllegalArgumentException("Type file can't be read: " + file, e);
        }
    }
}
