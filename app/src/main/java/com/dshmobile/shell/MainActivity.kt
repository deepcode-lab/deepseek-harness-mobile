package com.dshmobile.shell

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import java.io.File

/**
 * Shell activity: wires the Harness WebView, the boot wizard and the engine
 * lifecycle together. Pure orchestration 鈥?WebView plumbing lives in
 * HarnessWebView, the wizard UI in GuideWizard, picking in PickerBridge,
 * export in ExportFlow, notifications in NotificationHelper.
 */
class MainActivity : ComponentActivity() {
  private lateinit var harness: HarnessWebView
  private lateinit var wizard: GuideWizard
  private lateinit var picker: PickerBridge
  private lateinit var export: ExportFlow
  private lateinit var notifyHelper: NotificationHelper

  /** Engine lifecycle is owned by EngineService (keep-alive + watchdog); the
   *  Activity starts it but never kills it 鈥?onDestroy must not stop the
   *  engine or backgrounding would kill a healthy process that the watchdog
   *  then cold-boots again. */
  private val engineManager by lazy { EngineManager(this) }
  private val engineFlowRunning =
    java.util.concurrent.atomic
      .AtomicBoolean(false)

  /** Engine launch in flight (guards the Launch button against double taps). */
  private val launchInFlight =
    java.util.concurrent.atomic
      .AtomicBoolean(false)

  /** True while the setup wizard asked the user to press Launch manually. */
  private var manualLaunchRequired = false

  /** Screen-on wake lock: reuse a single instance (I-04 鈥?otherwise the lock
   *  could never be released and multiple locks would leak). */
  private var wakeLock: PowerManager.WakeLock? = null

  /** Testable update trigger (see onCreate); derived from the package so
   *  a package rename never leaves a stale action literal. */
  private val actionUpdate: String get() = packageName + ".action.UPDATE"

  /** AGP 8 does not generate BuildConfig by default; use the debuggable flag. */
  private val isDebuggable: Boolean
    get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** Record device/env facts once, so bug reports carry the context needed to
   *  diagnose ABI/runtime issues (e.g. x86_64 snapshot on an arm64 device). */
  private fun logDeviceInfo()
    // Accessibility: configure TalkBack support
    configureAccessibility() {
    val abis =
      android.os.Build.SUPPORTED_ABIS
        .joinToString(",")
    AppLog.log(
      "device",
      "model=" + android.os.Build.MODEL + " sdk=" + android.os.Build.VERSION.SDK_INT +
        " abis=[" + abis + "] debuggable=" + isDebuggable,
    )
  }

