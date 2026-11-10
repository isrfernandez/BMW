package com.cockpitf30.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShiftAdvisorTest {
    private val a = ShiftAdvisor()

    @Test fun ecoSuggestsUpshiftAt2100() {
        assertEquals(Shift.UP, a.raw(2, 2_300, 40, ShiftAdvisor.ECO))
        assertNull(a.raw(2, 1_800, 35, ShiftAdvisor.ECO))
        assertNull(a.raw(6, 3_000, 140, ShiftAdvisor.ECO)) // ya en sexta
    }

    @Test fun downshiftWhenLugging() {
        assertEquals(Shift.DOWN, a.raw(5, 1_000, 53, ShiftAdvisor.ECO))
        assertNull(a.raw(1, 900, 10, ShiftAdvisor.ECO))
    }

    @Test fun sportShiftsLater() {
        assertNull(a.raw(3, 3_000, 95, ShiftAdvisor.SPORT))
        assertEquals(Shift.UP, a.raw(3, 4_200, 133, ShiftAdvisor.SPORT))
    }

    @Test fun parkedNoAdvice() {
        assertNull(a.raw(2, 2_500, 3, ShiftAdvisor.ECO))
    }

    @Test fun holdsBeforeShowing() {
        val s = ShiftAdvisor()
        assertNull(s.update(0, 2, 2_400, 40, ShiftAdvisor.ECO))
        assertNull(s.update(600, 2, 2_400, 40, ShiftAdvisor.ECO))
        assertEquals(Shift.UP, s.update(1_300, 2, 2_400, 40, ShiftAdvisor.ECO))
        assertNull(s.update(1_400, 3, 1_600, 42, ShiftAdvisor.ECO))
    }
}
