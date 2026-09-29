package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test

class AddressConfirmationTest {
    @Test fun validAddressesNoLongerNeedConfirmationSwitches() {
        assertTrue(addressesReady("甲地", "乙院", false, false))
        assertTrue(bookingValidation("09/25", "10:00", "甲地", "乙院", false, false).isEmpty())
        assertFalse(addressesReady("", "乙院", true, true))
        assertFalse(addressesReady("上車地點待確認", "乙院", true, true))
        assertTrue(addressesReady("甲地", "乙院", true, true))
    }
    @Test fun displayKeepsUsefulAddressParentheses() {
        assertEquals("中興醫院", addressForDisplay("中興醫院（院名/定位待確認）"))
        assertEquals("臺安醫院（急診入口）", addressForDisplay("臺安醫院（急診入口）"))
    }
    @Test fun copyIncludesScheduleNameAndBothAddresses() {
        val text = RideOrder(date="2026/09/21", pickupTime="10:00–10:30", customer="測試乘客", contact="測試聯絡人",
            pickup="甲地", destination="乙院", fare="自付額 54", notes="按門鈴", contactPhone="聯絡假電話", passengerPhone="乘客假電話").copyText()
        listOf("10:00–10:30", "測試乘客", "測試聯絡人", "聯絡假電話", "乘客假電話", "上車：甲地", "下車：乙院", "自付額 54", "按門鈴").forEach { assertTrue(text.contains(it)) }
        assertFalse(text.contains("輪椅"))
    }
    @Test fun copyRetainsEntireLongAddress() {
        val longAddress = "測試醫院正式名稱及完整地址".repeat(20)
        val text = RideOrder(pickupTime="10:00", pickup=longAddress, destination="乙院").copyText()
        assertTrue(text.contains(longAddress))
    }
}
