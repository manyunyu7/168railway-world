// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView

/** Keeps platform cancellation and button callbacks while using the game's adaptive sheet. */
internal class StudioDialogBuilder(private val host: Activity) {
    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var content: View? = null
    private var choices: Array<out CharSequence> = emptyArray()
    private var initial: Int? = null
    private var onPick: DialogInterface.OnClickListener? = null
    private var cancel: DialogInterface.OnCancelListener? = null
    private data class Action(val label: String, val click: DialogInterface.OnClickListener?)
    private val actions = linkedMapOf<Int, Action>()

    fun setTitle(value: CharSequence?) = apply { title = value }
    fun setMessage(value: CharSequence?) = apply { message = value }
    fun setView(view: View) = apply { content = view }
    fun setOnCancelListener(listener: DialogInterface.OnCancelListener) = apply { cancel = listener }
    fun setItems(labels: Array<out CharSequence>, listener: DialogInterface.OnClickListener) = apply {
        choices = labels
        onPick = listener
    }
    fun setSingleChoiceItems(labels: Array<out CharSequence>, checked: Int, listener: DialogInterface.OnClickListener) =
        setItems(labels, listener).apply { initial = checked }

    fun setPositiveButton(label: String, listener: DialogInterface.OnClickListener?) = apply {
        actions[DialogInterface.BUTTON_POSITIVE] = Action(label, listener)
    }
    fun setNegativeButton(label: String, listener: DialogInterface.OnClickListener?) = apply {
        actions[DialogInterface.BUTTON_NEGATIVE] = Action(label, listener)
    }
    fun setNeutralButton(label: String, listener: DialogInterface.OnClickListener?) = apply {
        actions[DialogInterface.BUTTON_NEUTRAL] = Action(label, listener)
    }

    fun create(): StudioPrompt {
        val dialog = StudioPrompt(host)
        val body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val detail = StudioUi.text(host,"",16f,color = StudioDepot.SECONDARY).apply {
            setLineSpacing(StudioUi.dp(host,2).toFloat(),1f)
        }
        dialog.detail = detail
        dialog.setMessage(message)
        body.addView(detail,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = StudioUi.dp(host,8) })
        content?.let { body.addView(it) }
        val radios = mutableListOf<RadioButton>()
        choices.forEachIndexed { index, label ->
            val row = if (initial != null) RadioButton(host).apply {
                text = label
                typeface = StudioUi.face(host,true)
                textSize = 16f
                setTextColor(StudioDepot.TEAL)
                isChecked = initial == index
                setOnClickListener {
                    radios.forEach { it.isChecked = it === this }
                    onPick?.onClick(dialog,index)
                }
                radios.add(this)
            } else StudioUi.button(host,label.toString()) {
                dialog.dismiss()
                onPick?.onClick(dialog,index)
            }
            row.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            row.minHeight = StudioUi.dp(host,48)
            body.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = StudioUi.dp(host,5) })
        }
        val footer = if (actions.isEmpty()) null else object : LinearLayout(host) {
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                val gap = StudioUi.dp(host,6)
                val horizontal = childCount <= 2 && (0 until childCount).all { i ->
                    val button = getChildAt(i) as Button
                    button.paint.measureText(button.text.toString()) + button.paddingLeft + button.paddingRight < (width-gap)/childCount
                }
                orientation = if (horizontal) HORIZONTAL else VERTICAL
                for (i in 0 until childCount) {
                    val lp = getChildAt(i).layoutParams as LayoutParams
                    lp.width = if (horizontal) 0 else -1
                    lp.weight = if (horizontal) 1f else 0f
                    lp.marginStart = if (i > 0 && horizontal) gap else 0
                    lp.topMargin = if (i > 0 && !horizontal) gap else 0
                }
                super.onMeasure(w,h)
            }
        }.apply {
            for (id in listOf(DialogInterface.BUTTON_NEGATIVE,DialogInterface.BUTTON_NEUTRAL,DialogInterface.BUTTON_POSITIVE)) {
                val action = actions[id] ?: continue
                val tone = if (id == DialogInterface.BUTTON_POSITIVE) StudioUi.Tone.PRIMARY else StudioUi.Tone.SURFACE
                val button = StudioUi.button(host,action.label,tone) {
                    dialog.dismiss()
                    action.click?.onClick(dialog,id)
                }
                dialog.buttons[id] = button
                addView(button,LinearLayout.LayoutParams(-1,-2))
            }
        }
        // Short confirmations (message + buttons only) float as a centred glass card.
        val confirm = choices.isEmpty() && content == null && (message?.length ?: 0) < 400
        if (confirm) detail.gravity = Gravity.CENTER_HORIZONTAL
        StudioUi.layoutSheet(host,dialog,title,ScrollView(host).apply { addView(body) },footer,centered = confirm)
        dialog.setOnCancelListener(cancel)
        return dialog
    }
    fun show() = create().also { it.show() }
}

internal class StudioPrompt(host: Activity) : Dialog(host) {
    init { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    lateinit var detail: TextView
    val buttons = mutableMapOf<Int,Button>()
    fun getButton(which: Int) = buttons.getValue(which)
    fun setMessage(value: CharSequence?) {
        detail.text = value
        detail.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}
