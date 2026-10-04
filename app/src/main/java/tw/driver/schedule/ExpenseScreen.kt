package tw.driver.schedule

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter

/**
 * 營業成本花費項目
 */
data class ExpenseItem(
    val id: Long = System.nanoTime(),
    val date: String,             // 格式 "YYYY-MM-DD"
    val category: String,         // "加油", "停車費", "租車費用", "其他"
    val amountCents: Long = 0,    // 金額（分）；畫面顯示元
    val time: String = "",        // 記錄時間或加油時間，例如 "14:30"
    val note: String = "",        // 備註說明
    val paidDate: String = date,
    val paymentMethod: String = "現金",
    val costEndDate: String = ""
)

/**
 * 月租車設定與每日成本換算
 */
data class MonthlyRentalPlan(
    val model: String = "",          // 租車型號，例如 "Toyota Sienta 福祉車"
    val periodMonth: String = "",    // 租車月份/期間，例如 "2026-09"
    val feeCents: Long = 0,          // 租期總費用（分）
    val daysInMonth: Int = 30,       // 計算天數 (例如 30)
    val dailyCostCents: Long = 0,    // 試算基本分攤（分）；實際日期另外補足餘分
    val note: String = "",           // 備註說明
    val startDate: String = "",
    val endDate: String = "",
    val paidDate: String = "",
    val paymentMethod: String = "轉帳",
    val archivedPlans: List<MonthlyRentalPlan> = emptyList()
)

internal fun MonthlyRentalPlan.covers(date: LocalDate): Boolean {
    if (feeCents <= 0) return false
    val start = runCatching { LocalDate.parse(startDate) }.getOrNull()
    val end = runCatching { LocalDate.parse(endDate) }.getOrNull()
    if (start != null && end != null) return date in start..end
    // Existing month-only plans remain valid until their dates are edited.
    return periodMonth.isBlank() || periodMonth == date.format(DateTimeFormatter.ofPattern("yyyy-MM"))
}

/** Accrued ride revenue uses collected cash, tips and monthly billed amounts.
 * Monthly collections reduce receivables and never add operating revenue a second time. */
internal fun rideOperatingRevenue(ride: RideOrder): Long = operatingRevenueCents(ride)

internal fun ExpenseItem.toJson() = JSONObject().apply {
    put("id", id)
    put("date", date)
    put("category", category)
    put("amountCents", amountCents)
    put("paidDate", paidDate); put("paymentMethod", paymentMethod); put("costEndDate", costEndDate)
    put("time", time)
    put("note", note)
}

internal fun JSONObject.toExpenseItem() = ExpenseItem(
    id = getLong("id"),
    date = getString("date"),
    category = getString("category"),
    amountCents = if (has("amountCents")) getLong("amountCents") else getLong("amount") * 100,
    paidDate = optString("paidDate", getString("date")),
    paymentMethod = optString("paymentMethod", "現金"),
    costEndDate = optString("costEndDate"),
    time = optString("time", ""),
    note = optString("note", "")
)

/**
 * 每日出門與回家時間工時記錄
 */
data class WorkHourRecord(
    val date: String,
    val departureTime: String,
    val returnHomeTime: String,
    val breakStartTime: String = "",
    val breakEndTime: String = "",
    val startPoint: String = "",
    val endPoint: String = "",
    val startOdometer: String = "",
    val endOdometer: String = ""
)

/**
 * 花費完整備份資料結構（含花費項目、月租車設定、每日出門回家時間）
 */
data class ExpenseBackup(
    val expenses: List<ExpenseItem>,
    val rentalPlan: MonthlyRentalPlan,
    val workHours: List<WorkHourRecord>
)

internal fun exportExpenseData(
    expenses: List<ExpenseItem>,
    rentalPlan: MonthlyRentalPlan,
    workHours: List<WorkHourRecord>
): String = JSONObject().apply {
    put("schemaVersion", 2)
    put("type", "driver_expenses")
    put("exportedAt", java.time.Instant.now().toString())
    put("expenses", JSONArray().apply { expenses.forEach { put(it.toJson()) } })
    put("monthlyRentalPlan", JSONObject(rentalPlanMap(rentalPlan)))
    put("workHours", JSONArray().apply {
        workHours.forEach { wh ->
            put(JSONObject().apply {
                put("date", wh.date)
                put("departureTime", wh.departureTime)
                put("returnHomeTime", wh.returnHomeTime)
                put("breakStartTime", wh.breakStartTime)
                put("breakEndTime", wh.breakEndTime)
                put("startPoint", wh.startPoint)
                put("endPoint", wh.endPoint)
                put("startOdometer", wh.startOdometer)
                put("endOdometer", wh.endOdometer)
            })
        }
    })
}.toString(2)

