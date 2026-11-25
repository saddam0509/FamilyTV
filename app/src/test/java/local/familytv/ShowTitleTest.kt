package local.familytv

import org.junit.Assert.assertEquals
import org.junit.Test

class ShowTitleTest {
    @Test fun splitsTitleAndYear() {
        assertEquals("Abar Proloy" to "2023", showTitleYear("Abar Proloy [2023]"))
    }

    @Test fun stripsNonYearBrackets() {
        assertEquals("Some Show" to null, showTitleYear("Some Show [1080p]"))
    }

    @Test fun noBracketsLeavesTitleAndNullYear() {
        assertEquals("2 Broke Girls" to null, showTitleYear("2 Broke Girls"))
    }

    @Test fun keepsHyphensAndNumbersInTitle() {
        assertEquals("9-1-1 Lone.Star" to "2020", showTitleYear("9-1-1 Lone.Star [2020]"))
    }
}
