package tw.driver.schedule

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiBookingParserTest {
    private fun fixture() = JSONObject("""{
      "date":"09/21", "pickupTime":"10:00–10:30", "customer":"爸爸", "contact":"匿名聯絡人",
      "passengerPhone":"乘客假電話", "contactPhone":"聯絡人假電話", "pickup":"新北市永和區民生路21號",
      "destination":"臺安醫院", "fare":"自付額 54", "returnTime":"13:00", "notes":"11之3樓；跳+300意思待確認",
      "calendarTime":"10:00–13:10", "uncertainties":"年份及院區待確認", "tentative":false,
      "singleTrip":false,"timeFlexible":true
    }""")
    private fun envelope(vararg orders: JSONObject) = JSONObject().put("imageTranscript", "截圖文字，尚待校對")
        .put("orders", JSONArray(orders.toList())).toString()

    @Test fun glossaryKeepsRawAddressesAndImageTranscriptAcrossRoundTripDrafts() {
        val source = "北院去南院，回程13:00"
        val terms = LocationTerms(listOf(LocationTerm("北院", "北區醫院"), LocationTerm("南院", "南區醫院")))
        val json = envelope(fixture().put("pickup", "北區醫院").put("destination", "南區醫院"))
        val drafts = AiBookingParser.decode(json, source, "", terms)
        assertEquals("北院", drafts.first().pickup)
        assertEquals("南院", drafts.first().destination)
        assertEquals("南院", drafts.last().pickup)
        assertEquals(source, drafts.first().raw)
        assertEquals("截圖文字，尚待校對", drafts.first().imageTranscript)
    }
    @Test fun preservesSourceRangeUnknownYearAndDistinctPeople() {
        val order = AiBookingParser.decode(envelope(fixture()), "原始訊息", "/private/image.png").first()
        assertEquals("10:00–10:30", order.pickupTime)
        assertEquals("09/21", order.date)
        assertEquals("10:00–13:10", order.calendarTime)
        assertNotEquals(order.contactPhone, order.passengerPhone)
        assertFalse(order.wheelchairUnknown)
        assertTrue(order.wheelchair)
        assertEquals("自付額 54", order.fare)
        assertTrue(order.needsAddressCheck)
        assertEquals("原始訊息", order.raw)
        assertEquals("/private/image.png", order.sourceImage)
        assertEquals("截圖文字，尚待校對", order.imageTranscript)
        assertTrue(order.notes.contains("11之3樓"))
    }
    @Test fun roundTripAlwaysProducesTwoReversedOrders() {
        val orders = AiBookingParser.decode(envelope(fixture()), "回程13:00左右")
        assertEquals(2, orders.size)
        assertNotEquals(orders[0].id, orders[1].id)
        assertFalse(orders[1].tentative)
        assertEquals(orders[0].pickup, orders[1].destination)
        assertEquals(orders[0].destination, orders[1].pickup)
        assertEquals(orders[0].bookingId, orders[1].bookingId)
        assertTrue(orders[1].needsAddressCheck)
        assertEquals("13:00", orders[1].pickupTime)
    }
    @Test fun noBookingsDoesNotCreateAnOrder() {
        assertTrue(AiBookingParser.decode(envelope(), "非預約內容").isEmpty())
    }
    @Test(expected = Exception::class) fun rejectsIncompleteResultsAsAWhole() {
        AiBookingParser.decode(envelope(fixture(), JSONObject().put("pickupTime", "13:00")), "原文")
    }
    @Test fun explicitSingleTripDoesNotCreateReturn() {
        assertEquals(1, AiBookingParser.decode(envelope(fixture().put("singleTrip", true)), "單程").size)
    }
    @Test fun returnUsesCalendarEndWhenNoExplicitReturn() {
        assertEquals("13:10", AiBookingParser.decode(envelope(fixture().put("returnTime", "")), "原文").last().pickupTime)
    }
    @Test fun flexiblePickupRangeDoesNotBecomeReturnTime() {
        val orders = AiBookingParser.decode(envelope(fixture().put("returnTime", "").put("calendarTime", "")), "10:00–10:30都可")
        assertEquals(2, orders.size)
        assertEquals("時間待填", orders.last().pickupTime)
    }
    @Test fun saveFromEitherSideIncludesBothAndReversesEditedAddresses() {
        val drafts = AiBookingParser.decode(envelope(fixture()), "原文")
        val edited = drafts.last().copy(pickup = "更正醫院", pickupTime = "14:00")
        val batch = pairedOrdersToSave(drafts, edited)
        assertEquals(2, batch.size)
        assertEquals("更正醫院", batch.first { !it.returnRide }.destination)
        assertEquals("10:00–10:30", batch.first { !it.returnRide }.pickupTime)
        assertEquals("14:00", batch.first { it.returnRide }.pickupTime)
        assertEquals(1, pairedOrdersToSave(emptyList(), edited).size)
    }
    @Test fun fareAndPairSurviveStorageAndLegacyWheelchairIsFixed() {
        val order = AiBookingParser.decode(envelope(fixture()), "原文").first()
        val saved = order.toJson().toOrder()
        assertEquals(order.fare, saved.fare)
        assertEquals(order.bookingId, saved.bookingId)
        val legacy = order.toJson().put("wheelchair", false).put("wheelchairUnknown", true).put("uncertainties", "輪椅需求待確認；年份待確認")
        legacy.remove("fare")
        legacy.put("notes", "總額180，補助126，自付54")
        assertTrue(legacy.toOrder().wheelchair)
        assertFalse(legacy.toOrder().uncertainties.contains("輪椅"))
        assertEquals("自付額 54", legacy.toOrder().fare)
        assertEquals("自費", extractFare("自費 跳+300"))
        assertEquals("", extractFare("車資180，補助126"))
    }
}
