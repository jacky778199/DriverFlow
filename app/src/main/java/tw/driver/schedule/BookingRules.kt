package tw.driver.schedule

import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter

internal fun bookingDate(value: String, today: LocalDate = LocalDate.now()): String {
    if (value.isBlank() || value.contains("日期待") || value == "今天") return today.format(DateTimeFormatter.ofPattern("MM/dd"))
    val match = Regex("^(?:\\d{4}[/年.-])?(\\d{1,2})[/月.-](\\d{1,2})").find(value.trim()) ?: return value.trim()
    return "%02d/%02d".format(match.groupValues[1].toInt(), match.groupValues[2].toInt())
}

internal fun reminders(vararg values: String): String = values.flatMap { it.split(Regex("[；;\\n]")) }
    .map { it.trim() }.filter { it.isNotBlank() && !it.contains("年份") }.distinct().joinToString("\n")

internal fun bookingValidation(date: String, time: String, pickup: String, destination: String,
                               pickupConfirmed: Boolean, destinationConfirmed: Boolean, partnerTime: String? = null): List<String> {
    val problems = mutableListOf<String>()
    if (runCatching { MonthDay.parse(bookingDate(date), DateTimeFormatter.ofPattern("MM/dd")) }.isFailure) problems += "請填寫有效日期，例如 09/21。"
    val timePattern = Regex("(?:[01]?\\d|2[0-3]):[0-5]\\d(?:\\s*[–-]\\s*(?:[01]?\\d|2[0-3]):[0-5]\\d)?")
    if (!timePattern.matches(time.trim())) problems += "請填寫接客時間，例如 09:30 或 10:00–10:30。"
    if (partnerTime != null && !timePattern.matches(partnerTime.trim())) problems += "請填寫另一張單的接客時間。"
    if (pickup.isBlank() || pickup.contains("待確認") || pickup.contains("待填")) problems += "請填寫上車地址。"
    if (destination.isBlank() || destination.contains("待確認") || destination.contains("待填")) problems += "請填寫下車地址。"
    return problems
}

internal fun addressForDisplay(value: String): String = value.replace(Regex("[（(][^（）()]*待確認[^（）()]*[）)]"), "").trim()

internal fun remainingDrafts(drafts: List<RideOrder>, skippedId: Long) = drafts.filterNot { it.id == skippedId }

internal fun addressesReady(pickup: String, destination: String, pickupConfirmed: Boolean, destinationConfirmed: Boolean): Boolean =
    listOf(pickup, destination).all {
        it.isNotBlank() && !it.contains("待確認") && !it.contains("待填")
    }

internal fun RideOrder.copyText(): String = """
    ${bookingDate(date)} $pickupTime ${if(returnRide) "回程" else "去程"}${if(tentative) "（暫定）" else ""}
    乘客：$customer
    聯絡人：$contact
    聯絡人電話：${contactPhone.ifBlank { "未提供" }}
    乘客電話：${passengerPhone.ifBlank { "未提供" }}
    上車：${addressForDisplay(pickup)}
    下車：${addressForDisplay(destination)}
    費用：${fare.ifBlank { "未提供" }}
    $notes
""".trimIndent().trim()

internal fun cleanWheelchairWarning(text: String): String = text
    .replace(Regex("輪椅(?:需求)?(?:尚未確認|待確認|未確認)[；;，,。]?"), "").trim()

internal fun extractFare(text: String): String {
    val amount = Regex("自(?:付|負)(?:額|金額)?\\s*[:：]?\\s*[NT＄$]*\\s*([0-9]+(?:\\.[0-9]+)?)").find(text)?.groupValues?.get(1)
    return if (amount != null) "自付額 $amount" else if (text.contains("自費")) "自費" else ""
}

/** The AI emits one booking. Expansion is deterministic so it cannot omit or duplicate its return. */
internal fun expandBooking(outbound: RideOrder, singleTrip: Boolean, explicitReturnTime: String): List<RideOrder> {
    if (singleTrip) return listOf(outbound)
    val calendarTimes = Regex("\\d{1,2}:\\d{2}").findAll(outbound.calendarTime).map { it.value }.toList()
    val returnTime = explicitReturnTime.ifBlank { calendarTimes.getOrNull(1).orEmpty() }.ifBlank { "時間待填" }
    return listOf(outbound, outbound.copy(id = System.nanoTime(), pickupTime = returnTime,
        pickup = outbound.destination, destination = outbound.pickup, pickupPlaceId = outbound.destinationPlaceId, destinationPlaceId = outbound.pickupPlaceId, routeEstimate = "", returnRide = true,
        uncertainties = listOf(outbound.uncertainties, if(returnTime == "時間待填") "回程時間未提供，請手動填寫" else "").filter { it.isNotBlank() }.joinToString("\n")))
}

internal fun pairedOrdersToSave(drafts: List<RideOrder>, edited: RideOrder): List<RideOrder> {
    val partner = drafts.firstOrNull { edited.bookingId.isNotBlank() && it.bookingId == edited.bookingId && it.id != edited.id }
        ?: return listOf(edited)
    return listOf(edited, partner.copy(pickup = edited.destination, destination = edited.pickup,
        pickupPlaceId = edited.destinationPlaceId, destinationPlaceId = edited.pickupPlaceId, routeEstimate = "",
        customer = edited.customer, contact = edited.contact, contactPhone = edited.contactPhone,
        passengerPhone = edited.passengerPhone, date = edited.date, serviceDate = edited.serviceDate, category = edited.category))
}
