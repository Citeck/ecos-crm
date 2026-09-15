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
    /** Deprecated scalar phone of deal - the second source of the prologue. */
    private static final String DEPRECATED_PHONE = "phone";

    /** All 15 rows of the example table of ECOSCRM-106, in the order they are listed there. */
    static Stream<Arguments> exampleTable() {
        return Stream.of(
            // 11 digits, leading 8 dropped
            Arguments.of("84952233522", List.of("74952233522")),
            // 11 digits, leading 7 dropped
            Arguments.of("+79161177716", List.of("79161177716")),
            // separators removed
            Arguments.of("+7 (917) 582-92-99", List.of("79175829299")),
            // trailing space removed
            Arguments.of("+7(981)7257011 ", List.of("79817257011")),
            // split by comma, the "422" part is dropped by length
            Arguments.of("8-495-556-45-95, доб. 422", List.of("74955564595")),
            // split by comma, both parts produce a key
            Arguments.of("+7 903 234-41-35, +7 495 123-45-67", List.of("79032344135", "74951234567")),
            // 12 digits -> last 10
            Arguments.of("+375 (44) 523 14 73", List.of("375445231473")),
            // from the contacts of a counterparty
            Arguments.of("+7-917-946-7975", List.of("79179467975")),
            // 7 digits, shorter than 10
            Arguments.of("630-20-10", List.of()),
            // fewer than 10 digits or none at all
            Arguments.of("1", List.of()),
            Arguments.of("8", List.of()),
            Arguments.of("US", List.of()),
            // no digits; a real value of the deprecated deal.phone field
            Arguments.of("[your-phone]", List.of()),
            // an ordinary key, ambiguity is resolved by the matching step of COREDEV-510
            Arguments.of("+79999999999", List.of("79999999999")),
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
            List.of("79510934545"),
            phoneDigits("+79510934545", "+79510934545")
        );
    }

    // --- order of the steps: splitting happens before the digits are extracted ---

    @Test
    void extensionAfterCommaIsNotGluedToTheMainNumber() {

        List<String> keys = phoneDigits("8-495-556-45-95, доб. 422");

        assertEquals(List.of("74955564595"), keys);
        // the key that appears if the digits are extracted before the split
        assertFalse(keys.contains("84955564595422"), "extension must not be glued to the main number");
    }

    @Test
    void twoNumbersInOneFieldGiveTwoKeys() {
        assertEquals(
            List.of("79032344135", "74951234567"),
            phoneDigits("+7 903 234-41-35, +7 495 123-45-67")
        );
    }

    @Test
    void everySpecifiedSeparatorSplitsTheValue() {
        assertEquals(List.of("74955564595"), phoneDigits("8-495-556-45-95; 422"));
        assertEquals(List.of("74955564595"), phoneDigits("8-495-556-45-95 # 422"));
        assertEquals(List.of("74955564595"), phoneDigits("8-495-556-45-95 доб 422"));
        assertEquals(List.of("74955564595"), phoneDigits("8-495-556-45-95 вн. 422"));
        assertEquals(List.of("74955564595"), phoneDigits("8-495-556-45-95 ext 422"));
        // the dot after "вн" is optional, the way it already is after "доб" and "ext": a value
        // written without it used to produce the phantom key 5564595422 and lose the real number
        assertEquals(List.of("74955564595"), phoneDigits("8-495-556-45-95 вн 422"));
        assertEquals(List.of("74955564595"), phoneDigits("8-495-556-45-95 (вн 422)"));
        assertEquals(List.of("74955564595"), phoneDigits("8-495-556-45-95 внутр. 422"));
        assertEquals(List.of("74955564595"), phoneDigits("8-495-556-45-95 доп. 422"));
    }

    @Test
    void slashAndNewLineSeparateTwoNumbersInsteadOfGluingThem() {
        // both are ordinary ways to put a second number into one field; without them the digits of
        // the two numbers concatenate and "take the last 10" invents a key that belongs to nobody
        assertEquals(
            List.of("79161177716", "74951234567"),
            phoneDigits("+7 916 117-77-16 / +7 495 123-45-67")
        );
        assertEquals(
            List.of("79161177716", "74951234567"),
            phoneDigits("+7 916 117-77-16\n+7 495 123-45-67")
        );
        assertEquals(
            List.of("79161177716", "74951234567"),
            phoneDigits("+7 916 117-77-16\r\n+7 495 123-45-67")
        );
        // a short remainder after the slash is dropped by length and does not corrupt the key
        assertEquals(List.of("74955564595"), phoneDigits("+7 495 556-45-95 / 12"));
    }

    @Test
    void separatorsAreCaseInsensitive() {
        assertEquals(List.of("74955564595"), phoneDigits("8-495-556-45-95 ДОБ. 422"));
        assertEquals(List.of("74955564595"), phoneDigits("8-495-556-45-95 EXT 422"));
        assertEquals(List.of("74955564595"), phoneDigits("8-495-556-45-95 Вн. 422"));
    }

    @Test
    void bothPartsAroundASeparatorAreProcessedIndependently() {
        // the extension is long enough to become a key on its own - by design there is no
        // guessing whether a part is an extension or a second number
        assertEquals(
            List.of("74955564595", "79161177716"),
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
        assertEquals(List.of("79161177716"), phoneDigits("+79161177716", "8 916 117-77-16"));
    }

    @Test
    void cisNumberKeepsItsCountryCode() {
        assertEquals(List.of("375445231473"), phoneDigits("+375445231473"));
        assertEquals(List.of("998941852583"), phoneDigits("+998 94 185 25 83"));
        assertEquals(List.of("991234567890"), phoneDigits("+99 (123) 456-78-90"));
    }

    @Test
    void cisAndRussianNumbersDoNotCollide() {
        // the whole reason the key moved to E.164: "take the last 10 digits" gave a Tashkent
        // landline and a Saint Petersburg one the very same key, 8123456789
        assertEquals(List.of("998123456789"), phoneDigits("+998 12 345 67 89"));
        assertEquals(List.of("78123456789"), phoneDigits("+7 812 345-67-89"));
    }

    @Test
    void elevenDigitNumberWithOtherLeadingDigitIsKeptWhole() {
        // only a leading 8 is unfolded into 7. Deciding "this one is Russian" by length would
        // corrupt +38050215956 - an eleven digit Ukrainian number that really is in production
        assertEquals(List.of("98867276234"), phoneDigits("98867276234"));
        assertEquals(List.of("38050215956"), phoneDigits("+38050215956"));
    }

    @Test
    void tenDigitNumberGetsRussianCountryCode() {
        assertEquals(List.of("74952233522"), phoneDigits("4952233522"));
    }

    @Test
    void elevenDigitNumberWithLeadingEightBecomesSeven() {
        assertEquals(List.of("74952233522"), phoneDigits("84952233522"));
    }

    @Test
    void elevenDigitNumberWithLeadingSevenIsKeptAsIs() {
        assertEquals(List.of("79161177716"), phoneDigits("+79161177716"));
    }

    @Test
    void numberLongerThanFifteenDigitsGivesNoKey() {
        // the E.164 maximum; the value below is real production data
        assertEquals(List.of(), phoneDigits("+3752978798789798"));
    }

    @Test
    void twoNumbersGluedBySpaceGiveNoKey() {
        // 22 digits, past the E.164 maximum. Before the move to E.164 this returned 9161177716,
        // so the last number was found and every earlier one silently lost
        assertEquals(List.of(), phoneDigits("+7 495 556-45-95 и 8 916 117-77-16"));
        // 18 digits; this one used to produce 5956302010, a key belonging to no subscriber at all
        assertEquals(List.of(), phoneDigits("+7 495 556-45-95 и 630-20-10"));
    }

    @Test
    void sameNumberInTwoFormatsGivesOneKey() {
        // both spellings of this number are in the production data
        assertEquals(List.of("998881754747"), phoneDigits("+998881754747", "+998 88 175 47 47"));
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

        assertEquals(List.of("79161177716"), phoneDigitsOf(phones));
    }

    @Test
    void differentNumbersKeepTheOrderOfTheContacts() {
        assertEquals(
            List.of("79161177716", "74952233522", "79175829299"),
            phoneDigits("+79161177716", "84952233522", "+7 (917) 582-92-99")
        );
    }

    @Test
    void singleValueReturnedInsteadOfAListIsStillProcessed() {
        // defensive: the script must not iterate over the characters of a string
        assertEquals(List.of("79161177716"), phoneDigitsOf("+79161177716"));
    }

    @Test
    void singleNumericValueReturnedInsteadOfAListIsStillProcessed() {
        // contacts is free-form JSON, so an integration may write contactPhone as a number;
        // a scalar number has no length, so without the guard the loop would silently do nothing
        assertEquals(List.of("79161177716"), phoneDigitsOf(79161177716L));
    }

    @Test
    @DisplayName("the deprecated deal phone is not a source")
    void deprecatedDealPhoneIsNotASourceTest() {
        // `phone` is marked #Deprecated in deal.yml. It was a source for a while (commit ca14fd6),
        // added after deals with an empty `contacts` turned up on the stand - stand-only junk, as
        // it turned out. On production all 1213 deals that fill `phone` repeat the very same string
        // in contacts[].contactPhone (two samples, 45 records, no divergence), so reading it adds
        // no key there while tying phoneDigits to a field that is meant to disappear.
        assertEquals(List.of(), phoneDigitsOf("+7 (495) 123-45-67", List.of()));
    }

    @Test
    @DisplayName("the production shape - phone mirrored into contacts - gives exactly one key")
    void phoneMirroredIntoContactsGivesOneKeyTest() {
        assertEquals(
            List.of("74951234567"),
            phoneDigitsOf("+7 (495) 123-45-67", List.of("+7 495 123-45-67"))
        );
    }

    @Test
    @DisplayName("a deprecated phone holding another number adds nothing")
    void deprecatedPhoneWithAnotherNumberAddsNoKeyTest() {
        // it used to contribute a second key here, which is what made a journal row show a number
        // whose origin was nowhere on the row
        assertEquals(
            List.of("79161177716"),
            phoneDigitsOf("+7 (495) 123-45-67", List.of("+7 916 117-77-16"))
        );
    }

    @Test
    @DisplayName("an unusable deprecated phone does not break the contacts source either")
    void unusableDeprecatedPhoneIsSkippedTest() {
        assertEquals(
            List.of("79161177716"),
            phoneDigitsOf("630-20-10", List.of("+7 916 117-77-16"))
        );
    }

    @Test
    @DisplayName("a lead, which declares no deprecated phone, is unaffected by the second source")
    void missingDeprecatedPhoneIsSkippedTest() {
        assertEquals(List.of("79161177716"), phoneDigitsOf(null, List.of("+7 916 117-77-16")));
    }

    // --- helpers ---

    private static List<String> phoneDigits(String... contactPhones) {
        return phoneDigitsOf(Arrays.asList(contactPhones));
    }

    private static List<String> phoneDigitsOf(Object contactPhones) {
        return phoneDigitsOf(null, contactPhones);
    }

    /**
     * The fixture still declares the deprecated {@code phone} so the tests above can show that the
     * script ignores it whatever it holds. lead does not declare the attribute at all, which is why
     * null is the ordinary case here.
     */
    private static List<String> phoneDigitsOf(Object deprecatedPhone, Object contactPhones) {
        Map<String, Object> atts = new HashMap<>();
        atts.put(DEPRECATED_PHONE, deprecatedPhone);
        atts.put(CONTACT_PHONES, contactPhones);
        return ComputedScriptRunner
            .ofTypeAttribute(OPPORTUNITY, "phoneDigits")
            .executeToStringList(atts);
    }
}