internal fun exportExpenseCsv(expenses: List<ExpenseItem>): String {
    fun cell(v: String): String = "\"" + (if (v.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'" + v else v).replace("\"", "\"\"") + "\""
    val rows = expenses.map { listOf(it.id.toString(), it.date, it.category, yuan(it.amountCents), it.time, it.note, it.paidDate, it.paymentMethod, it.costEndDate) }
    return "\uFEFF" + (listOf(listOf("id", "date", "category", "amount_twd", "time", "note", "paid_date", "payment_method", "cost_end_date")) + rows).joinToString("\r\n") { it.joinToString(",", transform = ::cell) }
}

internal fun importExpenseData(text: String): ExpenseBackup {
    val root = JSONObject(text)
    require(root.optInt("schemaVersion", 1) in 1..2) { "不支援的資料版本" }
    val expArray = root.optJSONArray("expenses") ?: JSONArray()
    val expList = List(expArray.length()) { expArray.getJSONObject(it).toExpenseItem() }

    val planObj = root.optJSONObject("monthlyRentalPlan")
    val plan = planObj?.toRentalPlan() ?: MonthlyRentalPlan()

    val whArray = root.optJSONArray("workHours") ?: JSONArray()
    val whList = List(whArray.length()) { i ->
        val obj = whArray.getJSONObject(i)
        WorkHourRecord(
            date = obj.getString("date"),
            departureTime = obj.optString("departureTime", ""),
            returnHomeTime = obj.optString("returnHomeTime", ""),
            breakStartTime = obj.optString("breakStartTime", ""),
            breakEndTime = obj.optString("breakEndTime", ""),
            startPoint = obj.optString("startPoint", ""),
            endPoint = obj.optString("endPoint", ""),
            startOdometer = obj.optString("startOdometer", ""),
            endOdometer = obj.optString("endOdometer", "")
        )
    }
    return ExpenseBackup(expList, plan, whList)
}

internal fun collectAllWorkHours(prefs: android.content.SharedPreferences): List<WorkHourRecord> {
    val all = prefs.all
    val dates = mutableSetOf<String>()
    all.keys.forEach { key ->
        if (key.startsWith("departure_time_")) {
            dates.add(key.removePrefix("departure_time_"))
        } else if (key.startsWith("return_home_time_")) {
            dates.add(key.removePrefix("return_home_time_"))
        } else if (key.startsWith("break_start_time_")) {
            dates.add(key.removePrefix("break_start_time_"))
        } else if (key.startsWith("break_end_time_")) {
            dates.add(key.removePrefix("break_end_time_"))
        } else if (key.startsWith("day_start_point_")) {
            dates.add(key.removePrefix("day_start_point_"))
        } else if (key.startsWith("day_end_point_")) {
            dates.add(key.removePrefix("day_end_point_"))
        } else if (key.startsWith("day_start_odometer_")) {
            dates.add(key.removePrefix("day_start_odometer_"))
        } else if (key.startsWith("day_end_odometer_")) {
            dates.add(key.removePrefix("day_end_odometer_"))
        }
    }
    return dates.sorted().mapNotNull { d ->
        val dep = all["departure_time_$d"]?.toString() ?: ""
        val ret = all["return_home_time_$d"]?.toString() ?: ""
        val pauseStart = all["break_start_time_$d"]?.toString() ?: ""
        val pauseEnd = all["break_end_time_$d"]?.toString() ?: ""
        val journey = loadDailyJourney(prefs, d)
        if (dep.isNotBlank() || ret.isNotBlank() || pauseStart.isNotBlank() || pauseEnd.isNotBlank() ||
            journey.startPoint.isNotBlank() || journey.endPoint.isNotBlank() || journey.startOdometer.isNotBlank() || journey.endOdometer.isNotBlank()) {
            WorkHourRecord(date = d, departureTime = dep, returnHomeTime = ret, breakStartTime = pauseStart, breakEndTime = pauseEnd,
                startPoint = journey.startPoint, endPoint = journey.endPoint,
                startOdometer = journey.startOdometer, endOdometer = journey.endOdometer)
        } else null
    }
}

class ExpenseStore(context: Context) {
    private val prefs = context.getSharedPreferences("expenses", Context.MODE_PRIVATE)

    fun load(): List<ExpenseItem> = runCatching {
        val array = JSONArray(prefs.getString("items", "[]"))
        List(array.length()) { i -> array.getJSONObject(i).toExpenseItem() }.also { migrated ->
            if ((0 until array.length()).any { !array.getJSONObject(it).has("amountCents") }) save(migrated)
        }
    }.getOrDefault(emptyList())

    fun save(items: List<ExpenseItem>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        prefs.edit().putString("items", array.toString()).apply()
    }

    fun loadRentalPlan(): MonthlyRentalPlan {
        val raw = prefs.getString("monthly_rental_plan", null) ?: return MonthlyRentalPlan()
        return runCatching {
            val json = JSONObject(raw)
            json.toRentalPlan().also { if (!json.has("feeCents")) saveRentalPlan(it) }
        }.getOrDefault(MonthlyRentalPlan())
    }

    fun saveRentalPlan(plan: MonthlyRentalPlan) {
        val json = JSONObject(rentalPlanMap(plan))
        prefs.edit().putString("monthly_rental_plan", json.toString()).apply()
    }
}

@Composable
internal fun ExpenseScreen(
    rides: List<RideOrder>,
    onUpdateRides: (List<RideOrder>) -> Unit,
    onOpenRide: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember { ExpenseStore(context) }
    val appearancePrefs = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }

    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var expenses by remember { mutableStateOf(store.load()) }
    var rentalPlan by remember { mutableStateOf(store.loadRentalPlan()) }

    // 出門與回家時間：每天都不一樣，依據日期單獨設定並儲存
    var departureTime by remember(selectedDate) {
        mutableStateOf(appearancePrefs.getString("departure_time_$selectedDate", "") ?: "")
    }
    var returnHomeTime by remember(selectedDate) {
        mutableStateOf(appearancePrefs.getString("return_home_time_$selectedDate", "") ?: "")
    }
    var breakStartTime by remember(selectedDate) {
        mutableStateOf(appearancePrefs.getString("break_start_time_$selectedDate", "") ?: "")
    }
    var breakEndTime by remember(selectedDate) {
        mutableStateOf(appearancePrefs.getString("break_end_time_$selectedDate", "") ?: "")
    }
    var journey by remember(selectedDate) { mutableStateOf(loadDailyJourney(appearancePrefs, selectedDate.toString())) }
    var journeyRevision by remember { mutableIntStateOf(0) }
    var selectedWeekStart by remember { mutableStateOf(LocalDate.now().with(DayOfWeek.MONDAY).coerceAtLeast(LocalDate.of(2026, 9, 21))) }

    var showDepartureDialog by remember { mutableStateOf(false) }
    var showReturnHomeDialog by remember { mutableStateOf(false) }
    var showBreakStartDialog by remember { mutableStateOf(false) }
    var showBreakEndDialog by remember { mutableStateOf(false) }
    var showRentalDialog by remember { mutableStateOf(false) }
    var newRental by remember { mutableStateOf(false) }
    var editingRentalIndex by remember { mutableStateOf<Int?>(null) }
    var editingExpense by remember { mutableStateOf<ExpenseItem?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var expenseToDelete by remember { mutableStateOf<ExpenseItem?>(null) }

    var message by remember { mutableStateOf<String?>(null) }
    var showExportImportDialog by remember { mutableStateOf(false) }
    var pendingExpenseImport by remember { mutableStateOf<ExpenseBackup?>(null) }
    var chartMode by remember { mutableIntStateOf(0) } // 0: 每日收支圖表, 1: 時數效益圖表
    var showWeeklyDetails by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        FirebaseSyncManager.onRemoteExpensesUpdated = { remoteExp, deletedIds ->
            val currentLocal = store.load()
            val remainingLocal = currentLocal.filterNot { it.id in deletedIds }
            val mergedMap = remainingLocal.associateBy { it.id }.toMutableMap()
            remoteExp.forEach { mergedMap[it.id] = it }
            val mergedList = mergedMap.values.toList()
            expenses = mergedList
            store.save(mergedList)

            // 自動補充上傳本地未同步花費
            val remoteIdSet = remoteExp.map { it.id }.toSet()
            remainingLocal.filterNot { it.id in remoteIdSet }.forEach {
                FirebaseSyncManager.uploadExpense(it)
            }
        }
        FirebaseSyncManager.onRemoteWorkHourUpdated = { date, dep, ret, pauseStart, pauseEnd ->
            appearancePrefs.edit()
                .putString("departure_time_$date", dep)
                .putString("return_home_time_$date", ret)
                .putString("break_start_time_$date", pauseStart)
                .putString("break_end_time_$date", pauseEnd)
                .apply()
            if (date == selectedDate.toString()) {
                departureTime = dep
                returnHomeTime = ret
                breakStartTime = pauseStart
                breakEndTime = pauseEnd
            }
        }
        FirebaseSyncManager.onRemoteDailyJourneyUpdated = { date, updated ->
            journeyRevision++
            if (date == selectedDate.toString()) journey = updated
        }
        FirebaseSyncManager.onRemoteRentalPlanUpdated = { remotePlan ->
            rentalPlan = remotePlan
            store.saveRentalPlan(remotePlan)
        }
    }

    val expenseJsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) message = runCatching {
            val allWh = collectAllWorkHours(appearancePrefs)
            val jsonStr = exportExpenseData(expenses, rentalPlan, allWh)
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(jsonStr) } ?: error("無法寫入")
            "已匯出花費與工時 JSON（共 ${expenses.size} 筆花費、${allWh.size} 天工時）"
        }.getOrElse { "匯出失敗：${it.message}" }
    }

    val expenseCsvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) message = runCatching {
            val csvStr = exportExpenseCsv(expenses)
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csvStr) } ?: error("無法寫入")
            "已匯出花費明細 CSV（共 ${expenses.size} 筆花費）"
        }.getOrElse { "匯出失敗：${it.message}" }
    }

    val expenseImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= 8 * 1024 * 1024) { "檔案超過 8 MB" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: error("無法讀取檔案")
            require(bytes.size <= 8 * 1024 * 1024) { "檔案超過 8 MB" }
            val backup = importExpenseData(bytes.toString(Charsets.UTF_8))
            pendingExpenseImport = backup
        }.onFailure { message = "匯入失敗：${it.message}" }
    }

    val dateStr = selectedDate.toString()
    val dailyDistanceKm = remember(selectedDate, journey, journeyRevision) {
        effectiveDailyJourney(selectedDate) { day -> loadDailyJourney(appearancePrefs, day.toString()) }.distanceKm()
    }
    val todayExpenses = expenses.filter { it.costCentsOn(selectedDate) > 0 }

    // 當日租車成本（包含月租車分攤的每日成本 + 當日手動單項租車費用）
    val dailyRentalAmortized = rentalPlan.totalCostCentsOn(selectedDate)

    val gasCost = todayExpenses.filter { it.category == "加油" }.sumOf { it.costCentsOn(selectedDate) }
    val parkingCost = todayExpenses.filter { it.category == "停車費" }.sumOf { it.costCentsOn(selectedDate) }
    val manualRentalCost = todayExpenses.filter { it.category == "租車費用" }.sumOf { it.costCentsOn(selectedDate) }
    val rentalCost = dailyRentalAmortized + manualRentalCost
    val otherCost = todayExpenses.filter { it.category == "其他" }.sumOf { it.costCentsOn(selectedDate) }
    val totalCost = gasCost + parkingCost + rentalCost + otherCost

    // 今日載客營收（完成行程實收、小費及月結）
    val dayCompletedRides = rides.filter { it.serviceDate == dateStr && it.completed }
    val todayRevenue = dayCompletedRides.sumOf(::rideOperatingRevenue)

    // 當日工作時間計算 (分鐘)
    val workDurationMins = netWorkMinutes(departureTime, returnHomeTime, breakStartTime, breakEndTime)

    val netIncome = todayRevenue - totalCost

    fun saveExpenses(newList: List<ExpenseItem>) {
        expenses = newList
        store.save(newList)
    }

    // 計算本週區間（週一 至 週日）
    val startOfWeek = selectedWeekStart
    val endOfWeek = startOfWeek.plusDays(6)
    val weekDates = (0..6).map { startOfWeek.plusDays(it.toLong()) }

    // 本週營收與今日採同一計算方式
    val weekCompletedRides = rides.filter { ride ->
        ride.completed && runCatching {
            val d = LocalDate.parse(ride.serviceDate)
            d in startOfWeek..endOfWeek
        }.getOrDefault(false)
    }
    val weekRevenue = weekCompletedRides.sumOf(::rideOperatingRevenue)

    // 本週各類別花費與總支出
    val weekItems = expenses.filter { item -> weekDates.any { item.costCentsOn(it) > 0 } }
    val weekGas = weekItems.filter { it.category == "加油" }.sumOf { item -> weekDates.sumOf { item.costCentsOn(it) } }
    val weekParking = weekItems.filter { it.category == "停車費" }.sumOf { item -> weekDates.sumOf { item.costCentsOn(it) } }
    val weekOther = weekItems.filter { it.category == "其他" }.sumOf { item -> weekDates.sumOf { item.costCentsOn(it) } }
    val weekManualRental = weekItems.filter { it.category == "租車費用" }.sumOf { item -> weekDates.sumOf { item.costCentsOn(it) } }

    // 本週 7 天的每日月租分攤成本
    val weekRentalAmortized = weekDates.sumOf { day ->
        rentalPlan.totalCostCentsOn(day)
    }
    val weekRentalTotal = weekRentalAmortized + weekManualRental
    val weekTotalCost = weekGas + weekParking + weekRentalTotal + weekOther
    val weekNetIncome = weekRevenue - weekTotalCost

    // 本週總工時計算（遍歷這 7 天有設定出門回家的日期）
    val weekWorkDurationMins = weekDates.sumOf { d ->
        val dep = appearancePrefs.getString("departure_time_$d", "") ?: ""
        val ret = appearancePrefs.getString("return_home_time_$d", "") ?: ""
        val pauseStart = appearancePrefs.getString("break_start_time_$d", "") ?: ""
        val pauseEnd = appearancePrefs.getString("break_end_time_$d", "") ?: ""
        netWorkMinutes(dep, ret, pauseStart, pauseEnd) ?: 0
    }

    // 計算每週 7 天每日統計數據 (收入、支出、上下班工時、實際路程時間、完成趟數)
    val weekDailyStats = remember(weekDates, rides, expenses, rentalPlan, departureTime, returnHomeTime, breakStartTime, breakEndTime, selectedDate) {
        val weekDayNames = listOf("一", "二", "三", "四", "五", "六", "日")
        weekDates.mapIndexed { idx, dayDate ->
            val dayDStr = dayDate.toString()
            val dayRides = rides.filter { it.serviceDate == dayDStr }
            val sortedDayRides = dayRides.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
            val dayCompleted = sortedDayRides.filter { it.completed }
            val dRev = dayCompleted.sumOf(::rideOperatingRevenue)

            val dManualExp = expenses.sumOf { it.costCentsOn(dayDate) }
            val dRental = rentalPlan.totalCostCentsOn(dayDate)
            val dExp = dManualExp + dRental
            val dNet = dRev - dExp

            val dep = appearancePrefs.getString("departure_time_$dayDStr", "") ?: ""
            val ret = appearancePrefs.getString("return_home_time_$dayDStr", "") ?: ""
            val pauseStart = appearancePrefs.getString("break_start_time_$dayDStr", "") ?: ""
            val pauseEnd = appearancePrefs.getString("break_end_time_$dayDStr", "") ?: ""
            val dWorkMins = netWorkMinutes(dep, ret, pauseStart, pauseEnd) ?: 0

            val tripSecs = sortedDayRides.mapIndexedNotNull { i, r ->
                if (r.completed) billedRideSeconds(r, sortedDayRides.getOrNull(i - 1)) else null
            }.sum()
            val dTripMins = (tripSecs / 60).toInt()

            WeekDayEfficiencyStat(
                date = dayDate,
                dayName = weekDayNames[idx],
                revenue = dRev,
                expense = dExp,
                netProfit = dNet,
                workDurationMins = dWorkMins,
                tripDurationMins = dTripMins,
                completedCount = dayCompleted.size,
                isSelected = dayDate == selectedDate
            )
        }
    }

    val weekTotalTripMins = weekDailyStats.sumOf { it.tripDurationMins }
    val weekEfficiencyRate = if (weekWorkDurationMins > 0) ((weekTotalTripMins.toDouble() / weekWorkDurationMins) * 100).toInt() else 0
    val weekHourlyNet = if (weekWorkDurationMins > 0) hourlyYuan(weekNetIncome, weekWorkDurationMins) else null
    val weekTripHourlyRev = if (weekTotalTripMins > 0) hourlyYuan(weekRevenue, weekTotalTripMins) else null

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 0. 頂端標題與匯出/匯入按鈕
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "營業收支與工時統計",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { showExportImportDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("備份/匯出匯入", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // 1. 日期切換欄
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    DayNavigation(selectedDate, onSelect = { selectedDate = it }, calendarEnabled = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp))
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FinanceSections(selectedDate, rides, expenses, rentalPlan, onUpdateRides, onOpenRide)
                    OutlinedButton(onClick = { newRental = true; editingRentalIndex = null; showRentalDialog = true }) { Text("新增租車合約") }
                    rentalPlan.archivedPlans.forEachIndexed { index, plan ->
                        TextButton(onClick = { newRental = false; editingRentalIndex = index; showRentalDialog = true }) {
                            Text("租約 ${plan.startDate}～${plan.endDate} · ${plan.model} · ${yuan(plan.feeCents)} 元")
                        }
                    }
                }
            }

            // 2. 週統計
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 頂部週期間標題
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    Icons.Default.DateRange,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text("週統計", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { selectedWeekStart = selectedWeekStart.minusWeeks(1) }, enabled = selectedWeekStart > LocalDate.of(2026, 9, 21)) {
                                Text("◀ 上週")
                            }
                            Text("${startOfWeek.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))}－${endOfWeek.format(DateTimeFormatter.ofPattern("MM/dd"))}", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { selectedWeekStart = selectedWeekStart.plusWeeks(1) }) {
                                Text("下週 ▶")
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                        // 三大核心指標：本週總營收、總支出、淨收益
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // 本週總營收
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.MonetizationOn, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                                    Text("本週總營收", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${yuan(weekRevenue)} 元",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32)
                                )
                                Text("共 ${weekCompletedRides.size} 趟完成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            // 本週總支出
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    Text("本週總成本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${yuan(weekTotalCost)} 元",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text("含租車日分攤", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            // 本週淨利潤
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, tint = if (weekNetIncome >= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    Text("本週營運淨額", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${yuan(weekNetIncome)} 元",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (weekNetIncome >= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = weekHourlyNet?.let { "時薪 $it 元" } ?: "工時未填",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        TextButton(
                            onClick = { showWeeklyDetails = !showWeeklyDetails },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(if (showWeeklyDetails) "收起圖表與明細 ▲" else "展開圖表與明細 ▼")
                        }

                        if (showWeeklyDetails) {
                        // 本週各類別花費明細標籤列
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(14.dp))
                                    Text("油錢 ${yuan(weekGas)}", style = MaterialTheme.typography.labelSmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.LocalParking, contentDescription = null, tint = Color(0xFF1976D2), modifier = Modifier.size(14.dp))
                                    Text("停車 ${yuan(weekParking)}", style = MaterialTheme.typography.labelSmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = Color(0xFF7B1FA2), modifier = Modifier.size(14.dp))
                                    Text("租車 ${yuan(weekRentalTotal)}", style = MaterialTheme.typography.labelSmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.Receipt, contentDescription = null, tint = Color(0xFF5D4037), modifier = Modifier.size(14.dp))
                                    Text("其他 ${yuan(weekOther)}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // 工作效益指標摘要卡片（時數與效益）
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        Text("本週工作效益分析", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }
                                    if (weekWorkDurationMins > 0) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "路程佔工時 $weekEfficiencyRate%",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    // 上下班總工時
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("⏱️ 上下班工時", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        val wHrs = weekWorkDurationMins / 60
                                        val wMins = weekWorkDurationMins % 60
                                        Text(
                                            text = if (weekWorkDurationMins > 0) "${wHrs}h ${wMins}m" else "未填",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    // 實際路程時間
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("🚗 實際載客路程", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        val tHrs = weekTotalTripMins / 60
                                        val tMins = weekTotalTripMins % 60
                                        Text(
                                            text = "${tHrs}h ${tMins}m",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF00897B)
                                        )
                                    }
                                    // 工時淨時薪
                                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                        Text("💡 工時淨時薪", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            text = weekHourlyNet?.let { "$$it / hr" } ?: "-",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (weekNetIncome >= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                                        )
                                        if (weekTripHourlyRev != null) {
                                            Text(
                                                text = "車程 $$weekTripHourlyRev/h",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 圖表類型切換 Segmented Tabs
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (chartMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (chartMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { chartMode = 0 }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 7.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.BarChart,
                                        contentDescription = null,
                                        tint = if (chartMode == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "💰 每日收支圖表",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (chartMode == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (chartMode == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (chartMode == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { chartMode = 1 }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 7.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Timeline,
                                        contentDescription = null,
                                        tint = if (chartMode == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "⏱️ 時數效益圖表",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (chartMode == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // 可視化柱狀圖卡片
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                                // 圖表主體
                                WeeklyBarChartView(
                                    mode = chartMode,
                                    stats = weekDailyStats,
                                    onSelectDate = { selectedDate = it }
                                )

                                Spacer(Modifier.height(8.dp))

                                // 圖例
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (chartMode == 0) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Box(Modifier.size(10.dp).background(Color(0xFF2E7D32), RoundedCornerShape(2.dp)))
                                            Text("收入", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Spacer(Modifier.width(16.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Box(Modifier.size(10.dp).background(Color(0xFFE53935), RoundedCornerShape(2.dp)))
                                            Text("支出(含租車)", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Spacer(Modifier.width(16.dp))
                                        Text("（點擊柱狀切換日期）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Box(Modifier.size(10.dp).background(Color(0xFF1976D2), RoundedCornerShape(2.dp)))
                                            Text("上下班時數", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Spacer(Modifier.width(16.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Box(Modifier.size(10.dp).background(Color(0xFF00897B), RoundedCornerShape(2.dp)))
                                            Text("實際路程時間", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Spacer(Modifier.width(16.dp))
                                        Text("（點擊柱狀切換日期）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                        }

                        // 所選當日效益明細小卡 (Spotlight)
                        val selStat = weekDailyStats.firstOrNull { it.date == selectedDate }
                        if (selStat != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "📌 ${selStat.date.format(DateTimeFormatter.ofPattern("MM/dd"))} (週${selStat.dayName}) 工作效益細節：",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (selStat.completedCount > 0) {
                                            Text(
                                                text = "完成 ${selStat.completedCount} 趟",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "收支：收入 ${yuan(selStat.revenue)} - 支出 ${yuan(selStat.expense)} = ${if (selStat.netProfit >= 0) "+${yuan(selStat.netProfit)}" else "${yuan(selStat.netProfit)}"} 元",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (selStat.netProfit >= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        val wh = "${selStat.workDurationMins / 60}h ${selStat.workDurationMins % 60}m"
                                        val th = "${selStat.tripDurationMins / 60}h ${selStat.tripDurationMins % 60}m"
                                        val rate = if (selStat.workDurationMins > 0) "${(selStat.tripDurationMins * 100 / selStat.workDurationMins)}%" else "-"
                                        Text(
                                            text = "時數：上下班 $wh · 實際路程 $th (佔比 $rate)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            }

            item {
                Text("日統計", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            // 3. 今日工作時間
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text("今日工作時間", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            if (workDurationMins != null) {
                                val hrs = workDurationMins / 60
                                val mins = workDurationMins % 60
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "${hrs} 小時 ${mins} 分",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 當日出門時間按鈕
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (departureTime.isNotBlank()) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, if (departureTime.isNotBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showDepartureDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("🚗 出門", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = departureTime.ifBlank { "點此設定" },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (departureTime.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            // 當日回家時間按鈕
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (returnHomeTime.isNotBlank()) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, if (returnHomeTime.isNotBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showReturnHomeDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("🏠 回家", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = returnHomeTime.ifBlank { "點此設定" },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (returnHomeTime.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                        Text("工時＝出門至回家－休息時間；休息起訖都設定後才扣除。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val deductedMins = (netWorkMinutes(departureTime, returnHomeTime) ?: 0) - (workDurationMins ?: 0)
                        if (deductedMins > 0) {
                            Text("已扣除休息 ${deductedMins / 60} 小時 ${deductedMins % 60} 分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showBreakStartDialog = true }, modifier = Modifier.weight(1f)) {
                                Text("休息開始 ${breakStartTime.ifBlank { "設定" }}")
                            }
                            OutlinedButton(onClick = { showBreakEndDialog = true }, modifier = Modifier.weight(1f)) {
                                Text("休息結束 ${breakEndTime.ifBlank { "設定" }}")
                            }
                        }
                    }
                }
            }

            // 3. 營業成本總覽（點擊租車 icon 開啟月租試算 popup）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("今日收支與成本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("今日總成本：${yuan(totalCost)} 元", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.error)
                        }

                        // 各類別成本細目（點擊租車可設定月租換算）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            CostPill(icon = Icons.Default.LocalGasStation, label = "加油", amount = gasCost, tint = Color(0xFFE65100))
                            CostPill(icon = Icons.Default.LocalParking, label = "停車", amount = parkingCost, tint = Color(0xFF1976D2))
                            // 租車按鈕可點擊跳出月租設定 popup
                            CostPill(
                                icon = Icons.Default.DirectionsCar,
                                label = "租車(點擊)",
                                amount = rentalCost,
                                tint = Color(0xFF7B1FA2),
                                isClickable = true,
                                onClick = { showRentalDialog = true }
                            )
                            CostPill(icon = Icons.Default.Receipt, label = "其他", amount = otherCost, tint = Color(0xFF5D4037))
                        }

                        // 月租車設定狀態橫條
                        if (rentalPlan.feeCents > 0) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showRentalDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = Color(0xFF7B1FA2), modifier = Modifier.size(16.dp))
                                        Text(
                                            text = "租車：${rentalPlan.model.ifBlank { "車輛" }} · ${rentalPlan.startDate.ifBlank { rentalPlan.periodMonth }}～${rentalPlan.endDate.ifBlank { rentalPlan.periodMonth }} · 總費 ${yuan(rentalPlan.feeCents)} 元",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Text(
                                        text = "日分攤 ${yuan(rentalPlan.dailyCostCents)} 元/天 ⚙️",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF7B1FA2)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                        // 今日營收、淨收入、時薪與里程
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("今日載客營收", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${yuan(todayRevenue)} 元", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("營運淨額", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = "${yuan(netIncome)} 元",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (netIncome >= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("平均時薪", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val hourlyNet = if (workDurationMins != null && workDurationMins > 0) {
                                    hourlyYuan(netIncome, workDurationMins)
                                } else null
                                Text(
                                    text = hourlyNet?.let { "$it 元/時" } ?: "--",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("今日里程", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(dailyDistanceKm?.let { "$it km" } ?: "--",
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // 5. 新增花費項目與當日明細
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "花費項目 (${todayExpenses.size} 筆)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Button(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新增花費項目")
                    }
                }
            }

            // 6. 當日花費列表
            if (todayExpenses.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Receipt,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                "今日尚無手動花費記錄",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(todayExpenses, key = { it.id }) { item ->
                    ExpenseItemCard(
                        item = item,
                        onEdit = { editingExpense = item },
                        onDelete = { expenseToDelete = item }
                    )
                }
            }


        }
    }

    // 設定當日出門時間對話框
    if (showDepartureDialog) {
        TimeSettingDialog(
            title = "設定出門時間（${selectedDate.format(DateTimeFormatter.ofPattern("MM/dd"))}）",
            initialValue = departureTime,
            onDismiss = { showDepartureDialog = false },
            onConfirm = {
                departureTime = it
                appearancePrefs.edit().putString("departure_time_$selectedDate", it).apply()
                FirebaseSyncManager.uploadWorkHour(selectedDate.toString(), it, returnHomeTime, breakStartTime, breakEndTime)
                showDepartureDialog = false
            },
            onClear = {
                departureTime = ""
                appearancePrefs.edit().putString("departure_time_$selectedDate", "").apply()
                FirebaseSyncManager.uploadWorkHour(selectedDate.toString(), "", returnHomeTime, breakStartTime, breakEndTime)
                showDepartureDialog = false
            }
        )
    }

    // 設定當日回家時間對話框
    if (showReturnHomeDialog) {
        TimeSettingDialog(
            title = "設定回家時間（${selectedDate.format(DateTimeFormatter.ofPattern("MM/dd"))}）",
            initialValue = returnHomeTime,
            onDismiss = { showReturnHomeDialog = false },
            onConfirm = {
                returnHomeTime = it
                appearancePrefs.edit().putString("return_home_time_$selectedDate", it).apply()
                FirebaseSyncManager.uploadWorkHour(selectedDate.toString(), departureTime, it, breakStartTime, breakEndTime)
                showReturnHomeDialog = false
            },
            onClear = {
                returnHomeTime = ""
                appearancePrefs.edit().putString("return_home_time_$selectedDate", "").apply()
                FirebaseSyncManager.uploadWorkHour(selectedDate.toString(), departureTime, "", breakStartTime, breakEndTime)
                showReturnHomeDialog = false
            }
        )
    }

    if (showBreakStartDialog) {
        TimeSettingDialog(
            title = "設定休息開始（${selectedDate.format(DateTimeFormatter.ofPattern("MM/dd"))}）",
            initialValue = breakStartTime,
            onDismiss = { showBreakStartDialog = false },
            onConfirm = {
                breakStartTime = it
                appearancePrefs.edit().putString("break_start_time_$selectedDate", it).apply()
                FirebaseSyncManager.uploadWorkHour(selectedDate.toString(), departureTime, returnHomeTime, it, breakEndTime)
                showBreakStartDialog = false
            },
            onClear = {
                breakStartTime = ""
                appearancePrefs.edit().putString("break_start_time_$selectedDate", "").apply()
                FirebaseSyncManager.uploadWorkHour(selectedDate.toString(), departureTime, returnHomeTime, "", breakEndTime)
                showBreakStartDialog = false
            },
            supportingHint = "休息起訖都設定後，重疊的時間會從工時扣除",
            clearLabel = "清除休息開始"
        )
    }
    if (showBreakEndDialog) {
        TimeSettingDialog(
            title = "設定休息結束（${selectedDate.format(DateTimeFormatter.ofPattern("MM/dd"))}）",
            initialValue = breakEndTime,
            onDismiss = { showBreakEndDialog = false },
            onConfirm = {
                breakEndTime = it
                appearancePrefs.edit().putString("break_end_time_$selectedDate", it).apply()
                FirebaseSyncManager.uploadWorkHour(selectedDate.toString(), departureTime, returnHomeTime, breakStartTime, it)
                showBreakEndDialog = false
            },
            onClear = {
                breakEndTime = ""
                appearancePrefs.edit().putString("break_end_time_$selectedDate", "").apply()
                FirebaseSyncManager.uploadWorkHour(selectedDate.toString(), departureTime, returnHomeTime, breakStartTime, "")
                showBreakEndDialog = false
            },
            supportingHint = "休息起訖都設定後，重疊的時間會從工時扣除",
            clearLabel = "清除休息結束"
        )
    }

    // 租車月租設定與每日成本換算 Dialog
    if (showRentalDialog) {
        RentalPlanDialog(
            initial = if (newRental) MonthlyRentalPlan() else editingRentalIndex?.let { rentalPlan.archivedPlans[it] } ?: rentalPlan,
            currentMonth = selectedDate.format(DateTimeFormatter.ofPattern("yyyy-MM")),
            onDismiss = { showRentalDialog = false; newRental = false; editingRentalIndex = null },
            onSave = { updated ->
                val saved = if (newRental) updated.copy(archivedPlans = rentalPlan.allPlans().filter { it.feeCents > 0 })
                    else if (editingRentalIndex != null) rentalPlan.copy(archivedPlans = rentalPlan.archivedPlans.mapIndexed { i, plan -> if (i == editingRentalIndex) updated else plan })
                    else updated.copy(archivedPlans = rentalPlan.archivedPlans)
                rentalPlan = saved
                store.saveRentalPlan(saved)
                FirebaseSyncManager.uploadRentalPlan(saved)
                newRental = false; editingRentalIndex = null
                showRentalDialog = false
            },
            onClear = {
                val saved = if (editingRentalIndex != null) rentalPlan.copy(archivedPlans = rentalPlan.archivedPlans.filterIndexed { i, _ -> i != editingRentalIndex })
                    else MonthlyRentalPlan(archivedPlans = rentalPlan.archivedPlans)
                rentalPlan = saved
                store.saveRentalPlan(saved)
                FirebaseSyncManager.uploadRentalPlan(saved)
                newRental = false; editingRentalIndex = null
                showRentalDialog = false
            }
        )
    }

    // 新增花費對話框
    if (showAddDialog) {
        ExpenseEditDialog(
            title = "新增花費",
            initial = ExpenseItem(
                date = dateStr,
                category = "加油",
                amountCents = 0,
                time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            ),
            onDismiss = { showAddDialog = false },
            onConfirm = { newItem ->
                saveExpenses(expenses + newItem)
                FirebaseSyncManager.uploadExpense(newItem)
                showAddDialog = false
            }
        )
    }

    // 修改花費對話框
    editingExpense?.let { target ->
        ExpenseEditDialog(
            title = "修改花費",
            initial = target,
            onDismiss = { editingExpense = null },
            onConfirm = { updated ->
                saveExpenses(expenses.map { if (it.id == updated.id) updated else it })
                FirebaseSyncManager.uploadExpense(updated)
                editingExpense = null
            }
        )
    }

    // 刪除確認對話框
    expenseToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { expenseToDelete = null },
            title = { Text("刪除花費記錄？") },
            text = { Text("確定要刪除「${target.category} ${yuan(target.amountCents)} 元」這筆記錄嗎？\n對應的成本與付款紀錄也會移除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveExpenses(expenses.filterNot { it.id == target.id })
                        FirebaseSyncManager.deleteExpense(target.id)
                        expenseToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("刪除")
                }
            },
            dismissButton = {
                TextButton(onClick = { expenseToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 花費資料匯出與匯入選單
    if (showExportImportDialog) {
        AlertDialog(
            onDismissRequest = { showExportImportDialog = false },
            title = { Text("花費資料匯出與匯入") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("支援備份及轉移花費記錄、月租車設定及出門/回家工時。", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    TextButton(
                        onClick = {
                            showExportImportDialog = false
                            expenseJsonExport.launch("driver-expenses.json")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("匯出全部花費與工時 JSON（可還原）")
                        }
                    }
                    TextButton(
                        onClick = {
                            showExportImportDialog = false
                            expenseCsvExport.launch("driver-expenses.csv")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("匯出花費明細 CSV（Excel/分析用）")
                        }
                    }
                    TextButton(
                        onClick = {
                            showExportImportDialog = false
                            expenseImporter.launch(arrayOf("application/json", "text/plain"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("匯入花費與工時 JSON")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportImportDialog = false }) {
                    Text("關閉")
                }
            }
        )
    }

    // 匯入衝突處理與確認對話框
    pendingExpenseImport?.let { incoming ->
        val existingIds = expenses.map { it.id }.toSet()
        val conflicts = incoming.expenses.count { it.id in existingIds }
        AlertDialog(
            onDismissRequest = { pendingExpenseImport = null },
            title = { Text("匯入花費與工時資料") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("檔案包含：")
                    Text("• 花費記錄：${incoming.expenses.size} 筆（ID 重複衝突 $conflicts 筆）")
                    if (incoming.rentalPlan.feeCents > 0) {
                        Text("• 月租車設定：${incoming.rentalPlan.model}（月租 ${yuan(incoming.rentalPlan.feeCents)} 元）")
                    }
                    if (incoming.workHours.isNotEmpty()) {
                        Text("• 每日出門/回家工時：${incoming.workHours.size} 天")
                    }
                    Text("請選擇合併方式：", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val merged = expenses.filterNot { old -> incoming.expenses.any { it.id == old.id } } + incoming.expenses
                    saveExpenses(merged)
                    if (incoming.rentalPlan.feeCents > 0 || incoming.rentalPlan.model.isNotBlank()) {
                        rentalPlan = incoming.rentalPlan
                        store.saveRentalPlan(incoming.rentalPlan)
                    }
                    val editor = appearancePrefs.edit()
                    incoming.workHours.forEach { wh ->
                        if (wh.departureTime.isNotBlank()) editor.putString("departure_time_${wh.date}", wh.departureTime)
                        if (wh.returnHomeTime.isNotBlank()) editor.putString("return_home_time_${wh.date}", wh.returnHomeTime)
                        if (wh.breakStartTime.isNotBlank()) editor.putString("break_start_time_${wh.date}", wh.breakStartTime)
                        if (wh.breakEndTime.isNotBlank()) editor.putString("break_end_time_${wh.date}", wh.breakEndTime)
                        if (wh.startPoint.isNotBlank()) editor.putString("day_start_point_${wh.date}", wh.startPoint)
                        if (wh.endPoint.isNotBlank()) editor.putString("day_end_point_${wh.date}", wh.endPoint)
                        if (wh.startOdometer.isNotBlank()) editor.putString("day_start_odometer_${wh.date}", wh.startOdometer)
                        if (wh.endOdometer.isNotBlank()) editor.putString("day_end_odometer_${wh.date}", wh.endOdometer)
                    }
                    editor.apply()
                    journey = loadDailyJourney(appearancePrefs, selectedDate.toString())
                    journeyRevision++
                    departureTime = appearancePrefs.getString("departure_time_$selectedDate", "") ?: ""
                    returnHomeTime = appearancePrefs.getString("return_home_time_$selectedDate", "") ?: ""
                    breakStartTime = appearancePrefs.getString("break_start_time_$selectedDate", "") ?: ""
                    breakEndTime = appearancePrefs.getString("break_end_time_$selectedDate", "") ?: ""
                    message = "已匯入完成（採用匯入版本）"
                    pendingExpenseImport = null
                }) {
                    Text("使用匯入版本")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val merged = expenses + incoming.expenses.filterNot { it.id in existingIds }
                    saveExpenses(merged)
                    if (rentalPlan.feeCents == 0L && incoming.rentalPlan.feeCents > 0) {
                        rentalPlan = incoming.rentalPlan
                        store.saveRentalPlan(incoming.rentalPlan)
                    }
                    val editor = appearancePrefs.edit()
                    incoming.workHours.forEach { wh ->
                        if (!appearancePrefs.contains("departure_time_${wh.date}") && wh.departureTime.isNotBlank()) {
                            editor.putString("departure_time_${wh.date}", wh.departureTime)
                        }
                        if (!appearancePrefs.contains("return_home_time_${wh.date}") && wh.returnHomeTime.isNotBlank()) {
                            editor.putString("return_home_time_${wh.date}", wh.returnHomeTime)
                        }
                        if (!appearancePrefs.contains("break_start_time_${wh.date}") && wh.breakStartTime.isNotBlank()) {
                            editor.putString("break_start_time_${wh.date}", wh.breakStartTime)
                        }
                        if (!appearancePrefs.contains("break_end_time_${wh.date}") && wh.breakEndTime.isNotBlank()) {
                            editor.putString("break_end_time_${wh.date}", wh.breakEndTime)
                        }
                        if (!appearancePrefs.contains("day_start_point_${wh.date}") && wh.startPoint.isNotBlank()) {
                            editor.putString("day_start_point_${wh.date}", wh.startPoint)
                        }
                        if (!appearancePrefs.contains("day_end_point_${wh.date}") && wh.endPoint.isNotBlank()) {
                            editor.putString("day_end_point_${wh.date}", wh.endPoint)
                        }
                        if (!appearancePrefs.contains("day_start_odometer_${wh.date}") && wh.startOdometer.isNotBlank()) {
                            editor.putString("day_start_odometer_${wh.date}", wh.startOdometer)
                        }
                        if (!appearancePrefs.contains("day_end_odometer_${wh.date}") && wh.endOdometer.isNotBlank()) {
                            editor.putString("day_end_odometer_${wh.date}", wh.endOdometer)
                        }
                    }
                    editor.apply()
                    journey = loadDailyJourney(appearancePrefs, selectedDate.toString())
                    journeyRevision++
                    departureTime = appearancePrefs.getString("departure_time_$selectedDate", "") ?: ""
                    returnHomeTime = appearancePrefs.getString("return_home_time_$selectedDate", "") ?: ""
                    breakStartTime = appearancePrefs.getString("break_start_time_$selectedDate", "") ?: ""
                    breakEndTime = appearancePrefs.getString("break_end_time_$selectedDate", "") ?: ""
                    message = "已匯入完成（保留本機衝突項）"
                    pendingExpenseImport = null
                }) {
                    Text("保留本機版本")
                }
            }
        )
    }

    // 提示訊息對話框
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("確定") } }
        )
    }
}

@Composable
private fun CostPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    amount: Long,
    tint: Color,
    isClickable: Boolean = false,
    onClick: () -> Unit = {}
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = if (isClickable) Modifier.clickable { onClick() } else Modifier
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (isClickable) tint else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = "${yuan(amount)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (isClickable) tint else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ExpenseItemCard(
    item: ExpenseItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val (icon, color) = when (item.category) {
        "加油" -> Pair(Icons.Default.LocalGasStation, Color(0xFFE65100))
        "停車費" -> Pair(Icons.Default.LocalParking, Color(0xFF1976D2))
        "租車費用" -> Pair(Icons.Default.DirectionsCar, Color(0xFF7B1FA2))
        else -> Pair(Icons.Default.Receipt, Color(0xFF5D4037))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = color.copy(alpha = 0.15f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.category, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (item.time.isNotBlank()) {
                            Text(
                                if (item.category == "加油") "加油時間 ${item.time}" else item.time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (item.note.isNotBlank()) {
                        Text(item.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${yuan(item.amountCents)} 元",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.error
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "刪除",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 租車月租設定與每日成本試算 Dialog
 */
@Composable
private fun RentalPlanDialog(
    initial: MonthlyRentalPlan,
    currentMonth: String,
    onDismiss: () -> Unit,
    onSave: (MonthlyRentalPlan) -> Unit,
    onClear: () -> Unit
) {
    var model by remember { mutableStateOf(initial.model) }
    val defaultMonth = runCatching { java.time.YearMonth.parse(initial.periodMonth.ifBlank { currentMonth }) }
        .getOrElse { java.time.YearMonth.parse(currentMonth) }
    var startDate by remember { mutableStateOf(initial.startDate.ifBlank { defaultMonth.atDay(1).toString() }) }
    var endDate by remember { mutableStateOf(initial.endDate.ifBlank { defaultMonth.atEndOfMonth().toString() }) }
    var feeText by remember { mutableStateOf(if (initial.feeCents > 0) yuan(initial.feeCents) else "") }
    var note by remember { mutableStateOf(initial.note) }
    var paidDate by remember { mutableStateOf(initial.paidDate) }
    var paymentMethod by remember { mutableStateOf(initial.paymentMethod) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val monthlyFee = cents(feeText)
    val start = runCatching { LocalDate.parse(startDate.trim()) }.getOrNull()
    val end = runCatching { LocalDate.parse(endDate.trim()) }.getOrNull()
    val days = if (start != null && end != null && !end.isBefore(start)) ChronoUnit.DAYS.between(start, end).toInt() + 1 else 0
    val computedDaily = if (days > 0 && monthlyFee > 0) monthlyFee / days else 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = Color(0xFF7B1FA2))
                Text("租車期間與每日成本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "請填寫租車起始日與結束日；費用只會計入這段期間，含起訖日。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 租車型號
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("租車車型 / 型號") },
                    placeholder = { Text("例如：Toyota Sienta 福祉車") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 租車日期區間
                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("起始日") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = endDate,
                    onValueChange = { endDate = it },
                    label = { Text("結束日") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(paidDate, { paidDate = it }, label = { Text("租金付款日（YYYY-MM-DD；未付留空）") }, singleLine = true)
                Row { listOf("現金", "轉帳").forEach { v ->
                    FilterChip(paymentMethod == v, { paymentMethod = v }, label = { Text(v) })
                    Spacer(Modifier.width(8.dp))
                } }
                Text("租期總費用只分攤一次，請勿再新增同一份租金為一般費用。", style = MaterialTheme.typography.bodySmall)
                // 月租總費用
                OutlinedTextField(
                    value = feeText,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() || it == '.' }) {
                            feeText = input
                            errorMessage = null
                        }
                    },
                    label = { Text("租期總費用") },
                    suffix = { Text("元") },
                    placeholder = { Text("例如：24000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("租期共 $days 天（含起訖日）", style = MaterialTheme.typography.bodySmall)

                // 每日成本即時計算結果卡片
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF7B1FA2).copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "💡 每日租車成本試算：",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF7B1FA2)
                        )
                        Text(
                            text = "${yuan(monthlyFee)} 元 ÷ $days 天 ＝ 每日約 ${yuan(computedDaily)} 元（餘分自起始日補足）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF7B1FA2)
                        )
                    }
                }

                // 備註
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("備註") },
                    placeholder = { Text("例如：格上租車月合約、保險含在內") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (paidDate.isNotBlank() && runCatching { LocalDate.parse(paidDate) }.isFailure) {
                    errorMessage = "請填有效付款日期，未付請留空"; return@Button
                }
                if (Money.parse(feeText) == null || monthlyFee <= 0) {
                    errorMessage = "請輸入有效的月租費用"
                    return@Button
                }
                if (days <= 0) {
                    errorMessage = "請輸入有效起訖日（YYYY-MM-DD），結束日不可早於起始日"
                    return@Button
                }
                onSave(
                    MonthlyRentalPlan(
                        model = model.trim(),
                        periodMonth = "",
                        startDate = startDate.trim(),
                        endDate = endDate.trim(),
                        paidDate = paidDate.trim(), paymentMethod = paymentMethod,
                        feeCents = monthlyFee,
                        daysInMonth = days,
                        dailyCostCents = computedDaily,
                        note = note.trim()
                    )
                )
            }) {
                Text("儲存並計算")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (initial.feeCents > 0) {
                    TextButton(
                        onClick = onClear,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("清除設定")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
private fun ExpenseEditDialog(
    title: String,
    initial: ExpenseItem,
    onDismiss: () -> Unit,
    onConfirm: (ExpenseItem) -> Unit
) {
    var category by remember { mutableStateOf(initial.category) }
    var amountText by remember { mutableStateOf(if (initial.amountCents > 0) yuan(initial.amountCents) else "") }
    var timeText by remember { mutableStateOf(initial.time) }
    var noteText by remember { mutableStateOf(initial.note) }
    var costEndDate by remember { mutableStateOf(initial.costEndDate) }
    var paidDate by remember { mutableStateOf(initial.paidDate) }
    var paymentMethod by remember { mutableStateOf(initial.paymentMethod) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    val categories = listOf("加油", "停車費", "租車費用", "其他")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 類別選擇 chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(34.dp)
                        )
                    }
                }

                // 金額
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() || it == '.' }) {
                            amountText = input
                            errorMessage = null
                        }
                    },
                    label = { Text("費用金額") },
                    suffix = { Text("元") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("成本起始日：${initial.date}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(costEndDate, { costEndDate = it }, label = { Text("成本期間末日（YYYY-MM-DD；單日留空）") }, singleLine = true)
                OutlinedTextField(paidDate, { paidDate = it }, label = { Text("實際付款日（YYYY-MM-DD；未付留空）") }, singleLine = true)
                Row { listOf("現金", "轉帳").forEach { v ->
                    FilterChip(paymentMethod == v, { paymentMethod = v }, label = { Text(v) })
                    Spacer(Modifier.width(8.dp))
                } }
                // 時間（特別對加油提供明確時間標示）
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it },
                    label = { Text(if (category == "加油") "加油時間 (例如 14:30)" else "記錄時間 (例如 14:30)") },
                    placeholder = { Text("例如 14:30") },
                    singleLine = true,
                    supportingText = {
                        Text(if (category == "加油") "記錄加油的時間點" else "選填時間")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // 備註
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("備註") },
                    placeholder = {
                        Text(when (category) {
                            "加油" -> "例如：中油95、30公升"
                            "停車費" -> "例如：台大醫院地下停車場"
                            "租車費用" -> "例如：臨時額外加租"
                            else -> "例如：洗車、過路費"
                        })
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (paidDate.isNotBlank() && runCatching { LocalDate.parse(paidDate) }.isFailure) {
                    errorMessage = "請填有效付款日期，未付請留空"; return@Button
                }
                if (costEndDate.isNotBlank() && runCatching { LocalDate.parse(costEndDate) >= LocalDate.parse(initial.date) }.getOrDefault(false).not()) {
                    errorMessage = "成本末日需有效且不可早於起始日"; return@Button
                }
                val amt = Money.parse(amountText)?.cents
                if (amt == null || amt <= 0) {
                    errorMessage = "請輸入有效金額（需大於 0）"
                    return@Button
                }
                val normalizedTime = if (timeText.isBlank()) "" else normalizeTime(timeText)
                if (normalizedTime == null) {
                    errorMessage = "時間請填有效 24 小時格式，例如 14:30、1430 或 14：30"
                    return@Button
                }
                onConfirm(
                    initial.copy(
                        category = category,
                        amountCents = amt, paidDate = paidDate.trim(), paymentMethod = paymentMethod, costEndDate = costEndDate.trim(),
                        time = normalizedTime,
                        note = noteText.trim()
                    )
                )
            }) {
                Text("儲存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 每週營運每日效益統計資料結構
 */
internal data class WeekDayEfficiencyStat(
    val date: LocalDate,
    val dayName: String,
    val revenue: Long,
    val expense: Long,
    val netProfit: Long,
    val workDurationMins: Int,
    val tripDurationMins: Int,
    val completedCount: Int,
    val isSelected: Boolean
)

/**
 * 每週營運視覺化柱狀圖（支援收支對比與時數效益切換）
 */
@Composable
private fun WeeklyBarChartView(
    mode: Int, // 0: 每日收支, 1: 時數效益
    stats: List<WeekDayEfficiencyStat>,
    onSelectDate: (LocalDate) -> Unit
) {
    val maxMoney = remember(stats) {
        maxOf(stats.maxOfOrNull { maxOf(it.revenue, it.expense) } ?: 0L, 100000L)
    }
    val maxMins = remember(stats) {
        maxOf(stats.maxOfOrNull { maxOf(it.workDurationMins, it.tripDurationMins) } ?: 0, 120) // 至少以 2 小時為基準
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        stats.forEach { dayStat ->
            val isSelected = dayStat.isSelected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSelectDate(dayStat.date) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 上方數值標籤
                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (mode == 0) {
                        val net = dayStat.netProfit
                        if (dayStat.revenue > 0 || dayStat.expense > 0) {
                            val netStr = when {
                                net >= 100000 -> "+${(net / 100000.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }}k"
                                net <= -100000 -> "${(net / 100000.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }}k"
                                net > 0 -> "+${yuan(net)}"
                                else -> "${yuan(net)}"
                            }
                            Text(
                                text = netStr,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (net >= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                maxLines = 1
                            )
                        } else {
                            Text("-", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    } else {
                        if (dayStat.workDurationMins > 0 || dayStat.tripDurationMins > 0) {
                            val text = when {
                                dayStat.tripDurationMins >= 60 -> "%.1fh".format(dayStat.tripDurationMins / 60.0)
                                dayStat.tripDurationMins > 0 -> "${dayStat.tripDurationMins}m"
                                else -> "-"
                            }
                            Text(
                                text = text,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00897B),
                                maxLines = 1
                            )
                        } else {
                            Text("-", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }

                // 柱狀圖柱體容器
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    HorizontalDivider(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 1.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        if (mode == 0) {
                            // 每日收入柱
                            val revFrac = (dayStat.revenue.toFloat() / maxMoney).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .width(8.dp)
                                    .fillMaxHeight(fraction = if (dayStat.revenue > 0) revFrac.coerceAtLeast(0.06f) else 0.02f)
                                    .background(
                                        if (dayStat.revenue > 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                                    )
                            )
                            Spacer(Modifier.width(2.dp))
                            // 每日支出柱
                            val expFrac = (dayStat.expense.toFloat() / maxMoney).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .width(8.dp)
                                    .fillMaxHeight(fraction = if (dayStat.expense > 0) expFrac.coerceAtLeast(0.06f) else 0.02f)
                                    .background(
                                        if (dayStat.expense > 0) Color(0xFFE53935) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                                    )
                            )
                        } else {
                            // 上下班工時柱
                            val workFrac = (dayStat.workDurationMins.toFloat() / maxMins).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .width(8.dp)
                                    .fillMaxHeight(fraction = if (dayStat.workDurationMins > 0) workFrac.coerceAtLeast(0.06f) else 0.02f)
                                    .background(
                                        if (dayStat.workDurationMins > 0) Color(0xFF1976D2) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                                    )
                            )
                            Spacer(Modifier.width(2.dp))
                            // 實際載客路程柱
                            val tripFrac = (dayStat.tripDurationMins.toFloat() / maxMins).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .width(8.dp)
                                    .fillMaxHeight(fraction = if (dayStat.tripDurationMins > 0) tripFrac.coerceAtLeast(0.06f) else 0.02f)
                                    .background(
                                        if (dayStat.tripDurationMins > 0) Color(0xFF00897B) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                                    )
                            )
                        }
                    }
                }

                // 底部星期與日期標籤
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = dayStat.dayName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = dayStat.date.dayOfMonth.toString(),
                            fontSize = 10.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
