package dev.njr.zync

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Bundle
import android.text.format.DateFormat
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.njr.zync.capture.ContactMatcher
import dev.njr.zync.capture.DocScanActivity
import dev.njr.zync.home.CalendarSource
import dev.njr.zync.home.NotificationEvents
import dev.njr.zync.home.OpenMeteo
import dev.njr.zync.home.WeatherNow
import dev.njr.zync.home.buildAgenda
import dev.njr.zync.launcher.BarApps
import dev.njr.zync.launcher.BarRole
import dev.njr.zync.launcher.LauncherIntents
import dev.njr.zync.replica.PairingOutcome
import dev.njr.zync.ui.BarAction
import dev.njr.zync.ui.ZyncScreen
import dev.njr.zync.ui.capture.CaptureScreen
import dev.njr.zync.ui.ZyncShell
import dev.njr.zync.ui.createZyncWebView
import dev.njr.zync.ui.home.HomeState
import dev.njr.zync.ui.home.HomeTile
import dev.njr.zync.ui.loopbackUrl
import dev.njr.zync.web.content.DueDates
import dev.njr.zync.core.id.Ulid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var recordAudioResult: ((Boolean) -> Unit)? = null
    private val recordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            recordAudioResult?.invoke(granted)
            recordAudioResult = null
        }

    private var screen by mutableStateOf(ZyncScreen.Home)
    private var searchOpen by mutableStateOf(false)
    private var captureOpen by mutableStateOf(false)
    private var settingsRole by mutableStateOf<BarRole?>(null)
    private var barAppsTick by mutableIntStateOf(0)
    private var permissionTick by mutableIntStateOf(0)
    private var photoFile: java.io.File? = null
    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val file = photoFile
            if (ok && file != null && file.exists()) {
                val app = application as ZyncApp
                app.captureToInbox("Photo", dev.njr.zync.data.AttachmentType.PDF, file.readBytes(), "jpg")
                Toast.makeText(this, "Photo captured to Inbox", Toast.LENGTH_SHORT).show()
            }
            file?.delete()
            photoFile = null
        }
    private val calendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        webView = createZyncWebView(this) { callback ->
            recordAudioResult = callback
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        val app = application as ZyncApp
        setContent {
            val homeState = rememberHomeState(app, permissionTick)
            androidx.compose.foundation.layout.Box {
                ZyncShell(
                    webView = webView,
                    screen = screen,
                    homeState = homeState,
                    onBarAction = ::handleBarAction,
                    onTileTap = { screen = ZyncScreen.Web },
                    onContextSelect = { ctx ->
                        homeContextPrefs().edit()
                            .putString("context_id", ctx?.id?.toString())
                            .putString("context_name", ctx?.name?.removePrefix("@"))
                            .apply()
                        permissionTick++ // reuse the tick to recompute state
                    },
                    onCompleteTask = { task ->
                        app.contentCommands.complete(task.id)
                        app.contentChanges.notifyChanged()
                    },
                    onEnableWeather = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                    onEnableCalendar = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                    onOpenEvent = { event ->
                        CalendarSource.viewIntent(event)?.let { intent ->
                            runCatching { startActivity(intent) }
                        } ?: Toast.makeText(this@MainActivity, "No calendar entry for this item", Toast.LENGTH_SHORT).show()
                    },
                    onEnableNotifications = {
                        runCatching {
                            startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    },
                    barApps = { role -> barAppsTick; BarApps.load(this@MainActivity, role) },
                    onLaunchApp = { app ->
                        if (!dev.njr.zync.launcher.AppLaunch.launch(this@MainActivity, app.toEntry())) {
                            Toast.makeText(this@MainActivity, "${app.label} is not available", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onEditRole = { role -> settingsRole = role },
                    searchOpen = searchOpen,
                    onSearchOpenChange = { searchOpen = it },
                )
                settingsRole?.let { role ->
                    dev.njr.zync.ui.settings.BarSettingsScreen(
                        initialRole = role,
                        onDismiss = { settingsRole = null; barAppsTick++ },
                    )
                }
                if (captureOpen) {
                    CaptureScreen(
                        contexts = app.contentRead.contexts(),
                        projects = app.contentRead.projects(),
                        contactMatch = { name -> ContactMatcher.match(this@MainActivity, name) },
                        requestRecordAudio = { callback ->
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                callback(true)
                            } else {
                                recordAudioResult = callback
                                recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onPhoto = { captureOpen = false; takePhoto() },
                        onScan = { captureOpen = false; startActivity(Intent(this@MainActivity, DocScanActivity::class.java)) },
                        onSave = ::performCapture,
                        onDismiss = { captureOpen = false },
                    )
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    searchOpen || captureOpen || settingsRole != null -> Unit // their BackHandlers own it
                    screen == ZyncScreen.Web && webView.canGoBack() -> webView.goBack()
                    screen == ZyncScreen.Web -> screen = ZyncScreen.Home
                    // On the home surface an edge swipe (the system back gesture) opens
                    // search (device feedback) — there's nothing to go "back" to.
                    else -> searchOpen = true
                }
            }
        })
        lifecycleScope.launch(Dispatchers.IO) {
            val port = app.ensureServerStarted()
            withContext(Dispatchers.Main) { webView.loadUrl(loopbackUrl(port, app.serverToken)) }
        }
        maybePromptHomeRole()
        handlePairingIntent(intent)
    }

    /** Assembles everything the native home surface renders, refreshing on op-log changes. */
    @Composable
    private fun rememberHomeState(app: ZyncApp, permissionTick: Int): HomeState {
        var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var contentTick by remember { mutableIntStateOf(0) }
        var weather by remember { mutableStateOf<WeatherNow?>(null) }
        var notifEvents by remember { mutableStateOf<List<dev.njr.zync.home.CalEvent>>(emptyList()) }

        LaunchedEffect(Unit) { // minute clock
            while (true) {
                delay(60_000 - (System.currentTimeMillis() % 60_000))
                nowMillis = System.currentTimeMillis()
            }
        }
        LaunchedEffect(Unit) { app.contentChanges.changes.collect { contentTick++ } }
        LaunchedEffect(Unit) { NotificationEvents.events.collect { notifEvents = it } }
        LaunchedEffect(permissionTick) { // weather: on grant + hourly
            while (true) {
                weather = fetchWeather()
                delay(60L * 60_000)
            }
        }

        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val dayStart = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24L * 60 * 60_000

        return remember(nowMillis, contentTick, permissionTick, weather, notifEvents) {
            val read = app.contentRead
            val prefs = homeContextPrefs()
            val contextId = prefs.getString("context_id", null)?.let { runCatching { Ulid.parse(it) }.getOrNull() }
            val contextName = prefs.getString("context_name", null)
            val suggestions = if (contextId != null) read.contextTasks(contextId, nowMillis) else read.activeTasks(nowMillis)
            val events = CalendarSource.todaysEvents(this, dayStart, dayEnd) +
                notifEvents.filter { it.beginMillis in dayStart until dayEnd }
            val dueBy = DueDates.parse(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time))!!
            val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            HomeState(
                clockHours = SimpleDateFormat("HH", Locale.US).format(cal.time),
                clockMinutes = SimpleDateFormat("mm", Locale.US).format(cal.time),
                dateLine = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(cal.time),
                weatherLine = weather?.toString() ?: if (locationGranted) "retry weather ↻" else null,
                contextName = contextName,
                contexts = read.contexts(),
                inboxCount = read.inbox(null, nowMillis).size,
                todayCount = read.dueTasks(dueBy).size,
                nextCount = suggestions.size,
                waitingCount = read.waitingTasks(nowMillis).size,
                agenda = buildAgenda(events, nowMillis, suggestions),
                calendarPermission = CalendarSource.hasPermission(this),
                notificationsEnabled = NotificationEvents.listenerEnabled(this),
            )
        }
    }

    private fun homeContextPrefs() = getSharedPreferences("zync_launcher", Context.MODE_PRIVATE)

    private suspend fun fetchWeather(): WeatherNow? {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = getSystemService(LocationManager::class.java) ?: return null
        // Last-known from any provider, else a one-shot current fix — a fresh phone
        // often has NO last-known location, which read as "weather not loading".
        // The one-shot can also hang indoors (GPS never fixes), so it gets a hard
        // timeout and we fall back to the last COORDS that ever produced weather:
        // a stale position beats no forecast.
        val prefs = homeContextPrefs()
        val located = runCatching {
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .filter { it in lm.allProviders }
                .firstNotNullOfOrNull { lm.getLastKnownLocation(it) }
        }.getOrNull() ?: kotlinx.coroutines.withTimeoutOrNull(10_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .firstOrNull { it in lm.allProviders }
                if (provider == null) {
                    cont.resume(null) { _, _, _ -> }
                } else {
                    runCatching {
                        lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
                            if (cont.isActive) cont.resume(loc) { _, _, _ -> }
                        }
                    }.onFailure { if (cont.isActive) cont.resume(null) { _, _, _ -> } }
                }
            }
        }
        val lat = located?.latitude ?: prefs.getString("weather_lat", null)?.toDoubleOrNull() ?: return null
        val lon = located?.longitude ?: prefs.getString("weather_lon", null)?.toDoubleOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            val http = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
            try {
                kotlinx.coroutines.withTimeoutOrNull(15_000) { OpenMeteo(http).current(lat, lon) }?.also {
                    prefs.edit().putString("weather_lat", "$lat").putString("weather_lon", "$lon").apply()
                }
            } finally {
                http.close()
            }
        }
    }

    private val homeRoleRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* user decides */ }

    private fun isDefaultHome(): Boolean =
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

    /**
     * Offer ROLE_HOME once (launcher spec L2). Declining is remembered; the role stays
     * changeable any time under system Settings → Default apps → Home app.
     */
    private fun maybePromptHomeRole() {
        val roles = getSystemService(RoleManager::class.java) ?: return
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) return
        val prefs = getSharedPreferences("zync_launcher", MODE_PRIVATE)
        if (prefs.getBoolean("home_role_prompted", false)) return
        prefs.edit().putBoolean("home_role_prompted", true).apply()
        runCatching { homeRoleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
    }

    /** Launcher action bar taps (spec L1): app slots fire intents; Capture opens the screen. */
    private fun handleBarAction(action: BarAction) {
        when (action) {
            BarAction.Messages -> launch(LauncherIntents.messages(), "No messages app found")
            BarAction.Calendar -> launch(LauncherIntents.calendar(), "No calendar app found")
            BarAction.Phone -> launch(LauncherIntents.phone(), "No dialer found")
            BarAction.Capture -> captureOpen = true
        }
    }

    /** Camera capture → attachment into the inbox (capture-screen Photo mode). */
    private fun takePhoto() {
        val dir = java.io.File(cacheDir, "capture").apply { mkdirs() }
        val file = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
        photoFile = file
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "dev.njr.zync.fileprovider", file)
        runCatching { takePicture.launch(uri) }
            .onFailure { Toast.makeText(this, "No camera available", Toast.LENGTH_SHORT).show() }
    }

    /** Build the ops for a capture-screen save (native-capture spec routing rules). */
    private fun performCapture(result: dev.njr.zync.ui.capture.CaptureResult) {
        val app = application as ZyncApp
        val id = app.contentCommands.createTask(result.title, parent = result.parentId)
        result.contextId?.let { app.contentCommands.addTag(id, it) }
        result.dueMillis?.let { app.contentCommands.setDueDate(id, it) }
        result.person?.let { app.contentCommands.setPerson(id, it) }
        result.notes?.takeIf { it != result.title }?.let { app.contentCommands.setNotes(id, it) }
        app.contentChanges.notifyChanged()
        dev.njr.zync.sync.SyncScheduler.requestSync(this)
        Toast.makeText(
            this,
            result.parentId?.let { "Saved — filed under a project" } ?: "Saved to Inbox",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun launch(intent: Intent, missing: String) {
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, missing, Toast.LENGTH_SHORT).show() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingIntent(intent)
    }

    /** A tapped/scanned `zync://pair` link (from the server's /settings/pairing page). */
    private fun handlePairingIntent(intent: Intent?) {
        val uri = intent?.dataString?.takeIf { it.startsWith("zync://pair") } ?: return
        intent.data = null // consume: don't re-pair on config-change redelivery
        Toast.makeText(this, "Pairing with server…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { (application as ZyncApp).pairFromUri(uri) }
            val message = when (outcome) {
                is PairingOutcome.Paired -> "Paired — syncing with ${outcome.server.address}"
                is PairingOutcome.Failed -> "Pairing failed: ${outcome.reason}"
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }
}
