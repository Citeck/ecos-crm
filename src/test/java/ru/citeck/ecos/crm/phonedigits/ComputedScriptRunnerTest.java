package ru.citeck.ecos.crm.phonedigits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

/**
 * Checks the test harness itself: yaml -> script -> GraalJS -> java result.
 * Business logic of phoneDigits is covered separately.
 */
public class ComputedScriptRunnerTest {

    private static final Path HARNESS_TYPE =
        Paths.get("src/test/resources/computed-script-runner/harness-type.yml").toAbsolutePath();

    @Test
    void scriptFromYamlReturningTwoStringsReachesTheRunner() {

        List<String> result = ComputedScriptRunner
            .ofTypeAttribute(HARNESS_TYPE, "harnessTwoStrings")
            .executeToStringList(Map.of());

        assertEquals(List.of("first", "second"), result);
    }

    @Test
    void loadedMultipleAttributeBehavesAsAnArray() {

        // the whole point of running tests on a real GraalJS: value.load gives a java list wrapped
        // as a foreign object. On GraalJS 24.2.2 js.foreign-object-prototype is on by default, so
        // such a list IS an array for the script: Array.isArray is true and map/filter/join work.
        List<String> result = ComputedScriptRunner
            .ofTypeAttribute(HARNESS_TYPE, "harnessLoadedValueKind")
            .executeToStringList(Map.of("contacts[].contactPhone", List.of("+7 903 234-41-35")));

        assertEquals(List.of("true", "function", "1", "+7 903 234-41-35"), result);
    }

    @Test
    void loadedJsonObjectIsReadableByKeyButNotEnumerableByObjectKeys() {

        // members of a loaded java map are readable by key, but Object.keys returns nothing:
        // a script must address known fields explicitly instead of iterating over the keys
        Map<String, Object> contact = new HashMap<>();
        contact.put("contactPhone", "+7 903 234-41-35");

        List<String> result = ComputedScriptRunner
            .ofTypeAttribute(HARNESS_TYPE, "harnessLoadedObjectKind")
            .executeToStringList(Map.of("contacts[0]", contact));

        assertEquals(List.of("+7 903 234-41-35", "keys:0"), result);
    }

    @Test
    void scriptWithoutReturnKeywordIsEvaluatedAsIs() {

        List<String> result = ComputedScriptRunner
            .ofTypeAttribute(HARNESS_TYPE, "harnessWithoutReturnKeyword")
            .executeToStringList(Map.of());

        assertEquals(List.of("first", "second"), result);
    }

    @Test
    void returnWithoutSpaceIsNotWrappedAndFails() {

        // ScriptExecutorImpl wraps the script into (function(){...})() only when it contains
        // "return " with a space - "return['a']" is left unwrapped and is a syntax error
        assertThrows(
            PolyglotException.class,
            () -> ComputedScriptRunner.ofScript("return['a', 'b'];").execute(Map.of())
        );
    }

    @Test
    void nullResultIsDistinguishableFromEmptyArray() {

        assertEquals(null, ComputedScriptRunner.ofScript("return null;").executeToStringList(Map.of()));
        assertEquals(List.of(), ComputedScriptRunner.ofScript("return [];").executeToStringList(Map.of()));
    }

    @Test
    void unknownAttributeFailsWithExplanation() {

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> ComputedScriptRunner.ofTypeAttribute(HARNESS_TYPE, "noSuchAttribute")
        );

        String message = error.getMessage();
        assertTrue(message.contains("noSuchAttribute"), message);
        assertTrue(message.contains("Known attributes"), message);
        assertTrue(message.contains("harnessTwoStrings"), message);
    }

    @Test
    void attributeWithoutComputedSectionFailsWithExplanation() {

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> ComputedScriptRunner.ofTypeAttribute(HARNESS_TYPE, "harnessWithoutComputed")
        );

        assertTrue(error.getMessage().contains("has no 'computed' section"), error.getMessage());
    }

    @Test
    void missingTypeFileFailsWithExplanation() {

        Path missing = Paths.get("src/test/resources/computed-script-runner/no-such-type.yml");

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> ComputedScriptRunner.ofTypeAttribute(missing, "anyAtt")
        );

        assertTrue(error.getMessage().contains("Type file is not found"), error.getMessage());
    }

    @Test
    void attributeNotDeclaredByFixtureFailsWithExplanation() {

        Map<String, Object> atts = new HashMap<>();
        atts.put("someOtherAtt", null);

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> ComputedScriptRunner
                .ofTypeAttribute(HARNESS_TYPE, "harnessLoadedValueKind")
                .execute(atts)
        );

        String message = error.getMessage();
        assertTrue(message.contains("contacts[].contactPhone"), message);
        assertTrue(message.contains("someOtherAtt"), message);
    }

    @Test
    void typeFileHelperPointsToTheArtifactsOfThisProject() {

        Path opportunity = ComputedScriptRunner.typeFile("opportunity");

        assertTrue(opportunity.toString().endsWith(
            Paths.get("src/main/resources/app/artifacts/model/type/opportunity.yml").toString()
        ), opportunity.toString());
        assertTrue(java.nio.file.Files.isRegularFile(opportunity), opportunity.toString());
    }

    @Test
    void loadAcceptsListOfAttributes() {

        Map<String, Object> atts = new HashMap<>();
        atts.put("first", "1");
        atts.put("second", Arrays.asList("2", "3"));

        Object result = ComputedScriptRunner
            .ofScript("var loaded = value.load(['first', 'second']); return [loaded.first, loaded.second[1]];")
            .execute(atts);

        assertEquals(List.of("1", "3"), result);
    }
}
