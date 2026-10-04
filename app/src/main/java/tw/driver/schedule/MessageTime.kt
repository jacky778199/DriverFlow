package tw.driver.schedule

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val taiwanMessageTime = DateTimeFormatter.ofPattern("MM/dd HH:mm")
    .withZone(ZoneId.of("Asia/Taipei"))

internal fun messageTimeTaiwan(timestamp: Long, serverTime: String): String {
    val instant = when {
        timestamp > 0 -> runCatching { Instant.ofEpochSecond(timestamp) }.getOrNull()
        else -> runCatching { Instant.parse(serverTime) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(serverTime).toInstant() }.getOrNull()
    }
    return instant?.let(taiwanMessageTime::format) ?: serverTime.ifBlank { "—" }
}

internal fun messageAgeSeconds(timestamp: Long, serverTime: String, nowMillis: Long): Long? {
    val instant = if (timestamp > 0) runCatching { Instant.ofEpochSecond(timestamp) }.getOrNull()
        else runCatching { Instant.parse(serverTime) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(serverTime).toInstant() }.getOrNull()
    return instant?.let { java.time.Duration.between(it, Instant.ofEpochMilli(nowMillis)).seconds }
}

internal fun messageMinutesAgo(timestamp: Long, serverTime: String, nowMillis: Long): String {
    val seconds = messageAgeSeconds(timestamp, serverTime, nowMillis) ?: return "時間未知"
    if (seconds < 0) return "時間待確認"
    return "${seconds / 60} mins ago"
}

internal fun messageIsRecent(timestamp: Long, serverTime: String, nowMillis: Long): Boolean =
    messageAgeSeconds(timestamp, serverTime, nowMillis)?.let { it in 0 until 300 } ?: false
