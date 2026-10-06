package tw.driver.schedule

import java.time.*
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

internal data class InsertionCase(val pickup: String, val destination: String, val date: String,
    val time: String = "", val asap: Boolean = false, val note: String = "", val originalPickup: String = pickup) {
    fun window(now: Instant, zone: ZoneId): Pair<Instant, Instant?> {
        val day = LocalDate.parse(date)
        require(day == now.atZone(zone).toLocalDate()) { "目前只評估今天的插單，請確認接客日期" }
        require(pickup.isNotBlank() && destination.isNotBlank()) { "請填寫插單起點與終點" }
        if (asap) return now to null
        val parts = time.trim().split(Regex("[–—~～-]"))
        require(parts.size in 1..2 && parts.all { Regex("\\d{1,2}:\\d{2}").matches(it.trim()) && minuteOfDay(it.trim()) != null }) { "請填寫接客時間 HH:mm 或 HH:mm–HH:mm，或選即時可等" }
        fun at(value: String) = day.atStartOfDay(zone).plusMinutes(minuteOfDay(value.trim())!!.toLong()).toInstant()
        val start = at(parts.first()); val end = at(parts.last())
        require(end >= start) { "接客時間區間的結束不可早於開始" }
        return start to end
    }
}

internal fun InsertionCase.translated(terms: LocationTerms): InsertionCase =
    copy(pickup = terms.expand(pickup), destination = terms.expand(destination))

internal fun insertionScheduleKey(rides: List<RideOrder>): String {
    val fields = rides.sortedBy { it.id }.map { listOf(it.id, it.serviceDate, it.pickupTime, it.pickup, it.destination,
        it.completed, it.actualBoardedAt, it.actualAlightedAt, it.rideMinutes, it.routeEstimate,
        it.pickupPlaceId, it.destinationPlaceId, it.tentative, it.timeFlexible).joinToString("|") }
    return MessageDigest.getInstance("SHA-256").digest(JSONArray(fields).toString().toByteArray()).joinToString("") { "%02x".format(it) }
}

internal data class InsertionSchedule(val active: RideOrder?, val next: RideOrder?, val availableAt: Instant,
    val nextAt: Instant?, val key: String, val boundary: Instant?, val concerns: List<String>,
    val previousReadyAt: Instant? = null, val originReason: String = "", val evidence: String = "依排程推估",
    val warnings: List<String> = emptyList())

internal enum class InsertionOrigin { AUTO, SCHEDULE, CURRENT, MANUAL }
internal enum class InsertionLevel(val label: String) {
    AVAILABLE("可接"), CAUTION("時間緊／需確認"), INFEASIBLE("接不上"), UNKNOWN("尚無法判斷")
}

internal fun insertionRideOnboard(ride: RideOrder, now: Instant): Boolean =
    actualRideInstant(ride.actualBoardedAt, ride.serviceDate)?.let { it <= now } == true && ride.actualAlightedAt.isBlank()

internal fun insertionRideFinished(ride: RideOrder, now: Instant): Boolean =
    actualRideInstant(ride.actualAlightedAt, ride.serviceDate)?.let { it <= now } == true ||
        (ride.completed && !insertionRideOnboard(ride, now))

internal fun insertionRecordConcerns(rides: List<RideOrder>, now: Instant): List<String> = buildList {
    rides.forEach { ride ->
        val boarded = actualRideInstant(ride.actualBoardedAt, ride.serviceDate)
        val alighted = actualRideInstant(ride.actualAlightedAt, ride.serviceDate)
        val label = ride.customer.ifBlank { ride.pickup }
        if ((ride.actualBoardedAt.isNotBlank() && boarded == null) || (ride.actualAlightedAt.isNotBlank() && alighted == null))
            add("「$label」實際客上／客下時間格式不正確")
        if ((boarded != null && boarded > now) || (alighted != null && alighted > now)) add("「$label」實際紀錄晚於現在，請確認")
        if (boarded != null && alighted != null && alighted < boarded) add("「$label」客下早於客上，請確認")
        if (ride.completed && insertionRideOnboard(ride, now)) add("「$label」已完成但仍未記錄客下，請確認")
    }
}

