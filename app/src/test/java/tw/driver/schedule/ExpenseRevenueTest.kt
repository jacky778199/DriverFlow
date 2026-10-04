package tw.driver.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpenseRevenueTest {
    private fun ride(category: String, received: String = "", tip: String = "",
        subsidy: String = "", daycare: String = "") = RideOrder(
        pickupTime = "09:00", pickup = "起點", destination = "終點", category = category,
        received = received, tip = tip, subsidyDue = subsidy, daycareMonthly = daycare
    )

    @Test fun addsAllRideMonthlyAmountsToRevenue() {
        assertEquals(68000L, rideOperatingRevenue(ride("補助", received = "100", tip = "20", subsidy = "580")))
        assertEquals(45000L, rideOperatingRevenue(ride("日照", daycare = "450")))
        assertEquals(30000L, rideOperatingRevenue(ride("自費", received = "300")))
    }

    @Test fun legacyDaycareAmountIsNotCountedTwice() {
        assertEquals(45000L, rideOperatingRevenue(ride("日照", received = "450", daycare = "450")))
    }
}
