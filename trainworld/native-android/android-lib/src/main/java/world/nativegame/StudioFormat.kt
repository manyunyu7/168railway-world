// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The web's short figures (game/format.ts, game/points.ts) as the Indonesian game writes them:
 * `Intl.NumberFormat("id", {notation: "compact"})` gives "5 rb", "1,84 jt", "8 M", "1,2 T".
 */
internal object StudioFormat {
    private val locale: Locale = Locale.forLanguageTag("id-ID")
    private val UNITS = listOf(1e12 to "T", 1e9 to "M", 1e6 to "jt", 1e3 to "rb")

    /** Web `formatCompact(id, v, sig)`: `v` to `sig` significant digits in its compact unit. */
    fun compact(v: Double, sig: Int): String {
        val digits = sig.coerceIn(1, 21)
        val round = { x: Double -> BigDecimal(x).round(MathContext(digits, RoundingMode.HALF_EVEN)).toDouble() }
        val a = abs(round(v))
        val (scale, unit) = UNITS.firstOrNull { a >= it.first } ?: (1.0 to "")
        val shown = round(v / scale)
        val f = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 20; isGroupingUsed = true }
        return if (unit.isEmpty()) f.format(shown) else f.format(shown) + " " + unit
    }

    /** Web `compactOf(digits, scale, unit)` outside English: the value to as many significant digits. */
    private fun compactOf(digits: String, scale: Double): String =
        compact(digits.toDouble() * scale, digits.filter(Char::isDigit).trimStart('0').length)

    /** Web `sig3`: three significant figures, as a string. */
    private fun sig3(v: Double): String {
        val r = BigDecimal(v).round(MathContext(3, RoundingMode.HALF_EVEN)).toDouble()
        return if (r >= 100) r.roundToLong().toString() else if (r >= 10) "%.1f".format(Locale.ROOT, r) else "%.2f".format(Locale.ROOT, r)
    }

    /** Web `count`: 312000 -> "312 rb", 1.84e6 -> "1,84 jt". */
    fun count(n: Double): String = when {
        n >= 1e6 -> compactOf(String.format(Locale.ROOT, if (n >= 1e7) "%.1f" else "%.2f", n / 1e6).trimEnd('0').trimEnd('.'), 1e6)
        n >= 1e3 -> compactOf((n / 1000).roundToLong().toString(), 1e3)
        else -> Math.round(n).toString()
    }

    /** Web `pointsShort`: three significant digits, 980, "12,3 rb", "4,56 jt". */
    fun points(n: Double): String = when {
        n >= 999.5e6 -> compactOf(sig3(n / 1e9), 1e9)
        n >= 999.5e3 -> compactOf(sig3(n / 1e6), 1e6)
        n >= 1e3 -> compactOf(sig3(n / 1e3), 1e3)
        else -> Math.round(n).toString()
    }

    /** Web `exact`: every digit, grouped. */
    fun exact(n: Double): String = NumberFormat.getIntegerInstance(locale).format(Math.round(n))

    /** Web `percent` to one decimal (`pct1`): "12,3%". */
    fun pct1(v: Double): String = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1; maximumFractionDigits = 1
    }.format(v) + "%"

    /**
     * Web `money` (format.ts): an amount in US$ shown in `currency`, three significant figures from
     * a million up, thousands to the "rb", and under 500 of the shown currency its "0". The minus is
     * the web's "−".
     */
    fun money(usd: Double, currency: StudioCurrency): String {
        val a = abs(usd) * currency.rate
        val s = (if (usd < 0 && a >= 500) "−" else "") + currency.symbol
        return when {
            a < 500 -> currency.symbol + "0"
            a >= 999.5e12 -> s + compactOf(sig3(a / 1e15), 1e15)
            a >= 999.5e9 -> s + compactOf(sig3(a / 1e12), 1e12)
            a >= 999.5e6 -> s + compactOf(sig3(a / 1e9), 1e9)
            a >= 999.5e3 -> s + compactOf(sig3(a / 1e6), 1e6)
            else -> s + compactOf((a / 1000).roundToLong().toString(), 1e3)
        }
    }

    /** Web `moneyNegative`: whether `money(usd)` shows a minus (the panels' red). */
    fun moneyNegative(usd: Double, currency: StudioCurrency) = usd < 0 && abs(usd) * currency.rate >= 500
}
