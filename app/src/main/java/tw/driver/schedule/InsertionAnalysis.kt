package tw.driver.schedule

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate

internal data class InsertionAnalysisEntry(
    val case: InsertionCase? = null,
    val result: InsertionResult? = null,
    val status: String = "等待自動分析…"
)

/** Process-local cache shared by the receiver and every Message screen instance. Never sends replies. */
internal object InsertionAnalysis {
    @Volatile var foreground = false
    val entries = MutableStateFlow<Map<Pair<String, Long>, InsertionAnalysisEntry>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val slots = Semaphore(2)
    private val tasks = mutableMapOf<Pair<String, Long>, Deferred<InsertionAnalysisEntry>>()

    @Synchronized fun save(source: String, seq: Long, entry: InsertionAnalysisEntry) {
        entries.value = (entries.value + ((source to seq) to entry)).entries.toList().takeLast(200).associate { it.toPair() }
    }

    fun enqueue(context: Context, source: String, message: LineMessage) {
        task(context.applicationContext, source, message, false)
    }

    suspend fun analyze(context: Context, source: String, message: LineMessage, force: Boolean = false) =
        task(context.applicationContext, source, message, force).await()

    @Synchronized private fun task(context: Context, source: String, message: LineMessage, force: Boolean): Deferred<InsertionAnalysisEntry> {
        val key = source to message.seq
        tasks[key]?.takeIf { it.isActive }?.let { return it }
        if (!force) entries.value[key]?.let { return CompletableDeferred(it) }
        save(source, message.seq, InsertionAnalysisEntry())
        val task = scope.async(start = CoroutineStart.LAZY) {
            slots.withPermit {
                var entry = InsertionAnalysisEntry(status = "自動解析起訖點與接客時間…")
                save(source, message.seq, entry)
                try {
                    val parsed = InsertionParser.extract(message.content, LocalDate.now())
                    entry = entry.copy(case = parsed)
                    save(source, message.seq, entry)
                    val evaluator = InsertionEvaluator(GoogleRouteClient.create(context),
                        { query, _ -> error("「$query」有多個地點，請開啟評估選擇") },
                        {
                            check(foreground) { "已解析；請開啟評估取得現在位置" }
                            currentMessageLocation(context)
                        }, { progress -> save(source, message.seq, entry.copy(status = progress)) })
                    val rides = OrderStore(context).load()
                    val result = evaluator.evaluate(parsed, rides, { OrderStore(context).load() })
                    entry = entry.copy(result = result, status = "自動評估完成")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { entry = entry.copy(status = e.message ?: "請開啟評估補齊資料") }
                save(source, message.seq, entry)
                entry
            }
        }
        tasks[key] = task
        task.invokeOnCompletion { synchronized(this) { if (tasks[key] === task) tasks.remove(key) } }
        task.start()
        return task
    }
}