/** Completed trips need no route lookup unless the driver explicitly chooses their dropoff. */
internal fun insertionPrecedingRides(rides: List<RideOrder>, now: Instant, target: Instant,
    mode: InsertionOrigin = InsertionOrigin.AUTO, zone: ZoneId = ZoneId.systemDefault()): List<RideOrder> {
    val today = now.atZone(zone).toLocalDate().toString()
    val preceding = rides.filter { it.serviceDate == today &&
        (insertionRideOnboard(it, now) || (minuteOfDay(it.pickupTime) != null && scheduledPickup(it, zone) <= target)) }
    val pending = preceding.filterNot { insertionRideFinished(it, now) }
    return if (mode == InsertionOrigin.SCHEDULE) {
        val lastFinished = preceding.filter { insertionRideFinished(it, now) }.maxByOrNull { scheduledPickup(it, zone) }
        pending + listOfNotNull(lastFinished)
    } else pending
}

/** Explicit GPS mode starts now and protects the first upcoming booking. */
internal fun currentInsertionSchedule(rides: List<RideOrder>, now: Instant, zone: ZoneId = ZoneId.systemDefault()): InsertionSchedule {
    val today = now.atZone(zone).toLocalDate().toString()
    val upcoming = rides.filter { !it.completed && it.serviceDate >= today &&
        (minuteOfDay(it.pickupTime) == null || scheduledPickup(it, zone) > now) }
    val day = rides.filter { it.serviceDate == today }
    val concerns = insertionRecordConcerns(day, now) +
        if (day.any { insertionRideOnboard(it, now) }) listOf("已有客上尚未客下，現在位置模式需先確認載客狀態") else emptyList()
    val schedule = insertionSchedule(upcoming.filterNot { insertionRideFinished(it, now) }, now, emptyMap(), zone)
    return schedule.copy(
        key = insertionScheduleKey(rides), concerns = schedule.concerns + concerns,
        originReason = "手動指定現在時間與 GPS，銜接最近待接案件", evidence = "現在 GPS")
}

