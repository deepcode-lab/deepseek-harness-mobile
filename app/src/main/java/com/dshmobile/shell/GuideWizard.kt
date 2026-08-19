package com.dshmobile.shell

import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Cold-start bar states: STARTING breathes, FAILED persists (I-26: a failed
 *  boot must always leave an exit), SUCCESS fades away after a delay. */
enum class BarState { STARTING, FAILED, SUCCESS }

/**
 * Boot wizard UI: full-screen scroll guide (brand block, vertical step
 * cards, engine status card, action grid, version line) plus the floating
 * cold-start pill overlaying the Harness. Pure presentation 鈥?all flow
 * decisions live in the caller through the injected callbacks.
 */
class GuideWizard(
  private val activity: ComponentActivity,
  private val webView: android.webkit.WebView,
  private val onPrimaryAction: () -> Unit,
  private val onCheckUpdate: (status: (String) -> Unit) -> Unit,
  private val onCopyLog: () -> Unit,
  private val onBackToHarness: () -> Unit,
  private val onKeepAlive: () -> Unit,
  private val onOpenLog: () -> Unit,
  private val onReload: () -> Unit,
) {
  val guideView: ScrollView = buildGuideView()
  val topStatusBar: LinearLayout = buildTopStatusBar()

  private var engineStatus: TextView? = null
  private var statusDetail: TextView? = null
  private var progressBar: android.widget.ProgressBar? = null
  private var primaryButton: LinearLayout? = null
  private var primaryLabel: TextView? = null
  private var backButton: Button? = null
  private var errorBlock: LinearLayout? = null
  private var errorText: TextView? = null
  private var statusCard: LinearLayout? = null
  private var actionRow: LinearLayout? = null
  private var keepAliveBlock: LinearLayout? = null
  private var keepAliveText: TextView? = null
  private var keepAliveBattery: Button? = null
  private var keepAliveShizuku: Button? = null
  private var guideContent: LinearLayout? = null
  private var stepCircles: Array<TextView> = emptyArray()
  private var stepGlyphs: Array<TextView> = emptyArray()
  private var stepStatusTexts: Array<TextView> = emptyArray()
  private var stepCards: Array<LinearLayout> = emptyArray()
  private var stepActiveGlyph: View? = null
  private var stepPulseAnimator: android.animation.ValueAnimator? = null
  private var firstStepRender = true
  private var prevDone = 0
  private var topPulseDot: View? = null
  private var topPulseAnimator: android.animation.ValueAnimator? = null
  private lateinit var logPanel: LogPanel

  private val d: Float get() = activity.resources.displayMetrics.density

  private val interpolator = android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)

  private var currentBarState: BarState? = null

  init {
    logPanel =
      LogPanel(
        activity,
        buildVersionLineText(),
        onCopy = { onCopyLog() },
        onShare = { text -> shareLog(text) },
        onClose = { logPanel.close() },
      )
  }

  // ---- Public state API -------------------------------------------------

  /** Set the guide's status row; shows the spinner when the status is
   *  indeterminate progress (extraction, container install, engine start). */
  fun showGuideStatus(
    title: String,
    detail: String?,
    busy: Boolean,
  ) {
    engineStatus?.text = title
    statusDetail?.text = detail
    statusDetail?.visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE
    progressBar?.visibility = if (busy) View.VISIBLE else View.GONE
    if (busy) {
      errorBlock?.visibility = View.GONE
      primaryButton?.visibility = View.GONE
    }
  }

  /** Ready state: everything installed 鈫?show the Launch engine button. */
  fun showLaunchReady() {
    showGuideStatus(
      activity.getString(R.string.status_ready_to_launch),
      activity.getString(R.string.status_ready_to_launch_detail),
      false,
    )
    renderSteps(3, 3)
    primaryLabel?.text = activity.getString(R.string.button_launch_engine)
    primaryButton?.visibility = View.VISIBLE
  }

  fun showGuideError(title: String) {
    showGuideStatus(title, null, false)
    primaryLabel?.text = activity.getString(R.string.button_retry)
    primaryButton?.visibility = View.VISIBLE
    // Surface the tail of the diagnostic log as inline error context.
    val tail = AppLog.tail(1200)
    errorText?.text = tail
        errorBlock?.let { it.contentDescription = activity.getString(R.string.a11y_error_block) }
    errorBlock?.visibility = if (tail.isBlank()) View.GONE else View.VISIBLE
  }

  /** Cross-fade between the guide surface and the Harness web view. */
  fun showGuide() {
    cancelScheduledTopBarHide()
    webView
      .animate()
      .alpha(0f)
      .setDuration(150)
      .start()
    webView.visibility = View.GONE
    stopTopBarPulse()
    stopStepPulse()
    topStatusBar.visibility = View.GONE
    guideView.visibility = View.VISIBLE
    guideView.alpha = 1f
    animateGuideEntry()
  }

  /** Staggered entry: children fade in + rise 12dp, 80ms apart. */
  private fun animateGuideEntry() {
    val content = guideContent ?: return
    var delay = 0L
    for (i in 0 until content.childCount) {
      val child = content.getChildAt(i)
      child.alpha = 0f
      child.translationY = (12 * d)
      child
        .animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(delay)
        .setDuration(400)
        .setInterpolator(interpolator)
        .start()
      delay += 80
    }
  }

  fun showWeb() {
    backButton?.visibility = View.GONE
    stopStepPulse()
    guideView
      .animate()
      .alpha(0f)
      .setDuration(150)
      .withEndAction {
        guideView.visibility = View.GONE
      }.start()
    webView.visibility = View.VISIBLE
    webView
      .animate()
      .alpha(1f)
      .setDuration(200)
      .start()
    // NOTE: no reload here 鈥?MainActivity reloads only when the page had
    // failed to load; a blanket reload on every show would discard the page
    // state (and race picker callbacks) on each return to foreground.
    // SUCCESS keeps the 6s fade (pulse visible during the cold-start
    // transition); FAILED must persist 鈥?a failed boot always leaves an
    // exit (I-26). When no bar was shown, nothing to hide.
    val state = currentBarState
    if (state != null && state != BarState.FAILED) scheduleTopBarHide(6000L)
  }

  /** Show the cold-start pill: STARTING breathes, FAILED persists (I-26),
   *  SUCCESS fades after 6s. Slide-in from -32dp. */
  fun showTopBar(state: BarState) {
    cancelScheduledTopBarHide()
    currentBarState = state
    guideView.visibility = View.GONE
    webView.visibility = View.VISIBLE
    webView
      .animate()
      .alpha(1f)
      .setDuration(150)
      .start()
    val palette = GuidePalette(activity)
    val dotColor =
      when (state) {
        BarState.STARTING -> palette.accent
        BarState.FAILED -> palette.error
        BarState.SUCCESS -> palette.success
      }
    topPulseDot?.backgroundTintList =
      android.content.res.ColorStateList
        .valueOf(dotColor)
    topStatusLabel?.text =
      when (state) {
        BarState.STARTING -> activity.getString(R.string.status_engine_starting)
        BarState.FAILED -> activity.getString(R.string.bar_failed)
        BarState.SUCCESS -> activity.getString(R.string.bar_success)
      }
    topStatusBar.visibility = View.VISIBLE
    topStatusBar.alpha = 0f
    topStatusBar.translationY = (-32 * d)
    topStatusBar
      .animate()
      .alpha(1f)
      .translationY(0f)
      .setDuration(250)
      .setInterpolator(interpolator)
      .start()
    if (state == BarState.STARTING) startTopBarPulse() else stopTopBarPulse()
    if (state == BarState.SUCCESS) scheduleTopBarHide(6000L)
  }

  /** Public so failure paths can stop the pulse and dismiss the bar. */
  fun hideTopBar() {
    stopTopBarPulse()
    topStatusBar
      .animate()
      .alpha(0f)
      .setDuration(250)
      .withEndAction {
        topStatusBar.visibility = View.GONE
        topStatusBar.alpha = 1f
      }.start()
  }

  private var topBarHidePending: java.lang.Runnable? = null

  private fun scheduleTopBarHide(delayMs: Long) {
    topBarHidePending?.let { webView.removeCallbacks(it) }
    val r = java.lang.Runnable { hideTopBar() }
    topBarHidePending = r
    webView.postDelayed(r, delayMs)
  }

  private fun cancelScheduledTopBarHide() {
    topBarHidePending?.let { webView.removeCallbacks(it) }
    topBarHidePending = null
  }

  /** Guide entry from the cold-start bar: show actions, keep Harness in back. */
  fun showGuideFromTopBar() {
    primaryButton?.visibility = View.GONE
    backButton?.visibility = View.VISIBLE
    showGuide()
  }

  /** Render the step cards from the (done, active) counters: done rows show a
   *  green check (first appearance pops in at scale 0.9), the active row
   *  breathes, pending rows stay quiet. */
  fun renderSteps(
    done: Int,
    active: Int,
  ) {
    val model = StepModel(done, active)
    val palette = GuidePalette(activity)
    stopStepPulse()
    stepActiveGlyph = null
    for (i in stepCircles.indices) {
      val state = model.state(i)
      val circle = stepCircles[i]
      val glyph = stepGlyphs[i]
      val statusText = stepStatusTexts[i]
      val circleColor =
        when (state) {
          StepState.DONE -> palette.success
          StepState.ACTIVE -> palette.accent
          StepState.PENDING -> palette.hairline
        }
      circle.background =
        android.graphics.drawable.GradientDrawable().apply {
          shape = android.graphics.drawable.GradientDrawable.OVAL
          setColor(circleColor)
        }
      when (state) {
        StepState.DONE -> {
          circle.text = "鉁?
          glyph.text = "鉁?
          glyph.background = null
          glyph.setTextColor(palette.success)
          statusText.setTextColor(palette.textSecondary)
          statusText.text = activity.getString(R.string.step_status_done)
        }

        StepState.ACTIVE -> {
          circle.text = (i + 1).toString()
          glyph.text = ""
          glyph.background = null
          statusText.setTextColor(palette.accent)
          statusText.text = activity.getString(R.string.step_status_active)
          stepActiveGlyph = glyph
        }

        StepState.PENDING -> {
          circle.text = (i + 1).toString()
          glyph.text = ""
          glyph.background = null
          statusText.setTextColor(palette.textSecondary)
          statusText.text = activity.getString(R.string.step_status_pending)
        }
      }
    }
    // Newly-done rows pop in (scale 0.9 鈫?1); the first render stays static.
    if (done > prevDone && !firstStepRender) {
      for (i in prevDone until done) {
        val card = stepCards.getOrNull(i) ?: continue
        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card
          .animate()
          .alpha(1f)
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(200)
          .setInterpolator(interpolator)
          .start()
      }
    }
    prevDone = done
    firstStepRender = false
    if (stepActiveGlyph != null) startStepPulse()
  }

  /** Breathing alpha on the active step-card glyph. */
  private fun startStepPulse() {
    val glyph = stepActiveGlyph ?: return
    stepPulseAnimator?.cancel()
    val animator = android.animation.ValueAnimator.ofFloat(1f, 0.25f)
    animator.duration = 900
    animator.repeatMode = android.animation.ValueAnimator.REVERSE
    animator.repeatCount = android.animation.ValueAnimator.INFINITE
    animator.addUpdateListener { glyph.alpha = it.animatedValue as Float }
    animator.start()
    stepPulseAnimator = animator
  }

  private fun stopStepPulse() {
    stepPulseAnimator?.cancel()
    stepPulseAnimator = null
    stepActiveGlyph?.alpha = 1f
  }

  fun onDestroy() {
    cancelScheduledTopBarHide()
    stopTopBarPulse()
    stopStepPulse()
  }

  // ---- Construction -----------------------------------------------------

  /** Tactile press feedback: a light scale-down while pressed. */
  private fun attachPressFeedback(view: View) {
    view.setOnTouchListener { v, event ->
      when (event.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN -> {
          v
            .animate()
            .scaleX(0.98f)
            .scaleY(0.98f)
            .setDuration(80)
            .start()
        }

        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
          v
            .animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120)
            .start()
        }
      }
      false
    }
  }

  /** Pill button with the accent fill (primary action). */
  private fun accentButton(text: String): Button =
    Button(activity).apply {
      this.text = text
      setTextColor(0xFFFFFFFF.toInt())
      textSize = 14f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.pill_accent)
      minHeight = (48 * d).toInt()
      isAllCaps = false
      stateListAnimator = null
      attachPressFeedback(this)
    }

  /** Pill button with a hairline border (secondary action). */
  private fun ghostButton(text: String): Button =
    Button(activity).apply {
      this.text = text
      setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
      textSize = 14f
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.pill_ghost)
      minHeight = (48 * d).toInt()
      isAllCaps = false
      stateListAnimator = null
      attachPressFeedback(this)
    }

  private fun buildGuideView(): ScrollView {
    val pad = (24 * d).toInt()
    val palette = GuidePalette(activity)
    val content =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, 0, pad, pad)
      }
    guideContent = content

    // Page glow: radial brand-gradient wash behind the brand block (~14%
    // opacity), the third and last allowed gradient after logo and primary.
    val glow =
      View(activity).apply {
        background = glowDrawable(palette)
        layoutParams =
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (220 * d).toInt(),
          )
      }
    content.addView(glow)
    content.addView(buildBrandBlock())
    content.addView(buildStepCards())
    content.addView(buildStatusCard())
    // Keep-alive panel stays hidden until requested.
    keepAliveBlock = buildKeepAliveCard().also { it.visibility = View.GONE }
    content.addView(keepAliveBlock)
    content.addView(buildActionArea())
    content.addView(buildVersionLine())

    return ScrollView(activity).apply {
      isFillViewport = true
      visibility = View.GONE
      setBackgroundColor(palette.background)
      addView(content)
    }
  }

  /** Radial glow behind the brand block: the accent at ~14% alpha. */
  private fun glowDrawable(palette: GuidePalette): android.graphics.drawable.GradientDrawable {
    val center = palette.accent and 0x00FFFFFF.toInt() or (0x24 shl 24)
    return android.graphics.drawable.GradientDrawable().apply {
      shape = android.graphics.drawable.GradientDrawable.RECTANGLE
      gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
      colors = intArrayOf(center, palette.background)
      gradientRadius = (400 * d)
    }
  }

  /** Engine status card: status row + detail + progress + engine URL with a
   *  "View log" entry + inline error block (log tail). */
  private fun buildStatusCard(): LinearLayout {
    val palette = GuidePalette(activity)
    val statusDot =
      View(activity).apply {
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.status_dot)
        val size = (8 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
      }
    val statusTitle =
      TextView(activity).apply {
        setTextColor(palette.textPrimary)
        textSize = 15f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setPadding((8 * d).toInt(), 0, 0, 0)
      }
        statusTitle.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    statusTitle.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    engineStatus = statusTitle
    val statusRow =
      LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
      }
    statusRow.addView(statusDot)
    statusRow.addView(statusTitle)
    val detail =
      TextView(activity).apply {
        setTextColor(palette.textSecondary)
        textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, (10 * d).toInt(), 0, 0)
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
      }
    statusDetail = detail
    val progress =
      android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        progressTintList =
          android.content.res.ColorStateList
            .valueOf(palette.accent)
        progressBackgroundTintList =
          android.content.res.ColorStateList
            .valueOf(palette.accentDim)
        visibility = View.GONE
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4 * d).toInt())
        lp.topMargin = (16 * d).toInt()
        layoutParams = lp
      }
    progressBar = progress
    val urlText =
      TextView(activity).apply {
        text = EngineProbe.ENGINE_URL
        setTextColor(palette.textSecondary)
        textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, (10 * d).toInt(), 0, 0)
      }
    val viewLog =
      ghostButton(activity.getString(R.string.button_view_log)).apply {
        minHeight = (36 * d).toInt()
        setOnClickListener { onOpenLog() }
      }
    val urlRow =
      LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
      }
    urlRow.addView(urlText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
    urlRow.addView(viewLog)
    val cardBody =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
      }
    cardBody.addView(statusRow)
    cardBody.addView(detail)
    cardBody.addView(progress)
    cardBody.addView(urlRow)
    val errorDetail =
      TextView(activity).apply {
        setTextColor(palette.error)
        textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        maxLines = 4
        ellipsize = android.text.TextUtils.TruncateAt.END
      }
    errorText = errorDetail
    val errorBlock =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background =
          android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = (12 * d)
            setColor(palette.errorDim)
          }
        visibility = View.GONE
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (16 * d).toInt()
        layoutParams = lp
      }
    errorBlock.addView(errorDetail)
    this.errorBlock = errorBlock
    val inner =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.inset_bg)
      }
    inner.addView(cardBody)
    inner.addView(errorBlock)
    val card =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.card_bg)
        setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        layoutParams =
          LinearLayout
            .LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
              topMargin = (20 * d).toInt()
            }
      }
    card.addView(inner)
    statusCard = card
    return card
  }

  /** Brand block: programmatic gradient logo + app name + subtitle. */
  private fun buildBrandBlock(): LinearLayout {
    val palette = GuidePalette(activity)
    val logo =
      TextView(activity).apply {
        text = "D"
        textSize = 22f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = android.view.Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        background =
          android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            colors = intArrayOf(palette.accent, palette.accentEnd)
            gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
            orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
          }
        val size = (52 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
      }
    val brandTitle =
      TextView(activity).apply {
        text = activity.getString(R.string.app_name)
        setTextColor(palette.textPrimary)
        textSize = 22f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, (16 * d).toInt(), 0, 0)
      }
    val brandSub =
      TextView(activity).apply {
        text = activity.getString(R.string.guide_brand_subtitle)
        setTextColor(palette.textSecondary)
        textSize = 13f
        setPadding(0, (4 * d).toInt(), 0, 0)
      }
    return LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      gravity = android.view.Gravity.CENTER_HORIZONTAL
      setPadding(0, (16 * d).toInt(), 0, 0)
      addView(logo)
      addView(brandTitle)
      addView(brandSub)
    }
  }

  /** Action area: gradient primary pill + 2脳2 ghost grid (update / reload /
   *  keep-alive / copy log) + back-to-harness (visible only when the guide
   *  was opened from the cold-start bar). */
  private fun buildActionArea(): LinearLayout {
    val sep = (10 * d).toInt()
    val primary =
      primaryPill(activity.getString(R.string.button_launch_engine)).apply {
        visibility = View.GONE
        setOnClickListener { onPrimaryAction() }
      }
    primaryButton = primary
    primary.contentDescription = activity.getString(R.string.a11y_primary_button)
    primary.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

    fun ghost(
      text: String,
      action: () -> Unit,
    ): Button =
      ghostButton(text).apply {
        setOnClickListener { action() }
      }
    val update =
      ghost(activity.getString(R.string.button_check_update)) {
        onCheckUpdate { status -> showGuideStatus(status, null, true) }
      }
    val reload = ghost(activity.getString(R.string.button_reload)) { onReload() }
    val keepAlive = ghost(activity.getString(R.string.button_keep_alive)) { onKeepAlive() }
    val copyLog = ghost(activity.getString(R.string.button_copy_log)) { onCopyLog() }
    val back =
      ghost(activity.getString(R.string.button_back_to_harness)) { onBackToHarness() }.apply {
        visibility = View.GONE
      }
    backButton = back
    back.contentDescription = activity.getString(R.string.a11y_back_button)
    (update.layoutParams as LinearLayout.LayoutParams).bottomMargin = sep
    (reload.layoutParams as LinearLayout.LayoutParams).bottomMargin = sep
    (keepAlive.layoutParams as LinearLayout.LayoutParams).bottomMargin = sep
    val left =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams =
          LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
            rightMargin = (8 * d).toInt()
          }
      }
    val right =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams =
          LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
            leftMargin = (8 * d).toInt()
          }
      }
    left.addView(update)
    left.addView(keepAlive)
    right.addView(reload)
    right.addView(copyLog)
    val grid =
      LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams =
          LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (24 * d).toInt()
          }
      }
    grid.addView(left)
    grid.addView(right)
    back.layoutParams =
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (16 * d).toInt()
      }
    val actions =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams =
          LinearLayout
            .LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
              topMargin = (32 * d).toInt()
            }
      }
    actions.addView(primary)
    actions.addView(grid)
    actions.addView(back)
    actionRow = actions
    return actions
  }

  /** Gradient pill primary: label + trailing circular chevron. */
  private fun primaryPill(text: String): LinearLayout {
    val palette = GuidePalette(activity)
    val label =
      TextView(activity).apply {
        this.text = text
        textSize = 15f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(0xFFFFFFFF.toInt())
      }
    primaryLabel = label
    val chevron =
      TextView(activity).apply {
        this.text = "鈥?
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(0xFFFFFFFF.toInt())
        gravity = android.view.Gravity.CENTER
        background =
          android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0x33FFFFFF.toInt())
          }
        val size = (24 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
      }
    return LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER
      background =
        android.graphics.drawable.GradientDrawable().apply {
          shape = android.graphics.drawable.GradientDrawable.RECTANGLE
          cornerRadius = (24 * d)
          colors = intArrayOf(palette.accent, palette.accentEnd)
          gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
          orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
        }
      minimumHeight = (48 * d).toInt()
      setPadding((20 * d).toInt(), 0, (12 * d).toInt(), 0)
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      addView(label)
      addView(
        chevron,
        LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()).apply {
          leftMargin = (14 * d).toInt()
        },
      )
      setOnClickListener { onPrimaryAction() }
      attachPressFeedback(this)
    }
  }

  private fun buildVersionLineText(): String {
    val appVersion =
      try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
      } catch (_: Throwable) {
        "?"
      }
    val abi =
      android.os.Build.SUPPORTED_ABIS
        .firstOrNull() ?: "?"
    return VersionLine.format(appVersion, abi, SnapshotVersion.read(activity))
  }

  /** Bottom version row: app version 路 ABI 路 snapshot dsh version. */
  private fun buildVersionLine(): TextView {
    val palette = GuidePalette(activity)
    return TextView(activity).apply {
      text = buildVersionLineText()
      setTextColor(palette.textSecondary)
      textSize = 12f
      typeface = android.graphics.Typeface.MONOSPACE
      gravity = android.view.Gravity.CENTER_HORIZONTAL
      layoutParams =
        LinearLayout
          .LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
          ).apply {
            topMargin = (24 * d).toInt()
          }
    }
  }

  private fun buildKeepAliveCard(): LinearLayout {
    val palette = GuidePalette(activity)
    val title =
      TextView(activity).apply {
        text = activity.getString(R.string.keep_alive_title)
        setTextColor(palette.textPrimary)
        textSize = 15f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
      }
    val text =
      TextView(activity).apply {
        setTextColor(palette.textSecondary)
        textSize = 13f
        setPadding(0, (12 * d).toInt(), 0, 0)
      }
    keepAliveText = text
    val battery = accentButton(activity.getString(R.string.keep_alive_battery))
    keepAliveBattery = battery
    val shizuku = ghostButton(activity.getString(R.string.keep_alive_shizuku))
    keepAliveShizuku = shizuku
    val close =
      ghostButton(activity.getString(R.string.keep_alive_close)).apply {
        setOnClickListener { hideKeepAlivePanel() }
      }
    val sep = (10 * d).toInt()
    battery.layoutParams =
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (16 * d).toInt()
        bottomMargin = sep
      }
    shizuku.layoutParams =
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = sep
      }
    close.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    val body =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
        addView(title)
        addView(text)
        addView(battery)
        addView(shizuku)
        addView(close)
      }
    val inner =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.inset_bg)
        addView(body)
      }
    return LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.card_bg)
      setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
      layoutParams =
        LinearLayout
          .LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
          ).apply {
            topMargin = (32 * d).toInt()
          }
      addView(inner)
    }
  }

  /** Show the keep-alive panel with a status text and the two action buttons
   *  wired by the caller (battery-optimization page / Shizuku boost). */
  fun showKeepAlivePanel(
    statusText: String,
    onBattery: () -> Unit,
    onShizuku: () -> Unit,
  ) {
    keepAliveText?.text = statusText
    keepAliveBattery?.setOnClickListener { onBattery() }
    keepAliveShizuku?.setOnClickListener { onShizuku() }
    keepAliveBlock?.visibility = View.VISIBLE
    statusCard?.visibility = View.GONE
    actionRow?.visibility = View.GONE
  }

  fun updateKeepAliveStatus(text: String) {
    keepAliveText?.text = text
  }

  fun hideKeepAlivePanel() {
    keepAliveBlock?.visibility = View.GONE
    statusCard?.visibility = View.VISIBLE
    statusCard?.contentDescription = activity.getString(R.string.a11y_status_card)
    actionRow?.visibility = View.VISIBLE
  }

  /** The log-viewer overlay view; the caller adds it on top of everything. */
  val logPanelView: View get() = logPanel.view

  /** Reveal the diagnostic log overlay (copy / share / close). */
  fun openLogPanel() {
    logPanel.open()
  }

  /** ACTION_SEND (text/plain) 鈥?no storage permission needed. */
  private fun shareLog(text: String) {
    val intent =
      android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
      }
    try {
      activity.startActivity(
        android.content.Intent.createChooser(intent, activity.getString(R.string.log_share_title)),
      )
    } catch (_: Throwable) {
    }
  }

  /** Vertical three-step card list (runtime 鈫?container 鈫?launch). Each row
   *  is a hairline shell card with an inset inner: numbered circle + title +
   *  status text + trailing state glyph (鉁?done / breathing dot active). */
  private fun buildStepCards(): LinearLayout {
    val palette = GuidePalette(activity)
    val names =
      listOf(
        activity.getString(R.string.step_runtime),
        activity.getString(R.string.step_container),
        activity.getString(R.string.step_launch),
      )
    val circles = arrayOfNulls<TextView>(3)
    val glyphs = arrayOfNulls<TextView>(3)
    val statusTexts = arrayOfNulls<TextView>(3)
    val cards = arrayOfNulls<LinearLayout>(3)
    val list =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams =
          LinearLayout
            .LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
              topMargin = (32 * d).toInt()
            }
      }
    for (i in 0..2) {
      val circle =
        TextView(activity).apply {
          textSize = 12f
          typeface = android.graphics.Typeface.DEFAULT_BOLD
          gravity = android.view.Gravity.CENTER
          setTextColor(0xFFFFFFFF.toInt())
          val size = (24 * d).toInt()
          layoutParams = LinearLayout.LayoutParams(size, size)
        }
      circles[i] = circle
      val title =
        TextView(activity).apply {
          text = names[i]
          textSize = 15f
          typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
          setTextColor(palette.textPrimary)
          setPadding((12 * d).toInt(), 0, 0, 0)
        }
      val status =
        TextView(activity).apply {
          textSize = 13f
          setPadding((12 * d).toInt(), (2 * d).toInt(), 0, 0)
        }
      statusTexts[i] = status
      val titleColumn =
        LinearLayout(activity).apply {
          orientation = LinearLayout.VERTICAL
          layoutParams =
            LinearLayout
              .LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
              ).apply {
                weight = 1f
              }
        }
      titleColumn.addView(title)
      titleColumn.addView(status)
      val glyph =
        TextView(activity).apply {
          textSize = 11f
          typeface = android.graphics.Typeface.DEFAULT_BOLD
          gravity = android.view.Gravity.CENTER
          val size = (22 * d).toInt()
          layoutParams = LinearLayout.LayoutParams(size, size)
        }
      glyphs[i] = glyph
      val body =
        LinearLayout(activity).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = android.view.Gravity.CENTER_VERTICAL
          setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
          addView(circle)
          addView(titleColumn)
          addView(glyph)
        }
      val inset =
        LinearLayout(activity).apply {
          orientation = LinearLayout.VERTICAL
          background = activity.getDrawable(com.dshmobile.shell.R.drawable.inset_bg)
          addView(body)
        }
      val card =
        LinearLayout(activity).apply {
          orientation = LinearLayout.VERTICAL
          background = activity.getDrawable(com.dshmobile.shell.R.drawable.card_bg)
          setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
          layoutParams =
            LinearLayout
              .LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
              ).apply {
                bottomMargin = (12 * d).toInt()
              }
        }
      card.addView(inset)
      cards[i] = card
      val stepLabels = intArrayOf(R.string.a11y_step_card_runtime, R.string.a11y_step_card_container, R.string.a11y_step_card_launch)
      card.contentDescription = activity.getString(stepLabels[i])
      card.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
      list.addView(card)
    }
    stepCircles = circles.map { it!! }.toTypedArray()
    stepGlyphs = glyphs.map { it!! }.toTypedArray()
    stepStatusTexts = statusTexts.map { it!! }.toTypedArray()
    stepCards = cards.map { it!! }.toTypedArray()
    return list
  }

  /** Floating cold-start pill overlaying the Harness: tinted pulse dot +
   *  status + trailing chevron; taps open the full-screen guide. */
  private lateinit var topStatusLabel: TextView

  private fun buildTopStatusBar(): LinearLayout {
    val palette = GuidePalette(activity)
    val dot =
      View(activity).apply {
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.status_dot)
        val size = (8 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
      }
    topPulseDot = dot
    val label =
      TextView(activity).apply {
        setTextColor(palette.textPrimary)
        textSize = 13f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding((10 * d).toInt(), 0, (6 * d).toInt(), 0)
      }
    topStatusLabel = label
    val chevron =
      TextView(activity).apply {
        text = "鈥?
        setTextColor(palette.textSecondary)
        textSize = 16f
      }
    val bar =
      LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((16 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (9 * d).toInt())
        background =
          android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = (22 * d)
            setColor(palette.card)
            alpha = 230
            setStroke((1 * d).toInt(), palette.hairline)
          }
        visibility = View.GONE
        setOnClickListener { showGuideFromTopBar() }
      }
    bar.addView(dot)
    bar.addView(label)
    bar.addView(chevron)
        bar.contentDescription = activity.getString(R.string.a11y_top_bar)
    bar.elevation = (6 * d)
    return bar
  }

  /** Breathing alpha on the status-bar dot (engine working). */
  private fun startTopBarPulse() {
    val dot = topPulseDot ?: return
    // Idempotent: any prior animator must be cancelled or repeated starts
    // would drive the dot with several animators at once (visible jitter).
    topPulseAnimator?.cancel()
    val animator = android.animation.ValueAnimator.ofFloat(1f, 0.25f)
    animator.duration = 900
    animator.repeatMode = android.animation.ValueAnimator.REVERSE
    animator.repeatCount = android.animation.ValueAnimator.INFINITE
    animator.addUpdateListener { dot.alpha = it.animatedValue as Float }
    animator.start()
    topPulseAnimator = animator
  }

  private fun stopTopBarPulse() {
    topPulseAnimator?.cancel()
    topPulseAnimator = null
    topPulseDot?.alpha = 1f
  }
}

