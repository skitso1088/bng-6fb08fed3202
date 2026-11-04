package com.bumpngrind.tv

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** BumpNGrind loading screen shown over a channel while it starts. */
object Splash {
    /** Logo screen in the menu's style: orange Bump, yellow N, white Grind on the dark gradient. */
    fun build(ctx: Context, title: String): View {
        val h = ctx.resources.displayMetrics.heightPixels.toFloat()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
                gradientRadius = h * 1.1f; setGradientCenter(0.78f, 0f)
                colors = intArrayOf(0xFF241A2E.toInt(), 0xFF12111B.toInt(), 0xFF0A0B10.toInt())
            }
            isClickable = true // swallow touches while loading
        }
        val logo = android.text.SpannableString("BumpNGrind").apply {
            setSpan(android.text.style.ForegroundColorSpan(0xFFFF5A1F.toInt()), 0, 4, 0)
            setSpan(android.text.style.ForegroundColorSpan(0xFFFFD23F.toInt()), 4, 5, 0)
            setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 5, 10, 0)
        }
        box.addView(TextView(ctx).apply {
            text = logo; typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, h * 0.12f); letterSpacing = -0.02f
            gravity = android.view.Gravity.CENTER
        })
        box.addView(TextView(ctx).apply {
            text = if (title.isEmpty()) "Loading…" else "Loading $title…"
            setTextColor(0x99FFFFFF.toInt()); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, h * 0.035f)
            gravity = android.view.Gravity.CENTER; setPadding(0, (h * 0.02f).toInt(), 0, (h * 0.04f).toInt())
        })
        box.addView(ProgressBar(ctx).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFF5A1F.toInt())
        }, LinearLayout.LayoutParams((h * 0.07f).toInt(), (h * 0.07f).toInt()))
        return box
    }
}
