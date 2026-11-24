package world.nativegame

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale

/** web LineFare (ui/inspector/LineMoney.tsx, T-3567): the line's own fare multiple on top of the fares, a
 * stepper by 0,05× from 0 to 3, the value typed on a tap, and a button back to 1,00× once set. */
internal object LineFare {
    @JvmStatic private external fun get(handle: Long, id: Int): String
    @JvmStatic private external fun set(handle: Long, id: Int, now: Double, mul: Double): String

    private const val MAX = 3.0
    private val ID = Locale.forLanguageTag("id-ID")

    fun row(host: Activity, handle: Long, id: Int, editable: Boolean, transaction: PanelEdit, changed: () -> Unit): View {
        fun dp(n: Int) = StudioUi.dp(host, n)
        var mul = JSONObject(get(handle, id)).optDouble("fare", 1.0)
        fun text(v: Double) = String.format(ID, "%.2f×", v)
        val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; background = StudioUi.card(host, 22); setPadding(dp(14), dp(8), dp(8), dp(8)) }
        val line = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
        // the title on its own row: beside the stepper it wrapped into "Kelip / atan / tarif" on a phone
        box.addView(StudioUi.text(host, "Kelipatan tarif", 15f, true))
        line.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        box.addView(line)
        box.addView(StudioUi.text(host, "Mengalikan tarif untuk naik di lini ini, termasuk tarif dasar. Perjalanan lewat beberapa lini membayar tarif dasar tertinggi di antaranya.",
            12f, true, StudioUi.MUTED).apply { setPadding(0, dp(4), dp(6), 0) })
        if (!editable) {
            line.addView(StudioUi.text(host, text(mul), 16f).apply { typeface = StudioUi.heavy(host) })
            return box
        }
        val value = EditText(host).apply {
            setSingleLine()
            contentDescription = "Kelipatan tarif"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            typeface = StudioUi.heavy(host)
            textSize = 16f
            setTextColor(StudioUi.INK)
            background = null
            setPadding(0, 0, 0, 0)
        }
        val back = StudioUi.iconButton(host, "Kembali ke ${text(1.0)}", StudioUi.Icon.UNDO) {}
        fun show() {
            value.setText(text(mul))
            back.visibility = if (mul != 1.0) View.VISIBLE else View.INVISIBLE
        }
        // web Stepper: a step past a limit is refused with a shake
        fun refuse() {
            box.animate().translationX(dp(8).toFloat()).setDuration(50).withEndAction {
                box.animate().translationX(-dp(8).toFloat()).setDuration(70).withEndAction { box.animate().translationX(0f).setDuration(50) }
            }
        }
        fun apply(v: Double) {
            // The world may settle first; the answer, and the undo entry, come inside the edit.
            transaction { now ->
                val r = JSONObject(set(handle, id, now, v))
                if (r.has("error")) Toast.makeText(host, r.optString("error"), Toast.LENGTH_LONG).show()
                else {
                    val old = mul
                    mul = r.optDouble("fare", mul)
                    if (mul != old) changed()
                }
                show()
            }
        }
        fun step(d: Int) {
            if (if (d < 0) mul <= 0 else mul >= MAX) return refuse()
            apply(Math.round(mul * 100) / 100.0 + d * 0.05)
        }
        fun typed() {
            val v = value.text.toString().replace("×", "").replace("x", "").trim().replace(',', '.').toDoubleOrNull()
            if (v == null) { show(); return refuse() }
            if (Math.abs(v - mul) > 1e-9) apply(v) else show()
        }
        value.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { typed(); value.clearFocus() }; false }
        value.setOnFocusChangeListener { _, focus ->
            if (focus) value.setText(String.format(Locale.ROOT, "%.2f", mul).replace('.', ','))
            else typed()
        }
        back.setOnClickListener { apply(1.0) }
        fun adjust(d: Int) = StudioUi.iconButton(host, "${if (d < 0) "Kurangi" else "Tambah"} kelipatan tarif",
            if (d < 0) StudioUi.Icon.MINUS else StudioUi.Icon.PLUS) { step(d) }
        line.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        line.addView(adjust(-1), LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        line.addView(value, LinearLayout.LayoutParams(dp(72), dp(48)))
        line.addView(adjust(1), LinearLayout.LayoutParams(dp(48), dp(48)))
        show()
        return box
    }
}
