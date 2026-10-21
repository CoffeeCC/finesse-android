package com.coffeecc.finesse.video

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Finesse Video on the phone: what it is, and whether it found Finesse's sign-in. The video itself
 * is on the car screen. (Opening this once also takes the app out of Android's "never opened"
 * state, so Android Auto finds it.)
 */
class PhoneActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(24)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(48), pad, pad)
            setBackgroundColor(Color.rgb(11, 13, 18))
        }
        layout.addView(text("Finesse Video", 28f, Color.WHITE))
        layout.addView(
            text(
                "Films and episodes from Finesse on your car screen with Android Auto, only while the car is parked.\n\n" +
                    "In the car, open Finesse Video from the app list. If it isn't there: Android Auto settings → " +
                    "Customize launcher, tick Finesse Video, then reconnect.",
                16f,
                Color.rgb(200, 204, 214),
            ).apply { setPadding(0, dp(16), 0, dp(16)) },
        )
        status = text("", 16f, Color.rgb(147, 165, 232))
        layout.addView(status)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        val result = SignIn.read(this)
        status.text = (if (result is SignIn.Result.SignedIn) "✓ " else "⚠ ") + SignIn.explain(result)
    }

    private fun text(value: String, sp: Float, color: Int) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
