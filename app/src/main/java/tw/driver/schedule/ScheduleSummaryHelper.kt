package tw.driver.schedule

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 每日行程總結表生成與匯出分享工具 (文字檔 TXT & 圖片 PNG & 系統分享)
 */
object ScheduleSummaryHelper {

    fun generateScheduleText(date: LocalDate, rides: List<RideOrder>): String {
        val sorted = rides.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
        val dayOfWeekName = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "一"
            DayOfWeek.TUESDAY -> "二"
            DayOfWeek.WEDNESDAY -> "三"
            DayOfWeek.THURSDAY -> "四"
            DayOfWeek.FRIDAY -> "五"
            DayOfWeek.SATURDAY -> "六"
            DayOfWeek.SUNDAY -> "日"
            else -> ""
        }
        val dateHeader = "${date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))} (週$dayOfWeekName)"
        val sb = StringBuilder()
        sb.appendLine("【Driver Flow 行程總結】")
        sb.appendLine("📅 日期：$dateHeader")
        sb.appendLine("🚗 今日行程：共 ${sorted.size} 趟（已完成 ${sorted.count { it.completed }} 趟）")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")

        if (sorted.isEmpty()) {
            sb.appendLine("今日尚無接送排程行程。")
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("祝您 行車平安！")
            return sb.toString()
        }

