package com.dshmobile.shell

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Full-screen log viewer overlay for the boot guide: title bar (Copy /
 * Share / Close) over a monospace scroll of AppLog.dump(). Built once,
 * revealed on demand, auto-scrolled to the newest entry. Share goes through
 * ACTION_SEND (text/plain) so devices without adb/PC access can still
 * export diagnostics.
 */
class LogPanel(
  private val activity: ComponentActivity,
  private val versionLine: String,
  private val onCopy: () -> Unit,
  private val onShare: (String) -> Unit,
  private val onClose: () -> Unit,
) {
  private val d: Float get() = activity.resources.displayMetrics.density

  private var logText: TextView? = null
  private var scrollView: ScrollView? = null

  val view: FrameLayout = build()

  /** Reveal with a fresh dump and scroll to the newest entry. */
  fun open() {
    logText?.text = AppLog.dump()
    view.visibility = View.VISIBLE
    view.alpha = 0f
    view
      .animate()
      .alpha(1f)
      .setDuration(200)
      .start()
    scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
  }

  fun close() {
    view
      .animate()
      .alpha(0f)
      .setDuration(150)
      .withEndAction {
        view.visibility = View.GONE
        view.alpha = 1f
      }.start()
  }

  private fun share() {
    onShare(LogPanelText.shareText(versionLine, AppLog.dump()))
  }

  private fun ghostAction(
    text: String,
    action: () -> Unit,
  ): Button =
    Button(activity).apply {
      this.text = text
      setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
      textSize = 12f
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.pill_ghost)
      minHeight = (32 * d).toInt()
      isAllCaps = false
      stateListAnimator = null
      setOnClickListener { action() }
    }

  private fun build(): FrameLayout {
    val title =
      TextView(activity).apply {
        text = activity.getString(R.string.log_panel_title)
        setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
      }
    val copy = ghostAction(activity.getString(R.string.button_copy_panel)) { onCopy() }.apply { contentDescription = activity.getString(R.string.a11y_copy_button) }
    val share = ghostAction(activity.getString(R.string.button_share)) { share() }.apply { contentDescription = activity.getString(R.string.a11y_share_button) }
    val close = ghostAction(activity.getString(R.string.button_close)) { onClose() }.apply { contentDescription = activity.getString(R.string.a11y_close_button) }

    fun gap(): View =
      View(activity).apply {
        layoutParams = LinearLayout.LayoutParams((8 * d).toInt(), (36 * d).toInt())
      }
    val bar =
      LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.card_bg)
      }
    bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
    bar.addView(copy)
    bar.addView(gap())
    bar.addView(share)
    bar.addView(gap())
    bar.addView(close)
    val body =
      TextView(activity).apply {
        setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
        textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        setTextIsSelectable(true)
      }
        body.contentDescription = activity.getString(R.string.a11y_log_content)
    logText = body
    val scroll =
      ScrollView(activity).apply {
        isFillViewport = true
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.inset_bg)
      }
    scroll.addView(body)
    scrollView = scroll
    val panel =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.card_bg)
        setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        layoutParams =
          FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.Gravity.CENTER,
          )
      }
    panel.addView(bar)
    panel.addView(
      scroll,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
    )
    return FrameLayout(activity).apply {
      contentDescription = activity.getString(R.string.a11y_log_panel)
      visibility = View.GONE
      setBackgroundColor(0xCC000000.toInt())
      setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
      addView(panel)
    }
  }
}

