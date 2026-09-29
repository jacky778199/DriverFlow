package tw.driver.schedule

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Firebase 多裝置資料即時同步管理器
 * 支援排程訂單 (Rides)、營業花費 (Expenses)、月租車設定 (RentalPlan) 及每日上下班工時 (WorkHours) 的雙向即時同步
 */
object FirebaseSyncManager {
    private const val TAG = "FirebaseSyncManager"

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _syncStatus = MutableStateFlow("未登入")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private var ridesListener: ListenerRegistration? = null
    private var expensesListener: ListenerRegistration? = null
    private var workHoursListener: ListenerRegistration? = null
    private var rentalPlanListener: ListenerRegistration? = null

    // 回呼介面（當雲端有變更時通知 UI 及本機儲存）
    var onRemoteRidesUpdated: ((remoteRides: List<RideOrder>, deletedIds: Set<Long>) -> Unit)? = null
    var onRemoteExpensesUpdated: ((remoteExpenses: List<ExpenseItem>, deletedIds: Set<Long>) -> Unit)? = null
    var onRemoteWorkHourUpdated: ((date: String, departure: String, returnHome: String, breakStart: String, breakEnd: String) -> Unit)? = null
    var onRemoteRentalPlanUpdated: ((MonthlyRentalPlan) -> Unit)? = null

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        _currentUser.value = auth.currentUser
        _syncStatus.value = if (auth.currentUser != null) "已連線" else "未登入"

        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _currentUser.value = user
            if (user != null) {
                _syncStatus.value = "已連線 (${user.email ?: "司機帳號"})"
                startListening(user.uid, context)
            } else {
                _syncStatus.value = "未登入"
                stopListening()
            }
        }
    }

    /**
     * 登入現有司機帳號（支援完整 Email 或簡稱代碼）
     */
    fun signIn(
        accountInput: String,
        passwordInput: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val email = normalizeEmail(accountInput)
        auth.signInWithEmailAndPassword(email, passwordInput)
            .addOnSuccessListener {
                _currentUser.value = it.user
                _syncStatus.value = "登入成功"
                onSuccess()
            }
            .addOnFailureListener {
                val errorMsg = when {
                    it.message?.contains("user-not-found", true) == true -> "此帳號尚未註冊，請點擊「註冊帳號」"
                    it.message?.contains("wrong-password", true) == true -> "密碼錯誤，請重新輸入"
                    it.message?.contains("invalid-credential", true) == true -> "帳號或密碼不正確"
                    it.message?.contains("network", true) == true -> "網路連線失敗，請檢查網路"
                    else -> it.localizedMessage ?: "登入失敗"
                }
                onError(errorMsg)
            }
    }

    /**
     * 註冊新司機帳號
     */
    fun register(
        accountInput: String,
        passwordInput: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val email = normalizeEmail(accountInput)
        if (passwordInput.length < 6) {
            onError("密碼長度至少需 6 個字元")
            return
        }
        auth.createUserWithEmailAndPassword(email, passwordInput)
            .addOnSuccessListener {
                _currentUser.value = it.user
                _syncStatus.value = "註冊並登入成功"
                onSuccess()
            }
            .addOnFailureListener {
                val errorMsg = when {
                    it.message?.contains("email-already-in-use", true) == true -> "此帳號已被註冊，請直接點「登入」"
                    it.message?.contains("weak-password", true) == true -> "密碼強度不足，請輸入至少 6 位字元"
                    else -> it.localizedMessage ?: "註冊失敗"
                }
                onError(errorMsg)
            }
    }

    fun signOut() {
        auth.signOut()
        _currentUser.value = null
        _syncStatus.value = "未登入"
        stopListening()
    }

    /**
     * 簡化輸入：若使用者只輸入 driver123，自動補齊為 driver123@driver.app 以便通過 Firebase Email 格式檢驗
     */
    private fun normalizeEmail(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.contains("@")) trimmed else "$trimmed@driver.app"
    }

    // ==========================================
    // 雲端即時雙向監聽（Realtime Snapshot Listeners）
    // ==========================================

    private fun startListening(uid: String, context: Context) {
        stopListening()
        val userDoc = firestore.collection("users").document(uid)
        val appContext = context.applicationContext

        // 1. 監聽排程訂單：收到雲端變更立即寫入本機 OrderStore
        ridesListener = userDoc.collection("rides").addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Rides listen error", error)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val deletedIds = mutableSetOf<Long>()
                val remoteOrders = mutableListOf<RideOrder>()
                for (doc in snapshot.documents) {
                    val data = doc.data ?: continue
                    val isDeleted = data["isDeleted"] as? Boolean ?: false
                    val id = (data["id"] as? Number)?.toLong() ?: doc.id.toLongOrNull()
                    if (isDeleted) {
                        if (id != null) deletedIds.add(id)
                    } else {
                        val ride = mapToRide(data)
                        if (ride.id != 0L) remoteOrders.add(ride)
                    }
                }
                try {
                    val orderStore = OrderStore(appContext)
                    val localRides = orderStore.load()
                    val remainingLocal = localRides.filterNot { it.id in deletedIds }
                    val mergedMap = remainingLocal.associateBy { it.id }.toMutableMap()
                    remoteOrders.forEach { mergedMap[it.id] = it }
                    val mergedList = mergedMap.values.toList()
                    orderStore.save(mergedList)

                    // 自動補充上傳本地未同步訂單
                    val remoteIdSet = remoteOrders.map { it.id }.toSet()
                    remainingLocal.filterNot { it.id in remoteIdSet }.forEach { uploadRide(it) }

                    onRemoteRidesUpdated?.invoke(mergedList, deletedIds)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist remote rides", e)
                    onRemoteRidesUpdated?.invoke(remoteOrders, deletedIds)
                }
            }
        }

        // 2. 監聽花費記錄：收到雲端變更立即寫入本機 ExpenseStore
        expensesListener = userDoc.collection("expenses").addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Expenses listen error", error)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val deletedIds = mutableSetOf<Long>()
                val remoteExpenses = mutableListOf<ExpenseItem>()
                for (doc in snapshot.documents) {
                    val data = doc.data ?: continue
                    val isDeleted = data["isDeleted"] as? Boolean ?: false
                    val id = (data["id"] as? Number)?.toLong() ?: doc.id.toLongOrNull()
                    if (isDeleted) {
                        if (id != null) deletedIds.add(id)
                    } else {
                        val exp = mapToExpense(data)
                        if (exp.id != 0L) remoteExpenses.add(exp)
                    }
                }
                try {
                    val expStore = ExpenseStore(appContext)
                    val localExpenses = expStore.load()
                    val remainingLocal = localExpenses.filterNot { it.id in deletedIds }
                    val mergedMap = remainingLocal.associateBy { it.id }.toMutableMap()
                    remoteExpenses.forEach { mergedMap[it.id] = it }
                    val mergedList = mergedMap.values.toList()
                    expStore.save(mergedList)

                    // 自動補充上傳本地未同步花費
                    val remoteIdSet = remoteExpenses.map { it.id }.toSet()
                    remainingLocal.filterNot { it.id in remoteIdSet }.forEach { uploadExpense(it) }

                    onRemoteExpensesUpdated?.invoke(mergedList, deletedIds)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist remote expenses", e)
                    onRemoteExpensesUpdated?.invoke(remoteExpenses, deletedIds)
                }
            }
        }

        // 3. 監聽上下班出門與回家工時：收到雲端變更立即更新 SharedPreferences
        workHoursListener = userDoc.collection("work_hours").addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "WorkHours listen error", error)
                return@addSnapshotListener
            }
            val prefs = appContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            snapshot?.documents?.forEach { doc ->
                val data = doc.data ?: return@forEach
                val date = data["date"] as? String ?: doc.id
                val dep = data["departureTime"] as? String ?: ""
                val ret = data["returnHomeTime"] as? String ?: ""
                val pauseStart = data["breakStartTime"] as? String ?: ""
                val pauseEnd = data["breakEndTime"] as? String ?: ""
                editor.putString("departure_time_$date", dep)
                editor.putString("return_home_time_$date", ret)
                editor.putString("break_start_time_$date", pauseStart)
                editor.putString("break_end_time_$date", pauseEnd)
                onRemoteWorkHourUpdated?.invoke(date, dep, ret, pauseStart, pauseEnd)
            }
            editor.apply()
        }

        // 4. 監聽月租車設定：收到變更立即儲存至 ExpenseStore
        rentalPlanListener = userDoc.collection("settings").document("rental_plan").addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "RentalPlan listen error", error)
                return@addSnapshotListener
            }
            snapshot?.data?.let { data ->
                val plan = MonthlyRentalPlan(
                    model = data["model"] as? String ?: "",
                    periodMonth = data["periodMonth"] as? String ?: "",
                    monthlyFee = (data["monthlyFee"] as? Number)?.toInt() ?: 0,
                    daysInMonth = (data["daysInMonth"] as? Number)?.toInt() ?: 30,
                    dailyCost = (data["dailyCost"] as? Number)?.toInt() ?: 0,
                    note = data["note"] as? String ?: ""
                )
                try {
                    ExpenseStore(appContext).saveRentalPlan(plan)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist remote rental plan", e)
                }
                onRemoteRentalPlanUpdated?.invoke(plan)
            }
        }
    }

    private fun stopListening() {
        ridesListener?.remove()
        ridesListener = null
        expensesListener?.remove()
        expensesListener = null
        workHoursListener?.remove()
        workHoursListener = null
        rentalPlanListener?.remove()
        rentalPlanListener = null
    }

    // ==========================================
    // 上傳或同步單筆資料至雲端（本地改動時自動呼叫）
    // ==========================================

    fun uploadRide(ride: RideOrder) {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid)
            .collection("rides").document(ride.id.toString())
            .set(rideToMap(ride, isDeleted = false), SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "Failed to upload ride ${ride.id}", it) }
    }

    fun deleteRide(rideId: Long) {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid)
            .collection("rides").document(rideId.toString())
            .set(mapOf("id" to rideId, "isDeleted" to true, "updatedAt" to System.currentTimeMillis()), SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "Failed to delete ride $rideId", it) }
    }

    fun uploadExpense(expense: ExpenseItem) {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid)
            .collection("expenses").document(expense.id.toString())
            .set(expenseToMap(expense, isDeleted = false), SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "Failed to upload expense ${expense.id}", it) }
    }

    fun deleteExpense(expenseId: Long) {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid)
            .collection("expenses").document(expenseId.toString())
            .set(mapOf("id" to expenseId, "isDeleted" to true, "updatedAt" to System.currentTimeMillis()), SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "Failed to delete expense $expenseId", it) }
    }

    fun uploadWorkHour(date: String, departure: String, returnHome: String, breakStart: String? = null, breakEnd: String? = null) {
        val uid = auth.currentUser?.uid ?: return
        val fields = mutableMapOf<String, Any>(
            "date" to date,
            "departureTime" to departure,
            "returnHomeTime" to returnHome,
            "updatedAt" to System.currentTimeMillis()
        )
        if (breakStart != null) fields["breakStartTime"] = breakStart
        if (breakEnd != null) fields["breakEndTime"] = breakEnd
        firestore.collection("users").document(uid)
            .collection("work_hours").document(date)
            .set(fields, SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "Failed to upload work hour for $date", it) }
    }

    fun uploadRentalPlan(plan: MonthlyRentalPlan) {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid)
            .collection("settings").document("rental_plan")
            .set(mapOf(
                "model" to plan.model,
                "periodMonth" to plan.periodMonth,
                "monthlyFee" to plan.monthlyFee,
                "daysInMonth" to plan.daysInMonth,
                "dailyCost" to plan.dailyCost,
                "note" to plan.note,
                "updatedAt" to System.currentTimeMillis()
            ), SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "Failed to upload rental plan", it) }
    }

    /**
     * 首次登入或點擊「立即同步所有本機資料」：將本機所有歷史資料一次性備份上傳至雲端
     */
    fun syncAllLocalToCloud(
        rides: List<RideOrder>,
        expenses: List<ExpenseItem>,
        rentalPlan: MonthlyRentalPlan,
        workHours: List<WorkHourRecord>,
        onComplete: (String) -> Unit
    ) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            onComplete("尚未登入同步帳號")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val batch = firestore.batch()
                val userDoc = firestore.collection("users").document(uid)

                rides.forEach { r ->
                    val ref = userDoc.collection("rides").document(r.id.toString())
                    batch.set(ref, rideToMap(r), SetOptions.merge())
                }
                expenses.forEach { e ->
                    val ref = userDoc.collection("expenses").document(e.id.toString())
                    batch.set(ref, expenseToMap(e), SetOptions.merge())
                }
                workHours.forEach { wh ->
                    val ref = userDoc.collection("work_hours").document(wh.date)
                    batch.set(ref, mapOf(
                        "date" to wh.date,
                        "departureTime" to wh.departureTime,
                        "returnHomeTime" to wh.returnHomeTime,
                        "breakStartTime" to wh.breakStartTime,
                        "breakEndTime" to wh.breakEndTime,
                        "updatedAt" to System.currentTimeMillis()
                    ), SetOptions.merge())
                }
                val planRef = userDoc.collection("settings").document("rental_plan")
                batch.set(planRef, mapOf(
                    "model" to rentalPlan.model,
                    "periodMonth" to rentalPlan.periodMonth,
                    "monthlyFee" to rentalPlan.monthlyFee,
                    "daysInMonth" to rentalPlan.daysInMonth,
                    "dailyCost" to rentalPlan.dailyCost,
                    "note" to rentalPlan.note,
                    "updatedAt" to System.currentTimeMillis()
                ), SetOptions.merge())

                batch.commit().addOnSuccessListener {
                    onComplete("已成功同步 ${rides.size} 筆排程、${expenses.size} 筆花費與 ${workHours.size} 天工時至雲端！")
                }.addOnFailureListener {
                    onComplete("雲端上傳失敗：${it.localizedMessage}")
                }
            } catch (e: Exception) {
                onComplete("同步失敗：${e.message}")
            }
        }
    }

    // ==========================================
    // 資料轉換映射輔助函式
    // ==========================================

    private fun rideToMap(ride: RideOrder, isDeleted: Boolean = false): Map<String, Any?> = mapOf(
        "id" to ride.id,
        "date" to ride.date,
        "pickupTime" to ride.pickupTime,
        "customer" to ride.customer,
        "contact" to ride.contact,
        "pickup" to ride.pickup,
        "destination" to ride.destination,
        "wheelchair" to ride.wheelchair,
        "notes" to ride.notes,
        "raw" to ride.raw,
        "tentative" to ride.tentative,
        "returnRide" to ride.returnRide,
        "timeFlexible" to ride.timeFlexible,
        "calendarTime" to ride.calendarTime,
        "needsAddressCheck" to ride.needsAddressCheck,
        "contactPhone" to ride.contactPhone,
        "passengerPhone" to ride.passengerPhone,
        "uncertainties" to ride.uncertainties,
        "wheelchairUnknown" to ride.wheelchairUnknown,
        "fare" to ride.fare,
        "bookingId" to ride.bookingId,
        "serviceDate" to ride.serviceDate,
        "category" to ride.category,
        "completed" to ride.completed,
        "received" to ride.received,
        "rideMinutes" to ride.rideMinutes,
        "transferMinutes" to ride.transferMinutes,
        "pickupPlaceId" to ride.pickupPlaceId,
        "destinationPlaceId" to ride.destinationPlaceId,
        "routeEstimate" to ride.routeEstimate,
        "subsidyDue" to ride.subsidyDue,
        "tip" to ride.tip,
        "updatedAt" to System.currentTimeMillis(),
        "isDeleted" to isDeleted
    )

    private fun mapToRide(map: Map<String, Any?>): RideOrder = RideOrder(
        id = (map["id"] as? Number)?.toLong() ?: 0L,
        date = map["date"] as? String ?: "",
        pickupTime = map["pickupTime"] as? String ?: "",
        customer = map["customer"] as? String ?: "",
        contact = map["contact"] as? String ?: "",
        pickup = map["pickup"] as? String ?: "",
        destination = map["destination"] as? String ?: "",
        wheelchair = map["wheelchair"] as? Boolean ?: true,
        notes = map["notes"] as? String ?: "",
        raw = map["raw"] as? String ?: "",
        tentative = map["tentative"] as? Boolean ?: false,
        returnRide = map["returnRide"] as? Boolean ?: false,
        timeFlexible = map["timeFlexible"] as? Boolean ?: false,
        calendarTime = map["calendarTime"] as? String ?: "",
        needsAddressCheck = map["needsAddressCheck"] as? Boolean ?: false,
        contactPhone = map["contactPhone"] as? String ?: "",
        passengerPhone = map["passengerPhone"] as? String ?: "",
        uncertainties = map["uncertainties"] as? String ?: "",
        wheelchairUnknown = map["wheelchairUnknown"] as? Boolean ?: false,
        fare = map["fare"] as? String ?: "",
        bookingId = map["bookingId"] as? String ?: "",
        serviceDate = map["serviceDate"] as? String ?: "",
        category = map["category"] as? String ?: "未分類",
        completed = map["completed"] as? Boolean ?: false,
        received = map["received"] as? String ?: "",
        rideMinutes = map["rideMinutes"] as? String ?: "",
        transferMinutes = map["transferMinutes"] as? String ?: "",
        pickupPlaceId = map["pickupPlaceId"] as? String ?: "",
        destinationPlaceId = map["destinationPlaceId"] as? String ?: "",
        routeEstimate = map["routeEstimate"] as? String ?: "",
        subsidyDue = map["subsidyDue"] as? String ?: "",
        tip = map["tip"] as? String ?: ""
    )

    private fun expenseToMap(expense: ExpenseItem, isDeleted: Boolean = false): Map<String, Any?> = mapOf(
        "id" to expense.id,
        "date" to expense.date,
        "category" to expense.category,
        "amount" to expense.amount,
        "time" to expense.time,
        "note" to expense.note,
        "updatedAt" to System.currentTimeMillis(),
        "isDeleted" to isDeleted
    )

    private fun mapToExpense(map: Map<String, Any?>): ExpenseItem = ExpenseItem(
        id = (map["id"] as? Number)?.toLong() ?: 0L,
        date = map["date"] as? String ?: "",
        category = map["category"] as? String ?: "其他",
        amount = (map["amount"] as? Number)?.toInt() ?: 0,
        time = map["time"] as? String ?: "",
        note = map["note"] as? String ?: ""
    )
}