/** Time-based inference; missing durations must be resolved before declaring a free gap. */
internal fun insertionSchedule(rides: List<RideOrder>, now: Instant, durations: Map<Long, Long>, zone: ZoneId = ZoneId.systemDefault(),
    target: Instant = now, mode: InsertionOrigin = InsertionOrigin.AUTO, remainingSeconds: Map<Long, Long> = emptyMap()): InsertionSchedule {
    val today = now.atZone(zone).toLocalDate()
    val day = rides.filter { it.serviceDate == today.toString() }
    require(day.filterNot { insertionRideFinished(it, now) || insertionRideOnboard(it, now) }.all { minuteOfDay(it.pickupTime) != null }) { "當日有未填接客時間的行程，請先補齊再評估" }
    val preceding = insertionPrecedingRides(rides, now, target, mode, zone).map { ride ->
        val actualEnd = actualRideInstant(ride.actualAlightedAt, ride.serviceDate)
        val remaining = remainingSeconds[ride.id]
        val seconds = durations[ride.id] ?: if (actualEnd != null || remaining != null || ride.completed) 0L
            else error("「${ride.customer.ifBlank { ride.pickup }}」缺少車程，無法判斷是否執行中")
        require(seconds >= 0)
        val boarded = actualRideInstant(ride.actualBoardedAt, ride.serviceDate)
        val end = when {
            actualEnd != null -> actualEnd
            ride.completed && !insertionRideOnboard(ride, now) -> now
            remaining != null -> { require(remaining >= 0); now.plusSeconds(remaining + 300) }
            boarded != null -> boarded.plusSeconds(seconds + 300) // boarding is already recorded
            else -> scheduledPickup(ride, zone).plusSeconds(seconds + 600)
        }
        ride to end
    }
    val ongoing = preceding.filter { !insertionRideFinished(it.first, now) &&
        (insertionRideOnboard(it.first, now) || it.second > maxOf(now, target)) }
    val active = preceding.filter { mode == InsertionOrigin.SCHEDULE || it.second > now || insertionRideOnboard(it.first, now) }
        .maxByOrNull { it.second }
    // Include the next calendar day's first trip as well when an insertion runs across midnight.
    val upcoming = rides.filter { !insertionRideFinished(it, now) && !insertionRideOnboard(it, now) && it.serviceDate >= today.toString() && minuteOfDay(it.pickupTime) != null }
        .map { it to scheduledPickup(it, zone) }.filter { it.second > maxOf(now, target) }.minByOrNull { it.second }
    val concerns = buildList {
        addAll(insertionRecordConcerns(day, now))
        if (ongoing.size > 1) add("目前已有多趟行程時間重疊，請確認排程")
        val future = preceding.filter { !insertionRideFinished(it.first, now) && minuteOfDay(it.first.pickupTime) != null && it.second > now }
            .sortedBy { scheduledPickup(it.first, zone) }
        if (future.zipWithNext().any { (left, right) -> scheduledPickup(right.first, zone) > now && left.second > scheduledPickup(right.first, zone) })
            add("插單之前的待執行案件時間重疊，請確認排程")
        if (active?.first?.destination?.isBlank() == true) add("執行中行程缺少下車地點")
        if (upcoming?.first?.pickup?.isBlank() == true) add("下一趟缺少上車地點")
        if (active?.first?.tentative == true || active?.first?.timeFlexible == true) add("目前行程時間尚未確定，需人工確認")
        if (upcoming?.first?.tentative == true || upcoming?.first?.timeFlexible == true) add("下一趟時間尚未確定，需人工確認")
        if (preceding.any { insertionRideOnboard(it.first, now) && it.second <= now })
            add("已超過預估下車時間但尚未客下，請確認目前行程")
    }
    val actual = active?.first?.let { insertionRideOnboard(it, now) } == true
    val evidence = when {
        active == null -> "現在 GPS"
        active.first.id in remainingSeconds -> "GPS 剩餘車程"
        actual -> "實際客上＋車程推估"
        actualRideInstant(active.first.actualAlightedAt, active.first.serviceDate) != null -> "實際客下紀錄"
        active.first.completed -> "完成標記＋現在時間"
        else -> "依排程推估"
    }
    val reason = when {
        mode == InsertionOrigin.SCHEDULE && active != null -> "指定上一趟下車地點，沿用此空檔可出發時間"
        active == null -> if (mode == InsertionOrigin.SCHEDULE) "找不到上一趟下車案件，改用現在 GPS" else "目前無執行中或插單前的待執行案件，使用現在位置"
        actual -> "已記錄客上尚未客下，從目前行程下車後出發"
        scheduledPickup(active.first, zone) > now -> "指定接客時間前有待執行案件，從該趟下車後出發"
        else -> "沒有實際客下紀錄，依排程推估目前行程"
    }
    return InsertionSchedule(active?.first, upcoming?.first, maxOf(active?.second ?: now, now), upcoming?.second,
        insertionScheduleKey(rides), listOfNotNull(active?.second, upcoming?.second).filter { it > now }.minOrNull(), concerns,
        active?.second, reason, evidence, if (actual && active!!.first.id !in remainingSeconds)
            listOf("剩餘車程依客上時間推估，未取得 GPS 剩餘車程") else emptyList())
}

internal data class InsertionResult(val case: InsertionCase, val plan: InsertionSchedule, val origin: String,
    val toPickup: RouteLeg, val ride: RouteLeg, val toNext: RouteLeg?, val pickupArrival: Instant,
    val pickupStart: Instant, val dropoffReady: Instant, val nextArrival: Instant?, val pickupLateSeconds: Long,
    val nextSlackSeconds: Long?, val reasons: List<String>, val calculatedAt: Instant, val bufferMinutes: Long,
    val pickupDeadline: Instant?, val locationEstimates: List<String> = emptyList(),
    val originMode: InsertionOrigin = InsertionOrigin.AUTO, val basisMode: InsertionOrigin = InsertionOrigin.AUTO,
    val warningMinutes: Long = 10, val pickupPlace: RoutePlace? = null, val destinationPlace: RoutePlace? = null) {
    val level get() = when {
        plan.concerns.isNotEmpty() -> InsertionLevel.UNKNOWN
        reasons.isNotEmpty() -> InsertionLevel.INFEASIBLE
        locationEstimates.isNotEmpty() || plan.warnings.isNotEmpty() || (nextSlackSeconds != null && nextSlackSeconds < warningMinutes * 60) -> InsertionLevel.CAUTION
        else -> InsertionLevel.AVAILABLE
    }
    val feasible get() = level == InsertionLevel.AVAILABLE || level == InsertionLevel.CAUTION
    fun isFresh(rides: List<RideOrder>, now: Instant = Instant.now()): Boolean {
        val elapsed = Duration.between(calculatedAt, now).seconds
        val margin = if (feasible && plan.availableAt <= calculatedAt)
            listOfNotNull(nextSlackSeconds, pickupDeadline?.let { Duration.between(pickupArrival, it).seconds }).minOrNull() else null
        return elapsed in 0..minOf(300L, margin ?: 300L) && plan.key == insertionScheduleKey(rides) && (plan.boundary == null || now < plan.boundary)
    }
    val arrival get() = MessageArrival(case.originalPickup, origin, maxOf(0, Duration.between(calculatedAt, pickupArrival).seconds), toPickup.meters, calculatedAt.toEpochMilli())
    val summary get() = if (feasible) {
        if (nextSlackSeconds == null) "${level.label}：沒有下一趟待接行程" else "${level.label}：銜接下一趟尚餘 ${nextSlackSeconds / 60} 分鐘"
    } else reasons.joinToString("；")
}

