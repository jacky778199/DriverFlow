package tw.driver.schedule

import java.time.*
import kotlinx.coroutines.CancellationException

internal class InsertionEvaluator(
    private val client: GoogleRouteClient,
    private val choose: suspend (String, List<RoutePlace>) -> RoutePlace,
    private val location: suspend () -> RoutePoint,
    private val progress: (String) -> Unit = {},
    private val clock: () -> Instant = { Instant.now() },
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private var status: String = ""
        set(value) { field = value; progress(value) }
    private var position: RoutePoint? = null
    private var triedPosition = false
    private suspend fun optionalPosition(): RoutePoint? {
        if (!triedPosition) {
            triedPosition = true
            position = try { location() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
        return position
    }

    suspend fun prepare(snapshot: List<RideOrder>, at: Instant, target: Instant = at,
        mode: InsertionOrigin = InsertionOrigin.AUTO): InsertionSchedule {
        status = "依實際紀錄與排程判斷眼前空檔…"
        val day = snapshot.filter { it.serviceDate == at.atZone(zone).toLocalDate().toString() }
            .sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
        val durations = mutableMapOf<Long, Long>()
        val remaining = mutableMapOf<Long, Long>()
        for (ride in insertionPrecedingRides(snapshot, at, target, mode, zone)) {
            if (insertionRideFinished(ride, at)) continue
            if (insertionRideOnboard(ride, at)) {
                val gps = optionalPosition()
                if (gps != null) {
                    try {
                        status = "估算目前載客行程的剩餘車程…"
                        val end = client.resolve(ride.destination, ride.destinationPlaceId, choose)
                        remaining[ride.id] = client.computeFromPosition(gps.latitude, gps.longitude, end.id, at).seconds
                        continue
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Fall back to boarding + full ride. */ }
                }
            }
            durations[ride.id] = billedRideSeconds(ride, day.getOrNull(day.indexOf(ride) - 1)) ?: run {
                status = "估算既有行程：${ride.customer.ifBlank { ride.pickup }}"
                val from = client.resolve(ride.pickup, ride.pickupPlaceId, choose)
                val end = client.resolve(ride.destination, ride.destinationPlaceId, choose)
                val boarded = actualRideInstant(ride.actualBoardedAt, ride.serviceDate)
                val departure = boarded ?: scheduledPickup(ride, zone).plusSeconds(300)
                client.compute(from.id, end.id, maxOf(at, departure)).seconds
            }
        }
        var schedule = insertionSchedule(snapshot, at, durations, zone, target, mode, remaining)
        val active = schedule.active
        if (active != null && !insertionRideFinished(active, at) && !insertionRideOnboard(active, at) &&
            minuteOfDay(active.pickupTime) != null && scheduledPickup(active, zone) <= at && schedule.availableAt > at) {
            val gps = optionalPosition()
            if (gps != null) {
                try {
                    val end = client.resolve(active.destination, active.destinationPlaceId, choose)
                    remaining[active.id] = client.computeFromPosition(gps.latitude, gps.longitude, end.id, at).seconds
                    schedule = insertionSchedule(snapshot, at, durations, zone, target, mode, remaining)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Scheduled estimate remains visible. */ }
            }
        }
        return schedule
    }

    suspend fun evaluate(input: InsertionCase, rides: List<RideOrder>, latestRides: () -> List<RideOrder>,
        bufferMinutes: Long = 5, mode: InsertionOrigin = InsertionOrigin.AUTO, origin: String = "",
        manualBasis: InsertionOrigin = InsertionOrigin.AUTO, warningMinutes: Long = 10,
        pickupOverride: RoutePlace? = null, destinationOverride: RoutePlace? = null): InsertionResult {
        require(bufferMinutes in 0..60 && warningMinutes in 0..60) { "緩衝與提醒門檻請填 0–60 分鐘" }
        val snapshot = rides.toList()
        val start = clock()
        position = null; triedPosition = false
        val window = input.window(start, zone)
        val basis = if (mode == InsertionOrigin.MANUAL) manualBasis else mode
        require(basis != InsertionOrigin.MANUAL) { "請選擇手動位置沿用的時間基準" }
        val baseSchedule = if (basis == InsertionOrigin.CURRENT) currentInsertionSchedule(snapshot, start, zone)
            else prepare(snapshot, start, maxOf(start, window.first), basis)
        val schedule = if (mode == InsertionOrigin.MANUAL) baseSchedule.copy(
            originReason = if (baseSchedule.active == null) "沿用現在時間，出發地址由使用者指定"
                else "沿用上一趟下車後可出發時間，出發地址由使用者指定",
            evidence = if (baseSchedule.active == null) "現在時間" else baseSchedule.evidence) else baseSchedule
        status = "確認出發位置…"
        var from = when {
            mode == InsertionOrigin.MANUAL -> {
                require(origin.isNotBlank()) { "請填寫手動出發位置" }
                client.resolve(origin, "", choose)
            }
            schedule.active != null -> client.resolve(schedule.active.destination, schedule.active.destinationPlaceId, choose)
            else -> null
        }
        val gps = if (from == null) optionalPosition() ?: error("無法取得近期位置，請授權定位或改用手動位置") else null
        val originLabel = from?.let { "${it.name} ${it.address}".trim() }
            ?: "現在位置${gps!!.accuracyMeters?.let { "（精度約 $it 公尺）" }.orEmpty()}"
        val needsReference = listOf(input.pickup, input.destination).any {
            val expanded = client.expandedAddress(it)
            isStreetOnlyAddress(expanded) && streetRegion(expanded) == null
        }
        if (needsReference && from != null && from.point == null && streetRegion(from.address) == null) {
            try { from = client.placeDetails(from) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* Ask for region if it cannot be established. */ }
        }
        status = "確認插單起點與終點…"
        val pickup = pickupOverride ?: client.resolveInsertion(input.pickup, from, gps, choose = choose)
        val dropoff = destinationOverride ?: client.resolveInsertion(input.destination, from, gps, choose = choose)
        status = "第 1 段：出發位置 → 插單起點"
        val toPickup = from?.let { client.compute(it.id, pickup.id, schedule.availableAt) }
            ?: client.computeFromPosition(gps!!.latitude, gps.longitude, pickup.id, schedule.availableAt)
        val pickupStart = maxOf(schedule.availableAt.plusSeconds(toPickup.seconds), window.first)
        status = "第 2 段：插單起點 → 插單終點"
        val ride = client.compute(pickup.id, dropoff.id, pickupStart.plusSeconds(bufferMinutes * 60))
        val ready = pickupStart.plusSeconds(bufferMinutes * 120 + ride.seconds)
        val toNext = schedule.next?.let { next ->
            status = "第 3 段：插單終點 → 下一趟起點"
            val target = client.resolve(next.pickup, next.pickupPlaceId, choose)
            client.compute(dropoff.id, target.id, ready)
        }
        val estimates = listOf(input.pickup to pickup, input.destination to dropoff).filter { it.second.approximate }
            .map { (query, place) -> "「$query」採用 ${place.name} ${place.address} · ${place.selectionReason}" }
        val evaluated = assessInsertion(input, schedule, originLabel, start, toPickup, ride, toNext, bufferMinutes, zone, warningMinutes).copy(
            locationEstimates = estimates, originMode = mode, basisMode = basis, pickupPlace = pickup, destinationPlace = dropoff)
        require(evaluated.isFresh(latestRides(), clock())) { "評估期間空檔已不足或排程狀態已變動，請重新評估" }
        return evaluated
    }
}
