package app.decibel.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.FileProvider
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Native side of background playback. The audio itself still plays in the WebView (two-deck crossfade,
 * gapless, ReplayGain, offline files); this object makes Android treat that as real media playback:
 *  - MediaSession + MediaStyle notification (lock screen, notification shade, Bluetooth/headset buttons)
 *    run by MediaPlaybackService, a mediaPlayback foreground service, while there is something playing.
 *  - partial WakeLock + WifiLock while sound is playing, so the CPU and Wi-Fi do not sleep with the screen off.
 *  - "becoming noisy" (headphones unplugged). Audio focus is left to the WebView (Chromium requests it for
 *    the <audio> element and pauses/resumes/ducks it); a second holder here made them steal it from each other.
 * JS talks to it through `window.DecibelNative` (update / artwork / stop); native answers by calling
 * `window.__decibelNative(command, arg)`.
 */
@SuppressLint("StaticFieldLeak") // only the application context is kept
object DecibelMedia {
  private const val TAG = "DecibelMedia"
  private val main = Handler(Looper.getMainLooper())
  private lateinit var app: Context
  private var activity = WeakReference<Activity>(null)
  private var webView = WeakReference<WebView>(null)

  var session: MediaSession? = null; private set
  var title = ""; private set
  var artist = ""; private set
  var album = ""; private set
  var art: Bitmap? = null; private set
  var playing = false; private set
  private var trackId = ""
  private var artId = ""
  private var durationMs = 0L
  private var positionMs = 0L
  private var active = false // a track is loaded (playing or paused)
  /** Podcast episode: notification / headset "previous" and "next" seek back 15 s and forward 30 s. */
  var podcast = false; private set
  private var rate = 1f
  private var updates = 0 // bridge calls received (diagnostics)
  /** In-app update download: idle, downloading:<pct>, verifying, installing or error:<message>. */
  @Volatile private var updateState = "idle"
  @Volatile private var pendingApk: File? = null
  @Volatile var autoReady = false; private set
  @Volatile var sharedText = "" // set by MainActivity from an ACTION_SEND intent, read once by JS
  private var autoSequence = 0
  private var autoPlaybackGeneration = 0
  private val autoRequests = ConcurrentHashMap<Int, (JSONObject?) -> Unit>()
  private var autoError: String? = null

  /** True while the WebView must keep running in the background. */
  val keepAlive get() = active || AutoBrowserService.connected

  // ---- lifecycle -------------------------------------------------------------------------------------
  fun attach(activity: Activity) {
    this.activity = WeakReference(activity)
    if (!::app.isInitialized) app = activity.applicationContext
  }

  @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
  fun attachWebView(view: WebView) {
    setAutoReady(false)
    webView = WeakReference(view)
    view.addJavascriptInterface(Bridge, "DecibelNative")
  }

  /** The car may bind before the Activity exists. Keep one token for the entire process lifetime. */
  fun initialize(context: Context): MediaSession {
    if (!::app.isInitialized) app = context.applicationContext
    ensureSession()
    webView.get()?.onResume()
    publish()
    return session!!
  }

  fun release() {
    active = false
    setPlaying(false)
    watchInterruptions(false)
    trackId = ""; title = ""; artist = ""; album = ""; art = null
    positionMs = 0; durationMs = 0
    publish()
    MediaPlaybackService.stop(app)
  }

  fun detach() {
    setAutoReady(false)
    webView.clear()
    release()
  }

