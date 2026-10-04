package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RentalPlanTest {
    @Test fun rentalCostAppliesOnlyToInclusiveDateRangeAcrossMonths() {
        val plan = MonthlyRentalPlan(feeCents=40000, dailyCostCents=10000,
            startDate="2026-09-29", endDate="2026-10-02")
        assertFalse(plan.covers(LocalDate.of(2026, 9, 28)))
        assertTrue(plan.covers(LocalDate.of(2026, 9, 29)))
        assertTrue(plan.covers(LocalDate.of(2026, 10, 2)))
        assertFalse(plan.covers(LocalDate.of(2026, 10, 3)))
    }

    @Test fun legacyMonthPlanStillCoversItsMonth() {
        val plan = MonthlyRentalPlan(feeCents=300000, periodMonth="2026-09")
        assertTrue(plan.covers(LocalDate.of(2026, 9, 1)))
        assertFalse(plan.covers(LocalDate.of(2026, 10, 1)))
    }
}
