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
    private const val amount =
        """(?:a|an|\d+(?:\.\d+)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty(?:[-\s](?:one|two|three|four|five|six|seven|eight|nine))?|thirty(?:[-\s](?:one|two|three|four|five|six|seven|eight|nine))?|forty(?:[-\s](?:one|two|three|four|five|six|seven|eight|nine))?|fifty(?:[-\s](?:one|two|three|four|five|six|seven|eight|nine))?)"""
    private const val unit = """(?:seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)"""

    private val hourAndHalfSuffix = Regex(
        """\s+in\s+($amount)\s+hours?\s+and\s+(?:a\s+)?half(?:\s+hour)?\s*[.!?]?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val compoundSuffix = Regex(
        """\s+in\s+($amount)\s*($unit)\s+and\s+($amount)\s*($unit)\s*[.!?]?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val halfHourSuffix = Regex(
        """\s+in\s+half\s+(?:an?\s+)?hour\s*[.!?]?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val relativeSuffix = Regex(
        """\s+in\s+($amount)\s*($unit)\s*[.!?]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val wakePcPatterns = listOf(
        Regex("""^(?:please\s+)?(?:turn|switch|power)\s+(?:my\s+|the\s+)?(?:pc|computer)\s+on\s*[.!?]?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:please\s+)?(?:turn|switch|power)\s+on\s+(?:my\s+|the\s+)?(?:pc|computer)\s*[.!?]?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:please\s+)?wake\s+(?:up\s+)?(?:my\s+|the\s+)?(?:pc|computer)\s*[.!?]?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:please\s+)?(?:boot|start)\s+(?:up\s+)?(?:my\s+|the\s+)?(?:pc|computer)\s*[.!?]?\s*$""", RegexOption.IGNORE_CASE)
    )

    private val numberWords = mapOf(
        "zero" to 0.0, "one" to 1.0, "two" to 2.0, "three" to 3.0,
        "four" to 4.0, "five" to 5.0, "six" to 6.0, "seven" to 7.0,
        "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0,
        "twelve" to 12.0, "thirteen" to 13.0, "fourteen" to 14.0,
        "fifteen" to 15.0, "sixteen" to 16.0, "seventeen" to 17.0,
        "eighteen" to 18.0, "nineteen" to 19.0, "twenty" to 20.0,
        "thirty" to 30.0, "forty" to 40.0, "fifty" to 50.0
    )
    fun parseModifiers(
        text: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ParsedAssistantInput {
        val trimmed = text.trim()

        hourAndHalfSuffix.find(trimmed)?.let { match ->
            val hours = parseAmount(match.groupValues[1])
            return parsed(trimmed, match, nowMillis + ((hours + 0.5) * 3_600_000.0).toLong())
        }

        compoundSuffix.find(trimmed)?.let { match ->
            val first = toDelayMillis(parseAmount(match.groupValues[1]), match.groupValues[2])
            val second = toDelayMillis(parseAmount(match.groupValues[3]), match.groupValues[4])
            return parsed(trimmed, match, nowMillis + first + second)
        }

        halfHourSuffix.find(trimmed)?.let { match ->
            return parsed(trimmed, match, nowMillis + 1_800_000L)
        }

        relativeSuffix.find(trimmed)?.let { match ->
            val delay = toDelayMillis(parseAmount(match.groupValues[1]), match.groupValues[2])
            return parsed(trimmed, match, nowMillis + delay)
        }

        return ParsedAssistantInput(trimmed)
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

    private fun parsed(
        text: String,
        match: MatchResult,
        runAtMillis: Long
    ) = ParsedAssistantInput(
        commandText = text.substring(0, match.range.first).trim(),
        runAtMillis = runAtMillis,
        modifierText = match.value.trim()
    )

    private fun parseAmount(value: String): Double {
        val normalized = value.trim().lowercase()
        if (normalized == "a" || normalized == "an") return 1.0
        normalized.toDoubleOrNull()?.let { return it }
        return normalized
            .split(Regex("""[-\s]+"""))
            .filter { it.isNotBlank() }
            .sumOf { part ->
                numberWords[part]
                    ?: throw IllegalArgumentException("Unsupported spoken number: $value")
            }
    }

    private fun toDelayMillis(amount: Double, unitValue: String): Long {
        val unit = unitValue.lowercase()
        return when {
            unit.startsWith("sec") -> (amount * 1_000.0).toLong()
            unit.startsWith("min") -> (amount * 60_000.0).toLong()
            unit.startsWith("hour") || unit.startsWith("hr") ->
                (amount * 3_600_000.0).toLong()
            else -> (amount * 86_400_000.0).toLong()
        }
    }
}