  // ---- JS → native -----------------------------------------------------------------------------------
  object Bridge {
    @JavascriptInterface fun update(json: String) { main.post { applyUpdate(json) } }
    @JavascriptInterface fun artwork(id: String, base64: String) { main.post { applyArtwork(id, base64) } }
    @JavascriptInterface fun artworkUrl(id: String, url: String) {
      if (!url.startsWith("https://")) return
      Thread {
        try {
          val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
          connection.connectTimeout = 10_000; connection.readTimeout = 15_000
          val bytes = connection.inputStream.use { it.readBytes() }
          connection.disconnect()
          main.post { applyArtworkBytes(id, bytes) }
        } catch (e: Exception) { Log.w(TAG, "artwork", e) }
      }.start()
    }
    @JavascriptInterface fun stop() { main.post { release() } }
    @JavascriptInterface fun version(): Int = 8
    @JavascriptInterface fun sharedText(): String = sharedText.also { sharedText = "" }
    @JavascriptInterface fun autoReady(ready: Boolean) { main.post { setAutoReady(ready) } }
    @JavascriptInterface fun autoRequestActive(id: Int): Boolean = autoReady && autoRequests.containsKey(id)
    @JavascriptInterface fun autoResult(id: Int, json: String) {
      main.post {
        val result = try { JSONObject(json) } catch (_: Exception) { null }
        autoPlayRequests.remove(id)
        autoRequests.remove(id)?.invoke(result)
      }
    }
    @JavascriptInterface fun autoRefresh() { main.post { AutoBrowserService.catalogChanged() } }
    /** Whether the playback notification can be shown (permission granted and notifications on for the app). */
    @JavascriptInterface fun notificationsEnabled(): Boolean = notificationsAllowed()
    /** System dialog when Android still allows it, otherwise the app's notification settings. */
    @JavascriptInterface fun requestNotifications() { main.post { requestNotificationsFromApp() } }
    @JavascriptInterface fun openNotificationSettings() { main.post { openSettings() } }
    /**
     * Android share sheet for an image made in the WebView (Wrapped summary). The WebView has no Web Share
     * API, so the PNG/JPEG arrives as base64, is written to the app cache and offered through the
     * FileProvider Tauri already declares (`${applicationId}.fileprovider`, cache-path). Returns false
     * if it could not be prepared, so JS falls back to a download.
     */
    @JavascriptInterface fun shareImage(base64: String, mime: String, text: String): Boolean = try {
      val type = if (mime == "image/jpeg") "image/jpeg" else "image/png"
      val bytes = Base64.decode(base64.substringAfter(","), Base64.DEFAULT)
      val dir = File(app.cacheDir, "share").apply { mkdirs() }
      dir.listFiles()?.forEach { it.delete() } // only the latest shared image is kept
      val file = File(dir, "decibel-wrapped-${System.currentTimeMillis()}.${if (type == "image/jpeg") "jpg" else "png"}")
      file.writeBytes(bytes)
      val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
      val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      if (text.isNotBlank()) send.putExtra(Intent.EXTRA_TEXT, text)
      send.clipData = ClipData.newRawUri("Decibel", uri)
      val chooser = Intent.createChooser(send, "Compartir").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      main.post {
        try {
          val from = activity.get()
          if (from != null) from.startActivity(chooser) else app.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Log.w(TAG, "share sheet", e) }
      }
      true
    } catch (e: Exception) { Log.w(TAG, "shareImage", e); false }
    /**
     * App update (0.1.9, bridge 5): downloads the APK over https into the app cache, checks its sha256 and opens
     * the system installer through the FileProvider (cache-path). Android itself refuses an APK signed with
     * another key. Progress is polled from JS with updateStatus(). Returns false if it cannot start.
     */
    @JavascriptInterface fun installUpdate(url: String, sha256: String, version: String): Boolean {
      if (!url.startsWith("https://") || !Regex("[a-f0-9]{64}").matches(sha256) || !Regex("[0-9][0-9A-Za-z.-]{0,30}").matches(version)) return false
      if (updateState.startsWith("downloading") || updateState == "verifying") return true
      pendingApk = null
      updateState = "downloading:0"
      Thread {
        try {
          val dir = File(app.cacheDir, "updates").apply { mkdirs() }
          dir.listFiles()?.forEach { it.delete() }
          val file = File(dir, "Decibel-$version.apk")
          val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = false }
          if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
          val total = conn.contentLengthLong
          val digest = MessageDigest.getInstance("SHA-256")
          var done = 0L
          conn.inputStream.use { input -> file.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              val n = input.read(buffer); if (n < 0) break
              out.write(buffer, 0, n); digest.update(buffer, 0, n); done += n
              if (total > 0) updateState = "downloading:${(done * 100 / total).toInt()}"
            }
          } }
          updateState = "verifying"
          val hex = digest.digest().joinToString("") { "%02x".format(it) }
          if (hex != sha256) { file.delete(); throw IllegalStateException("sha256 no coincide") }
          pendingApk = file
          main.post { launchInstaller() }
        } catch (e: Exception) {
          Log.w(TAG, "installUpdate", e)
          updateState = "error:${e.message ?: "descarga fallida"}"
        }
      }.start()
      return true
    }
    @JavascriptInterface fun updateStatus(): String = updateState
    /**
     * 0.1.11 (bridge 6): after «Instalar apps desconocidas» was granted in Settings, hands the already
     * downloaded and verified APK to the installer without downloading it again. False if there is none.
     */
    @JavascriptInterface fun resumeInstall(): Boolean {
      if (pendingApk?.exists() != true) return false
      main.post { launchInstaller() }
      return true
    }
    /** Whether Android lets this app open the package installer (Settings → «Instalar apps desconocidas»). */
    @JavascriptInterface fun canInstallPackages(): Boolean =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageManager.canRequestPackageInstalls()

    /**
     * System installer for the verified APK through the FileProvider (cache-path). Without the «install
     * unknown apps» permission it opens that settings screen for Decibel instead and reports "permission";
     * JS calls resumeInstall() when the app comes back. Same keystore, so it installs over the current app.
     */
    private fun launchInstaller() {
      val file = pendingApk
      if (file == null || !file.exists()) { updateState = "error:No se encontró la descarga"; return }
      val from = activity.get()
      try {
        if (!canInstallPackages()) {
          val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${app.packageName}"))
          if (from != null) from.startActivity(settings) else app.startActivity(settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          updateState = "permission"
          return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val install = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (from != null) from.startActivity(install) else app.startActivity(install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        updateState = "installing"
      } catch (e: Exception) { Log.w(TAG, "installer", e); updateState = "error:No se pudo abrir el instalador" }
    }
    /** Opens an https page (the download page) in the browser. */
    @JavascriptInterface fun openUrl(url: String) {
      if (!url.startsWith("https://")) return
      main.post {
        try {
          val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
          val from = activity.get()
          if (from != null) from.startActivity(view) else app.startActivity(view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Log.w(TAG, "openUrl", e) }
      }
    }
    /** JSON snapshot for the "Reproducción en segundo plano" check in Ajustes. */
    @JavascriptInterface fun diagnostics(): String = JSONObject()
      .put("bridge", 4)
      .put("updates", updates)
      .put("active", active)
      .put("playing", playing)
      .put("service", MediaPlaybackService.state)
      .put("serviceError", MediaPlaybackService.lastError ?: JSONObject.NULL)
      .put("notifications", notificationsAllowed())
      .put("permission", permissionState())
      .put("sdk", Build.VERSION.SDK_INT)
      .put("focus", "webview")
      .put("interrupted", interrupted)
      .put("pauses", pauseLog())
      .toString()
  }

  private fun applyUpdate(json: String) {
    val data = try { JSONObject(json) } catch (e: Exception) { return }
    if (updates++ == 0) Log.i(TAG, "bridge connected")
    val id = data.optString("id")
    if (id != trackId) { trackId = id; if (artId != id) art = null }
    title = data.optString("title")
    artist = data.optString("artist")
    album = data.optString("album")
    durationMs = (data.optDouble("duration", 0.0) * 1000).toLong().coerceAtLeast(0)
    positionMs = (data.optDouble("position", 0.0) * 1000).toLong().coerceAtLeast(0)
    podcast = data.optBoolean("podcast")
    rate = data.optDouble("rate", 1.0).toFloat().takeIf { it in 0.25f..4f } ?: 1f
    active = id.isNotEmpty()
    if (!active) { release(); return }
    autoError = null
    ensureSession()
    setPlaying(data.optBoolean("playing"))
    publish()
  }

  private fun applyArtwork(id: String, base64: String) {
    val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) { return }
    applyArtworkBytes(id, bytes)
  }

  private fun applyArtworkBytes(id: String, bytes: ByteArray) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 512) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
    artId = id
    if (id == trackId) { art = bitmap; publish() }
  }

  // ---- native → JS -----------------------------------------------------------------------------------
  fun command(name: String, arg: Double? = null) {
    if (name == "pause" || name == "next" || name == "prev") cancelAutoPlayback()
    val js = "window.__decibelNative&&window.__decibelNative('$name',${arg ?: "null"})"
    main.post { webView.get()?.evaluateJavascript(js, null) }
  }

  // ---- Android Auto: asynchronous requests to the authenticated WebView -------------------------------
  private fun cancelAutoPlayback() {
    autoPlaybackGeneration++
    // Remove the request before notifying JS; its guard must reject late provider responses.
    for (id in autoPlayRequests.toList()) {
      autoPlayRequests.remove(id)
      autoRequests.remove(id)?.invoke(null)
    }
  }

  private val autoPlayRequests = mutableSetOf<Int>()

  private fun setAutoReady(ready: Boolean) {
    autoReady = ready && webView.get() != null
    if (!autoReady) {
      cancelAutoPlayback()
      val pending = autoRequests.values.toList()
      autoRequests.clear()
      pending.forEach { it(null) }
    }
    autoError = null
    if (::app.isInitialized) { ensureSession(); publish() }
    AutoBrowserService.catalogChanged()
  }

  fun requestAuto(action: String, value: String, callback: (JSONObject?) -> Unit) {
    if (!autoReady || webView.get() == null) {
      autoFailure("Abre Decibel en el teléfono antes de conducir")
      callback(null)
      return
    }
    val id = ++autoSequence
    autoRequests[id] = callback
    if (action != "browse") autoPlayRequests.add(id)
    val request = JSONObject().put("id", id).put("action", action).put("value", value)
    webView.get()?.evaluateJavascript("window.__decibelAuto&&window.__decibelAuto($request)", null)
    main.postDelayed({
      autoPlayRequests.remove(id)
      autoRequests.remove(id)?.invoke(null)
    }, 12_000)
  }

  private fun playAuto(action: String, value: String) {
    cancelAutoPlayback()
    val generation = autoPlaybackGeneration
    requestAuto(action, value) { response ->
      if (generation == autoPlaybackGeneration) {
        if (response == null) autoFailure(if (autoReady) "No se pudo cargar la música. Inténtalo de nuevo" else "Abre Decibel en el teléfono antes de conducir")
        else if (response.has("error")) autoFailure("No se pudo reproducir la selección. Inténtalo de nuevo")
      }
    }
  }

  fun autoFailure(message: String) {
    autoError = message
    publish()
  }

  fun autoRecovered() {
    if (autoError != null) { autoError = null; publish() }
  }

  // ---- MediaSession ----------------------------------------------------------------------------------
  private fun ensureSession() {
    if (session != null) return
    session = MediaSession(app, "Decibel").apply {
      setCallback(object : MediaSession.Callback() {
        override fun onPlay() {
          if (!autoReady && !active) autoFailure("Abre Decibel en el teléfono antes de conducir")
          else if (active) { cancelAutoPlayback(); command("play") }
          else playAuto("search", "")
        }
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) { if (mediaId != null) playAuto("play", mediaId) }
        override fun onPlayFromSearch(query: String?, extras: Bundle?) { playAuto("search", query ?: "") }
        override fun onPause() {
          cancelAutoPlayback()
          pauseBy("system", "MediaSession (auriculares, Bluetooth, pantalla de bloqueo)")
        }
        override fun onSkipToNext() { command(if (podcast) "forward" else "next") }
        override fun onSkipToPrevious() { command(if (podcast) "back" else "prev") }
        override fun onFastForward() { command("forward") }
        override fun onRewind() { command("back") }
        override fun onSeekTo(pos: Long) { positionMs = pos; publish(); command("seek", pos / 1000.0) }
        override fun onStop() {
          cancelAutoPlayback()
          pauseBy("system", "MediaSession onStop")
        }
      })
      @Suppress("DEPRECATION")
      setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
      setSessionActivity(PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
      isActive = true
    }
  }

  private fun publish() {
    val s = session ?: return
    val meta = MediaMetadata.Builder()
      .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, trackId)
      .putString(MediaMetadata.METADATA_KEY_TITLE, title)
      .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
      .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
      .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs) // > 0 gives the progress bar (Android 13+)
    art?.let { meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it).putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
    s.setMetadata(meta.build())
    val error = autoError ?: if (!autoReady && !active) "Abre Decibel en el teléfono antes de conducir" else null
    val state = PlaybackState.Builder()
      .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH or
        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or
        (if (podcast) PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND else 0L))
      .setState(if (playing) PlaybackState.STATE_PLAYING else if (error != null) PlaybackState.STATE_ERROR else if (active) PlaybackState.STATE_PAUSED else PlaybackState.STATE_NONE,
        positionMs, if (playing) rate else 0f, SystemClock.elapsedRealtime())
    if (error != null) {
      state.setErrorMessage(error)
      if (!autoReady) state.setExtras(Bundle().apply {
        putString("android.media.extras.ERROR_RESOLUTION_ACTION_LABEL", "Abrir Decibel")
        putParcelable("android.media.extras.ERROR_RESOLUTION_ACTION_INTENT", s.controller.sessionActivity)
      })
    }
    s.setPlaybackState(state.build())
    if (active) MediaPlaybackService.refresh(app)
  }

  // ---- playing / paused ------------------------------------------------------------------------------
  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null
  private var noisyRegistered = false

  private fun setPlaying(value: Boolean) {
    if (value == playing) { if (value) MediaPlaybackService.start(app); return }
    playing = value
    if (value) {
      playingSince = SystemClock.elapsedRealtime()
      watchInterruptions(true)
      MediaPlaybackService.start(app)
      acquireLocks()
      registerNoisy(true)
      askNotificationPermissionOnce() // normally already asked at launch; covers a launch where the dialog could not show
    } else {
      releaseLocks()
      registerNoisy(false)
    }
  }

  @SuppressLint("WakelockTimeout")
  private fun acquireLocks() {
    if (wakeLock == null) wakeLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Decibel:playback").apply { setReferenceCounted(false) }
    wakeLock?.acquire()
    if (wifiLock == null) {
      val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
      @Suppress("DEPRECATION") // still honoured for apps with a running foreground service
      val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
      wifiLock = wifi.createWifiLock(mode, "Decibel:playback").apply { setReferenceCounted(false) }
    }
    try { wifiLock?.acquire() } catch (e: Exception) { Log.w(TAG, "wifi lock", e) }
  }

  private fun releaseLocks() {
    if (wakeLock?.isHeld == true) wakeLock?.release()
    if (wifiLock?.isHeld == true) wifiLock?.release()
  }

  private val noisy = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseBy("noisy", "auriculares desconectados")
    }
  }

  private fun registerNoisy(on: Boolean) {
    if (on == noisyRegistered) return
    noisyRegistered = on
    if (on) {
      val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
      if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
      else app.registerReceiver(noisy, filter)
    } else try { app.unregisterReceiver(noisy) } catch (e: Exception) {}
  }

  // ---- notification permission (Android 13+) ---------------------------------------------------------
  // Asked automatically once (first launch, or the first play if the launch could not show the dialog). The
  // flag is only stored when Android answered, so a request it dropped is retried. Without the permission
  // playback and the foreground service still work; Android may then hide the notification, and Ajustes
  // offers a button that opens the dialog again or the app's notification settings.
  private const val PREF_ASKED = "notification-permission-answered" // new key: 0.1.4 stored its flag before asking, even if Android dropped the request
  private var asking = false
  private val prefs get() = app.getSharedPreferences("decibel", Context.MODE_PRIVATE)

  private fun permissionGranted() = Build.VERSION.SDK_INT < 33 ||
    app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  private fun permissionState() = when {
    Build.VERSION.SDK_INT < 33 -> "not-needed"
    permissionGranted() -> "granted"
    !prefs.getBoolean(PREF_ASKED, false) -> "not-asked"
    else -> "denied"
  }

  private fun notificationsAllowed() =
    ::app.isInitialized && permissionGranted() && (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()

  fun askNotificationPermissionOnce() {
    if (!::app.isInitialized || permissionGranted() || prefs.getBoolean(PREF_ASKED, false)) return
    showPermissionDialog()
  }

  private fun showPermissionDialog(): Boolean {
    if (Build.VERSION.SDK_INT < 33 || asking) return asking
    val current = activity.get() as? MainActivity ?: return false
    asking = current.requestNotificationPermission()
    Log.i(TAG, if (asking) "asking POST_NOTIFICATIONS" else "POST_NOTIFICATIONS request deferred (activity not resumed)")
    return asking
  }

  fun onNotificationPermissionResult(granted: Boolean) {
    asking = false
    prefs.edit().putBoolean(PREF_ASKED, true).apply()
    Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
    MediaPlaybackService.refresh(app)
    command("notifications", if (notificationsAllowed()) 1.0 else 0.0)
  }

  private fun requestNotificationsFromApp() {
    if (notificationsAllowed()) { command("notifications", 1.0); return }
    val current = activity.get()
    // Dialog while Android still shows it (never asked, or denied once); after that only Settings can grant it.
    val canAsk = Build.VERSION.SDK_INT >= 33 && !permissionGranted() &&
      (!prefs.getBoolean(PREF_ASKED, false) || current?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true)
    if (!(canAsk && showPermissionDialog())) openSettings()
  }

  private fun openSettings() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", app.packageName, null))
    val from = activity.get()
    try {
      if (from != null) from.startActivity(intent) else app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { Log.w(TAG, "notification settings", e) }
  }

  // ---- audio focus: owned by the WebView ---------------------------------------------------------
  // Chromium's AudioFocusDelegate already requests AUDIOFOCUS_GAIN for every <audio> that plays and pauses /
  // resumes / ducks it on focus changes (calls, other apps, voice notes, assistant). Until 0.1.5 this object
  // ALSO requested GAIN when JS reported "playing": Android gives focus to one holder at a time, so our request
  // made the WebView lose it (Chromium paused the <audio>), and pressing play again made the WebView take it back
  // and our listener lose it (we sent "pause"): play → pause almost at once, every time. 0.1.6: a single owner
  // (the WebView); no AudioRecordingCallback either (recorders that matter request focus themselves).

  // ---- interruptions: calls, voice notes, other apps recording ------------------------------------------
  // Observed without holding audio focus (see above): another app recording (AudioRecordingCallback, API 24+)
  // or the phone in a call / VoIP call / ringing (OnModeChangedListener, API 31+). JS gets
  // "interrupt" 1 when one begins and 0 when all have ended; it fades the music out and pauses, then fades it back
  // in, unless the user changed something meanwhile (src/lib/audio/interruption.ts).
  private var interrupted = false
  private var recording = false
  private var inCall = false
  private var watching = false
  private val audioManager by lazy { app.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
  private val recordings = object : AudioManager.AudioRecordingCallback() {
    override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
      recording = !configs.isNullOrEmpty()
      interruptionChanged("recording")
    }
  }
  private val modeListener: Any? = if (Build.VERSION.SDK_INT >= 31) AudioManager.OnModeChangedListener { mode ->
    inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE ||
      (Build.VERSION.SDK_INT >= 33 && mode == AudioManager.MODE_CALL_SCREENING)
    interruptionChanged("call")
  } else null

  private fun interruptionChanged(cause: String) {
    val now = recording || inCall
    if (now == interrupted) return
    interrupted = now
    Log.i(TAG, "interruption ${if (now) "begins" else "ends"} ($cause)")
    if (now) notePause("recording", if (cause == "call") "llamada" else "otra app está grabando (nota de voz…)")
    command("interrupt", if (now) 1.0 else 0.0)
  }

  /** Watch while there is a session (playing or paused by an interruption), so the end is seen too. */
  private fun watchInterruptions(on: Boolean) {
    if (on == watching || !::app.isInitialized) return
    watching = on
    try {
      if (on) {
        audioManager.registerAudioRecordingCallback(recordings, main)
        recording = audioManager.activeRecordingConfigurations.isNotEmpty()
        if (Build.VERSION.SDK_INT >= 31) {
          audioManager.addOnModeChangedListener({ main.post(it) }, modeListener as AudioManager.OnModeChangedListener)
          val mode = audioManager.mode
          inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
        }
        interrupted = recording || inCall
      } else {
        audioManager.unregisterAudioRecordingCallback(recordings)
        if (Build.VERSION.SDK_INT >= 31) audioManager.removeOnModeChangedListener(modeListener as AudioManager.OnModeChangedListener)
        recording = false; inCall = false; interrupted = false
      }
    } catch (e: Exception) { Log.w(TAG, "interruptions", e) }
  }

  // ---- pause causes (diagnostics) -------------------------------------------------------------------
  private val pauses = ArrayDeque<JSONObject>()
  private var playingSince = 0L

  /** Native-originated pause: logged with its cause (user | system | noisy) and forwarded to JS. */
  fun pauseBy(by: String, detail: String) {
    notePause(by, detail)
    command("pause")
  }

  fun notePause(by: String, detail: String, ignored: Boolean = false) {
    Log.i(TAG, "pause by=$by detail=$detail ignored=$ignored")
    pauses.addLast(JSONObject().put("at", System.currentTimeMillis()).put("by", by).put("detail", detail).put("ignored", ignored))
    while (pauses.size > 12) pauses.removeFirst()
  }

  private fun pauseLog() = org.json.JSONArray().also { list -> pauses.forEach { list.put(it) } }
}