internal fun assessInsertion(case: InsertionCase, plan: InsertionSchedule, origin: String, now: Instant,
    toPickup: RouteLeg, ride: RouteLeg, toNext: RouteLeg?, bufferMinutes: Long = 5, zone: ZoneId = ZoneId.systemDefault(), warningMinutes: Long = 10): InsertionResult {
    require(bufferMinutes in 0..60) { "上下車緩衝請填 0–60 分鐘" }
    require(warningMinutes in 0..60) { "餘裕提醒門檻請填 0–60 分鐘" }
    require(toPickup.seconds >= 0 && ride.seconds >= 0 && (toNext == null || toNext.seconds >= 0))
    require((plan.next == null) == (toNext == null)) { "缺少銜接下一趟的路線" }
    val (earliest, latest) = case.window(now, zone)
    val arrival = plan.availableAt.plusSeconds(toPickup.seconds)
    val pickup = maxOf(arrival, earliest)
    val dropoff = pickup.plusSeconds(bufferMinutes * 120 + ride.seconds)
    val nextArrival = toNext?.let { dropoff.plusSeconds(it.seconds) }
    val late = latest?.let { maxOf(0, Duration.between(it, arrival).seconds) } ?: 0
    val slack = nextArrival?.let { Duration.between(it, plan.nextAt!!).seconds }
    val reasons = plan.concerns.toMutableList()
    if (late > 0) reasons.add("插單接客將遲到 ${routeMinutes(late)} 分鐘")
    if (slack != null && slack < 0) reasons.add("下一趟將延誤 ${routeMinutes(-slack)} 分鐘")
    val crossDayConcern = "插單跨日，需確認翌日排程".takeIf {
        dropoff.atZone(zone).toLocalDate() != now.atZone(zone).toLocalDate() && plan.next == null }
    crossDayConcern?.let { reasons.add(it) }
    val checkedPlan = if (crossDayConcern == null) plan else plan.copy(concerns = plan.concerns + crossDayConcern)
    return InsertionResult(case, checkedPlan, origin, toPickup, ride, toNext, arrival, pickup, dropoff, nextArrival, late, slack, reasons, now, bufferMinutes, latest,
        warningMinutes = warningMinutes)
}

internal object InsertionParser {
    private fun isImmediate(text: String): Boolean {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("[\\s\\p{Z}\\u200B\\uFEFF]+"), "")
        return Regex("即時可等|即時|現在出發|馬上出發").containsMatchIn(normalized)
    }

    fun decode(json: String, original: String, today: LocalDate): InsertionCase {
        val obj = JSONObject(json)
        fun address(key: String): String = obj.optString(key).trim().also { require(it.isEmpty() || original.contains(it)) { "AI 地址不在訊息原文，請手動填寫" } }
        val date = obj.optString("date").ifBlank { today.toString() }
        LocalDate.parse(date)
        val immediate = isImmediate(original)
        return InsertionCase(address("pickup"), address("destination"), date,
            if (immediate) "" else obj.optString("time"), immediate, obj.optString("note"))
    }
    suspend fun extract(text: String, today: LocalDate, settings: AiSettings = AiSettings(), terms: LocationTerms = LocationTerms()): InsertionCase {
        val instructions = messageAnalysisPrompt(settings, terms, text, today)
        return decode(MessageAiJson.extract(text, instructions, settings), text, today)
    }
}
