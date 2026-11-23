// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/** Real Studio city maps and scenario snapshots. Cards decode thumbnails at list resolution. */
internal object StudioMapPreview {
    private val thumbnails = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cityPath(id: String) = "world-native/zone-previews/$id.webp"
    fun scenarioPath(id: String) = "world-native/clock/snapshots/$id.jpg"

    fun image(context: Context, path: String, description: String, thumbnail: Boolean = false): ImageView? {
        val bitmap = (if (thumbnail) thumbnails.get(path) else null) ?: try {
            context.assets.open(path).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = if (thumbnail) 4 else 1 })
            }?.also { if (thumbnail) thumbnails.put(path, it) }
        } catch (_: java.io.IOException) { null }
        bitmap ?: return null
        return ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = if (thumbnail) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = !thumbnail
            background = StudioUi.panel(context, StudioDepot.PAPER, 14, StudioDepot.LINE)
            clipToOutline = true
            contentDescription = description
        }
    }

    fun card(context: Context, path: String, title: String, detail: String, selected: Boolean, click: () -> Unit) =
        LinearLayout(context).apply {
            fun dp(n: Int) = StudioUi.dp(context, n)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7),dp(7),dp(12),dp(7))
            background = StudioDepot.touch(context, if (selected) StudioDepot.MINT else StudioUi.WHITE, 16,
                if (selected) StudioDepot.TEAL else StudioDepot.LINE)
            val thumb = image(context, path, "", true) ?: StudioDepot.symbol(context,StudioUi.Icon.PIN,24f,StudioDepot.TEAL)
            thumb.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(thumb,LinearLayout.LayoutParams(dp(72),dp(54)))
            val copy = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(StudioUi.text(context,title,16f,true,StudioDepot.TEAL))
                addView(StudioUi.text(context,detail,13f,color=StudioDepot.SECONDARY))
            }
            addView(copy,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(12); marginEnd=dp(4) })
            addView(StudioUi.text(context,if(selected) "✓" else "›",19f,true,StudioDepot.TEAL))
            contentDescription = listOf(title,detail,if(selected) "Terpilih" else "").filter { it.isNotBlank() }.joinToString(". ")
            isFocusable = true
            isSelected = selected
            setOnClickListener { click() }
        }
}
