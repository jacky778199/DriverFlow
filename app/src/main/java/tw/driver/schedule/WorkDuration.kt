package tw.driver.schedule

/** 出門至回家，扣除與這段工作時間重疊的休息；回家或休息結束可在隔日。 */
internal fun netWorkMinutes(
    departure: String,
    returnHome: String,
    breakStart: String = "",
    breakEnd: String = ""
): Int? {
    val start = minuteOfDay(departure) ?: return null
    val home = minuteOfDay(returnHome) ?: return null
    val finish = if (home >= start) home else home + 1440
    val raw = finish - start
    val pauseStart = minuteOfDay(breakStart) ?: return raw
    val pauseEnd = minuteOfDay(breakEnd) ?: return raw
    if (pauseStart == pauseEnd) return raw
    val pauseFinish = if (pauseEnd <= pauseStart) pauseEnd + 1440 else pauseEnd
    val overlap = listOf(-1440, 0, 1440).maxOf { shift ->
        (minOf(finish, pauseFinish + shift) - maxOf(start, pauseStart + shift)).coerceAtLeast(0)
    }
    return (raw - overlap).coerceAtLeast(0)
}
