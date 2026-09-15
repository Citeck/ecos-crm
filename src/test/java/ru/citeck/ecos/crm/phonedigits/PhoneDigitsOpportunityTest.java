package ru.citeck.ecos.crm.phonedigits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Specification of the phoneDigits normalization on the opportunity type (lead and deal inherit it).
 *
 * <p>The script is taken from opportunity.yml and executed on a real GraalJS engine, so the yaml
 * stays the single source of truth - see {@link ComputedScriptRunner}. The example table below is
 * copied from ECOSCRM-106; the same table is replayed against ecos-counterparty, because a
 * divergence between the two scripts makes the lookup keys differ and the search silently miss.
 */
public class PhoneDigitsOpportunityTest {

    private static final Path OPPORTUNITY = ComputedScriptRunner.typeFile("opportunity");
    private static final String CONTACT_PHONES = "contacts[].contactPhone";

    /** All 14 rows of the example table of ECOSCRM-106, in the order they are listed there. */
    static Stream<Arguments> exampleTable() {
        return Stream.of(
            // 11 digits, leading 8 dropped
            Arguments.of("84952233522", List.of("4952233522")),
            // 11 digits, leading 7 dropped
            Arguments.of("+79161177716", List.of("9161177716")),
            // separators removed
            Arguments.of("+7 (917) 582-92-99", List.of("9175829299")),
            // trailing space removed
            Arguments.of("+7(981)7257011 ", List.of("9817257011")),
            // split by comma, the "422" part is dropped by length
            Arguments.of("8-495-556-45-95, доб. 422", List.of("4955564595")),
            // split by comma, both parts produce a key
            Arguments.of("+7 903 234-41-35, +7 495 123-45-67", List.of("9032344135", "4951234567")),
            // 12 digits -> last 10
            Arguments.of("+375 (44) 523 14 73", List.of("5445231473")),
            // from the contacts of a counterparty
            Arguments.of("+7-917-946-7975", List.of("9179467975")),
            // 6 digits, shorter than 10
            Arguments.of("630-20-10", List.of()),
            // fewer than 10 digits or none at all
            Arguments.of("1", List.of()),
            Arguments.of("8", List.of()),
            Arguments.of("US", List.of()),
            // no digits; a real value of the deprecated deal.phone field
            Arguments.of("[your-phone]", List.of()),
            // an ordinary key, ambiguity is resolved by the matching step of COREDEV-510
            Arguments.of("+79999999999", List.of("9999999999")),
            // empty value
            Arguments.of("", List.of())
        );
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @MethodSource("exampleTable")
    @DisplayName("example table of ECOSCRM-106")
    void exampleTableRowProducesTheSpecifiedKeys(String phone, List<String> expected) {
        assertEquals(expected, phoneDigits(phone));
    }

    @Test
    @DisplayName("example table: the same number twice gives one key")
    void duplicatedNumberOfTheTableGivesOneKey() {
        assertEquals(
            List.of("9510934545"),
            phoneDigits("+79510934545", "+79510934545")
        );
    }

    // --- order of the steps: splitting happens before the digits are extracted ---

    @Test
    void extensionAfterCommaIsNotGluedToTheMainNumber() {

        List<String> keys = phoneDigits("8-495-556-45-95, доб. 422");

        assertEquals(List.of("4955564595"), keys);
        // the key that appears if the digits are extracted before the split
        assertFalse(keys.contains("5564595422"), "extension must not be glued to the main number");
    }

    @Test
    void twoNumbersInOneFieldGiveTwoKeys() {
        assertEquals(
            List.of("9032344135", "4951234567"),
            phoneDigits("+7 903 234-41-35, +7 495 123-45-67")
        );
    }

    @Test
    void everySpecifiedSeparatorSplitsTheValue() {
        assertEquals(List.of("4955564595"), phoneDigits("8-495-556-45-95; 422"));
        assertEquals(List.of("4955564595"), phoneDigits("8-495-556-45-95 # 422"));
        assertEquals(List.of("4955564595"), phoneDigits("8-495-556-45-95 доб 422"));
        assertEquals(List.of("4955564595"), phoneDigits("8-495-556-45-95 вн. 422"));
        assertEquals(List.of("4955564595"), phoneDigits("8-495-556-45-95 ext 422"));
    }

    @Test
    void separatorsAreCaseInsensitive() {
        assertEquals(List.of("4955564595"), phoneDigits("8-495-556-45-95 ДОБ. 422"));
        assertEquals(List.of("4955564595"), phoneDigits("8-495-556-45-95 EXT 422"));
        assertEquals(List.of("4955564595"), phoneDigits("8-495-556-45-95 Вн. 422"));
    }

    @Test
    void bothPartsAroundASeparatorAreProcessedIndependently() {
        // the extension is long enough to become a key on its own - by design there is no
        // guessing whether a part is an extension or a second number
        assertEquals(
            List.of("4955564595", "9161177716"),
            phoneDigits("8-495-556-45-95 доб. +79161177716")
        );
    }

    // --- edge cases ---

    @Test
    void recordWithoutAnyUsableNumberGetsAnEmptyArrayAndNotNull() {

        List<String> keys = phoneDigits("630-20-10", "US", "");

        assertNotNull(keys, "phoneDigits must never be null");
        assertEquals(List.of(), keys);
    }

    @Test
    void recordWithoutContactsAtAllGetsAnEmptyArray() {
        assertEquals(List.of(), phoneDigitsOf(List.of()));
        assertEquals(List.of(), phoneDigitsOf(null));
    }

    @Test
    void sameNumberInTwoContactsGivesOneKey() {
        assertEquals(List.of("9161177716"), phoneDigits("+79161177716", "8 916 117-77-16"));
    }

    @Test
    void twelveDigitForeignNumberIsCutToTheLastTenDigits() {
        assertEquals(List.of("5445231473"), phoneDigits("+375445231473"));
        assertEquals(List.of("1234567890"), phoneDigits("+99 (123) 456-78-90"));
    }

    @Test
    void elevenDigitNumberNotStartingWithSevenOrEightAlsoLosesItsFirstDigit() {
        // known and accepted side effect of "take the last 10 digits", see ECOSCRM-106
        assertEquals(List.of("8867276234"), phoneDigits("98867276234"));
    }

    @Test
    void exactlyTenDigitsAreKeptAsIs() {
        assertEquals(List.of("4952233522"), phoneDigits("4952233522"));
    }

    @Test
    void nineDigitsAreNotAKey() {
        assertEquals(List.of(), phoneDigits("495223352"));
    }

    @Test
    void contactWithoutAPhoneIsSkippedAndDoesNotBreakTheRest() {

        List<String> phones = new ArrayList<>();
        phones.add(null);
        phones.add("+79161177716");
        phones.add(null);

        assertEquals(List.of("9161177716"), phoneDigitsOf(phones));
    }

    @Test
    void differentNumbersKeepTheOrderOfTheContacts() {
        assertEquals(
            List.of("9161177716", "4952233522", "9175829299"),
            phoneDigits("+79161177716", "84952233522", "+7 (917) 582-92-99")
        );
    }

    @Test
    void singleValueReturnedInsteadOfAListIsStillProcessed() {
        // defensive: the script must not iterate over the characters of a string
        assertEquals(List.of("9161177716"), phoneDigitsOf("+79161177716"));
    }

    // --- helpers ---

    private static List<String> phoneDigits(String... contactPhones) {
        return phoneDigitsOf(Arrays.asList(contactPhones));
    }

    private static List<String> phoneDigitsOf(Object contactPhones) {
        Map<String, Object> atts = new HashMap<>();
        atts.put(CONTACT_PHONES, contactPhones);
        return ComputedScriptRunner
            .ofTypeAttribute(OPPORTUNITY, "phoneDigits")
            .executeToStringList(atts);
    }
}