  /** Follow the system theme for the window bars (light/dark auto). */
  private fun applyTheme() {
    val palette = GuidePalette(this)
    window.statusBarColor = palette.background
    window.navigationBarColor = palette.background
    val lightFlags =
      if (palette.dark) {
        window.decorView.systemUiVisibility and
          android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            .inv() and
          android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            .inv()
      } else {
        window.decorView.systemUiVisibility or
          android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
          android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
      }
    window.decorView.systemUiVisibility = lightFlags
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AppLog.init(this)
    logDeviceInfo()
    // Accessibility: configure TalkBack support
    configureAccessibility()

    notifyHelper = NotificationHelper(this)
    val onNotify: (String, String) -> Unit = { title, text ->
      runOnUiThread { notifyHelper.showTestNotification(title, text) }
    }
    export = ExportFlow(this, onNotify, { ok, detail -> pushExportResult(ok, detail) })
    picker =
      PickerBridge(
        this,
        onDirectoryPicked = { callbackId, path ->
          harness.postScript(
            "window.__dshBridge?.onDirectoryPicked?.(" + jsString(callbackId) + ", " +
              (path?.let { jsString(it) } ?: "null") + ")",
          )
        },
        onPermissionRequired = { harness.postScript("window.__dshBridge?.onPermissionRequired?.()") },
        notify = onNotify,
      )
    picker.restoreState(savedInstanceState)
    harness =
      HarnessWebView(
        this,
        picker,
        export,
        onNotify,
        onEngineError = {
          showGuide()
          wizard.showTopBar(BarState.FAILED)
        },
        onKeepScreen = { keepScreenOn(it) },
        pickToken = EngineManager.pickToken,
      )
    wizard =
      GuideWizard(
        this,
        harness.view,
        onPrimaryAction = { if (manualLaunchRequired) launchEngine() else startEngineFlow() },
        onCheckUpdate = { statusCb ->
          // UpdateManager's worker thread reports status directly; the wizard
          // UI must only be touched on the main thread.
          UpdateManager(this).checkAndApply { status -> runOnUiThread { statusCb(status) } }
        },
        onCopyLog = { copyLog() },
        onBackToHarness = { showWeb(null) },
        onKeepAlive = { showKeepAlivePanel() },
        onOpenLog = { wizard.openLogPanel() },
        onReload = { harness.view.loadUrl(EngineProbe.ENGINE_URL) },
      )

    val root =
      FrameLayout(this).apply {
        setBackgroundColor(GuidePalette(this@MainActivity).background)
      }
    root.addView(harness.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    root.addView(
      wizard.topStatusBar,
      FrameLayout
        .LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
          android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL,
        ).apply {
          topMargin = (12 * resources.displayMetrics.density).toInt()
        },
    )
    root.addView(wizard.guideView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    root.addView(wizard.logPanelView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    setContentView(root)
    applyTheme()
    harness.configure()

    // Quick path: rootfs AND container already provisioned 鈫?go straight to
    // the Harness; the cold start is covered by the thin status bar, not the
    // full-screen guide.
    val provisioned =
      File(filesDir, DshPaths.ROOTFS_DIR + "/" + DshPaths.ROOTFS_BASH).isFile &&
        File(filesDir, DshPaths.ROOTFS_DIR + "/" + DshPaths.DSH_ENTRY).isFile
    if (provisioned) {
      harness.view.visibility = View.VISIBLE
    } else {
      wizard.guideView.visibility = View.VISIBLE
    }
    // Testable update trigger: adb am start -n .../.MainActivity -a com.dshmobile.shell.action.UPDATE
    if (intent?.action == actionUpdate) {
      // I-03: the activity is exported (LAUNCHER), so any app can fire this
      // intent and trigger the download+execute chain 鈥?accept it only in
      // debug builds and ignore it in release.
      if (isDebuggable) runUpdate()
    } else {
      startEngineFlow()
    }
  }

  override fun onResume() {
    super.onResume()
    // Back from the directory picker / Termux: re-route if the engine came up.
    // I-05: the probe performs network I/O; calling it on the main thread
    // always throws NetworkOnMainThreadException (swallowed) 鈫?it would always
    // report "not running" and force a reload losing page state on every
    // return to foreground. Move it to a background thread.
    Thread {
      val running = EngineProbe.check().optBoolean("running", false)
      if (!running) startEngineFlow()
    }.start()
  }

  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    harness.pushSystemDark()
    applyTheme()
  }

  override fun onBackPressed() {
    if (harness.canGoBack()) harness.goBack() else super.onBackPressed()
  }

  override fun onSaveInstanceState(outState: android.os.Bundle) {
    super.onSaveInstanceState(outState)
    picker.saveState(outState)
  }

  override fun onDestroy() {
    super.onDestroy()
    wizard.onDestroy()
    // Screen-on wake lock: release unconditionally 鈥?a held lock survives
    // the activity (and any recreation), keeping the screen on forever and
    // draining the battery; the page re-requests keep-screen-on after a
    // recreation.
    if (wakeLock?.isHeld == true) {
      wakeLock?.release()
      wakeLock = null
    }
    // The engine keeps running: EngineService owns its lifecycle (watchdog
    // restarts it on death, stopEngine is only invoked when the service
    // itself is stopped). Killing it here would destroy a healthy engine the
    // watchdog then cold-boots again a few seconds later.
  }

  /** Report the export result to the WebView: the UI plugin shows an in-app
   *  result dialog via window.__dshExportResult. */
  private fun pushExportResult(
    ok: Boolean,
    detail: String,
  ) {
    val title = if (ok) getString(R.string.export_success) else getString(R.string.export_failed)
    val payload = "{\"ok\":" + ok + ",\"title\":" + jsString(title) + ",\"detail\":" + jsString(detail) + "}"
    harness.postScript("window.__dshExportResult && window.__dshExportResult(" + payload + ")")
  }

  private fun copyLog() {
    val copied = AppLog.copyToClipboard(this)
    notifyHelper.showTestNotification(
      getString(R.string.notif_log_copied),
      getString(R.string.notif_log_copied_detail, copied.lines().size.toString()),
    )
  }

  private fun keepScreenOn(enable: Boolean) {
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    val lock =
      wakeLock ?: power
        .newWakeLock(
          PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
          DshPaths.WAKE_LOCK_TAG,
        ).also { wakeLock = it }
    if (enable && !lock.isHeld) lock.acquire()
    if (!enable && lock.isHeld) lock.release()
  }

  // ---- Engine flow --------------------------------------------------------

  /**
   * Engine-first flow: use an already-running engine (Termux or prior
   * embedded), else extract the embedded snapshot and install the mandatory
   * Ubuntu container, then either wait for the manual Launch action (any
   * setup ran) or cold-start straight under the thin status bar.
   */
  private fun startEngineFlow() {
    // Both onCreate and the following onResume trigger this flow; the
    // in-flight guard prevents a double-threaded extract/start race (observed
    // on device: a double start kills the engine process).
    if (!engineFlowRunning.compareAndSet(false, true)) return
    AppLog.log("boot", "engine flow start")
    Thread {
      try {
        val probe = EngineProbe.check()
        AppLog.log(
          "boot",
          "probe before start: " + probe.optBoolean("running", false) +
            " latency=" + probe.optInt("latencyMs", -1) + " error=" + probe.optString("error", "-"),
        )
        if (probe.optBoolean("running", false)) {
          runOnUiThread { showWeb() }
          return@Thread
        }
        var setupRan = false
        val prootRuntime = ProotRuntime(this)
        val rootfsDir = engineManager.rootfsDir
        if (!engineManager.engineReady) {
          runOnUiThread {
            showGuide()
            wizard.renderSteps(0, 0)
            wizard.showGuideStatus(getString(R.string.status_first_extract), null, true)
          }
          AppLog.log("boot", "extracting rootfs to " + rootfsDir)
          val ok =
            engineManager.extractRootfs { done, _ ->
              runOnUiThread {
                // done is extracted bytes; total is the archive bytes (different
                // baselines) 鈥?show only the extracted amount.
                wizard.showGuideStatus(
                  getString(R.string.status_extracting, done / 1024 / 1024),
                  null,
                  true,
                )
              }
            }
          if (!ok) {
            runOnUiThread {
              wizard.showGuideError(getString(R.string.status_extract_failed))
            }
            AppLog.log("boot", "extract FAILED")
            return@Thread
          }
          setupRan = true
          AppLog.log("boot", "extract ok, engineReady=" + engineManager.engineReady)
        }
        // Step 2 鈥?container is mandatory: proot runtime must be present and
        // a real in-container command must pass (rootfs bash + node). A
        // failing container counts as an engine start failure 鈥?the engine is
        // not started without it. Every sub-step is logged under boot: so the
        // container init is visible in diagnostics.
        if (!prootRuntime.ensureProot()) {
          AppLog.log("boot", "container init FAILED: proot runtime unavailable")
          runOnUiThread {
            wizard.renderSteps(1, 1)
            wizard.showGuideError(getString(R.string.status_container_init_failed))
          }
          return@Thread
        }
        val projectsDir = File(engineManager.ensureDshDataHome(), DshPaths.PROJECTS_DIR)
        val containerProbe = ContainerProbe(prootRuntime, rootfsDir, projectsDir, EngineManager.pickToken)
        val smoke = containerProbe.smokeTest()
        if (smoke != null) {
          AppLog.log("boot", "container init FAILED: " + smoke)
          runOnUiThread {
            wizard.renderSteps(1, 1)
            wizard.showGuideError(getString(R.string.status_container_init_failed))
          }
          return@Thread
        }
        AppLog.log("boot", "container init: smoke test pass")
        // Step 3 鈥?after any setup, the user launches the engine manually;
        // a fully provisioned install (snapshot + container) starts straight
        // into the Harness.
        if (setupRan) {
          runOnUiThread {
            manualLaunchRequired = true
            showGuide()
            wizard.renderSteps(2, 2)
            wizard.showLaunchReady()
          }
          AppLog.log("boot", "setup done, waiting for manual launch")
          return@Thread
        }
        // Quick path: everything provisioned 鈫?cold start under the thin bar.
        runOnUiThread {
          harness.view.visibility = View.VISIBLE
          wizard.showTopBar(BarState.STARTING)
        }
        launchEngineInternal()
      } catch (t: Throwable) {
        AppLog.log("boot", "engine flow exception", t)
        runOnUiThread {
          wizard.showGuide()
          wizard.showGuideError(getString(R.string.status_engine_start_failed))
        }
      } finally {
        engineFlowRunning.set(false)
      }
    }.start()
  }

  /** Manual launch action (guide primary button). */
  private fun launchEngine() {
    if (!launchInFlight.compareAndSet(false, true)) return
    wizard.showGuideStatus(getString(R.string.status_engine_starting), null, true)
    Thread {
      launchEngineInternal()
      launchInFlight.set(false)
    }.start()
  }

  /** Start the engine and poll until the web service answers. */
  private fun launchEngineInternal() {
    try {
      if (!engineManager.startEngine()) {
        runOnUiThread {
          wizard.showGuide()
          wizard.showGuideError(getString(R.string.status_engine_start_failed))
        }
        AppLog.log("boot", "startEngine() returned false")
        return
      }
      // Poll up to 60s for the web service (cold boot takes 20-45s; a CAS
      // or cooldown-deferred start can push the answer past 30s).
      var reached = false
      for (i in 0..60) {
        if (EngineProbe.check().optBoolean("running", false)) {
          reached = true
          startEngineService()
          applyShizukuKeepAlive()
          runOnUiThread { showWeb() }
          break
        }
        Thread.sleep(1000)
      }
      if (!reached) {
        AppLog.log("boot", "engine web service not reachable within 30s poll")
        val proc = EngineManager.engineProcess
        if (proc == null) {
          AppLog.log("boot", "engine process: null")
        } else if (!proc.isAlive) {
          val code =
            try {
              proc.exitValue()
            } catch (_: Exception) {
              -1
            }
          AppLog.log("boot", "engine process DEAD exitValue=" + code)
        } else {
          AppLog.log("boot", "engine process alive but web service down")
        }
        AppLog.includeFile(java.io.File(filesDir, DshPaths.ENGINE_LOG), DshPaths.ENGINE_LOG)
        runOnUiThread {
          wizard.showGuide()
          wizard.showGuideError(getString(R.string.status_engine_timeout))
        }
      } else {
        AppLog.log("boot", "engine reachable, showing web")
      }
    } catch (t: Throwable) {
      AppLog.log("boot", "engine launch exception", t)
      runOnUiThread {
        wizard.showGuide()
        wizard.showGuideError(getString(R.string.status_engine_start_failed))
      }
    }
  }

  /** Run the runtime snapshot update; status mirrored to a file for adb verification. */
  private fun runUpdate() {
    val statusFile = java.io.File(filesDir, "update-status.txt")
    val manager = UpdateManager(this)
    manager.checkAndApply { status ->
      runOnUiThread {
        showGuide()
        wizard.showGuideStatus(status, null, true)
      }
      try {
        statusFile.appendText(status + "\n")
      } catch (_: Exception) {
      }
    }
  }

  /** Start the foreground service (engine keep-alive + watchdog) and arm the
   *  heartbeat recovery alarm. */
  private fun startEngineService() {
    KeepAliveAlarm.schedule(this)
    try {
      startForegroundService(Intent(this, EngineService::class.java))
    } catch (_: Exception) {
      // Foreground-service start limits: service will start on next launch.
    }
  }

  /** Keep-alive settings panel (battery-optimization exemption + Shizuku
   *  appops boost + OEM autostart hint). */
  private fun showKeepAlivePanel() {
    val batteryLine =
      getString(
        if (ShizukuSupport.isBatteryOptimizationIgnored(this)) {
          R.string.keep_alive_battery_ignored
        } else {
          R.string.keep_alive_battery_not_ignored
        },
      )
    val shizukuLine = ShizukuSupport.status(this)
    val status = batteryLine + "\n" + shizukuLine + "\n" + getString(R.string.keep_alive_autostart_hint)
    wizard.showKeepAlivePanel(
      status,
      onBattery = {
        if (ShizukuSupport.isBatteryOptimizationIgnored(this)) {
          wizard.updateKeepAliveStatus(
            status.replaceFirst(
              getString(R.string.keep_alive_battery_not_ignored),
              getString(R.string.keep_alive_battery_ignored),
            ),
          )
          return@showKeepAlivePanel
        }
        try {
          startActivity(
            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
              .setData(android.net.Uri.parse("package:" + packageName)),
          )
        } catch (_: Exception) {
          try {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
          } catch (_: Exception) {
          }
        }
      },
      onShizuku = {
        ShizukuSupport.applyAppOpsBoost(this) { result ->
          runOnUiThread {
            wizard.updateKeepAliveStatus(
              batteryLine + "\n" + getString(R.string.shizuku_boost) + result + "\n" +
                getString(R.string.keep_alive_autostart_hint),
            )
          }
        }
      },
    )
  }

  /** Best-effort Shizuku keep-alive boost; outcome logged only. */
  private fun applyShizukuKeepAlive() {
    try {
      Thread {
        val result = ShizukuSupport.status(this)
        Log.i("dsh-shizuku", result)
      }.start()
    } catch (_: Throwable) {
    }
  }

  /** Show the Harness; a null [barState] keeps the current bar state (the
   *  FAILED bar must persist when returning from the error guide, I-26). */
  private fun showWeb(barState: BarState? = BarState.SUCCESS) {
    // Reload only when the page actually failed to load (error page shown
    // before the engine answered); onResume/pick-return must NOT reload a
    // healthy page 鈥?that discards session UI and races in-flight pick
    // callbacks.
    harness.reloadIfFailed()
    if (barState != null) wizard.showTopBar(barState)
    wizard.showWeb()
  }

  private fun showGuide() {
    wizard.showGuide()
  }

  /** Configure accessibility: enable font scaling, set up TalkBack hints. */
  private fun configureAccessibility() {
    val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (am?.isEnabled == true) {
      AppLog.log("a11y", "accessibility service enabled: " + am.installedAccessibilityServiceNames.size + " services")
    }
    window.decorView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
  }}

