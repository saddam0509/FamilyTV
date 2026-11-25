package local.familytv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMatchTest {
    @Test fun exactMatchAfterNormalization() {
        assertTrue(tvNameMatches(titleKey("9-1-1 Lone Star"), "9-1-1: Lone Star"))
    }

    @Test fun rejectsShortAmbiguousFuzzyMatch() {
        // The bug: "3%" (normalises to "3") must NOT accept "3 Mulheres".
        assertFalse(tvNameMatches(titleKey("3%"), "3 Mulheres"))
    }

    @Test fun acceptsTheRealShortTitle() {
        // "3%" and "3" both normalise to "3" — the genuine show is still accepted.
        assertTrue(tvNameMatches(titleKey("3%"), "3%"))
    }

    @Test fun acceptsSuffixedName() {
        assertTrue(tvNameMatches(titleKey("The Office"), "The Office (US)"))
    }

    @Test fun rejectsDifferentLongerShow() {
        assertFalse(tvNameMatches(titleKey("Antormahal"), "Antoraal"))
    }

    @Test fun acceptsBanglaExact() {
        assertTrue(tvNameMatches(titleKey("Amriter Sandhane-The Banaras Chapter"), "Amriter Sandhane - The Banaras Chapter"))
    }
}