        sorted.forEachIndexed { index, r ->
            val num = "${index + 1}."
            val time = r.pickupTime.ifBlank { "時間待定" }
            val name = r.customer.ifBlank { "乘客" }
            val type = if (r.returnRide) "回程" else "去程"
            val categoryStr = if (r.category.isNotBlank() && r.category != "未分類") " [${r.category}]" else ""
            val statusStr = if (r.completed) " (已完成)" else ""

            sb.appendLine("$num $time $name ($type)$categoryStr$statusStr")
            sb.appendLine("   上車：${addressForDisplay(r.pickup).ifBlank { "未填上車地點" }}")
            sb.appendLine("   下車：${addressForDisplay(r.destination).ifBlank { "未填下車地點" }}")
            if (index < sorted.size - 1) {
                sb.appendLine()
            }
        }

        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("祝 行車平安、順利！")
        return sb.toString()
    }

    /**
     * 將行程表繪製成清晰、美觀的高解析度圖片 (1080px 寬)
     */
    fun generateScheduleBitmap(date: LocalDate, rides: List<RideOrder>): Bitmap {
        val sorted = rides.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
        val width = 1080
        val margin = 48f
        val cardWidth = width - margin * 2

        val dayOfWeekName = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "一"
            DayOfWeek.TUESDAY -> "二"
            DayOfWeek.WEDNESDAY -> "三"
            DayOfWeek.THURSDAY -> "四"
            DayOfWeek.FRIDAY -> "五"
            DayOfWeek.SATURDAY -> "六"
            DayOfWeek.SUNDAY -> "日"
            else -> ""
        }
        val dateStr = "${date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))} (週$dayOfWeekName)"

        // Paint 設定
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E0F2F1")
            textSize = 34f
        }
        val headerCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#176B5A")
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5F7F8")
        }
        val itemBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        val itemBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val tripHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
        }
        val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val addrLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 30f
        }
        val addrTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 32f
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }

        // 計算圖片高度
        val headerHeight = 220f
        val itemHeight = 220f
        val spacing = 24f
        val footerHeight = 120f
        val totalHeight = (headerHeight + margin + (if (sorted.isEmpty()) 200f else (itemHeight + spacing) * sorted.size) + footerHeight).toInt()

        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, width.toFloat(), totalHeight.toFloat(), bgPaint)

        // 1. 繪製頂部 Header
        val headerRect = RectF(margin, margin, width - margin, margin + headerHeight)
        canvas.drawRoundRect(headerRect, 28f, 28f, headerCardPaint)
        canvas.drawText("Driver Flow 行程總結", margin + 36f, margin + 80f, titlePaint)
        canvas.drawText("$dateStr · 共 ${sorted.size} 趟接送行程", margin + 36f, margin + 140f, subTitlePaint)

        var curY = headerRect.bottom + spacing

        if (sorted.isEmpty()) {
            val emptyRect = RectF(margin, curY, width - margin, curY + 160f)
            canvas.drawRoundRect(emptyRect, 20f, 20f, itemBgPaint)
            canvas.drawRoundRect(emptyRect, 20f, 20f, itemBorderPaint)
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 34f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("本日無接送排程行程", width / 2f, curY + 95f, emptyPaint)
            curY += 160f + spacing
        } else {
            // 2. 依序繪製每個 Case 行程卡片
            sorted.forEachIndexed { idx, r ->
                val cardRect = RectF(margin, curY, width - margin, curY + itemHeight)
                canvas.drawRoundRect(cardRect, 20f, 20f, itemBgPaint)
                canvas.drawRoundRect(cardRect, 20f, 20f, itemBorderPaint)

                // 卡片內左側指示條
                val indicatorPaint = Paint().apply {
                    color = if (r.completed) Color.parseColor("#22C55E") else Color.parseColor("#0284C7")
                }
                canvas.drawRoundRect(RectF(margin, curY, margin + 14f, curY + itemHeight), 20f, 20f, indicatorPaint)

                // 標題列：序號、時間、乘客
                val headerY = curY + 54f
                val titleStr = "${idx + 1}.  ${r.pickupTime.ifBlank { "待定" }}   ${r.customer.ifBlank { "乘客" }}"
                canvas.drawText(titleStr, margin + 36f, headerY, tripHeaderPaint)

                // 標籤 (去/回程、類別)
                var tagX = width - margin - 30f
                val typeTag = if (r.returnRide) "回程" else "去程"
                val catTag = if (r.category.isNotBlank() && r.category != "未分類") r.category else ""
                val tags = listOfNotNull(catTag.takeIf { it.isNotBlank() }, typeTag)

                tags.reversed().forEach { t ->
                    val textW = tagTextPaint.measureText(t)
                    val tagRect = RectF(tagX - textW - 24f, curY + 22f, tagX, curY + 62f)
                    tagBgPaint.color = when (t) {
                        "補助", "補助單" -> Color.parseColor("#DCFCE7")
                        "自費" -> Color.parseColor("#FEF3C7")
                        "回程" -> Color.parseColor("#F1F5F9")
                        else -> Color.parseColor("#E0F2FE")
                    }
                    tagTextPaint.color = when (t) {
                        "補助", "補助單" -> Color.parseColor("#15803D")
                        "自費" -> Color.parseColor("#B45309")
                        else -> Color.parseColor("#0369A1")
                    }
                    canvas.drawRoundRect(tagRect, 10f, 10f, tagBgPaint)
                    canvas.drawText(t, tagRect.left + 12f, tagRect.bottom - 10f, tagTextPaint)
                    tagX -= (textW + 36f)
                }

                // 上車地點
                val pickupY = headerY + 56f
                canvas.drawText("上車", margin + 36f, pickupY, addrLabelPaint)
                val pickupText = addressForDisplay(r.pickup).ifBlank { "未填上車地點" }
                val maxLen = 28
                val shortPickup = if (pickupText.length > maxLen) pickupText.take(maxLen) + "…" else pickupText
                canvas.drawText(shortPickup, margin + 116f, pickupY, addrTextPaint)

                // 下車地點
                val destY = pickupY + 52f
                canvas.drawText("下車", margin + 36f, destY, addrLabelPaint)
                val destText = addressForDisplay(r.destination).ifBlank { "未填下車地點" }
                val shortDest = if (destText.length > maxLen) destText.take(maxLen) + "…" else destText
                canvas.drawText(shortDest, margin + 116f, destY, addrTextPaint)

                curY += itemHeight + spacing
            }
        }

        // 3. Footer
        canvas.drawText("Driver Flow · 專業接送服務 · 祝 行車平安", width / 2f, curY + 50f, footerPaint)

        return bitmap
    }

    /**
     * 以系統分享文字 (可發至 LINE、簡訊、WhatsApp 等)
     */
    fun shareText(context: Context, text: String, title: String = "分享行程總結表") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "今日行程總結")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    /**
     * 以系統分享圖片 (可發至 LINE、相簿、Messenger 等)
     */
    fun shareBitmap(context: Context, bitmap: Bitmap, date: LocalDate) {
        runCatching {
            val cacheFolder = File(context.cacheDir, "shared_schedules").apply { mkdirs() }
            val file = File(cacheFolder, "schedule_${date}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享行程圖片"))
        }.onFailure {
            Toast.makeText(context, "分享圖片失敗：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 將圖片儲存至使用者指定的 Uri (透過 CreateDocument 回傳)
     */
    fun saveBitmapToUri(context: Context, bitmap: Bitmap, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } != null
        }.getOrDefault(false)
    }

    /**
     * 將純文字檔儲存至使用者指定的 Uri
     */
    fun saveTextToUri(context: Context, text: String, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(text)
            } != null
        }.getOrDefault(false)
    }
}
