package tw.driver.schedule

import java.text.Normalizer

data class SenderAlertRule(val chat: String, val sender: String)

object SenderAlertRules {
    private fun normalized(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        .lowercase()

    fun parse(text: String): List<SenderAlertRule> {
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        require(lines.size <= 50) { "最多設定 50 組群組與發送者" }
        return lines.map { line ->
            val parts = line.split('|')
            require(parts.size == 2) { "每行請用 | 分隔群組名稱與發送者" }
            val chat = parts[0].trim()
            val sender = parts[1].trim()
            require(chat.isNotEmpty() && sender.isNotEmpty() && chat.length <= 100 && sender.length <= 100) {
                "群組名稱與發送者都必填，且各不得超過 100 字"
            }
            SenderAlertRule(chat, sender)
        }.distinctBy { normalized(it.chat) to normalized(it.sender) }
    }

    fun validate(input: List<SenderAlertRule>): List<SenderAlertRule> {
        require(input.size <= 50) { "最多設定 50 組群組與發送者" }
        val rules = input.map { SenderAlertRule(it.chat.trim(), it.sender.trim()) }
        require(rules.all { it.chat.isNotEmpty() && it.sender.isNotEmpty() && it.chat.length <= 100 && it.sender.length <= 100 }) { "請填寫每組的群組名稱與發送者，各不得超過 100 字" }
        return rules.distinctBy { normalized(it.chat) to normalized(it.sender) }
    }

    fun matches(message: LineMessage, rules: List<SenderAlertRule>): Boolean = rules.any {
        normalized(message.chat) == normalized(it.chat) && normalized(message.sender) == normalized(it.sender)
    }
}
