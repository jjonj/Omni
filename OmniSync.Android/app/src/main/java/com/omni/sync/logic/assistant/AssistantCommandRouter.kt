package com.omni.sync.logic.assistant

data class ParsedAssistantInput(
    val commandText: String,
    val runAtMillis: Long? = null,
    val modifierText: String? = null
)

data class AndroidAssistantCommand(
    val id: String,
    val runAtMillis: Long? = null
)

object AssistantCommandRouter {
    private val relativeSuffix = Regex(
        """\s+\bin\s+(?<amount>\d+(?:\.\d+)?|one|two|three|four|five|six|seven|eight|nine|ten)\s*(?<unit>seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\s*[.!?]?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val halfHourSuffix = Regex(
        """\s+\bin\s+half\s+(?:an?\s+)?hour\s*[.!?]?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val wakePcPatterns = listOf(
        Regex("""^(?:please\s+)?(?:turn|switch|power)\s+(?:my\s+|the\s+)?pc\s+on\s*[.!?]?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:please\s+)?(?:turn|switch|power)\s+on\s+(?:my\s+|the\s+)?pc\s*[.!?]?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:please\s+)?wake\s+(?:up\s+)?(?:my\s+|the\s+)?pc\s*[.!?]?\s*$""", RegexOption.IGNORE_CASE)
    )
    private val wordNumbers = mapOf(
        "one" to 1.0,
        "two" to 2.0,
        "three" to 3.0,
        "four" to 4.0,
        "five" to 5.0,
        "six" to 6.0,
        "seven" to 7.0,
        "eight" to 8.0,
        "nine" to 9.0,
        "ten" to 10.0
    )

    fun parseModifiers(
        text: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ParsedAssistantInput {
        val trimmed = text.trim()

        val half = halfHourSuffix.find(trimmed)
        if (half != null && half.range.last == trimmed.lastIndex) {
            return ParsedAssistantInput(
                commandText = trimmed.substring(0, half.range.first).trim(),
                runAtMillis = nowMillis + 30 * 60 * 1000L,
                modifierText = half.value.trim()
            )
        }

        val match = relativeSuffix.find(trimmed)
        if (match == null || match.range.last != trimmed.lastIndex) {
            return ParsedAssistantInput(trimmed)
        }

        val amountText = match.groups["amount"]!!.value
        val amount = wordNumbers[amountText.lowercase()] ?: amountText.toDouble()
        val unit = match.groups["unit"]!!.value.lowercase()
        val delayMillis = when {
            unit.startsWith("sec") -> (amount * 1_000L).toLong()
            unit.startsWith("min") -> (amount * 60_000L).toLong()
            unit.startsWith("hour") || unit.startsWith("hr") -> (amount * 3_600_000L).toLong()
            else -> (amount * 86_400_000L).toLong()
        }

        return ParsedAssistantInput(
            commandText = trimmed.substring(0, match.range.first).trim(),
            runAtMillis = nowMillis + delayMillis,
            modifierText = match.value.trim()
        )
    }

    fun resolve(
        text: String,
        nowMillis: Long = System.currentTimeMillis()
    ): AndroidAssistantCommand? {
        val parsed = parseModifiers(text, nowMillis)
        if (wakePcPatterns.any { it.matches(parsed.commandText) }) {
            return AndroidAssistantCommand(
                id = "wake.pc",
                runAtMillis = parsed.runAtMillis
            )
        }
        return null
    }
}
