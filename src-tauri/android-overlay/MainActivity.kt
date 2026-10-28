package app.decibel.music

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private val APP_BACKGROUND = Color.rgb(0x12, 0x12, 0x12)

class MainActivity : TauriActivity() {
  private var webView: WebView? = null
  private var insetTopDp = 0
  private val main = Handler(Looper.getMainLooper())

  /** Android 13+ runtime permission for the playback notification (the system dialog). */
  private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    DecibelMedia.onNotificationPermissionResult(granted)
  }

  /** Shows the system dialog; false when the activity is not in the foreground (Android would drop it). */
  fun requestNotificationPermission(): Boolean {
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    return true
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    // Dark app: light status/navigation icons on transparent bars, whatever the system theme.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)
    DecibelMedia.attach(this)
    window.decorView.setBackgroundColor(APP_BACKGROUND)
    // Edge-to-edge is mandatory (targetSdk 35+). The WebView draws behind the transparent status bar
    // (so the full-screen player's cover colour reaches the top of the screen); Android's WebView does
    // not report the bars through env(safe-area-inset-*), so the top inset is handed to CSS as
    // --android-inset-top (var(--safe-top) in the app). Sides, navigation bar and keyboard still pad
    // the view. With the navigation bar hidden its inset is 0; swiped in, it shows on top transiently.
    val content = findViewById<View>(android.R.id.content)
    content.setBackgroundColor(APP_BACKGROUND)
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
      view.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
      val top = Math.round(bars.top / resources.displayMetrics.density)
      if (top != insetTopDp) { insetTopDp = top; pushInsetTop() }
      WindowInsetsCompat.CONSUMED
    }
    hideNavigationBar()
    if (savedInstanceState == null) takeShared(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    takeShared(intent)
  }

  /** Share sheet (ACTION_SEND): keep the first URL of the text (YouTube adds a title) for the page to open. */
  private fun takeShared(intent: Intent?) {
    if (intent?.action != Intent.ACTION_SEND) return
    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    DecibelMedia.sharedText = Regex("https?://\\S+").find(text)?.value ?: text.trim()
    webView?.evaluateJavascript("window.__decibelNative?.('share', null)", null)
  }

  override fun onWebViewCreate(webView: WebView) {
    this.webView = webView
    // MediaSession commands from Android Auto are user actions outside the WebView.
    webView.settings.mediaPlaybackRequiresUserGesture = false
    DecibelMedia.attachWebView(webView)
    // Keep the renderer at "important" priority even when the app is not visible, so Android does not
    // starve or kill it while music plays in the background.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
    }
    pushInsetTop()
  }

  /** The page may not be loaded yet (or reloads): repeat a few times; the boot script also reads localStorage. */
  private fun pushInsetTop() {
    val px = "${insetTopDp}px"
    val js = "try{document.documentElement.style.setProperty('--android-inset-top','$px');localStorage.setItem('decibel-inset-top','$px')}catch(e){}"
    for (delay in longArrayOf(0, 400, 1500, 4000)) main.postDelayed({ webView?.evaluateJavascript(js, null) }, delay)
  }

  override fun onResume() {
    super.onResume()
    pushInsetTop()
    // First launch (Android 13+): ask for notifications once the window is up, so the system dialog shows
    // over the app right away instead of waiting for the first song. Asked automatically only once.
    main.postDelayed({ DecibelMedia.askNotificationPermissionOnce() }, 600)
  }

  override fun onPause() {
    super.onPause() // WryActivity calls webView.onPause()
    // While music plays, undo that: the page (audio element, queue logic, next-track fetches) must keep running.
    if (DecibelMedia.keepAlive) webView?.onResume()
  }

  override fun onStop() {
    super.onStop()
    if (DecibelMedia.keepAlive) webView?.onResume()
  }

  override fun onDestroy() {
    if (isFinishing) DecibelMedia.detach()
    super.onDestroy()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) hideNavigationBar()
  }

  /** Immersive: only the navigation bar (back/home/recents) is hidden; a swipe from the bottom shows it briefly. */
  private fun hideNavigationBar() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.navigationBars())
    controller.show(WindowInsetsCompat.Type.statusBars())
  }
}
