package tw.driver.schedule

import android.location.Location
import java.time.*

internal class InsertionEvaluator(
    private val client: GoogleRouteClient,
    private val choose: suspend (String, List<RoutePlace>) -> RoutePlace,
    private val location: suspend () -> Location,
    private val progress: (String) -> Unit = {}
) {
    private var status: String = ""
        set(value) { field = value; progress(value) }
    suspend fun prepare(snapshot: List<RideOrder>, at: Instant): InsertionSchedule {
        status = "判斷目前與下一趟行程…"
        val day = snapshot.filter { it.serviceDate == at.atZone(ZoneId.systemDefault()).toLocalDate().toString() }.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
        require(day.filterNot { it.completed }.all { minuteOfDay(it.pickupTime) != null }) { "當日有未填接客時間的行程，請先補齊" }
        val durations = mutableMapOf<Long, Long>()
        day.forEachIndexed { index, ride ->
            if (!ride.completed && scheduledPickup(ride) <= at) {
                durations[ride.id] = billedRideSeconds(ride, day.getOrNull(index - 1)) ?: run {
                    status = "估算既有行程：${ride.customer.ifBlank { ride.pickup }}"
                    val from = client.resolve(ride.pickup, ride.pickupPlaceId, choose)
                    val end = client.resolve(ride.destination, ride.destinationPlaceId, choose)
                    client.compute(from.id, end.id, at).seconds
                }
            }
        }
        return insertionSchedule(snapshot, at, durations)
    }

    suspend fun evaluate(inputCase: InsertionCase, rides: List<RideOrder>, latestRides: () -> List<RideOrder>,
        bufferMinutes: Long = 5, manualOrigin: Boolean = false, origin: String = ""): InsertionResult {
        val snapshot = rides.toList()
        val start = Instant.now()
        val input = inputCase
        val window = input.window(start, ZoneId.systemDefault())
        val padding = bufferMinutes
        val schedule = prepare(snapshot, start)
        status = "確認插單起點與終點…"
        val pickup = client.resolve(input.pickup, "", choose)
        val dropoff = client.resolve(input.destination, "", choose)
        val toPickup: RouteLeg
        val originLabel: String
        if (manualOrigin || schedule.active != null) {
            val address = if (manualOrigin) origin else schedule.active!!.destination
            val from = client.resolve(address, if (manualOrigin) "" else schedule.active!!.destinationPlaceId, choose)
            originLabel = "${from.name} ${from.address}".trim()
            status = "第 1 段：出發位置 → 插單起點"
            toPickup = client.compute(from.id, pickup.id, schedule.availableAt)
        } else {
            status = "取得現在位置…"
            val position = location()
            originLabel = "現在位置（精度約 ${position.accuracy.toInt()} 公尺）"
            status = "第 1 段：現在位置 → 插單起點"
            toPickup = client.computeFromPosition(position.latitude, position.longitude, pickup.id, schedule.availableAt)
        }
        val pickupStart = maxOf(schedule.availableAt.plusSeconds(toPickup.seconds), window.first)
        status = "第 2 段：插單起點 → 插單終點"
        val ride = client.compute(pickup.id, dropoff.id, pickupStart.plusSeconds(padding * 60))
        val ready = pickupStart.plusSeconds(padding * 120 + ride.seconds)
        val toNext = schedule.next?.let { next ->
            status = "第 3 段：插單終點 → 下一趟起點"
            val target = client.resolve(next.pickup, next.pickupPlaceId, choose)
            client.compute(dropoff.id, target.id, ready)
        }
        val evaluated = assessInsertion(input, schedule, originLabel, start, toPickup, ride, toNext, padding)
        require(evaluated.isFresh(latestRides())) { "評估期間空檔已不足或排程狀態已變動，請重新評估" }
        return evaluated
    }
}
