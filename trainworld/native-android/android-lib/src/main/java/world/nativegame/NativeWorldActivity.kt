package world.nativegame

import android.app.Activity
import android.content.Intent
import android.graphics.PointF
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import android.view.Gravity
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.text.TextUtils
import android.text.InputFilter
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.min

internal typealias PanelEdit = ((Double) -> Unit) -> Unit

internal object Engine {
    init { System.loadLibrary("world_native") }
    @JvmStatic external fun forkCandidate(handle: Long, clock: Double, roadDir: String): Long
    @JvmStatic external fun requiredRoads(handle: Long, points: DoubleArray): String
    @JvmStatic external fun workshop(): Long
    @JvmStatic external fun build(handle: Long, points: DoubleArray, name: String, clock: Double, mode: Int, bends: DoubleArray): String
    @JvmStatic external fun open(bytes: ByteArray): Long
    @JvmStatic external fun guide(handle: Long, bit: Int, card: Int): String
    @JvmStatic external fun geometry(handle: Long): String
    @JvmStatic external fun previewArchive(handle:Long,time:Double):ByteArray?
    @JvmStatic external fun trainScene(handle:Long,time:Double,x:Float,y:Float,halfWidth:Float,halfHeight:Float,detail:Int):FloatArray
    @JvmStatic external fun positions(handle: Long, time: Double): FloatArray
    @JvmStatic external fun save(handle: Long, time: Double): ByteArray
    @JvmStatic external fun swapWorlds(editor: Long, candidate: Long)
    @JvmStatic external fun close(handle: Long)
}

private val DOCK_GREEN = 0xff173e37.toInt()

class NativeWorldActivity : Activity() {
    private var handle = 0L
    private val executor = Executors.newSingleThreadExecutor()
    private val archiveSlot by lazy {
        intent.getStringExtra("archiveSlot")?.let { id -> WorldDocuments.list(this).firstOrNull { it.id == id } }
    }
    private val legacyWorkshop by lazy { archiveSlot == null && intent.getBooleanExtra("workshop", false) }
    private val workshop get() = archiveSlot != null || legacyWorkshop || !city.hasNetwork
    private val cityStore by lazy { StudioCatalog.get(this) }
    private val homeCity by lazy {
        archiveSlot?.city ?: if (legacyWorkshop) "nyc" else intent.getStringExtra("city")
            ?: getSharedPreferences("world-native",0).getString("last-city","nyc") ?: "nyc"
    }
    private val city by lazy { cityStore.city(intent.getStringExtra("viewCity") ?: homeCity) }
    private var focusId: String? = null
    private var localClockShift = 0
    private val detailedSurfaces = linkedSetOf<String>()
    private val surfaces = linkedMapOf<String, CitySurface.Layer>()
    private val viewWorker = Executors.newSingleThreadExecutor()
    private var loadingSurface = false
    private var wantedZone: String? = null
    private var viewportLabel = ""
    private var worldHomeSetting: Boolean? = null
    private val visitedThisSession = linkedSetOf<String>()

    private val hostLibrary get() = intent.getBooleanExtra("hostLibrary", false)
    private var activePack: CityPackStore.CityPack? = null
    private var openedCities: Set<String> = emptySet()
    private val local by lazy {
        AtomicFile(archiveSlot?.let { WorldDocuments.path(this,it.id) } ?: File(filesDir, if (legacyWorkshop) "world-native-workshop.save" else "world-native-${homeCity}.save"))
    }
    private lateinit var build: Button
    private var plan = ConstructionPlan(BuildTool.RAIL)
    private var reviewDialog: android.app.Dialog? = null
    private var railOptions = ConstructionPlan(BuildTool.RAIL)
    private val pointProfile=mutableListOf<Double>()
    private fun recordRailPoint(level:Int=plan.level,snap:Int=1) {
        pointProfile.addAll(listOf(level.toDouble(),plan.radius,snap.toDouble()))
    }
    private lateinit var railBar:RailTouchBar
    private var placingRail=false
    private val railMode get()=drawing && plan.tool==BuildTool.RAIL
    private data class RailStep(val origin:PointF,val level:Int,val snap:Int,val edit:java.lang.ref.WeakReference<WorldEditHistory.Entry>)
    private val railChain=mutableListOf<RailStep>()
    private val stationOrder = mutableListOf<Int>()
    private var drawing = false
    private var lastService = ""
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var map: NetworkView
    private lateinit var speedChip: WorldSpeedChip
    private lateinit var play: Button
    private lateinit var save: Button
    private lateinit var more: Button
    private lateinit var lines: Button
    private lateinit var finance: Button
    private lateinit var zoomTools: LinearLayout
    private lateinit var controlHint: LinearLayout
    private lateinit var cancel: Button
    private lateinit var sunArc: SunArcView
    private lateinit var missionCard: LinearLayout
    private lateinit var lensLegend: LinearLayout
    private lateinit var warmChip: LinearLayout
    private lateinit var warmChipText: TextView
    private lateinit var warmChipRing: WarmRing
    private lateinit var warmCard: LinearLayout
    private lateinit var warmBar: StudioProgress.Bar
    private lateinit var warmPercent: TextView
    private lateinit var warmTip: TextView
    private lateinit var missionText: TextView
    private lateinit var missionBar: StudioProgress.Bar
    private var lastCash = Double.NaN
    private var reportDay = -1
    private var dayStartCash = Double.NaN
    private var progressTick = 0L
    private lateinit var localTime: TextView
    private lateinit var balance: TextView
    private lateinit var balanceUnit: TextView
    private lateinit var dockTitle: TextView
    private lateinit var currency: StudioCurrency
    private var loadingDemand = false
    private var saveRevision = 0L
    private var worldChanged = false
    private var restoringEdit = false
    private data class RetainedEdits(val slotPath: String, val revision: Long, val history: WorldEditHistory)
    private var history = WorldEditHistory()
    private var clock = 0.0
    private var running = false
    private var lastTick = 0L
    private var localRate = 60.0
    private var lastStatus = -1
    private var closed = false
    private enum class ReferenceState { OPENING, PLAYING, SAVING }
    private var reference: SimulationClock? = null
    private var referenceState: ReferenceState? = null
    private var referenceTime: Double? = null
    private var trainPreview: java.util.concurrent.Future<*>? = null
    private var previewGeneration = 0L
    private var resumeOnPanelClose = false
    private var checkpointInFlight = false
    /** A station tapped while a checkpoint was in flight; opened once it lands instead of being dropped. */
    private var afterCheckpoint: (() -> Unit)? = null
    private var foreground = false
    // A replacement activity must read the completed checkpoint after a rotation.
    private companion object {
        val pendingWrites = ConcurrentHashMap<String, CountDownLatch>()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.hasExtra("archiveSlot") && archiveSlot == null) {
            Toast.makeText(this, "Dunia ini tidak tersedia untuk akun aktif.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(StudioUi.BACKGROUND)
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xffd7f0ff.toInt()
        map = NetworkView(this).apply {
            workshop = this@NativeWorldActivity.workshop
            trainEngine = { handle }
            overlay.city = city.id
            val overlayCity = city.id
            overlay.manifest = { bytes, viewed ->
                val local = cityStore.load(overlayCity)
                NativeRidership.inputs(this@NativeWorldActivity,bytes,local.header,local.cells,true,viewed).toString()
            }
            overlay.allowTap = { !removingTrack && !selectingInfrastructure && !restoringEdit }
            overlay.snapshot = {
                if (closed || !this@NativeWorldActivity.foreground || handle == 0L) null else {
                    stopTrainPreview()
                    Engine.save(handle,clock)
                }
            }
            onTrainPreviewNeeded = ::previewTrainLoads
            doubleTapZoom = true
        }
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        // Measure the HUD independently of the map so zooming and drawing retain a single viewport.
        val compact = resources.configuration.screenWidthDp < 360
        val gutter = dp(if (compact) 8 else 12)
        val hud = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(hud, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(gutter, gutter, gutter, 0)
        })
        fun glassIcon(icon: StudioUi.Icon, description: String, size: Int, click: () -> Unit) =
            StudioUi.iconButton(this, description, icon) { click() }.apply {
                background = StudioUi.glass(context, size / 2)
                elevation = dp(3).toFloat()
                setPadding(0, 0, 0, 0)
                textSize = 22f
            }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        hud.addView(header)
        header.addView(glassIcon(StudioUi.Icon.BACK, "Kembali", 50) { leaveWorld() },
            LinearLayout.LayoutParams(dp(50), dp(50)))
        // One frosted pill: day arc, clock, network summary and cash.
        val clockPill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = StudioUi.glass(context, 26)
            elevation = dp(3).toFloat()
            minimumHeight = dp(52)
            setPadding(dp(8), dp(5), dp(5), dp(5))
        }
        sunArc = SunArcView(this)
        clockPill.addView(sunArc, LinearLayout.LayoutParams(dp(if (compact) 36 else 44), dp(28)))
        val timeColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        localTime = StudioUi.text(this, "—", if (compact) 16f else 18f, color = StudioUi.INK).apply {
            typeface = StudioUi.heavy(context)
            singleLine()
            includeFontPadding = false
        }
        timeColumn.addView(localTime)
        info = StudioUi.text(this, "Membuka jaringan…", 11f, true, StudioUi.MUTED).apply { singleLine() }
        timeColumn.addView(info)
        timeColumn.contentDescription = "Ringkasan semua kota"
        timeColumn.setOnClickListener { if (handle != 0L && !drawing) allCities() }
        clockPill.addView(timeColumn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        val cashPill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = StudioDepot.touch(context, 0xccffffff.toInt(), 22)
            setPadding(dp(12), dp(3), dp(12), dp(4))
            isClickable = true
            contentDescription = "Buka keuangan"
            setOnClickListener { withCommittedWorld(pause = false) { openFinance() } }
        }
        currency = StudioCurrency.current(this)
        balanceUnit = StudioUi.text(this, "KAS · ${currency.code}", 9f, true, StudioUi.MUTED)
        balance = StudioUi.text(this, "—", if (compact) 14f else 16f, color = StudioUi.INK).apply {
            typeface = StudioUi.heavy(context)
            maxLines = 1
            includeFontPadding = false
            minWidth = (paint.measureText("Rp 000,00 T") + dp(2)).toInt()
        }
        cashPill.addView(balanceUnit)
        cashPill.addView(balance)
        clockPill.addView(cashPill, LinearLayout.LayoutParams(-2, dp(42)))
        header.addView(clockPill, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = dp(6) })
        save = glassIcon(StudioUi.Icon.SAVE, "Simpan ke Studio", 46) { withCommittedWorld { saveWorld() } }.apply { isEnabled = false }

        val mapTools = LinearLayout(this).apply { gravity = Gravity.TOP }
        hud.addView(mapTools, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(10) })
        // Mission card: the closest unfinished badge, tap for Pencapaian.
        val missionSlot = android.widget.FrameLayout(this)
        missionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.pill(context, StudioUi.GLASS_DARK, 22)
            elevation = dp(3).toFloat()
            setPadding(dp(14), dp(10), dp(14), dp(12))
            isClickable = true
            visibility = View.GONE
            setOnClickListener { openProgress() }
        }
        missionCard.addView(StudioUi.text(this, "MISI", 11f, true, 0xffd9d0ff.toInt()).apply { letterSpacing = .08f })
        missionText = StudioUi.text(this, "", 14f, color = StudioUi.WHITE).apply { typeface = StudioUi.heavy(context); maxLines = 2 }
        missionCard.addView(missionText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        missionBar = StudioProgress.Bar(this, 0xffffd23f.toInt(), 0x40ffffff)
        missionCard.addView(missionBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8) })
        val leftTools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }
        leftTools.addView(missionCard, LinearLayout.LayoutParams(dp(if (compact) 210 else 236), -2))
        warmChipRing = WarmRing(this)
        warmChipText = StudioUi.text(this, "", 12.5f, color = StudioUi.INK).apply { typeface = StudioUi.heavy(context); singleLine() }
        warmChip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = StudioUi.glass(context, 18)
            elevation = dp(3).toFloat()
            setPadding(dp(9), dp(7), dp(13), dp(7))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(warmChipRing, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
            addView(warmChipText)
        }
        leftTools.addView(warmChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        missionSlot.addView(leftTools, android.widget.FrameLayout.LayoutParams(-2, -2))
        mapTools.addView(missionSlot, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        val rightTools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightTools.addView(save, LinearLayout.LayoutParams(dp(46), dp(46)).apply { bottomMargin = dp(8) })
        zoomTools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightTools.addView(zoomTools)
        listOf(
            Triple(StudioUi.Icon.PLUS, "Perbesar peta", { map.zoomStep(true) }),
            Triple(StudioUi.Icon.MINUS, "Perkecil peta", { map.zoomStep(false) }),
            Triple(StudioUi.Icon.CENTER, "Tampilkan jaringan", { map.resetCamera() })
        ).forEach { (icon, description, click) ->
            zoomTools.addView(glassIcon(icon, description, 46, click),
                LinearLayout.LayoutParams(dp(46), dp(46)).apply { bottomMargin = dp(8) })
        }
        zoomTools.visibility = if (StudioGameSettings.read(this).zoomButtons) View.VISIBLE else View.GONE
        mapTools.addView(rightTools)

        val dock = object : LinearLayout(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val width = minOf(View.MeasureSpec.getSize(widthSpec), dp(440))
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightSpec)
            }
        }.apply { orientation = LinearLayout.VERTICAL; clipToPadding = false }
        root.addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(gutter,0,gutter,gutter)
        })
        dock.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            map.statusBottomInset = root.height - top + gutter
        }
        controlHint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.glass(context, 22)
            elevation = dp(3).toFloat()
            setPadding(dp(16),dp(9),dp(16),dp(10))
        }
        warmCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.glass(context, 26)
            elevation = dp(3).toFloat()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        warmCard.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(StudioDepot.symbol(context, StudioUi.Icon.TRAIN, 22f, StudioUi.WHITE).apply {
                gravity = Gravity.CENTER
                background = StudioUi.pill(context, StudioUi.GREEN, 14)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(StudioUi.text(context, "Menghitung perjalanan warga", 16f, color = StudioUi.INK).apply { typeface = StudioUi.display(context) })
                addView(StudioUi.text(context, "${city.name} · sekali saja, lalu simulasi langsung jalan", 12f, true, StudioUi.MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        warmBar = StudioProgress.Bar(this, StudioUi.GREEN, 0x1a22204a)
        warmPercent = StudioUi.text(this, "", 13f, color = StudioUi.INK).apply { typeface = StudioUi.heavy(context) }
        warmCard.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(warmBar, LinearLayout.LayoutParams(0, dp(12), 1f).apply { marginEnd = dp(10) })
            addView(warmPercent)
        })
        warmTip = StudioUi.text(this, "", 12.5f, true, StudioUi.INK).apply {
            background = StudioUi.pill(context, 0x38ffd23f, 14)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        warmCard.addView(warmTip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        dock.addView(warmCard, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(8) })
        lensLegend = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = StudioUi.glass(context, 22)
            elevation = dp(3).toFloat()
            setPadding(dp(16), dp(9), dp(8), dp(9))
            visibility = View.GONE
        }
        dock.addView(lensLegend, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(8) })
        railBar=RailTouchBar(this) { next ->
            if(!placingRail && !restoringEdit) {
                plan=next; railOptions=next
                if(map.draft.size==2) setRailTip(map.rawRailTip ?: map.draft.last())
                else updateDraft()
            }
        }
        dock.addView(railBar)
        dock.addView(controlHint, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(8) })
        dockTitle = StudioUi.text(this, "", 15f).apply { typeface = StudioUi.heavy(context) }
        status = StudioUi.text(this, "Memuat dunia…", 13f, true, StudioUi.MUTED)
        controlHint.addView(dockTitle)
        controlHint.addView(status)
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = StudioUi.glass(context, 34)
            elevation = dp(4).toFloat()
            setPadding(dp(8),dp(8),dp(8),dp(8))
        }
        dock.addView(bar)
        fun action(primary: Boolean = false, click: () -> Unit) = Button(this).apply {
            backgroundTintList = null
            if (primary) StudioUi.style(this, StudioUi.Tone.PRIMARY)
            else background = StudioDepot.touch(context, 0xd9ffffff.toInt(), 22)
            stateListAnimator = null
            isAllCaps = false
            typeface = StudioUi.heavy(context)
            textSize = if (primary) 14f else 11f
            letterSpacing = 0f
            gravity = Gravity.CENTER
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(54)
            minimumHeight = dp(54)
            setTextColor(if (primary) StudioUi.WHITE else StudioUi.INK)
            setPadding(0,dp(5),0,if (primary) dp(9) else dp(4))
            maxLines = 1
            compoundDrawablePadding = dp(1)
            setOnClickListener { click() }
        }
        build = action(true) {
            withCommittedWorld { when {
                railMode -> placeRailSegment()
                drawing && map.draft.size >= plan.tool.minPoints -> reviewDraft()
                !drawing -> chooseConstruction()
            } }
        }
        play = action {
            if(railMode) railBack() else if (drawing) undoDraft() else if (map.requiresStudioClock) {
                if (playWhenWarm) { playWhenWarm = false; updateWarmUi(); updateControls() }
                else if (reference == null) startReference() else withCommittedWorld()
            } else {
                if (running) advanceLocalClock()
                running = !running
                localRate = speedChip.rate
                lastTick = SystemClock.elapsedRealtime()
                updateControls()
                map.removeCallbacks(tick)
                if (running) map.post(tick)
            }
        }
        lines = action { withCommittedWorld(pause = false) { openServices() } }
        finance = action { openLenses() }
        cancel = action { if(railMode) resetRailRun() else discardDraft() }
        more = action { withCommittedWorld(pause = false) { if(railMode) { cancelDrawing(); chooseConstruction() } else manageWorld() } }
        speedChip = WorldSpeedChip(this, savedInstanceState?.getDouble("speed") ?: intent.getDoubleExtra("speed",1.0)) { start ->
            if (running) advanceLocalClock()
            localRate = speedChip.rate
            if (referenceState == ReferenceState.PLAYING) reference?.play(speedChip.rate)
            else if (start && !running && !playWhenWarm && play.isEnabled) play.performClick()
        }
        map.overlay.onComputing = { speedChip.capped = it }
        for (item in listOf(cancel, play, lines, build, speedChip, finance, more)) {
            bar.addView(item, LinearLayout.LayoutParams(0,-2,if (item == build) 1.6f else 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
        updateControls()
        map.onRailDrag={ point -> if(railMode && !placingRail && !restoringEdit) setRailTip(point) }
        map.onLocation = { point ->
            when {
                removingTrack -> selectTrackForRemoval(point)
                selectingInfrastructure -> inspectRail(point)
                drawing -> selectPoint(point)
            }
        }
        map.onStationTap = { station ->
            if (!drawing && !removingTrack && !restoringEdit && handle != 0L) {
                selectingInfrastructure=false
                updateControls()
                if (checkpointInFlight) afterCheckpoint = { openStation(station) }
                else withCommittedWorld(pause = false) { openStation(station) }
                true
            } else false
        }
        map.onBend = { point ->
            if (drawing && plan.tool in setOf(BuildTool.BUS,BuildTool.FERRY) && map.draft.isNotEmpty() && map.draft.size < 24 && map.bends.size < 64) {
                map.bends.add(map.draft.lastIndex to point)
                updateDraft()
            }
        }
        setContentView(root)
        val pending = pendingWrites[local.baseFile.path]
        executor.execute {
            try {
                if (pending != null) check(pending.await(90, TimeUnit.SECONDS)) { "Penyimpanan simulasi sebelumnya belum selesai." }
                var progressPercent = -1
                val pack = try {
                    cityStore.load(city.id) { done,total ->
                        val percent=(done*100/total).toInt()
                        if (percent!=progressPercent) {
                            progressPercent=percent
                            runOnUiThread { if(!closed) info.text="Mengunduh ${city.name} · $percent%" }
                        }
                    }
                } catch (e: Exception) {
                    if(city.id!="nyc") throw e
                    null // Bundled legacy starter remains available on a first offline launch.
                }
                val snapshot = try { local.readFully() } catch (e: java.io.FileNotFoundException) {
                    if (archiveSlot != null) throw e
                    null
                }
                val restored = snapshot != null
                val loaded = when {
                    snapshot != null -> Engine.open(snapshot)
                    legacyWorkshop -> Engine.workshop()
                    pack != null && !city.hasNetwork -> CitySurface.empty(city.id,pack.header.readText())
                    pack != null -> Engine.open(pack.network.readBytes())
                    else -> assets.open("world-native/nyc.save").use { Engine.open(it.readBytes()) }
                }
                check(loaded != 0L) { "Format simpanan tidak dikenali" }
                val geometry: JSONObject
                val zones = ViewportZones(this)
                val layers = mutableMapOf<String,CitySurface.Layer>()
                val knownCities = linkedSetOf(homeCity)
                var expandedSave: ByteArray? = null
                try {
                    geometry=JSONObject(Engine.geometry(loaded))
                    val opened = geometry.optJSONArray("opened")
                    if (opened != null) for (i in 0 until opened.length()) knownCities.add(opened.getString(i))
                    knownCities.add(city.id)
                    val viewedRows = geometry.optJSONArray("viewed")
                    val viewed = (0 until (viewedRows?.length() ?: 0)).map { viewedRows!!.getString(it) }.toSet()
                    val ids = (knownCities - viewed) + zones.constructionZones(loaded,geometry,viewed) + city.id
                    val catalog = cityStore.cities().map { it.id }.toSet()
                    for (id in ids.filter { it in catalog }) {
                        val localPack = if (id == city.id) pack ?: continue else cityStore.load(id)
                        layers[id] = CitySurface.load(loaded, localPack, overview = id != city.id)
                    }
                    if (intent.hasExtra("viewCity") && pack != null && CitySurface.visit(loaded,homeCity,city.id)) {
                        expandedSave = Engine.save(loaded,geometry.getDouble("clock"))
                    }
                } catch (e: Throwable) {
                    Engine.close(loaded)
                    throw e
                }
                runOnUiThread {
                    if (closed || isFinishing) { Engine.close(loaded); return@runOnUiThread }
                    saveRevision = local.baseFile.lastModified()
                    (lastNonConfigurationInstance as? RetainedEdits)?.let { previous ->
                        if (previous.slotPath == local.baseFile.path && previous.revision == saveRevision) history = previous.history
                    }
                    handle = loaded; activePack=pack; clock = geometry.getDouble("clock")
                    if (expandedSave != null && !persistBytes(expandedSave)) { finish(); return@runOnUiThread }
                    val layer = layers[city.id]
                    openedCities = knownCities
                    surfaces.putAll(layers)
                    if (layers[city.id] != null) detailedSurfaces.add(city.id)
                    map.zones = zones
                    map.onCameraMoving = { wantedZone = null }
                    map.onViewSettled = ::followViewport
                    viewportLabel = city.name
                    focusId = city.id
                    localClockShift = StudioNewGame.localShift(city.id)
                    map.cityLayers=layers.values.toList()
                    map.cityLayer=layer
                    map.load(geometry)
                    if (CitySurface.worldHome(handle,-1) && savedInstanceState == null) map.locateWorld()
                    map.cameraMoved()
                    if (intent.hasExtra("viewCity") && layer != null) {
                        val origin = JSONObject(requireNotNull(pack).header.readText()).getJSONObject("origin")
                        val point = CitySurface.project(handle,doubleArrayOf(origin.getDouble("lon"),origin.getDouble("lat")))
                        map.focusCity(point[0],point[1])
                    }
                    val selection = savedInstanceState ?: intent.extras
                    selection?.getStringArray("mapLayers")?.takeIf { it.size == map.overlay.selection.size }
                        ?.copyInto(map.overlay.selection)
                    selection?.getString("mapPurpose")?.let(map.overlay::choosePurpose)
                    map.overlay.startNeeded()
                    updateSummary()
                    if(!legacyWorkshop) getSharedPreferences("world-native",0).edit().putString("last-city",homeCity).apply()
                    updateControls()
                    updateLensLegend()
                    scheduleWarm(2500)
                    if (workshop && map.routeCount > 0) {
                        val routes = geometry.getJSONArray("routes")
                        lastService = routes.getJSONObject(routes.length()-1).getString("name")
                    }
                    refresh()
                    if (restored) info.announceForAccessibility("Simpanan dibuka")
                    if (savedInstanceState != null) {
                        map.restoreView(savedInstanceState)
                        val restoredPlan = ConstructionPlan.restore(savedInstanceState)
                        if (savedInstanceState.getBoolean("drawing") && restoredPlan != null) {
                            drawing = true
                            plan = restoredPlan
                            if(plan.tool==BuildTool.RAIL) {
                                plan=plan.copy(deferred=true)
                                railOptions=plan
                                val stored=savedInstanceState.getDoubleArray("railPointProfile")
                                if(stored?.size==map.draft.size*3) pointProfile.addAll(stored.toList())
                                else repeat(map.draft.size) { recordRailPoint() }
                                while(map.draft.size>2) { map.draft.removeAt(1); repeat(3){pointProfile.removeAt(3)} }
                                val snaps=savedInstanceState.getIntArray("railSnaps")
                                repeat(map.draft.size) { i -> map.railSnaps.add(snaps?.getOrNull(i) ?: if(pointProfile[3*i+2]==0.0) 0 else 1) }
                                if(map.draft.size==2) map.rawRailTip=map.draft.last()
                            }
                            savedInstanceState.getIntArray("linePlatforms")?.let { stationOrder.addAll(it.toList()) }
                            updateDraft()
                        } else {
                            map.draft.clear()
                            map.bends.clear()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!closed && !isFinishing) {
                        info.text = "Jaringan ${city.name} gagal dibuka."
                        StudioDialogBuilder(this).setTitle(info.text).setMessage(e.message)
                            .setPositiveButton("Coba lagi") { _, _ -> recreate() }
                            .setNegativeButton("Kembali") { _, _ -> finish() }.show()
                    }
                }
            }
        }
    }
    private fun saveWorld() {
        if (!writeSave()) return
        if (archiveSlot != null && hostLibrary) {
            openLibrary(sync = true)
        } else {
            Toast.makeText(this, "Dunia tersimpan di perangkat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLibrary(sync: Boolean = false) {
        val library = if (hostLibrary) StudioLibraryActivity::class.java else WorldArchiveActivity::class.java
        val target = Intent(this, library).apply {
            putExtra("archiveSource", archiveSlot?.id ?: local.baseFile.name)
            putExtra("city", city.id)
            if (sync) putExtra("studioAction", "save")
        }
        if (hostLibrary) startActivityForResult(target, 168) else startActivity(target)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (StudioNetworkDiagram.finishExport(this,requestCode,resultCode,data)) return
        if (requestCode == 168 && resultCode == RESULT_OK) {
            setResult(RESULT_OK,data)
            finish()
        }
    }

    private fun TextView.singleLine() {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun openServices() {
        ServicePanel.open(this, handle, clock, true, ::editPanel) { name ->
            recordPanelChange("Jadwal layanan")
            lastService = name
            lastStatus = -1
            refresh()
        }
    }

    private fun openFinance() {
        NativeFinance.show(this, handle, { clock }, ::editPanel, onCurrencyChanged = {
            currency = StudioCurrency.current(this)
            showBalance(NativeFinance.balance(handle, clock))
        }) {
            recordPanelChange("Keuangan")
            showBalance(NativeFinance.balance(handle, clock))
        }
    }

    private fun progressSnapshot(clockNow: Double) = StudioProgress.Snapshot(
        map.routeCount, map.stationCount, map.trainCount, (clockNow / 86400).toInt() + 1,
        if (lastCash.isFinite()) lastCash else map.balance)

    private fun openProgress() {
        if (handle == 0L) return
        StudioProgress.show(this, currency, progressSnapshot(referenceTime ?: clock))
    }

    /** Mission card, new-badge toast and the end-of-day report. Cheap: runs at most twice a second. */
    private fun updateProgress(clockNow: Double) {
        if (handle == 0L || !::missionCard.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        if (now - progressTick < 500) return
        progressTick = now
        val snap = progressSnapshot(clockNow)
        val list = StudioProgress.badges(currency)
        val next = StudioProgress.nextMission(list, snap)
        missionCard.visibility = if (next != null && !drawing && !removingTrack && !selectingInfrastructure) View.VISIBLE else View.GONE
        if (next != null) {
            missionText.text = next.goal
            missionBar.fraction = StudioProgress.progress(next, snap).toFloat()
        }
        val world = local.baseFile.name
        val seen = StudioProgress.seen(this, world)
        val got = StudioProgress.unlocked(list, snap).map { it.id }.toSet()
        if (seen.isEmpty() && got.isNotEmpty()) StudioProgress.remember(this, world, got)
        else (got - seen).firstOrNull()?.let { id ->
            StudioProgress.remember(this, world, seen + got)
            val badge = list.first { it.id == id }
            Toast.makeText(this, "Lencana baru: ${badge.title}!", Toast.LENGTH_LONG).show()
        }
        val day = snap.day
        val cash = snap.cashMillions
        if (reportDay < 0) { reportDay = day; dayStartCash = cash }
        else if (day > reportDay) {
            val finished = reportDay
            val start = dayStartCash
            reportDay = day
            dayStartCash = cash
            updateSummary(cash)
            if (referenceState == ReferenceState.PLAYING && foreground && hasWindowFocus() && !drawing &&
                speedChip.multiple <= 24.0 && cash.isFinite() && start.isFinite())
                StudioProgress.report(this, currency, finished, snap.trains, start, cash, null, next?.goal)
        }
    }

    private fun showBalance(cash: Double) {
        if (!cash.isFinite()) return
        lastCash = cash
        balanceUnit.text = "KAS · ${currency.code}"
        balance.text = currency.compact(cash)
        balance.contentDescription = "Kas, ${currency.millions(cash)}"
    }

    private fun discardDraft() {
        if (map.draft.isEmpty()) { cancelDrawing(); return }
        StudioDialogBuilder(this).setTitle("Batalkan rancangan jalur?")
            .setView(StudioDecision.draft(this,map.draft,map.bends,plan.tool.label,plan.specification))
            .setMessage("Titik dan lengkung yang sedang digambar akan dihapus.")
            .setPositiveButton("Batalkan rancangan") { _, _ -> cancelDrawing() }
            .setNegativeButton("Lanjut menggambar", null).show()
    }

    private fun manageWorld() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (drawing) {
            options.add("Pengaturan alat" to { configureTool(plan.tool) })
            options.add("Pilih titik di tengah peta" to { selectPoint(map.centerPoint()) })
            if (plan.tool in setOf(BuildTool.BUS,BuildTool.FERRY) && map.draft.isNotEmpty() && map.draft.size < 24 && map.bends.size < 64) options.add("Tambah lengkung di tengah peta" to { map.onBend?.invoke(map.centerPoint()); Unit })
            options.add("Batalkan rancangan" to { discardDraft() })
        } else {
            for (backwards in listOf(true, false)) {
                history.next(backwards)?.let { entry ->
                    val verb = if (backwards) "Urungkan" else "Ulangi"
                    options.add("$verb · ${entry.label}" to {
                        val resume = reference != null || playWhenWarm
                        withCommittedWorld { restoreEdit(backwards, entry) { if (resume) startReference() } }
                    })
                }
            }
            if (map.stops.any { it.id >= 0 }) options.add("Pilih stasiun" to { chooseStation() })
            options.add("Lensa peta lanjutan" to { passengerLayers() })
            if (workshop) options.add("Bongkar layanan atau stasiun" to { withCommittedWorld { demolition() } })
            options.add("Keuangan" to { openFinance() })
            options.add("Pencapaian & misi" to { openProgress() })
            options.add("Tutorial bermain" to { chooseTutorial() })
            options.add("Diagram jaringan" to { StudioNetworkDiagram.show(this, JSONObject(Engine.geometry(handle)), cityStore.city(focusId ?: city.id).name, ::openStation, map.cityLayer) { id ->
                ServicePanel.details(this,handle,id,clock,true,::editPanel) { recordPanelChange("Jadwal layanan"); lastStatus = -1; refresh() }
            } })
            options.add("Cetak biru & konstruksi" to { withCommittedWorld { chooseConstruction() } })
            options.add("Pengaturan permainan" to { StudioGameSettings.show(this) {
                map.displaySettings = StudioGameSettings.read(this)
                zoomTools.visibility = if (map.displaySettings.zoomButtons) View.VISIBLE else View.GONE
                val selected = StudioCurrency.current(this)
                if (currency != selected) {
                    currency = selected
                    showBalance(NativeFinance.balance(handle,clock))
                }
            } })
            options.add("Arsip dan akun" to { withCommittedWorld { if (writeSave()) openLibrary() } })
            options.add("Kota di dunia ini" to {
                StudioZonePicker.show(this,cityStore,focusId ?: city.id,opened = openedCities,
                    allCities = ::allCities, select = ::locateCity)
            })
            if (!hostLibrary) options.add("Pilih kota" to { withCommittedWorld { showCityPicker(this, cityStore, city.id) { writeSave() } } })
        }
        if (drawing) {
            StudioUi.sheet(this, "Rancangan jalur") { column, dialog ->
                options.forEach { (label, click) ->
                    column.addView(StudioUi.button(this, label) { dialog.dismiss(); click() }.apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                }
            }
            return
        }
        searchMenu(options)
    }

    /** Cari & menu: one box finds commands, settings and stations; replaces the old Kelola list. */
    private fun searchMenu(options: List<Pair<String, () -> Unit>>) {
        fun iconFor(label: String): Pair<StudioUi.Icon, Int> = when {
            label.startsWith("Urungkan") || label.startsWith("Ulangi") -> StudioUi.Icon.UNDO to 0xffefedf5.toInt()
            label.startsWith("Pilih stasiun") -> StudioUi.Icon.PIN to 0xffe3edf9.toInt()
            label.startsWith("Lensa") -> StudioUi.Icon.LAYERS to 0xffffe4e4.toInt()
            label.startsWith("Bongkar") -> StudioUi.Icon.CLOSE to 0xffffe4e4.toInt()
            label.startsWith("Tutorial") -> StudioUi.Icon.HELP to 0xffe8e2ff.toInt()
            label.startsWith("Diagram") -> StudioUi.Icon.ROUTE to 0xffe1f1f6.toInt()
            label.startsWith("Cetak") -> StudioUi.Icon.PLUS to 0xffddf4d3.toInt()
            label.startsWith("Pengaturan") -> StudioUi.Icon.MENU to 0xffefedf5.toInt()
            label.startsWith("Arsip") -> StudioUi.Icon.CLOUD to 0xffe3edf9.toInt()
            label.startsWith("Keuangan") -> StudioUi.Icon.WALLET to 0xfffff3c4.toInt()
            else -> StudioUi.Icon.FOLDER to 0xfffff3c4.toInt()
        }
        val stations = map.stops.filter { it.id >= 0 }.distinctBy { it.name }.sortedBy { it.name }
        StudioUi.sheet(this, "Cari & menu") { column, dialog ->
            val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun row(label: String, sub: String?, icon: StudioUi.Icon, tint: Int, click: () -> Unit) {
                results.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = StudioDepot.touch(context, 0xd9ffffff.toInt(), 18)
                    setPadding(dp(10), dp(8), dp(12), dp(8))
                    minimumHeight = dp(56)
                    isClickable = true
                    contentDescription = label
                    addView(StudioDepot.symbol(context, icon, 19f, StudioUi.INK).apply {
                        background = StudioUi.panel(context, tint, 12, tint)
                    }, LinearLayout.LayoutParams(dp(38), dp(38)))
                    val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    texts.addView(StudioUi.text(context, label, 15f, true))
                    if (sub != null) texts.addView(StudioUi.text(context, sub, 12f, true, StudioUi.MUTED))
                    addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                    setOnClickListener { dialog.dismiss(); click() }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            fun section(name: String) = results.addView(StudioUi.text(this, name.uppercase(), 12f, true, StudioUi.MUTED).apply {
                letterSpacing = .06f
                setPadding(dp(4), dp(12), 0, dp(2))
            })
            fun render(query: String) {
                results.removeAllViews()
                val q = query.trim().lowercase()
                val commands = options.filter { q.isEmpty() || it.first.lowercase().contains(q) }
                if (commands.isNotEmpty()) section(if (q.isEmpty()) "Menu" else "Perintah")
                commands.forEach { (label, click) -> val (icon, tint) = iconFor(label); row(label, null, icon, tint, click) }
                if (q.length >= 2) {
                    val found = stations.filter { it.name.lowercase().contains(q) }.take(8)
                    if (found.isNotEmpty()) section("Stasiun")
                    found.forEach { st -> row(st.name, "Buka stasiun", StudioUi.Icon.PIN, 0xffe3edf9.toInt()) { openStation(st.id) } }
                }
                if (results.childCount == 0) results.addView(StudioUi.text(this, "Tidak ada yang cocok dengan “$query”.", 14f, true, StudioUi.MUTED).apply {
                    setPadding(dp(4), dp(16), dp(4), dp(16))
                })
            }
            if (!selectingInfrastructure && !removingTrack && status.text.isNotEmpty()) column.addView(StudioUi.text(this, status.text.toString(), 13f, true, StudioUi.MUTED).apply {
                setPadding(dp(4), 0, dp(4), dp(8))
            })
            column.addView(android.widget.EditText(this).apply {
                hint = "Cari perintah, menu, atau stasiun"
                typeface = StudioUi.face(context, true)
                textSize = 16f
                setTextColor(StudioUi.INK)
                setHintTextColor(StudioUi.MUTED)
                isSingleLine = true
                background = StudioUi.pill(context, -1, 28)
                setPadding(dp(18), 0, dp(18), 0)
                minHeight = dp(54)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { render(s?.toString().orEmpty()) }
                })
            }, LinearLayout.LayoutParams(-1, dp(54)))
            column.addView(results)
            render("")
        }
    }

    private var selectingInfrastructure = false
    private var removingTrack = false

    private fun inspectRail(point: PointF) {
        val id=map.nearestRail(point)
        val rail=id?.let { map.railInfo(it) }
        if(rail==null) { status.text="Ketuk lebih dekat ke rel atau stasiun."; return }
        selectingInfrastructure=false
        updateControls()
        map.contentDescription="Peta jaringan kereta"
        lastStatus=-1; refresh()
        fun level(n:Int)=when { n>0 -> "Viaduk +$n"; n<0 -> "Terowongan $n"; else -> "Permukaan" }
        val type=when(rail.kind) { 1 -> "Rel cepat"; 2 -> "Trem jalan"; 3 -> "Trem"; else -> "Rel konvensional" }
        StudioUi.sheet(this,type) { body,dialog ->
            body.addView(StudioDecision(this,"Ruas rel").apply {
                fact("Panjang",java.lang.String.format(java.util.Locale.forLanguageTag("id-ID"),"%.2f km",rail.length/1000))
                fact("Elevasi",if(rail.low==rail.high) level(rail.low) else "${level(rail.low)} → ${level(rail.high)}")
                fact("Jumlah sepur",rail.tracks.toString())
                if(rail.speed.isFinite()) fact("Batas kecepatan","${rail.speed.toInt()} km/jam")
            })
            body.addView(StudioUi.button(this,"Tempatkan stasiun",StudioUi.Tone.PRIMARY) {
                dialog.dismiss()
                configureTool(BuildTool.STATION)
            })
            body.addView(StudioUi.button(this,"Kembali ke peta") { dialog.dismiss() })
        }
    }

    private fun startTrackRemoval() {
        stopClock()
        removingTrack = true
        updateControls()
        status.text = "Ketuk ruas rel yang ingin dibongkar."
        map.contentDescription = status.text
        map.announceForAccessibility(status.text)
    }

    private fun selectTrackForRemoval(point: PointF) {
        val edge = map.nearestRail(point)
        if (edge == null) {
            Toast.makeText(this,"Ketuk lebih dekat ke ruas rel.",Toast.LENGTH_SHORT).show()
            return
        }
        removingTrack = false
        updateControls()
        map.contentDescription = "Peta jaringan kereta"
        refresh()
        val preview = JSONObject(DemolitionPanel.remove(handle,2,edge,clock,false))
        if (preview.has("error")) {
            Toast.makeText(this,preview.getString("error"),Toast.LENGTH_LONG).show()
            return
        }
        StudioDialogBuilder(this).setTitle("Bongkar ruas rel?")
            .setView(DemolitionPanel.impact(this,2,preview))
            .setNegativeButton("Batal",null)
            .setPositiveButton("Bongkar") { _,_ -> commitDemolition(2,edge,"Ruas rel") }.show()
    }

    private fun demolition() {
        stopClock()
        DemolitionPanel.show(this,handle,clock,::commitDemolition)
    }

    private fun commitDemolition(kind: Int, id: Int, label: String) {
        val before = Engine.save(handle, clock)
        if (!history.canKeep(before) || local.baseFile.lastModified() != saveRevision) {
            Toast.makeText(this, "Cadangan untuk urungkan belum siap. Buka kembali dunia ini.", Toast.LENGTH_LONG).show()
            return
        }
        val result = JSONObject(DemolitionPanel.remove(handle, kind, id, clock, true))
        if (result.has("error")) {
            Toast.makeText(this, result.getString("error"), Toast.LENGTH_LONG).show()
            return
        }
        history.record("Bongkar $label", before)
        worldChanged = true
        val geometry = JSONObject(Engine.geometry(handle))
        map.load(geometry, refit = false)
        val routes = geometry.getJSONArray("routes")
        lastService = routes.optJSONObject(routes.length() - 1)?.optString("name").orEmpty()
        updateSummary()
        lastStatus = -1
        refresh()
        updateControls()
        writeSave()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::map.isInitialized) {
            map.finishStationInspection()
            if (resumeOnPanelClose && foreground && !checkpointInFlight) {
                resumeOnPanelClose = false
                if (reference == null && !playWhenWarm && !drawing && !restoringEdit) startReference()
            }
        }
    }

    private fun openStation(node: Int) {
        map.showStationAboveSheet(node)
        StationInspector.riders = { id -> map.stops.firstOrNull { it.id == id }?.let { map.overlay.riders(it.x, it.y) } }
        StationInspector.show(this,handle,node,clock, ::editPanel, {
            recordPanelChange("Fasilitas stasiun")
            map.load(JSONObject(Engine.geometry(handle)), refit = false, keepTrainLoads = true)
            showBalance(NativeFinance.balance(handle,clock))
            updateControls()
        })
    }

    private fun chooseStation() {
        val stations = map.stops.filter { it.id >= 0 }.sortedBy { it.name }
        StudioDialogBuilder(this).setTitle("Pilih stasiun").setItems(stations.map { it.name }.toTypedArray()) { _, i ->
            val station = stations[i]
            openStation(station.id)
        }.setNegativeButton("Tutup", null).show()
    }

    private fun passengerLayers() {
        StudioUi.sheet(this,"Lapisan peta") { column,dialog ->
            column.addView(StudioUi.text(this,"Pilih yang ingin dilihat di peta",14f,true,StudioUi.MUTED))
            fun group(index: Int, name: String, tint: Int, icon: StudioUi.Icon, options: List<Pair<String,String>>) {
                column.addView(StudioUi.section(this,name))
                // Segmented chips: one choice per group, applied at once.
                val chips = WrapRow(this)
                options.forEach { (label,layer) ->
                    val selected = map.overlay.selection[index] == layer
                    chips.addView(StudioUi.text(this,label,14f,true,if (selected) StudioUi.WHITE else StudioUi.INK).apply {
                        background = StudioDepot.touch(context, if (selected) StudioUi.INK else 0xd9ffffff.toInt(), 20)
                        setPadding(dp(14),dp(9),dp(14),dp(10))
                        isClickable = true
                        contentDescription = "$name · $label"
                        setOnClickListener {
                            dialog.dismiss()
                            map.overlay.choose(index,layer)
                            updateLensLegend()
                            map.announceForAccessibility("$name · $label")
                        }
                    })
                }
                column.addView(chips)
            }
            // the web's demand selector (DemandSelector.tsx) in its words: bubbles, purpose, stations
            group(0,"Gelembung perjalanan",StudioUi.TINT_PURPLE,StudioUi.Icon.ROUTE,listOf("Mati" to "none","Rumah" to "home","Destinasi" to "work","Total" to "both"))
            column.addView(StudioUi.section(this,"Tujuan perjalanan di peta"))
            column.addView(purposeChips { dialog.dismiss() })
            group(1,"Penumpang stasiun",StudioUi.TINT_RED,StudioUi.Icon.PIN,listOf("Mati" to "none","Asal" to "origins","Pindah" to "transfers","Total" to "total"))
            group(2,"Warna jalur",StudioUi.TINT_BLUE,StudioUi.Icon.TRAIN,listOf("Warna layanan" to "line","Lalu lintas" to "traffic","Kecepatan" to "speed","Ketinggian" to "height","Laba" to "profit"))
            if (map.overlay.selection[1] != "none" || map.overlay.selection[2] == "traffic") column.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4),dp(12),dp(4),0)
                addView(StudioUi.text(context,"Sepi",12f,true,StudioUi.MUTED))
                addView(View(context).apply {
                    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(0xffffd23f.toInt(),0xffff7a2f.toInt(),0xffe5484d.toInt())).apply { cornerRadius = dp(5).toFloat() }
                }, LinearLayout.LayoutParams(0,dp(10),1f).apply { marginStart = dp(8); marginEnd = dp(8) })
                addView(StudioUi.text(context,"Padat",12f,true,StudioUi.MUTED))
            })
            column.addView(StudioUi.row(this,StudioUi.Icon.CENTER,StudioUi.TINT_YELLOW,"Ringkasan penumpang","Asal dan tujuan perjalanan di kota",
                StudioDepot.symbol(this,StudioUi.Icon.ROUTE,16f,StudioUi.MUTED)) { dialog.dismiss(); showDemand() },
                LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(14) })
        }
    }

    private fun showDemand() {
        if (loadingDemand) return
        val requestedCity = focusId ?: city.id
        val snapshot = Engine.save(handle, clock)
        val hour = (clock.toLong() / 3600 % 24).toInt()
        val period = when (hour) { in 6..9 -> 0; in 10..15 -> 1; in 16..19 -> 2; in 20..23 -> 3; else -> 4 }
        fun show(pack: CityPackStore.CityPack) = NativeRidership.show(this, snapshot, pack.header, pack.cells, period)
        val pack = activePack?.takeIf { it.city.id == requestedCity }
        if (pack != null) { show(pack); return }
        loadingDemand = true
        Toast.makeText(this, "Memuat data penumpang…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val result = runCatching { cityStore.load(requestedCity) }
            runOnUiThread {
                loadingDemand = false
                if (!closed && !isFinishing) result.onSuccess { show(it) }.onFailure {
                    StudioDialogBuilder(this).setTitle("Data penumpang belum tersedia")
                        .setMessage(it.localizedMessage).setPositiveButton("Tutup", null).show()
                }
            }
        }
    }

    private fun stopClock() {
        running = false
        map.removeCallbacks(tick)
        updateControls()
    }

    private fun updateControls() {
        val openBend = map.bends.lastOrNull()?.first == map.draft.lastIndex
        val ready = handle != 0L && !placingRail && !restoringEdit &&
            referenceState != ReferenceState.OPENING && referenceState != ReferenceState.SAVING
        build.isEnabled = ready && (!drawing || (map.draft.size >= plan.tool.minPoints && !openBend))
        dockTitle.text = if (drawing) plan.specification else ""
        dockTitle.visibility = if (drawing) View.VISIBLE else View.GONE
        controlHint.visibility = if (!ready || drawing || selectingInfrastructure || removingTrack) View.VISIBLE else View.GONE
        speedChip.isEnabled = ready
        speedChip.visibility = if (drawing) View.GONE else View.VISIBLE
        lines.visibility = if (drawing) View.GONE else View.VISIBLE
        finance.visibility = lines.visibility
        cancel.visibility = if (drawing) View.VISIBLE else View.GONE
        lines.isEnabled = ready
        finance.isEnabled = ready
        more.isEnabled = ready
        cancel.isEnabled = ready
        play.isEnabled = ready && (!drawing || map.draft.isNotEmpty())
        save.isEnabled = ready && !drawing
        save.contentDescription = if (hostLibrary) "Simpan ke Studio" else "Simpan di perangkat"
        fun render(button: Button, label: String, description: String, icon: StudioUi.Icon) {
            if (button.text != label) button.text = label
            button.contentDescription = description
            button.alpha = if (button.isEnabled) 1f else .55f
            if (button.tag != icon) {
                val glyph = StudioUi.glyph(this, icon, if (button == build) StudioUi.WHITE else StudioUi.INK)
                button.setCompoundDrawables(null,glyph,null,null)
                button.tag = icon
            }
        }
        railBar.visibility=if(railMode) View.VISIBLE else View.GONE
        if(railMode) railBar.display(plan)
        map.railTool=if(railMode) plan.kind else null
        render(build, if(railMode) "Konfirmasi" else if (drawing) "Tinjau" else "Bangun", if(railMode) "Konfirmasi segmen rel" else if (drawing) plan.tool.review else "Bangun jalur",
            if (drawing) StudioUi.Icon.ROUTE else StudioUi.Icon.PLUS)
        render(lines,"Jalur","Jalur",StudioUi.Icon.ROUTE)
        render(finance,"Lensa","Lensa peta",StudioUi.Icon.LAYERS)
        render(cancel,if(railMode) "Stop" else "Batal",if(railMode) "Akhiri rangkaian segmen" else "Batalkan rancangan",StudioUi.Icon.CLOSE)
        render(more,if (drawing) "Alat" else "Menu",if (drawing) "Alat rancangan" else "Cari dan menu",StudioUi.Icon.MENU)
        when {
            drawing -> render(play,if(railMode) "Kembali" else "Urung",if(railMode) "Kembali satu segmen" else "Urungkan titik terakhir",StudioUi.Icon.UNDO)
            running || referenceState == ReferenceState.PLAYING -> render(play,"Jeda","Jeda",StudioUi.Icon.PAUSE)
            playWhenWarm && computing() -> render(play,"${(warmFraction()*100).toInt()}%","Batalkan putar",StudioUi.Icon.CLOSE)
            else -> render(play,"Putar",if (parked != null) "Putar, penumpang siap" else "Putar",StudioUi.Icon.PLAY)
        }
        // A finished forecast shows as a green dot on the play button.
        val dot = !drawing && reference == null && !running && parked != null
        if (playDot != dot) {
            playDot = dot
            play.tag = null
            when {
                drawing -> render(play,if(railMode) "Kembali" else "Urung",if(railMode) "Kembali satu segmen" else "Urungkan titik terakhir",StudioUi.Icon.UNDO)
                running || referenceState == ReferenceState.PLAYING -> render(play,"Jeda","Jeda",StudioUi.Icon.PAUSE)
                playWhenWarm && computing() -> render(play,"${(warmFraction()*100).toInt()}%","Batalkan putar",StudioUi.Icon.CLOSE)
                else -> render(play,"Putar","Putar",StudioUi.Icon.PLAY)
            }
            if (dot) {
                val glyph = play.compoundDrawables[1]
                val badge = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xff2fb36d.toInt()); setStroke(dp(2), StudioUi.WHITE)
                }
                val size = glyph.bounds.width()
                val layered = android.graphics.drawable.LayerDrawable(arrayOf(glyph, badge)).apply {
                    setLayerInset(1, size - dp(9), 0, -dp(2), size - dp(11))
                    setBounds(0, 0, size, glyph.bounds.height())
                }
                play.setCompoundDrawables(null, layered, null, null)
            }
        }
        map.isDrafting = drawing
        map.placingStation = drawing && !railMode && plan.tool == BuildTool.STATION
        if (::lensLegend.isInitialized && lensLegend.childCount > 0) lensLegend.visibility = if (drawing || removingTrack) View.GONE else View.VISIBLE
        // Demolition mode reads as a red banner; everything else is the frosted status pill.
        val danger = removingTrack
        if (controlHint.tag != danger) {
            controlHint.tag = danger
            controlHint.background = if (danger) StudioUi.pill(this, 0xf2e5484d.toInt(), 22) else StudioUi.glass(this, 22)
            dockTitle.setTextColor(if (danger) StudioUi.WHITE else StudioUi.INK)
            status.setTextColor(if (danger) StudioUi.WHITE else StudioUi.MUTED)
        }
        if (danger) { dockTitle.text = "Mode bongkar"; dockTitle.visibility = View.VISIBLE }
    }

    private fun chooseTutorial() {
        StudioUi.sheet(this, "Tutorial bermain") { form, dialog ->
            form.addView(StudioUi.text(this, "Pilih latihan untuk dunia ini.", 15f, color = StudioUi.MUTED))
            form.addView(StudioUi.button(this, "Bangun lintas baru", StudioUi.Tone.PRIMARY, StudioUi.Icon.ROUTE) {
                dialog.dismiss()
                withCommittedWorld { chooseConstruction() }
            }, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(12) })
            form.addView(StudioUi.text(this, "Pilih moda, gambar jalur, lalu tempatkan stasiun atau halte dan atur layanannya.", 14f).apply {
                setPadding(dp(3), dp(4), dp(3), 0)
            })
            form.addView(StudioUi.button(this, "Panduan operasi", icon = StudioUi.Icon.TRAIN) {
                dialog.dismiss()
                openGuide()
            }, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(12) })
            form.addView(StudioUi.text(this, "Ikuti panduan penumpang dan pengoperasian layanan di dunia ini.", 14f).apply {
                setPadding(dp(3), dp(4), dp(3), 0)
            })
        }
    }

    private fun chooseConstruction() {
        selectingInfrastructure=false
        removingTrack=false
        stopClock()
        var dialog: android.app.Dialog? = null
        run {
            val tiles=BuildTool.values().map { tool -> Triple(tool.key,tool.label,{
                dialog?.dismiss()
                configureTool(tool)
            }) }.toMutableList()
            if(map.hasPlans) tiles.add(Triple("plan","Rencana") { dialog?.dismiss(); reviewRailPlans() })
            tiles.add(Triple("select","Pilih") {
                dialog?.dismiss()
                selectingInfrastructure=true
                updateControls()
                status.text="Ketuk ruas rel atau stasiun untuk memeriksanya."
                map.contentDescription=status.text
                map.announceForAccessibility(status.text)
            })
            tiles.add(Triple("delete","Bongkar") {
                dialog?.dismiss()
                StudioUi.sheet(this,"Bongkar") { choices,menu ->
                    choices.addView(StudioUi.button(this,"Bongkar rel di peta") { menu.dismiss(); startTrackRemoval() })
                    choices.addView(StudioUi.button(this,"Layanan dan stasiun") { menu.dismiss(); demolition() })
                }
            })
            val hint = when {
                map.routeCount == 0 && map.stationCount == 0 -> "Mulai dari Rel, lalu taruh Stasiun, lalu buat Layanan."
                map.stationCount > 0 && map.routeCount == 0 -> "Stasiun sudah ada. Buat Layanan supaya kereta jalan."
                else -> "Saran: tambah Layanan di lintas yang padat, atau perpanjang Rel ke kawasan baru."
            }
            dialog = BuildWheel.show(this, "Mau bangun apa?", tiles, hint)
        }
    }

    private fun reviewRailPlans() {
        val quote=JSONObject(NativeConstruction.buildPlans(handle,clock,false))
        if(quote.has("error")) { Toast.makeText(this,quote.getString("error"),Toast.LENGTH_LONG).show(); return }
        StudioDialogBuilder(this).setTitle("Bangun rencana rel dan stasiun?")
            .setMessage("${quote.getInt("edges")} ruas rel · ${quote.getInt("stations")} peron · ${currency.compact(quote.getDouble("cost"))}")
            .setNegativeButton("Nanti",null)
            .setPositiveButton("Bangun") { _,_ ->
                val before=Engine.save(handle,clock)
                val candidate=Engine.forkCandidate(handle,clock,RoadTileStore.directory(this).absolutePath)
                if(candidate==0L) return@setPositiveButton
                val result=JSONObject(NativeConstruction.buildPlans(candidate,clock,true))
                if(result.has("error") || !persistBytes(Engine.save(candidate,clock))) {
                    Engine.close(candidate)
                    Toast.makeText(this,result.optString("error","Rencana belum tersimpan."),Toast.LENGTH_LONG).show()
                } else {
                    Engine.close(handle); handle=candidate
                    history.record("Bangun rencana",before)
                    map.load(JSONObject(Engine.geometry(handle)),refit=false)
                    worldChanged=true; updateSummary(); updateControls(); lastStatus=-1; refresh()
                }
            }.show()
    }

    private fun configureTool(tool: BuildTool) {
        if(tool==BuildTool.RAIL) {
            stopTrainPreview()
            drawing=true; plan=railOptions.copy(deferred=true)
            resetRailRun()
            return
        }
        val label=when(tool) {
            BuildTool.RAIL -> ""
            BuildTool.STATION -> ""
            BuildTool.LINE -> "Lintas ${map.routeCount+1}"
            BuildTool.BUS, BuildTool.FERRY -> "${tool.label} ${map.routeCount+1}"
        }
        val keep=drawing && plan.tool==tool
        val initial=if(keep) plan else ConstructionPlan(tool,label)
        ConstructionPanel.options(this,initial) {
            if(!keep) { pointProfile.clear(); map.draft.clear(); map.bends.clear(); stationOrder.clear() }
            plan=it
            stopTrainPreview()
            drawing=true
            updateDraft()
        }
    }

    private fun stationName(point: PointF): String {
        if(plan.name.isNotBlank()) return plan.name
        val base = (map.cityLayers.asSequence().flatMap { it.places.asSequence() }.minByOrNull { kotlin.math.hypot(it.x-point.x,it.y-point.y) }?.name
            ?: cityStore.city(focusId ?: city.id).name).take(42)
        val existing = map.stops.map { it.name }.toSet() + map.plannedStationNames
        if(base !in existing) return base
        return generateSequence(2) { it+1 }.map { "$base $it" }.first { it !in existing }
    }

    @Volatile private var draftVersion = 0
    private val draftWorker = Executors.newSingleThreadExecutor()
    private fun previewDraft() {
        val version=++draftVersion
        map.junctionMarks=emptyList()
        map.curveCircle=null; map.refused=false
        val spec=plan
        map.draftShape=if(spec.tool in setOf(BuildTool.RAIL,BuildTool.LINE)) emptyList() else null
        if(map.draftShape==null || map.draft.size<2) return
        val profile=pointProfile.toDoubleArray()
        val coords=map.draft.flatMap { listOf(it.x.toDouble(),it.y.toDouble()) }.toDoubleArray()
        val order=stationOrder.map { node -> map.stops.first { it.id==node }.packedId }.toIntArray()
        map.postDelayed({
            if(closed || !drawing || version!=draftVersion) return@postDelayed
            val directory=RoadTileStore.directory(this)
            val time=clock
            val candidate=Engine.forkCandidate(handle,time,directory.absolutePath)
            if(candidate==0L) return@postDelayed
            draftWorker.execute {
                if(version!=draftVersion) { Engine.close(candidate); return@execute }
                val result=try {
                    val raw=if(spec.tool==BuildTool.LINE) NativeRailLine.create(candidate,order,spec.name,time,spec.vehicle,false)
                    else {
                        if(spec.wireKind in setOf(2,10,11)) RoadTileStore.prepare(directory,Engine.requiredRoads(candidate,coords))
                        NativeConstruction.rail(candidate,coords,spec.level,spec.wireKind,spec.tracks,time,false,spec.speed,spec.flySpeed,spec.autoRamp,spec.flying,spec.radius,spec.deferred,true,profile)
                    }
                    JSONObject(raw)
                } catch(e:Exception) { JSONObject().put("error",e.localizedMessage) }
                finally { Engine.close(candidate) }
                runOnUiThread {
                    if(closed || !drawing || version!=draftVersion) return@runOnUiThread
                    if(result.has("error")) status.text=result.getString("error")
                    else {
                        val xs=result.getJSONArray("path")
                        map.draftShape=(0 until xs.length()-1 step 2).map { PointF(xs.getDouble(it).toFloat(),xs.getDouble(it+1).toFloat()) }
                        if(spec.tool==BuildTool.RAIL) {
                            map.railPreviewLabels(result)
                            if(map.refused) status.text=result.getString("issue")
                            val curve=if(result.isNull("radius")) "" else " · R ${result.getDouble("radius").toInt()} m · ${result.getDouble("speed").toInt()} km/jam"
                            val grade=String.format(java.util.Locale.forLanguageTag("id-ID"),"%.1f",result.getDouble("grade"))
                            dockTitle.text="${result.getDouble("length").toInt()} m · ${currency.compact(result.getDouble("cost"))}$curve · kemiringan maks. $grade%"
                        }
                    }
                    map.invalidate()
                }
            }
        },120)
    }

    private fun updateDraft() {
        updateControls()
        dockTitle.text=plan.specification
        val n=map.draft.size
        status.text=when(plan.tool) {
            BuildTool.RAIL -> when(n) {
                0 -> "Ketuk peta untuk menandai awal rel."
                1 -> "Ketuk tujuan untuk melihat pratinjau."
                else -> "Geser ujung atau konfirmasi segmen."
            }
            BuildTool.STATION -> if(n==0) "Ketuk rel yang sudah dibangun untuk menempatkan peron." else "Lokasi dipilih. Ketuk rel lain atau tinjau stasiun."
            BuildTool.LINE -> if(n==0) "Ketuk stasiun pertama yang akan dilayani." else "$n stasiun · Ketuk stasiun berikutnya sesuai urutan perjalanan."
            BuildTool.BUS -> if(n==0) "Ketuk lokasi awal bus di dekat jalan." else "$n titik · Ketuk tujuan berikutnya. Rute akan mengikuti jalan."
            BuildTool.FERRY -> if(n==0) "Ketuk pesisir untuk memilih dermaga awal." else "$n dermaga · Ketuk dermaga berikutnya. Tahan untuk menandai lintasan air."
        }
        // Live estimate while drawing: straight-line length of the marked points (cost follows in the review).
        if(n>=2 && plan.tool in setOf(BuildTool.RAIL,BuildTool.BUS,BuildTool.FERRY)) {
            val metres=(1 until n).sumOf { i -> Math.hypot((map.draft[i].x-map.draft[i-1].x).toDouble(),(map.draft[i].y-map.draft[i-1].y).toDouble()) }
            dockTitle.text="${plan.specification} · ≈ ${java.lang.String.format(java.util.Locale.forLanguageTag("id-ID"),"%.1f",metres/1000)} km"
        }
        if(railMode && n==2) status.text="Geser ujung, ketuk lagi, atau pilih Konfirmasi."
        if(n>0 && plan.tool==BuildTool.STATION) dockTitle.text="${stationName(map.draft.last())} · peron ${plan.platform} m"
        previewDraft()
        status.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        map.contentDescription="Peta konstruksi ${plan.tool.label}. ${status.text}"
        map.invalidate()
    }

    private fun undoDraft() {
        if(map.draft.isEmpty()) return
        if(map.bends.lastOrNull()?.first==map.draft.lastIndex) map.bends.removeAt(map.bends.lastIndex)
        else {
            map.draft.removeAt(map.draft.lastIndex)
            if(plan.tool==BuildTool.RAIL) repeat(3) { pointProfile.removeAt(pointProfile.lastIndex) }
            if(plan.tool==BuildTool.LINE && stationOrder.isNotEmpty()) stationOrder.removeAt(stationOrder.lastIndex)
        }
        updateDraft()
    }

    private fun cancelDrawing() {
        railChain.clear(); map.railSnaps.clear(); map.rawRailTip=null
        pointProfile.clear(); draftVersion++; map.draftShape=null; map.curveCircle=null; map.junctionMarks=emptyList()
        drawing=false
        status.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_NONE
        map.draft.clear(); map.bends.clear(); stationOrder.clear()
        updateControls()
        lastStatus=-1
        refresh()
    }

    private fun resetRailRun() {
        railChain.clear()
        pointProfile.clear(); map.draft.clear(); map.railSnaps.clear(); map.rawRailTip=null
        updateDraft()
    }
    private fun setRailTip(raw:PointF) {
        val hit=org.json.JSONArray(NativeConstruction.railPoint(handle,raw.x.toDouble(),raw.y.toDouble(),
            map.metresPerDp(),plan.kind,plan.level))
        if(map.draft.size==2) {
            map.draft.removeAt(1); map.railSnaps.removeAt(1)
            repeat(3) {pointProfile.removeAt(pointProfile.lastIndex)}
        }
        val snap=hit.getInt(2)
        map.draft.add(PointF(hit.getDouble(0).toFloat(),hit.getDouble(1).toFloat()))
        map.railSnaps.add(snap)
        recordRailPoint(hit.getInt(3),snap)
        map.rawRailTip=raw
        updateDraft()
    }
    private fun railBack() {
        if(placingRail || restoringEdit) return
        val step=railChain.lastOrNull()
        val target=history.next(true)
        if(step==null || target==null || step.edit.get()!==target) { resetRailRun(); return }
        restoreEdit(true,target) {
            railChain.removeAt(railChain.lastIndex)
            map.draft.clear(); map.railSnaps.clear(); pointProfile.clear()
            map.draft.add(step.origin); map.railSnaps.add(step.snap)
            recordRailPoint(step.level,step.snap)
            map.rawRailTip=null; updateDraft()
        }
    }
    private fun placeRailSegment() {
        if(map.draft.size!=2 || placingRail || restoringEdit || map.refused) return
        val spec=plan; val time=clock; val source=handle
        val before=Engine.save(source,time)
        val directory=RoadTileStore.directory(this)
        val candidate=Engine.forkCandidate(source,time,directory.absolutePath)
        if(candidate==0L) { status.text="Rencana rel belum dapat dibuka."; return }
        val coords=map.draft.flatMap { listOf(it.x.toDouble(),it.y.toDouble()) }.toDoubleArray()
        val profile=pointProfile.toDoubleArray()
        val version=++draftVersion
        placingRail=true; updateControls()
        draftWorker.execute {
            val result=runCatching {
                if(spec.wireKind in setOf(2,10,11)) RoadTileStore.prepare(directory,Engine.requiredRoads(candidate,coords))
                val built=JSONObject(NativeConstruction.rail(candidate,coords,spec.level,spec.wireKind,spec.tracks,
                    time,true,spec.speed,spec.flySpeed,spec.autoRamp,spec.flying,spec.radius,true,false,profile))
                check(!built.has("error")) { built.optString("error") }
                JSONObject(Engine.geometry(candidate)) to Engine.save(candidate,time)
            }
            runOnUiThread {
                placingRail=false
                if(closed || !railMode || version!=draftVersion || handle!=source || isFinishing || isChangingConfigurations) {
                    Engine.close(candidate); return@runOnUiThread
                }
                val prepared=result.getOrNull()
                if(prepared==null || !persistBytes(prepared.second)) {
                    Engine.close(candidate); updateControls()
                    status.text=result.exceptionOrNull()?.localizedMessage ?: "Segmen belum tersimpan."
                    return@runOnUiThread
                }
                history.record("Taruh segmen rel",before)
                railChain.removeAll { it.edit.get()==null }
                history.next(true)?.let { railChain.add(RailStep(map.draft.first(),profile[0].toInt(),map.railSnaps.first(),java.lang.ref.WeakReference(it))) }
                handle=candidate; Engine.close(source)
                map.load(prepared.first,refit=false)
                val end=map.lastTrackEnd ?: map.draft.last()
                pointProfile.clear(); map.draft.clear(); map.railSnaps.clear()
                map.draft.add(end); map.railSnaps.add(1); recordRailPoint(profile[3].toInt())
                map.rawRailTip=null; updateSummary(); updateDraft()
            }
        }
    }

    private fun selectPoint(point: PointF) {
        if(railMode) {
            if(placingRail || restoringEdit) return
            val last=map.rawRailTip ?: map.draft.lastOrNull()
            if(last!=null && kotlin.math.hypot(last.x-point.x,last.y-point.y)<24*map.metresPerDp()) {
                if(map.draft.size==2) placeRailSegment() else resetRailRun()
            } else setRailTip(point)
            return
        }
        if(plan.tool==BuildTool.STATION) {
            val target=JSONObject(NativeConstruction.platformPoint(handle,point.x.toDouble(),point.y.toDouble(),map.pickRadiusMeters(),plan.deferred))
            if(target.has("error")) { status.text=target.getString("error"); return }
            map.draft.clear()
            map.draft.add(PointF(target.getDouble("x").toFloat(),target.getDouble("y").toFloat()))
            updateDraft()
            return
        } else if(map.draft.size>=24) {
            status.text="Batas 24 titik tercapai. Tinjau rancangan atau urungkan titik terakhir."
            return
        }
        if(plan.tool==BuildTool.LINE) {
            val nearest=map.stops.asSequence().filter { it.id>=0 }.minByOrNull { kotlin.math.hypot(it.x-point.x,it.y-point.y) }
            if(nearest==null || kotlin.math.hypot(nearest.x-point.x,nearest.y-point.y)>map.pickRadiusMeters()) {
                status.text="Ketuk penanda stasiun di peta."
                return
            }
            if(stationOrder.lastOrNull()==nearest.id) return
            stationOrder.add(nearest.id)
            map.draft.add(PointF(nearest.x,nearest.y))
        } else {
            val previous=map.draft.lastOrNull()
            val minDistance=if(plan.tool==BuildTool.BUS || plan.tool==BuildTool.FERRY) 350f else 1f
            if(previous!=null && kotlin.math.hypot(point.x-previous.x,point.y-previous.y)<minDistance) {
                status.text="Titik berikutnya harus berjarak setidaknya ${minDistance.toInt()} m."
                return
            }
            map.draft.add(point)
        }
        updateDraft()
    }

    private data class Quote(val geometry: JSONObject,val bytes: ByteArray,val charge: Double,val vertices: List<PointF>?,val snapped: PointF?)

    private fun reviewDraft() {
        val spec=if(plan.tool==BuildTool.STATION) plan.copy(name=stationName(map.draft.single())) else plan
        val points=map.draft.map { PointF(it.x,it.y) }
        val bends=map.bends.toList()
        val order=stationOrder.map { node -> map.stops.first { it.id==node }.packedId }.toIntArray()
        val snap=map.pickRadiusMeters()
        val coords=points.flatMap { listOf(it.x.toDouble(),it.y.toDouble()) }.toDoubleArray()
        val via=points.flatMapIndexed { i,p -> listOf(p)+bends.filter { it.first==i }.map { it.second } }
            .flatMap { listOf(it.x.toDouble(),it.y.toDouble()) }.toDoubleArray()
        val curves=bends.flatMap { (leg,p) -> listOf(leg.toDouble(),p.x.toDouble(),p.y.toDouble()) }.toDoubleArray()
        val original=Engine.save(handle,clock)
        val editTime=clock
        val directory=RoadTileStore.directory(this)
        val candidate=Engine.forkCandidate(handle,clock,directory.absolutePath)
        if(candidate==0L) { Toast.makeText(this,"Rancangan belum dapat dibuka.",Toast.LENGTH_LONG).show(); return }
        val activeHandle=handle
        var prepared=0L
        var geometry: JSONObject?=null
        var bytes: ByteArray?=null
        lateinit var primary: Button
        reviewDialog=StudioUi.sheet(this,spec.tool.review,pinnedAction={ primary }) { body,dialog ->
            body.addView(StudioDecision.draft(this,points,bends,spec.tool.label,spec.specification))
            val cost=StudioUi.text(this,if(spec.tool==BuildTool.BUS || spec.tool==BuildTool.RAIL && spec.kind==2) "Menghitung lintasan jalan…" else "Menghitung biaya…",17f,true,StudioDepot.TEAL)
            cost.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            body.addView(cost,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
            if(spec.tool==BuildTool.STATION) body.addView(StudioUi.button(this,"Ubah nama stasiun") {
                dialog.dismiss(); plan=spec; configureTool(BuildTool.STATION)
            })
            body.addView(StudioUi.button(this,"Kembali ke peta") { dialog.dismiss() })
            primary=StudioUi.button(this,if(spec.deferred) "Simpan rencana" else spec.tool.action,StudioUi.Tone.PRIMARY) {
                val snapshot=bytes ?: return@button
                val geo=geometry ?: return@button
                if(handle!=activeHandle || prepared==0L || !persistBytes(snapshot)) return@button
                val old=handle
                handle=prepared
                prepared=0L
                Engine.close(old)
                history.record(if(spec.deferred) "Simpan rencana" else spec.tool.action,original)
                dialog.dismiss()
                map.load(geo,refit=false)
                clock=geo.getDouble("clock")
                val routes=geo.getJSONArray("routes")
                lastService=routes.optJSONObject(routes.length()-1)?.optString("name").orEmpty()
                updateSummary()
                cancelDrawing()
                constructionComplete(spec)
            }.apply { isEnabled=false }
            dialog.setOnDismissListener {
                reviewDialog=null
                if(prepared!=0L) { Engine.close(prepared); prepared=0L }
            }
            // Only this worker touches the candidate until it hands ownership back to the UI.
            Thread({
                val result=runCatching {
                    val balanceBefore=NativeFinance.balance(candidate,editTime)
                    val json=when(spec.tool) {
                        BuildTool.RAIL -> error("Konfirmasi rel dilakukan di peta.")
                        BuildTool.STATION -> NativeConstruction.station(candidate,coords[0],coords[1],snap,spec.name,spec.platform,editTime,true,spec.deferred)
                        BuildTool.LINE -> NativeRailLine.create(candidate,order,spec.name,editTime,spec.vehicle,true)
                        BuildTool.BUS, BuildTool.FERRY -> {
                            if(spec.tool==BuildTool.BUS) RoadTileStore.prepare(directory,Engine.requiredRoads(candidate,via))
                            Engine.build(candidate,coords,spec.name,editTime,if(spec.tool==BuildTool.BUS) 5 else 4,curves)
                        }
                    }
                    val built=JSONObject(json)
                    check(!built.has("error")) { built.optString("error") }
                    val save=Engine.save(candidate,editTime)
                    check(save.isNotEmpty()) { "Simpanan rancangan belum siap." }
                    val geo=JSONObject(Engine.geometry(candidate))
                    val path=built.optJSONArray("path") ?: if(spec.tool in setOf(BuildTool.BUS,BuildTool.FERRY,BuildTool.LINE)) {
                        val id=built.optInt("line",built.optInt("id",-1))
                        val routes=geo.getJSONArray("routes")
                        (0 until routes.length()).map { routes.getJSONObject(it) }.firstOrNull { it.optInt("id")==id }?.optJSONArray("points")
                    } else null
                    val vertices=path?.let { xs -> (0 until xs.length()-1 step 2).map { i -> PointF(xs.getDouble(i).toFloat(),xs.getDouble(i+1).toFloat()) } }
                    val builtPoint=if(built.has("x") && built.has("y")) PointF(built.getDouble("x").toFloat(),built.getDouble("y").toFloat()) else null
                    Quote(geo,save,built.optDouble("cost",balanceBefore-NativeFinance.balance(candidate,editTime)),vertices,builtPoint)
                }
                runOnUiThread {
                    if(closed || !dialog.isShowing || handle!=activeHandle || result.isFailure) {
                        Engine.close(candidate)
                        if(dialog.isShowing) cost.text=result.exceptionOrNull()?.localizedMessage ?: "Dunia sudah berubah."
                    } else {
                        val (geo,save,charge,vertices,builtPoint)=result.getOrThrow()
                        geometry=geo; bytes=save; prepared=candidate
                        if(!vertices.isNullOrEmpty() || builtPoint!=null) {
                            body.removeViewAt(0)
                            val markers=if(builtPoint==null) points else listOf(builtPoint)
                            body.addView(StudioDecision.draft(this,markers,bends,spec.tool.label,spec.specification,vertices),0)
                        }
                        cost.text=if(spec.deferred) "Rencana disimpan tanpa biaya. Bangun nanti dari menu Bangun." else "Biaya pembangunan · ${currency.compact(charge)}"
                        cost.typeface=StudioUi.heavy(this)
                        cost.background=StudioUi.pill(this,StudioUi.TINT_YELLOW,18)
                        cost.setPadding(dp(14),dp(10),dp(14),dp(12))
                        primary.isEnabled=true
                    }
                }
            },"studio-construction-quote").start()
        }
    }

    private fun constructionComplete(spec: ConstructionPlan) {
        val next=when(spec.tool){ BuildTool.RAIL -> BuildTool.STATION; BuildTool.STATION -> BuildTool.LINE; else -> null }
        val title=if(spec.deferred) "Rencana tersimpan" else when(spec.tool){ BuildTool.RAIL -> "Rel selesai dibangun"; BuildTool.STATION -> "Stasiun selesai dibangun"; else -> "Layanan siap diatur" }
        StudioUi.sheet(this,title) { body,dialog ->
            body.addView(StudioUi.text(this,spec.specification,16f,true,StudioDepot.TEAL))
            val label=when(next){ BuildTool.STATION -> "Tempatkan stasiun"; BuildTool.LINE -> "Buat layanan kereta"; else -> "Atur jadwal" }
            body.addView(StudioUi.button(this,label,StudioUi.Tone.PRIMARY) {
                dialog.dismiss()
                if(next==null) openServices() else configureTool(next)
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
            if(spec.tool==BuildTool.STATION) body.addView(StudioUi.button(this,"Tambah stasiun") { dialog.dismiss(); configureTool(BuildTool.STATION) })
            body.addView(StudioUi.button(this,"Kembali ke peta") { dialog.dismiss() })
        }
    }

    private fun dp(value:Int) = (value * resources.displayMetrics.density).toInt()
    private fun advanceLocalClock() {
        val now = SystemClock.elapsedRealtime()
        if (now > lastTick) worldChanged = true
        clock += (now - lastTick) * localRate / 1000.0
        lastTick = now
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running || handle==0L || map.requiresStudioClock) return
            advanceLocalClock()
            refresh(); map.postDelayed(this, 65)
        }
    }
    private fun refresh() {
        if (reference == null && warming == null && !running) scheduleWarm()
        val displayClock = referenceTime ?: clock
        if (::sunArc.isInitialized) sunArc.second = Math.floorMod(displayClock.toLong() + localClockShift, 86400L).toInt()
        updateProgress(displayClock)
        val trainCount = map.updateTrains(displayClock, referenceState == ReferenceState.PLAYING)
        val second = Math.floorMod(displayClock.toLong() + localClockShift, 86400L).toInt()
        if(second!=lastStatus) {
            lastStatus=second
            localTime.text = "%02d:%02d:%02d".format(java.util.Locale.ROOT,second/3600%24,second/60%60,second%60)
            if (!drawing && !selectingInfrastructure && !removingTrack && !restoringEdit && referenceState != ReferenceState.OPENING && referenceState != ReferenceState.SAVING) {
                val service = if (workshop && lastService.isNotEmpty()) " · $lastService" else ""
                status.text = if (map.routeCount == 0) "Pilih moda untuk mulai membangun jaringan." else "${trainCount} kereta di peta$service"
            }
        }
    }
    // A panel may stay open for hours of simulated time. Settle again at Apply so
    // the edit and its undo snapshot include all income earned since opening it.
    private var beforePanelEdit: ByteArray? = null

    private fun editPanel(apply: (Double) -> Unit) {
        val resume = reference != null || playWhenWarm
        withCommittedWorld {
            cancelWarm()
            beforePanelEdit = Engine.save(handle, clock)
            try {
                apply(clock)
                if (worldChanged) writeSave()
            } finally {
                beforePanelEdit = null
                if (resume && foreground && !drawing) startReference()
            }
        }
    }

    private fun recordPanelChange(label: String) {
        beforePanelEdit?.let { history.record(label, it) }
        worldChanged = true
    }

    private fun restoreEdit(backwards: Boolean, target: WorldEditHistory.Entry, restored:(()->Unit)?=null) {
        if (restoringEdit || history.next(backwards) !== target) return
        stopClock()
        val current = Engine.save(handle, clock)
        if (!history.canKeep(current)) {
            Toast.makeText(this, "Dunia terlalu besar untuk riwayat edit.", Toast.LENGTH_LONG).show()
            return
        }
        restoringEdit = true
        updateControls()
        status.text = "Memulihkan ${target.label}…"
        executor.execute {
            var candidate = 0L
            val result = runCatching {
                candidate = Engine.open(target.bytes)
                check(candidate != 0L) { "Cadangan edit tidak dapat dibuka." }
                JSONObject(Engine.geometry(candidate))
            }
            runOnUiThread {
                try {
                    if (closed || isFinishing || isChangingConfigurations) return@runOnUiThread
                    result.onSuccess { geometry ->
                        val changed = restoreMapPreferences(candidate)
                        if (!persistBytes(if (changed) Engine.save(candidate,geometry.getDouble("clock")) else target.bytes)) return@onSuccess
                        val previous = handle
                        CitySurface.copy(previous,candidate)
                        handle = candidate
                        candidate = 0L
                        Engine.close(previous)
                        history.moved(backwards, current)
                        clock = geometry.getDouble("clock")
                        map.load(geometry, refit = false)
                        val routes = geometry.getJSONArray("routes")
                        lastService = routes.optJSONObject(routes.length() - 1)?.optString("name").orEmpty()
                        updateSummary()
                        restored?.invoke()
                    }.onFailure {
                        Toast.makeText(this, it.localizedMessage ?: "Edit gagal dipulihkan.", Toast.LENGTH_LONG).show()
                    }
                    restoringEdit = false
                    lastStatus = -1
                    updateControls()
                    refresh()
                } finally {
                    if (candidate != 0L) Engine.close(candidate)
                }
            }
        }
    }

    private fun previewTrainLoads() {
        if (reference != null || parked != null || trainPreview != null || closed || !foreground || drawing || handle == 0L) return
        val version = map.trainNetworkVersion
        val source = handle
        val time = clock
        val bytes = Engine.previewArchive(source,time) ?: return
        val pack = activePack
        val cityId = city.id
        val generation = ++previewGeneration
        if (prepStart == 0L) prepStart = SystemClock.elapsedRealtime()
        if (::map.isInitialized) { map.removeCallbacks(warmTick); map.post(warmTick) }
        trainPreview = executor.submit {
            val rows = runCatching {
                val local = pack ?: cityStore.load(cityId)
                val files = NativeRidership.inputs(this,bytes,local.header,local.cells,true)
                NativeRidership.trainLoads(bytes,files.toString(),time)
            }.getOrNull()
            runOnUiThread {
                if (generation != previewGeneration) return@runOnUiThread
                trainPreview = null
                if (playWhenWarm) scheduleWarm(0)
                if (rows != null && !closed && handle == source && version == map.trainNetworkVersion && reference == null) {
                    val epoch = kotlin.math.floor(time/3600).toLong()*3600
                    map.trainLoads(epoch,org.json.JSONArray(rows))
                }
            }
        }
    }

    private fun stopTrainPreview() {
        previewGeneration++
        trainPreview?.cancel(true)
        trainPreview=null
    }

    private fun startReference() {
        if (reference != null || handle == 0L || drawing) return
        // The passenger preview already builds the demand the clock needs; finish it rather than start over.
        if (trainPreview != null && parked == null) {
            playWhenWarm = true
            updateWarmUi()
            updateControls()
            return
        }
        stopTrainPreview()
        if (worldChanged && !writeSave()) return
        val bytes = Engine.save(handle, clock)
        // A paused simulation whose world is unchanged resumes at once instead of rebuilding demand.
        parked?.let { idle ->
            val same = idle.isReady && parkedBytes?.contentEquals(bytes) == true
            parked = null
            parkedBytes = null
            if (same) {
                reference = idle
                referenceState = ReferenceState.PLAYING
                updateControls()
                idle.play(speedChip.rate)
                return
            }
            idle.close()
        }
        playWhenWarm = true
        if (warming == null) beginWarm(bytes)
        updateWarmUi()
        updateControls()
    }

    private fun withCommittedWorld(pause: Boolean = true, action: (() -> Unit)? = null) {
        val runtime = reference
        if (runtime == null) {
            if (pause && foreground) { resumeOnPanelClose = false; cancelWarm() }
            action?.invoke()
            return
        }
        if (referenceState == ReferenceState.OPENING) {
            finishReference(runtime)
            action?.invoke()
            return
        }
        if (checkpointInFlight) return
        if (pause && foreground) resumeOnPanelClose = false
        checkpointInFlight = true
        if (pause) {
            referenceState = ReferenceState.SAVING
            updateControls()
            status.text = "Menyimpan hasil simulasi…"
        }
        val key = local.baseFile.path
        val completed = CountDownLatch(1)
        pendingWrites[key] = completed
        fun done(error: String? = null) {
            checkpointInFlight = false
            when {
                error != null || closed || !foreground || isFinishing -> finishReference(runtime)
                pause -> parkReference(runtime)
                else -> {
                    referenceState = ReferenceState.PLAYING
                    resumeOnPanelClose = true
                    updateControls()
                }
            }
            completed.countDown()
            pendingWrites.remove(key, completed)
            if (closed) executor.shutdown()
            else if (error != null) referenceUnavailable(error)
            else if (foreground && !isFinishing) action?.invoke()
            val queued = afterCheckpoint
            afterCheckpoint = null
            if (queued != null && action == null && error == null && !closed && foreground && !isFinishing && !drawing && !removingTrack && !restoringEdit)
                withCommittedWorld(pause = false, queued)
        }
        runtime.snapshot(pause) { result ->
            result.onFailure { done(it.localizedMessage ?: "Snapshot simulasi tidak tersedia.") }
                .onSuccess { bytes ->
                    executor.execute {
                        var candidate = 0L
                        val restored = runCatching {
                            candidate = Engine.open(bytes)
                            check(candidate != 0L) { "Hasil simulasi tidak dapat dibuka." }
                            JSONObject(Engine.geometry(candidate))
                        }
                        runOnUiThread {
                            try {
                                restored.onFailure { done(it.localizedMessage ?: "Hasil simulasi belum dapat dibaca.") }
                                    .onSuccess { geometry ->
                                        val changed = restoreMapPreferences(candidate)
                                        val merged = if (changed) Engine.save(candidate,geometry.getDouble("clock")) else bytes
                                        if (!persistBytes(merged)) { done("Hasil simulasi belum tersimpan di perangkat."); return@onSuccess }
                                        if (!closed) {
                                            CitySurface.copy(handle,candidate)
                                            Engine.swapWorlds(handle, candidate)
                                            clock = geometry.getDouble("clock")
                                            map.load(geometry, refit = false, keepTrainLoads = true)
                                            updateSummary()
                                        }
                                        done()
                                    }
                            } finally { if (candidate != 0L) Engine.close(candidate) }
                        }
                    }
                }
        }
    }

    private var parked: SimulationClock? = null
    private var parkedBytes: ByteArray? = null

    private fun parkReference(runtime: SimulationClock) {
        reference = null
        referenceState = null
        referenceTime = null
        parked = runtime
        parkedBytes = if (handle != 0L) Engine.save(handle, clock) else null
        if (!closed && handle != 0L) {
            lastStatus = -1
            updateControls()
            refresh()
        }
    }

    private fun dropParked() {
        val idle = parked
        parked = null
        parkedBytes = null
        idle?.close()
    }

    private fun finishReference(runtime: SimulationClock) {
        reference = null
        referenceState = null
        referenceTime = null
        runtime.close()
        if (!closed && handle != 0L) {
            lastStatus = -1
            updateControls()
            refresh()
        }
    }

    private fun openGuide() {
        val guide = StudioOperationsGuide(this, { bit, card, show ->
            editPanel {
                val previous = Engine.guide(handle,-1,0)
                val result = Engine.guide(handle, if (bit == -1 && card == 0) -2 else bit, card)
                if (previous != result) { worldChanged = true; writeSave() }
                show(if (result == "null") JSONObject() else JSONObject(result))
            }
        }) { task ->
            when (task) {
                "zoom" -> map.zoomStep(true)
                "summary" -> showDemand()
                "layers" -> passengerLayers()
                "rail" -> withCommittedWorld { configureTool(BuildTool.RAIL) }
                "station" -> withCommittedWorld { configureTool(BuildTool.STATION) }
                "line" -> withCommittedWorld { configureTool(BuildTool.LINE) }
                "services" -> openServices()
                "play" -> if (play.isEnabled) play.performClick() else openServices()
                "finance" -> openFinance()
            }
        }
        guide.show()
    }

    private fun referenceUnavailable(reason: String) {
        if (closed || !foreground || isFinishing) return
        StudioDialogBuilder(this).setTitle("Simulasi belum bisa dilanjutkan")
            .setMessage("$reason\n\nSimpanan terakhir masih tersedia.")
            .setNegativeButton("Tutup", null)
            .setPositiveButton("Coba lagi") { _, _ -> startReference() }.show()
    }

    private fun restoreMapPreferences(candidate: Long): Boolean {
        var changed = false
        for (zone in visitedThisSession) if (CitySurface.visitViewport(candidate,homeCity,zone)) changed = true
        worldHomeSetting?.let {
            CitySurface.worldHome(candidate,if (it) 1 else 0)
            changed = true
        }
        return changed
    }

    private fun locateCity(id: String) {
        val ll = map.zones?.center(id) ?: return
        val xy = CitySurface.project(handle,ll)
        map.focusCity(xy[0],xy[1])
    }

    private fun allCities() {
        withCommittedWorld(pause = false) {
            val bytes = Engine.save(handle,clock)
            val manifest = map.overlay.manifest
            StudioAllCities.show(this,{ bytes },{ manifest?.invoke(bytes,false) },{ bounds ->
                if (!closed && handle != 0L) map.showBounds(CitySurface.project(handle,bounds))
            },::locateCity) { home ->
                if (!closed && handle != 0L) {
                    worldHomeSetting = home == "world"
                    CitySurface.worldHome(handle,if (home == "world") 1 else 0)
                    worldChanged = true
                    if (reference == null) writeSave()
                }
            }
        }
    }

    private fun followViewport(id: String?, zoom: Float) {
        if (closed || handle == 0L) return
        viewportLabel = when {
            zoom < 7 -> "Semua kota"
            id == null -> ""
            else -> cityStore.city(id).name
        }
        updateViewportSummary()
        map.outlineFocus = id
        wantedZone = id?.takeIf { zoom > 7.5f }
        loadViewedZone()
    }

    private fun loadViewedZone() {
        val id = wantedZone ?: return
        if (closed || loadingSurface || handle == 0L) return
        val installed = surfaces[id]
        if (installed != null) {
            if (id != focusId) {
                focusId = id
                localClockShift = StudioNewGame.localShift(id)
                map.cityLayer = installed
                map.invalidate()
            }
            if (id in detailedSurfaces) return
        }
        loadingSurface = true
        info.text = "Memuat peta ${cityStore.city(id).name}…"
        val candidate = CitySurface.fork(handle)
        viewWorker.execute {
            val result = runCatching { CitySurface.load(candidate,cityStore.load(id)) }
            runOnUiThread {
                try {
                    if (closed || isFinishing || handle == 0L) return@runOnUiThread
                    if (id == wantedZone) result.onSuccess { layer ->
                        surfaces[id] = layer
                        detailedSurfaces.add(id)
                        if (detailedSurfaces.size > 2) {
                            val oldest = detailedSurfaces.first()
                            detailedSurfaces.remove(oldest)
                            surfaces[oldest]?.let { surfaces[oldest] = it.overview() }
                        }
                        map.cityLayers = surfaces.values.toList()
                        map.cityLayer = layer
                        focusId = id
                        localClockShift = StudioNewGame.localShift(id)
                        map.invalidate()
                        updateViewportSummary()
                        if (installed == null) {
                            CitySurface.copy(candidate,handle)
                            val before = if (parked != null || warming != null) Engine.save(handle,clock) else null
                            if (!CitySurface.visitViewport(handle,homeCity,id)) return@onSuccess
                            visitedThisSession.add(id)
                            openedCities = openedCities + id
                            worldChanged = true
                            val after = Engine.save(handle,clock)
                            if (before != null) {
                                if (parkedBytes?.contentEquals(before) == true) parkedBytes = after
                                if (warmBytes?.contentEquals(before) == true) warmBytes = after
                            }
                            if (reference == null && !drawing) persistBytes(after)
                            map.overlay.zoneAdded()
                        }
                        android.util.Log.i("WorldViewport","Kota terbuka: $id (${surfaces.size} paket)")
                    }.onFailure {
                        Toast.makeText(this,"Peta ${cityStore.city(id).name} belum tersedia. Geser untuk mencoba lagi.",Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    Engine.close(candidate)
                    loadingSurface = false
                    if (!closed && !isFinishing && (id != wantedZone || id in detailedSurfaces)) loadViewedZone()
                }
            }
        }
    }

    private fun updateViewportSummary() {
        val day = ((referenceTime ?: clock) / 86400).toInt() + 1
        info.text = (if (viewportLabel.isEmpty()) "" else "$viewportLabel · ") + "Hari $day · ${map.routeCount} jalur · ${map.stationCount} stasiun"
    }

    private fun updateSummary(cash: Double = map.balance) {
        updateViewportSummary()
        showBalance(cash)
    }

    private fun writeSave(restoredBytes: ByteArray? = null): Boolean {
        if (handle == 0L || drawing || reference != null) return false
        if (restoredBytes == null && !worldChanged && local.baseFile.isFile) return true
        return persistBytes(restoredBytes ?: Engine.save(handle, clock))
    }

    private fun persistBytes(bytes: ByteArray): Boolean {
        return runCatching {
            check(local.baseFile.lastModified() == saveRevision)
            check(bytes.isNotEmpty())
            val stream = local.startWrite()
            try { stream.write(bytes); local.finishWrite(stream) }
            catch (e: Exception) { local.failWrite(stream); throw e }
            saveRevision = local.baseFile.lastModified()
            worldChanged = false
        }.onSuccess {
            if (hostLibrary) archiveSlot?.let { slot ->
                runCatching { StudioPendingSaves.markDirty(this, slot.id) }.onFailure {
                    Toast.makeText(this, "Tersimpan di perangkat. Pilih Simpan untuk mengirim ke Studio.", Toast.LENGTH_LONG).show()
                }
            }
        }.onFailure {
            Toast.makeText(this, "Dunia gagal disimpan di perangkat.", Toast.LENGTH_LONG).show()
        }.isSuccess
    }

    private fun leaveWorld() {
        if(removingTrack || selectingInfrastructure) {
            selectingInfrastructure=false; removingTrack=false
            map.contentDescription="Peta jaringan kereta"
            updateControls(); lastStatus=-1; refresh(); return
        }
        if (reference != null) { withCommittedWorld { leaveWorld() }; return }
        if (handle == 0L) { finish(); return }
        if (drawing && map.draft.isNotEmpty()) {
            StudioDialogBuilder(this).setTitle("Tinggalkan rancangan jalur?")
                .setView(StudioDecision.draft(this,map.draft,map.bends,plan.tool.label,plan.specification))
                .setMessage("Titik rancangan belum menjadi jalur. Dunia yang sudah dibangun akan disimpan di perangkat.")
                .setPositiveButton("Tinggalkan") { _, _ -> cancelDrawing(); if (writeSave()) finish() }
                .setNegativeButton("Lanjut menggambar", null).show()
        } else {
            if (drawing) cancelDrawing()
            if (writeSave()) finish()
        }
    }

    @Deprecated("Compatibility with API 24")
    override fun onBackPressed() = leaveWorld()

    @Deprecated("Retained for configuration changes on API 24 and above")
    override fun onRetainNonConfigurationInstance(): Any? =
        if (handle == 0L) null else RetainedEdits(local.baseFile.path, saveRevision, history)

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (handle == 0L || local.baseFile.lastModified() != saveRevision) return
        if (!drawing && worldChanged) writeSave()
        out.putDouble("speed", speedChip.multiple)
        out.putBoolean("drawing", drawing)
        plan.save(out)
        out.putIntArray("railSnaps",map.railSnaps.toIntArray())
        out.putDoubleArray("railPointProfile",pointProfile.toDoubleArray())
        out.putIntArray("linePlatforms",stationOrder.map { id -> map.stops.first { it.id==id }.packedId }.toIntArray())
        map.saveView(out)
    }
    override fun onResume() {
        super.onResume()
        foreground = true
        if (hostLibrary && archiveSlot != null && WorldDocuments.list(this).none { it.id == archiveSlot?.id }) {
            finish()
            return
        }
        if (handle != 0L && local.baseFile.lastModified() != saveRevision) {
            recreate()
            return
        }
        if (handle != 0L) {
            scheduleWarm(1500)
            map.overlay.startNeeded()
            val selected = StudioCurrency.current(this)
            if (currency != selected) {
                currency = selected
                showBalance(NativeFinance.balance(handle, clock))
            }
        }
        if (!legacyWorkshop && handle != 0L) getSharedPreferences("world-native",0).edit().putString("last-city",homeCity).apply()
    }
    // ---- Lensa peta: one-tap presets over the three overlay groups ----
    private enum class Lens(val label: String, val trips: String, val stations: String, val track: String, val swatch: IntArray, val legend: String) {
        NORMAL("Normal","none","none","line", intArrayOf(0xffe5484d.toInt(),0xff3e7bfa.toInt(),0xff2fb36d.toInt()), ""),
        HOMES("Rumah","home","none","line", intArrayOf(0xff317bb8.toInt(),0xff67ab55.toInt(),0xffd99154.toInt()), "Rumah warga yang bepergian · warna = cara mereka bepergian"),
        DESTINATIONS("Destinasi","work","none","line", intArrayOf(0xff317bb8.toInt(),0xff67ab55.toInt(),0xffd99154.toInt()), "Tempat yang dituju warga · warna = cara mereka bepergian"),
        TRIPS("Total perjalanan","both","none","line", intArrayOf(0xffd8cffd.toInt(),0xffff7a2f.toInt()), "Rumah dan destinasi perjalanan warga"),
        PASSENGERS("Penumpang","none","total","line", intArrayOf(0xfffde3e3.toInt(),0xffe5484d.toInt()), "Lingkaran makin besar = makin banyak penumpang per hari"),
        ORIGINS("Asal penumpang","none","origins","line", intArrayOf(0xffdbe6ff.toInt(),0xff2456e0.toInt()), "Penumpang yang memulai perjalanan di tiap stasiun"),
        TRANSFERS("Transit","none","transfers","line", intArrayOf(0xffebe5ff.toInt(),0xff6c4df6.toInt()), "Penumpang yang pindah layanan di tiap stasiun"),
        TRAFFIC("Lalu lintas","none","none","traffic", intArrayOf(0xffe5484d.toInt(),0xff3e7bfa.toInt(),0xff2fb36d.toInt()), "Warna = layanan, garis makin tebal = makin ramai"),
        PROFIT("Laba","none","none","profit", intArrayOf(0xffb2182b.toInt(),0xffe9853b.toInt(),0xfff1d54a.toInt(),0xff96d58f.toInt(),0xff2166ac.toInt()), "Rugi → impas → untung"),
        SPEED("Kecepatan","none","none","speed", intArrayOf(0xff8a2232.toInt(),0xffe2922e.toInt(),0xffa3c23c.toInt(),0xff397bc1.toInt(),0xffa03fc2.toInt()), "Lambat → cepat"),
        HEIGHT("Ketinggian","none","none","height", intArrayOf(0xffc2351c.toInt(),0xfff0b928.toInt(),0xff58b04a.toInt(),0xff2f6fd0.toInt(),0xff4a2c9a.toInt()), "Bawah tanah → permukaan → layang")
    }

    private fun currentLens(): Lens? = map.overlay.selection.let { sel ->
        Lens.values().firstOrNull { it.trips == sel[0] && it.stations == sel[1] && it.track == sel[2] }
    }

    private fun applyLens(lens: Lens) {
        val sel = map.overlay.selection
        if (sel[0] != lens.trips) map.overlay.choose(0, lens.trips)
        if (sel[1] != lens.stations) map.overlay.choose(1, lens.stations)
        if (sel[2] != lens.track) map.overlay.choose(2, lens.track)
        updateLensLegend()
        map.announceForAccessibility("Lensa ${lens.label}")
    }

    private fun swatch(colors: IntArray, radius: Int) = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, if (colors.size == 1) intArrayOf(colors[0], colors[0]) else colors
    ).apply { cornerRadius = dp(radius).toFloat() }

    private fun updateLensLegend() {
        if (!::lensLegend.isInitialized || !::map.isInitialized) return
        val lens = currentLens()
        lensLegend.removeAllViews()
        if (lens == Lens.NORMAL || drawing) { lensLegend.visibility = View.GONE; return }
        lensLegend.visibility = View.VISIBLE
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val filtered = map.overlay.selection[0] != "none" || map.overlay.track == "traffic"
        val purpose = if (filtered && map.overlay.purpose != "all") " · " + map.overlay.purposeName() else ""
        text.addView(StudioUi.text(this, (lens?.label ?: "Lensa campuran") + purpose, 15f, color = StudioUi.INK).apply { typeface = StudioUi.display(context) })
        if (lens != null) {
            text.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, 0)
                addView(View(context).apply { background = swatch(lens.swatch, 5) }, LinearLayout.LayoutParams(dp(64), dp(9)).apply { marginEnd = dp(8) })
                addView(StudioUi.text(context, lens.legend, 11.5f, true, StudioUi.MUTED).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        } else text.addView(StudioUi.text(this, "Diatur sendiri lewat Lensa peta lanjutan", 11.5f, true, StudioUi.MUTED))
        lensLegend.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        lensLegend.addView(StudioDepot.symbol(this, StudioUi.Icon.CLOSE, 18f, StudioUi.INK).apply {
            gravity = Gravity.CENTER
            background = StudioDepot.touch(context, 0x1422204a, 18)
            contentDescription = "Kembali ke peta normal"
            isClickable = true
            setOnClickListener { applyLens(Lens.NORMAL) }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
    }

    /** The web's purpose filter (`PurposeSelect`): every purpose or one, for the trip bubbles and the
     * Traffic widths (station circles stay every purpose's, as on the web). */
    private fun purposeChips(done: () -> Unit) = WrapRow(this).apply {
        StudioPassengerOverlay.PURPOSES.forEach { (key, label, colour) ->
            val selected = map.overlay.purpose == key
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = StudioDepot.touch(context, if (selected) StudioUi.INK else 0xf2ffffff.toInt(), 16)
                setPadding(dp(10), dp(7), dp(12), dp(8))
                isClickable = true
                contentDescription = "Tujuan perjalanan · $label"
                if (key != "all") addView(View(context).apply { background = swatch(intArrayOf(colour), 5) }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(6) })
                addView(StudioUi.text(context, label, 13f, true, if (selected) StudioUi.WHITE else StudioUi.INK))
                setOnClickListener {
                    done()
                    map.overlay.choosePurpose(key)
                    updateLensLegend()
                    map.announceForAccessibility("Tujuan perjalanan · $label")
                }
            })
        }
    }

    private fun openLenses() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.glass(context, 28)
            elevation = dp(8).toFloat()
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        // scrolls when the tiles and the purpose row are taller than the space above the dock (landscape)
        val scroller = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }
        val popup = android.widget.PopupWindow(scroller, finance.rootView.width - 2 * dp(10), -2, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        }
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(10))
            addView(StudioUi.text(context, "Lensa peta", 17f, color = StudioUi.INK).apply { typeface = StudioUi.display(context) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(StudioUi.text(context, "ketuk untuk ganti", 12f, true, StudioUi.MUTED))
        })
        val active = currentLens()
        val tiles = Lens.values().map { lens -> Triple(lens.label, lens.swatch, { applyLens(lens) }) } +
            Triple("Atur sendiri", intArrayOf(0xffeeeeee.toInt(), 0xffffffff.toInt(), 0xffeeeeee.toInt()), { passengerLayers() })
        tiles.chunked(3).forEach { row ->
            val line = LinearLayout(this)
            row.forEachIndexed { i, (label, colors, pick) ->
                val selected = label == active?.label || (active == null && label == "Atur sendiri")
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = StudioDepot.touch(context, if (selected) StudioUi.INK else 0xf2ffffff.toInt(), 18)
                    setPadding(dp(7), dp(7), dp(7), dp(8))
                    isClickable = true
                    contentDescription = "Lensa $label"
                    setOnClickListener { popup.dismiss(); pick() }
                    addView(View(context).apply { background = swatch(colors, 12) }, LinearLayout.LayoutParams(-1, dp(38)))
                    addView(StudioUi.text(context, label, 12.5f, color = if (selected) StudioUi.WHITE else StudioUi.INK).apply {
                        typeface = StudioUi.heavy(context); gravity = Gravity.CENTER; maxLines = 2
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                }
                line.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(7) })
            }
            content.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        content.addView(StudioUi.text(this, "Tujuan perjalanan di peta", 12.5f, true, StudioUi.MUTED).apply { setPadding(dp(4), dp(4), dp(4), dp(6)) })
        content.addView(purposeChips { popup.dismiss() })
        val bar = finance.parent as View
        val location = IntArray(2).also { bar.getLocationInWindow(it) }
        val room = location[1] - dp(40)
        content.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        if (room > dp(120) && content.measuredHeight > room) popup.height = room
        popup.showAtLocation(bar, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, bar.rootView.height - location[1] + dp(8))
    }

    // ---- Passenger forecast computed in the background as soon as the world is idle ----
    private var warming: SimulationClock? = null
    private var warmBytes: ByteArray? = null
    private var warmStart = 0L
    private var playWhenWarm = false
    private var warmReadyUntil = 0L
    private var prepStart = 0L
    private fun computing() = warming != null || (trainPreview != null && parked == null && reference == null)
    private var playDot: Boolean? = null
    private var warmAt = 0L
    private val warmTask = Runnable { warmAt = 0L; prewarm() }
    private val warmTick = object : Runnable {
        override fun run() {
            updateWarmUi()
            if (playWhenWarm) updateControls()
            if (computing() || SystemClock.uptimeMillis() < warmReadyUntil) map.postDelayed(this, 250)
        }
    }
    private val tips = listOf(
        "Stasiun tempat dua lintas bertemu jadi titik transit. Penumpangnya bisa 2–3× stasiun biasa.",
        "Kereta yang terlalu penuh membuat penumpang beralih. Tambah rangkaian di lintas yang padat.",
        "Lensa Laba menunjukkan lintas yang rugi. Coba ubah tarif atau jadwalnya.",
        "Jalur yang melewati pusat kota biasanya paling ramai di jam sibuk pagi.")

    private fun warmEstimate() = getSharedPreferences("world-native", 0).getLong("warm-ms-${city.id}", 18_000L).coerceIn(2_000L, 120_000L)
    private fun warmFraction() = if (!computing()) 1f else
        (0.95f * (SystemClock.elapsedRealtime() - (if (prepStart != 0L) prepStart else warmStart)) / warmEstimate()).coerceIn(0f, .95f)

    private fun scheduleWarm(delay: Long = 2500) {
        if (!::map.isInitialized || closed) return
        // Frequent callers (refresh) must not keep pushing an already pending check further away.
        val at = SystemClock.uptimeMillis() + delay
        if (warmAt != 0L && warmAt <= at) return
        map.removeCallbacks(warmTask)
        warmAt = at
        map.postDelayed(warmTask, delay)
    }

    private fun prewarm() {
        if (warming != null || reference != null || closed || !foreground || drawing || handle == 0L || restoringEdit || running) return
        if (!map.requiresStudioClock || map.routeCount == 0) return
        if (trainPreview != null) { scheduleWarm(1500); return }
        if (worldChanged && !writeSave()) return
        val bytes = Engine.save(handle, clock)
        parked?.let { idle -> if (idle.isReady && parkedBytes?.contentEquals(bytes) == true) return; dropParked() }
        beginWarm(bytes)
    }

    private fun beginWarm(bytes: ByteArray) {
        lateinit var runtime: SimulationClock
        val onClock: (Double) -> Unit = { time ->
            if (reference === runtime && referenceState == ReferenceState.PLAYING && !closed) {
                referenceTime = time
                refresh()
            }
        }
        val onFailure: (String) -> Unit = { reason ->
            when {
                warming === runtime -> { val wanted = playWhenWarm; cancelWarm(); updateControls(); if (wanted) referenceUnavailable(reason) }
                parked === runtime -> dropParked()
                reference === runtime && !checkpointInFlight -> { finishReference(runtime); referenceUnavailable(reason) }
            }
        }
        val pack = activePack
        val cityId = city.id
        val inputs: ((ByteArray) -> String)? = if (SimulationEngine.supportsEditor(handle)) null else { archive ->
            val local = pack ?: cityStore.load(cityId)
            NativeRidership.inputs(this,archive,local.header,local.cells,true).toString()
        }
        runtime = NativeSimulationClock(inputs,onClock,onFailure)
        runtime.onTrainLoads = { epoch, rows -> if (reference === runtime && !closed) map.trainLoads(epoch,rows) }
        runtime.onCash = { cash -> if (reference === runtime && !closed) showBalance(cash) }
        warming = runtime
        warmBytes = bytes
        warmStart = SystemClock.elapsedRealtime()
        if (prepStart == 0L) prepStart = warmStart
        map.removeCallbacks(warmTick)
        map.post(warmTick)
        updateWarmUi()
        runtime.open(bytes) {
            if (warming !== runtime || closed) return@open
            val took = SystemClock.elapsedRealtime() - (if (prepStart != 0L) prepStart else warmStart)
            prepStart = 0L
            getSharedPreferences("world-native", 0).edit().putLong("warm-ms-${city.id}", took).apply()
            android.util.Log.i("WorldSimulation", "forecast ready in $took ms")
            warming = null
            parked = runtime
            parkedBytes = warmBytes
            warmBytes = null
            if (playWhenWarm && foreground) {
                playWhenWarm = false
                startReference()
            } else {
                warmReadyUntil = SystemClock.uptimeMillis() + 4000
                map.removeCallbacks(warmTick)
                map.post(warmTick)
                scheduleWarm()
            }
            updateWarmUi()
            updateControls()
        }
    }

    private fun cancelWarm() {
        if (::map.isInitialized) { map.removeCallbacks(warmTask); map.removeCallbacks(warmTick) }
        warmAt = 0L
        val runtime = warming
        warming = null
        warmBytes = null
        playWhenWarm = false
        runtime?.close()
        if (::warmCard.isInitialized) updateWarmUi()
    }

    private fun updateWarmUi() {
        if (!::warmCard.isInitialized) return
        val fraction = warmFraction()
        val left = ((1f - fraction / .95f) * warmEstimate() / 1000f).toInt().coerceAtLeast(1)
        val computing = computing()
        warmCard.visibility = if (computing && playWhenWarm) View.VISIBLE else View.GONE
        if (computing && playWhenWarm) {
            warmBar.fraction = fraction
            warmPercent.text = "${(fraction * 100).toInt()}% · ±$left dtk"
            if (warmTip.text.isEmpty()) warmTip.text = "💡  " + tips.random()
            warmTip.visibility = if (left > 5) View.VISIBLE else View.GONE
        } else warmTip.text = ""
        val ready = !computing && parked != null && SystemClock.uptimeMillis() < warmReadyUntil && reference == null
        warmChip.visibility = if ((computing && !playWhenWarm) || ready) View.VISIBLE else View.GONE
        if (computing) {
            warmChip.background = StudioUi.glass(this, 18)
            warmChipRing.fraction = fraction; warmChipRing.done = false
            warmChipText.setTextColor(StudioUi.INK)
            warmChipText.text = "Menyiapkan penumpang · ${(fraction * 100).toInt()}%"
        } else if (ready) {
            warmChip.background = StudioUi.pill(this, StudioUi.INK, 18)
            warmChipRing.done = true
            warmChipText.setTextColor(StudioUi.WHITE)
            warmChipText.text = "Penumpang siap · tekan Putar untuk main"
        }
    }

    private fun writeCover() {
        val slot = archiveSlot ?: return
        if (!::map.isInitialized || handle == 0L) return
        val image = runCatching { map.cover() }.getOrNull()
        val lines = map.routeCount
        val stations = map.stationCount
        val cash = if (lastCash.isFinite()) lastCash else map.balance
        val time = referenceTime ?: clock
        val day = (time / 86400).toInt() + 1
        val second = Math.floorMod(time.toLong() + localClockShift, 86400L).toInt()
        // Written before the library resumes so it shows the fresh cover (a small JPEG, a few ms).
        WorldCovers.write(this, slot.id, image, lines, stations, cash, day, second)
        image?.recycle()
    }

    override fun onPause() {
        writeCover()
        super.onPause()
        foreground = false
        cancelWarm()
        if (::map.isInitialized) map.overlay.stop()
        stopTrainPreview()
        dropParked()
        if (reference != null) withCommittedWorld()
        if (::play.isInitialized) {
            stopClock()
            if (reference == null && hostLibrary && !drawing && worldChanged) writeSave()
        }
    }
    override fun onDestroy() {
        closed = true
        viewWorker.shutdown()
        draftVersion++
        draftWorker.shutdown()
        cancelWarm()
        if (::map.isInitialized) map.overlay.stop()
        reviewDialog?.dismiss()
        stopTrainPreview()
        dropParked()
        if (::map.isInitialized) map.removeCallbacks(tick)
        if (!checkpointInFlight) {
            reference?.close()
            executor.shutdownNow()
        }
        if(handle!=0L) {Engine.close(handle); handle=0}
        super.onDestroy()
    }
}

private const val STATIC_MARGIN = .2f

internal class NetworkView(context: Context):View(context) {
    var displaySettings = StudioGameSettings.read(context)
        set(value) { field=value; invalidate() }
    val overlay = StudioPassengerOverlay(context as? Activity){ invalidate() }
    private var baseLayer: StudioBaseLayer? = null
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if(baseLayer==null)baseLayer=StudioBaseLayer(context){invalidate()} }
    override fun onDetachedFromWindow() { cameraRevision++; removeCallbacks(settled); boundaryWorker.queue.clear(); overlay.stop(); baseLayer?.close();baseLayer=null;super.onDetachedFromWindow() }
    var outlineFocus: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    var zones: ViewportZones? = null
    var onCameraMoving: (() -> Unit)? = null
    var onViewSettled: ((String?,Float) -> Unit)? = null
    private val boundaryWorker = java.util.concurrent.ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>()).apply { allowCoreThreadTimeOut(true) }
    private var cameraRevision = 0
    private val settled = Runnable {
        val h = trainEngine()
        val registry = zones
        if (h != 0L && registry != null && isAttachedToWindow) {
            val ll = CitySurface.lonLat(h,centerX,centerY)
            val zoom = stationZoom()
            val revision = cameraRevision
            val coarse = registry.place(ll[0],ll[1])
            onViewSettled?.invoke(coarse,zoom)
            if (zoom > 7.5f) {
                boundaryWorker.queue.clear()
                boundaryWorker.execute {
                    val exact = registry.place(ll[0],ll[1],true)
                    post {
                        if (isAttachedToWindow && cameraRevision == revision) {
                            if (exact != coarse) onViewSettled?.invoke(exact,zoom)
                            invalidate()
                        }
                    }
                }
            }
        }
    }
    fun cameraMoved() {
        cameraRevision++
        onCameraMoving?.invoke()
        removeCallbacks(settled)
        if (onViewSettled != null) postDelayed(settled,200)
    }
    private fun scaleAtZoom(zoom: Double) =
        (density * trainPainter.mercatorPerFrame * 512.0 * Math.pow(2.0,zoom)/40075016.7).toFloat()
    fun locateWorld() {
        val built = stops.filter { it.id >= 0 }
        val points = if (built.isEmpty()) plannedStops.map { it.second } else built.map { PointF(it.x,it.y) }
        if (points.isEmpty()) {
            val xy = CitySurface.project(trainEngine(),doubleArrayOf(20.0,30.0))
            scale = scaleAtZoom(1.8)
            focusAt(xy[0],xy[1])
        } else showBounds(floatArrayOf(points.minOf { it.x },points.minOf { it.y },points.maxOf { it.x },points.maxOf { it.y }))
    }
    fun showBounds(xy: FloatArray) {
        if (xy.size != 4) return
        scale = min(scaleAtZoom(6.75), .72f * min(width/max(1500f,abs(xy[2]-xy[0])),height/max(1500f,abs(xy[3]-xy[1]))))
        focusAt((xy[0]+xy[2])/2,(xy[1]+xy[3])/2)
    }
    var cityLayers: List<CitySurface.Layer> = emptyList()
    var cityLayer: CitySurface.Layer? = null
    private var cityCenter: PointF? = null
    var workshop = false
    var requiresStudioClock = true
        private set
    var statusBottomInset = 0
        set(value) { if (field != value) { field = value; invalidate() } }
    var doubleTapZoom = false
    var isDrafting = false
        set(value) {
            if (field == value) return
            field = value
            contentDescription = if (value) "Peta konstruksi. Ketuk lokasi untuk alat yang dipilih." else "Peta jaringan kereta"
            invalidate()
        }
    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Peta jaringan kereta"
    }
    var onLocation: ((PointF) -> Unit)? = null
    var onBend: ((PointF) -> Unit)? = null
    val bends = mutableListOf<Pair<Int,PointF>>()
    var onStationTap: ((Int) -> Boolean)? = null
    /** Station tool armed: rings 1 and 2 km round the chosen spot, as the web's T-312 `stationRings`. */
    var placingStation = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var lastTrackEnd:PointF?=null
        private set
    fun railPreviewLabels(result:JSONObject) {
        val labels=mutableListOf<Pair<PointF,String>>()
        fun mark(row:org.json.JSONArray,label:String) {
            labels.add(PointF(row.getDouble(0).toFloat(),row.getDouble(1).toFloat()) to label)
        }
        result.optJSONArray("marks")?.let { xs ->
            for(i in 0 until xs.length() step 4) {
                val p=PointF(xs.getDouble(i).toFloat(),xs.getDouble(i+1).toFloat())
                labels.add(p to if(xs.getInt(i+2)==0) "X" else "Y")
            }
        }
        result.optJSONArray("tags")?.let { tags -> for(i in 0 until tags.length()) {
            val v=tags.getJSONArray(i)
            mark(v,"R ${v.getDouble(2).toInt()} m · ${v.getDouble(3).toInt()} km/jam")
        } }
        result.optJSONArray("tips")?.let { tips -> for(i in 0 until tips.length()) {
            val v=tips.getJSONArray(i)
            when(v.getInt(2)) { 1 -> mark(v,"Sambung"); 2 -> mark(v,"Wesel") }
        } }
        result.optJSONArray("ramp")?.let { v ->
            mark(v,String.format(java.util.Locale.forLanguageTag("id-ID"),"%+.1f%%",v.getDouble(2)))
        }
        junctionMarks=labels
        refused=!result.isNull("issue")
        curveCircle=result.optJSONArray("arc")?.takeIf { it.length()>=3 && it.getDouble(2)>0 }?.let { v ->
            floatArrayOf(v.getDouble(0).toFloat(),v.getDouble(1).toFloat())
        }
    }
    var railTool:Int?=null
    val railSnaps=mutableListOf<Int>()
    var rawRailTip:PointF?=null
    var onRailDrag:((PointF)->Unit)?=null
    private var holdingRail=false
    private var draggedRail=false
    private var railDown=PointF()
    private val looseEnds=mutableListOf<Pair<PointF,Int>>()
    fun metresPerDp()=(density/scale).toDouble()
    var curveCircle:FloatArray?=null
    var refused=false
    var junctionMarks:List<Pair<PointF,String>> = emptyList()
    var draftShape:List<PointF>?=null
    val draft = mutableListOf<PointF>()
    val lines = mutableListOf<Pair<Int,Path>>()
    private data class RailStyle(val level: Int, val kind: Int, val tracks: Int)
    data class RailInfo(val low:Int,val high:Int,val kind:Int,val tracks:Int,val length:Double,val speed:Double)
    private data class RailPiece(val id: Int, val points: FloatArray, val info:RailInfo) {
        val path by lazy {
            Path().apply {
                if (points.size >= 2) moveTo(points[0],points[1])
                for (j in 2 until points.size-1 step 2) lineTo(points[j],points[j+1])
            }
        }
    }
    private val pieces = mutableListOf<RailPiece>()
    fun railInfo(id:Int): RailInfo? = pieces.firstOrNull { it.id==id }?.info
    fun nearestRail(point: PointF): Int? {
        var best: Int? = null
        var limit = pickRadiusMeters()*pickRadiusMeters()
        for ((id,coords) in pieces) {
            for (i in 2 until coords.size step 2) {
                val x = coords[i-2].toDouble()
                val y = coords[i-1].toDouble()
                val dx = coords[i]-x
                val dy = coords[i+1]-y
                val length2 = dx*dx+dy*dy
                if (length2 == 0.0) continue
                val u = (((point.x-x)*dx+(point.y-y)*dy)/length2).coerceIn(0.0,1.0)
                val ex = point.x-(x+u*dx)
                val ey = point.y-(y+u*dy)
                val distance2 = ex*ex+ey*ey
                if (distance2 <= limit) { limit = distance2; best = id }
            }
        }
        return best
    }
    private val blueprintPath=Path()
    private val plannedStops=mutableListOf<Pair<String,PointF>>()
    val plannedStationNames get()=plannedStops.map { it.first }
    var hasPlans=false
        private set
    private val infrastructure = mutableMapOf<RailStyle,Path>()
    private var railBounds: RectF? = null
    fun pickRadiusMeters(): Double = (28.0 * density / scale).coerceIn(6.0,250.0)

    data class Station(val x:Float,val y:Float,val name:String,val id:Int,val platform:StudioStationGlyph?,val packedId:Int)
    private fun stationZoom() = kotlin.math.log2(scale / density * 40075016.7f / (512f * trainPainter.mercatorPerFrame))
    val stops=mutableListOf<Station>()
    var onTrainPreviewNeeded: (() -> Unit)? = null
    var trainNetworkVersion = 0
        private set
    private var previewRequested = false
    private val trainPainter = StudioTrainPainter(resources.displayMetrics.density)
    var trainEngine: () -> Long
        get() = trainPainter.engine
        set(value) { trainPainter.engine = value }
    fun trainLoads(epoch:Long,rows:org.json.JSONArray) { trainPainter.setLoads(epoch,rows); redraw() }
    /** Content changed: the cached static layer is stale. Trains and the camera redraw through [redraw]. */
    private var contentVersion = 0
    override fun invalidate() { contentVersion++; super.invalidate() }
    private fun redraw() = super.invalidate()
    private val redrawTask = Runnable { redraw() }
    private var staticBitmap: android.graphics.Bitmap? = null
    private var staticVersion = -1
    private var staticCx = 0f; private var staticCy = 0f; private var staticScale = 0f
    private var staleSince = 0L
    private val staticMatrix = android.graphics.Matrix()
    private val staticPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val renderStaticTask = Runnable { renderStaticLayer(); redraw() }
    private fun staticSize() = Pair(width + 2*(width*STATIC_MARGIN).toInt(), height + 2*(height*STATIC_MARGIN).toInt())
    private fun renderStaticLayer() {
        if (width <= 0 || height <= 0) return
        val (bw,bh) = staticSize()
        var bmp = staticBitmap
        if (bmp == null || bmp.width != bw || bmp.height != bh) {
            bmp?.recycle()
            bmp = android.graphics.Bitmap.createBitmap(bw,bh,android.graphics.Bitmap.Config.ARGB_8888)
            staticBitmap = bmp
        }
        val version = contentVersion
        vw = bw; vh = bh
        try { drawStatic(Canvas(bmp)) } finally { vw = width; vh = height }
        staticVersion = version
        staticCx = centerX; staticCy = centerY; staticScale = scale
        staleSince = 0L
    }
    /** Visible part of the cached static map, scaled down for the world library cover. */
    fun cover(maxWidth: Int = 720): android.graphics.Bitmap? {
        if (width <= 0 || height <= 0) return null
        if (staticBitmap == null || staticVersion != contentVersion || staticCx != centerX || staticCy != centerY || staticScale != scale) renderStaticLayer()
        val bmp = staticBitmap ?: return null
        val mx = ((bmp.width - width) / 2).coerceAtLeast(0)
        val my = ((bmp.height - height) / 2).coerceAtLeast(0)
        val k = minOf(1f, maxWidth.toFloat() / width)
        val out = android.graphics.Bitmap.createBitmap((width * k).toInt().coerceAtLeast(1), (height * k).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, android.graphics.Rect(mx, my, mx + width, my + height),
            android.graphics.Rect(0, 0, out.width, out.height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
    val trainCount get() = trainPainter.count
    private var trainFrom = Double.NaN
    private var trainTo = Double.NaN
    private var trainAt = 0L
    private var trainWindow = 0L
    private fun trainTime(now:Long) = if (trainWindow > 0L)
        trainFrom + (trainTo-trainFrom)*((now-trainAt).toDouble()/trainWindow).coerceIn(0.0,1.0) else trainTo
    fun updateTrains(time:Double,interpolate:Boolean = false):Int {
        val now = SystemClock.elapsedRealtime()
        if (!interpolate || time != trainTo) {
            val previous = trainTime(now)
            trainWindow = if (interpolate && previous.isFinite() && time >= previous && time-previous <= 16.0)
                (now-trainAt).coerceIn(0L,650L) else 0L
            trainFrom = previous
            trainTo = time
            trainAt = now
        }
        trainPainter.time = trainTime(now)
        trainPainter.update(centerX,centerY,scale,width,height)
        redraw()
        return trainCount
    }
    val stationCount get()=stops.size
    val routeCount get()=lines.size
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val draftDash = android.graphics.DashPathEffect(floatArrayOf(7*density, 4*density), 0f)
    private val water=context.assets.open("world-native/nyc-water.png").use { BitmapFactory.decodeStream(it) }
    private val waterBox=FloatArray(4)

    private var centerX=0f; private var centerY=0f
    private var scale=0.01f; private var homeScale=0.01f
    // Static map content renders into [staticBitmap] at a virtual viewport of vw×vh pixels.
    private var vw=0; private var vh=0
    private fun sx(x:Float)=(x-centerX)*scale+vw/2f
    private fun sy(y:Float)=(centerY-y)*scale+vh/2f
    var balance = Double.NaN
        private set

    fun load(data:JSONObject, refit:Boolean = true,keepTrainLoads:Boolean = false) {
        overlay.reset()
        trainNetworkVersion++
        if (!keepTrainLoads) previewRequested=false
        trainPainter.reset(keepTrainLoads)
        trainPainter.mercatorPerFrame = data.optDouble("mercatorPerFrame",1.0).toFloat()
        balance = data.optDouble("cash")
        requiresStudioClock = data.optBoolean("requiresStudioClock", true)
        lines.clear()
        stops.clear()
        cityCenter = data.optJSONArray("homePoint")?.takeIf { it.length() == 2 }?.let {
            PointF(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())
        }
        val bounds=data.getJSONArray("waterBox")
        for(i in 0..3) waterBox[i]=cityLayer?.bounds?.get(i) ?: bounds.getDouble(i).toFloat()
        blueprintPath.reset()
        looseEnds.clear()
        data.optJSONArray("looseEnds")?.let { ends -> for(i in 0 until ends.length()) {
            val row=ends.getJSONArray(i)
            looseEnds.add(PointF(row.getDouble(0).toFloat(),row.getDouble(1).toFloat()) to row.getInt(2))
        } }
        lastTrackEnd=data.optJSONArray("lastTrackEnd")?.let { PointF(it.getDouble(0).toFloat(),it.getDouble(1).toFloat()) }
        plannedStops.clear()
        val planned=data.optJSONArray("plannedStations")
        for(i in 0 until (planned?.length() ?: 0)) {
            val row=planned!!.getJSONArray(i)
            plannedStops.add(row.getString(2) to PointF(row.getDouble(0).toFloat(),row.getDouble(1).toFloat()))
        }
        hasPlans=plannedStops.isNotEmpty()
        infrastructure.clear()
        pieces.clear()
        railBounds = null
        val tracks = data.optJSONArray("tracks")
        for (index in 0 until (tracks?.length() ?: 0)) {
            val track = tracks!!.getJSONObject(index)
            val points = track.getJSONArray("points")
            val style = RailStyle(track.getInt("level"),track.getInt("kind"),track.getInt("tracks"))
            val built=track.optBoolean("built",true)
            val path = if(built) infrastructure.getOrPut(style) { Path() } else blueprintPath
            if(!built) hasPlans=true
            val coords = FloatArray(points.length()) { points.getDouble(it).toFloat() }
            val length = if(track.has("length")) track.getDouble("length") else
                (2 until coords.size step 2).sumOf { j -> kotlin.math.hypot((coords[j]-coords[j-2]).toDouble(),(coords[j+1]-coords[j-1]).toDouble()) }
            if(built) pieces.add(RailPiece(track.getInt("id"),coords,RailInfo(style.level,track.optInt("topLevel",style.level),style.kind,style.tracks,length,track.optDouble("speed"))))
            for (j in 0 until coords.size-1 step 2) {
                val x = coords[j]
                val y = coords[j+1]
                if (j == 0) path.moveTo(x,y) else path.lineTo(x,y)
                val bounds = railBounds
                if (bounds == null) railBounds = RectF(x,y,x,y) else bounds.union(x,y)
            }
        }
        val routes=data.getJSONArray("routes")
        for(i in 0 until routes.length()) {
            val row=routes.getJSONObject(i);val points=row.getJSONArray("points")
            val path=Path()
            for(j in 0 until points.length() step 2) {
                val x=points.getDouble(j).toFloat();val y=points.getDouble(j+1).toFloat()
                if(j==0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            val color = row.getInt("color") or Color.BLACK
            lines.add(color to path)
            if (overlay.snapshot != null) overlay.route(row.getInt("id"),color,path)
        }
        val stations=data.getJSONArray("stations")
        for(i in 0 until stations.length()) {
            val s=stations.getJSONArray(i)
            stops.add(Station(s.getDouble(0).toFloat(),s.getDouble(1).toFloat(),s.getString(2),s.getInt(3),StudioStationGlyph.read(s.optJSONObject(4)),s.optInt(5,s.getInt(3))))
        }
        if(refit)fit(); overlay.startNeeded(); invalidate()
    }
    private var inspectingStation: Station? = null
    fun showStationAboveSheet(id:Int) {
        val station=stops.firstOrNull { it.id == id } ?: return
        inspectingStation=station
        val x=station.x+(station.platform?.midX ?: 0f)
        val y=station.y+(station.platform?.midY ?: 0f)
        // Leave the kilometre ring between the map controls and the station sheet.
        val radiusPx = min(width*.36f,height*.12f)
        scale = radiusPx / (1000f * kotlin.math.cosh(y / 6371008.8f))
        focusAt(x,y-height*.15f/scale)
    }

    fun finishStationInspection() {
        val station=inspectingStation ?: return
        inspectingStation=null
        focusAt(station.x+(station.platform?.midX ?: 0f),station.y+(station.platform?.midY ?: 0f))
    }

    fun centerPoint() = PointF(centerX, centerY)
    fun focusCity(x: Float, y: Float) {
        homeScale = width.coerceAtLeast(1) / 12000f
        scale = homeScale
        focusAt(x,y)
    }
    fun focusAt(x: Float, y: Float) {
        centerX = x
        centerY = y
        cameraMoved()
        invalidate()
    }
    private var zoomAnimation: android.animation.ValueAnimator? = null
    private var zoomTarget = 0f
    fun zoomStep(closer: Boolean, x: Float = width/2f, y: Float = height/2f) {
        val start = scale
        val base = if (zoomAnimation?.isRunning == true) zoomTarget else start
        zoomAnimation?.cancel()
        zoomTarget = (base * if (closer) 2f else .5f).coerceIn(scaleAtZoom(1.0), max(homeScale * 35f,6.7f * density * trainPainter.mercatorPerFrame))
        val ratio = (zoomTarget/start).toDouble()
        zoomAnimation = android.animation.ValueAnimator.ofFloat(0f,1f).apply {
            duration = 300
            addUpdateListener { zoom(start * Math.pow(ratio,(it.animatedValue as Float).toDouble()).toFloat()/scale,x,y) }
            start()
        }
    }
    fun zoom(factor: Float, pivotX: Float = width / 2f, pivotY: Float = height / 2f) {
        val old = scale
        scale = (scale * factor).coerceIn(scaleAtZoom(1.0), max(homeScale * 35f,6.7f * density * trainPainter.mercatorPerFrame))
        centerX += (pivotX - width / 2f) * (1f/old - 1f/scale)
        cameraMoved()
        centerY -= (pivotY - height / 2f) * (1f/old - 1f/scale)
        invalidate()
    }
    fun resetCamera() { fit(); cameraMoved(); invalidate() }
    fun saveView(out: Bundle) {
        out.putString("mapPurpose",overlay.purpose)
        out.putStringArray("mapLayers",overlay.selection.copyOf())
        out.putFloatArray("camera", floatArrayOf(centerX, centerY, scale / homeScale))
        out.putFloatArray("stops", draft.flatMap { listOf(it.x, it.y) }.toFloatArray())
        out.putFloatArray("bends", bends.flatMap { (leg, p) -> listOf(leg.toFloat(), p.x, p.y) }.toFloatArray())
    }
    fun restoreView(state: Bundle) {
        state.getFloatArray("camera")?.let {
            centerX = it[0]
            centerY = it[1]
            scale = homeScale * it[2]
        }
        state.getFloatArray("stops")?.let { for (i in it.indices step 2) draft.add(PointF(it[i], it[i+1])) }
        state.getFloatArray("bends")?.let { for (i in it.indices step 3) bends.add(it[i].toInt() to PointF(it[i+1], it[i+2])) }
        invalidate()
    }
    private fun fit() {
        if (width == 0 || height == 0) return
        if (stops.isEmpty()) {
            railBounds?.let { box ->
                centerX = box.centerX()
                centerY = box.centerY()
                homeScale = .7f * min(width/max(600f,box.width()),height/max(600f,box.height()))
                scale = homeScale
                return
            }
            centerX = cityCenter?.x ?: cityLayer?.bounds?.let { (it[0] + it[2]) / 2f } ?: 0f
            centerY = cityCenter?.y ?: cityLayer?.bounds?.let { (it[1] + it[3]) / 2f } ?: 0f
            homeScale = width / 9000f
            scale = homeScale
            return
        }
        val xs=stops.map{it.x}.sorted();val ys=stops.map{it.y}.sorted()
        val lo=stops.size/8; val hi=stops.size-1-lo
        centerX=(xs[lo]+xs[hi])/2;centerY=(ys[lo]+ys[hi])/2
        homeScale=min(width/max(600f,xs[hi]-xs[lo]),height/max(600f,ys[hi]-ys[lo]))*.7f
        scale=homeScale
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 || oldh == 0) fit()
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        vw = width; vh = height
        val (bw,bh) = staticSize()
        val cached = staticBitmap
        if (cached == null || cached.width != bw || cached.height != bh) renderStaticLayer()
        else if (staticVersion != contentVersion || staticCx != centerX || staticCy != centerY || staticScale != scale) {
            // Panning and zooming reuse the last picture; it is redrawn once the camera rests.
            val now = SystemClock.uptimeMillis()
            if (staleSince == 0L) staleSince = now
            removeCallbacks(renderStaticTask)
            if (now - staleSince > 450L) renderStaticLayer() else postDelayed(renderStaticTask, 90L)
        }
        val bmp = staticBitmap
        if (bmp != null && staticScale > 0f) {
            if (staticScale != scale || staticCx != centerX || staticCy != centerY) c.drawColor(0xffeaf2ee.toInt())
            val k = scale / staticScale
            staticMatrix.setTranslate(-bmp.width/2f, -bmp.height/2f)
            staticMatrix.postScale(k, k)
            staticMatrix.postTranslate((staticCx-centerX)*scale + width/2f, (centerY-staticCy)*scale + height/2f)
            c.drawBitmap(bmp, staticMatrix, staticPaint)
        }
        drawDynamic(c)
    }

    private fun drawStatic(c:Canvas){
        c.drawColor(0xffeaf2ee.toInt())
        if(cityLayers.isEmpty() && waterBox[0]!=waterBox[2]) {
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE;paint.isFilterBitmap=true
            c.drawBitmap(water,null,RectF(sx(waterBox[0]),sy(waterBox[3]),sx(waterBox[2]),sy(waterBox[1])),paint)
        }
        for (layer in cityLayers) {
            val b = layer.bounds
            val bounds = RectF(sx(b[0]),sy(b[3]),sx(b[2]),sy(b[1]))
            if (RectF.intersects(bounds,RectF(0f,0f,vw.toFloat(),vh.toFloat()))) {
                paint.color = Color.WHITE
                paint.isFilterBitmap = true
                c.drawBitmap(layer.bitmap,null,bounds,paint)
            }
        }
        baseLayer?.draw(c,trainEngine(),centerX,centerY,scale,vw,vh,trainPainter.mercatorPerFrame,displaySettings.mapNames)
        val h = trainEngine()
        zones?.takeIf { h != 0L }?.let { registry ->
            val sw = CitySurface.lonLat(h,centerX-vw/(2*scale),centerY-vh/(2*scale))
            val ne = CitySurface.lonLat(h,centerX+vw/(2*scale),centerY+vh/(2*scale))
            c.save()
            c.translate(vw/2f,vh/2f)
            c.scale(scale,-scale)
            c.translate(-centerX,-centerY)
            paint.style = Paint.Style.STROKE
            val zoom = stationZoom()
            val alpha = (92 * ((10.5f - zoom)/2).coerceIn(0f,1f)).toInt()
            paint.strokeWidth = .7f*density/scale
            for (zone in registry.visible(sw[0],sw[1],ne[0],ne[1])) {
                val focus = zone.id == outlineFocus
                if (!focus && alpha == 0) continue
                if (zoom < 4 && (zone.box[2]-zone.box[0])*vw < (ne[0]-sw[0])*2) continue
                paint.color = if (focus) 0x68657082 else Color.argb(alpha,115,121,139)
                c.drawPath(registry.path(h,zone),paint)
            }
            c.restore()
            paint.style = Paint.Style.FILL
        }
        if (displaySettings.contrast < 1f) c.drawColor(Color.argb(((1-displaySettings.contrast)*255).toInt(),245,246,239))
        if (displaySettings.mapNames) {
            val cells = mutableSetOf<Long>()
            paint.style=Paint.Style.FILL
            for (layer in cityLayers) for (place in layer.places) {
                val x=sx(place.x); val y=sy(place.y)
                if (x<0 || x>=vw || y<14*density || y>=vh) continue
                val cell=((x/(95*density)).toLong() shl 32) or (y/(30*density)).toLong()
                if(!cells.add(cell)) continue
                paint.textSize=(if(place.kind <= 1) 12f else 10f)*density
                paint.color=0xff5a6c64.toInt()
                c.drawText(place.name,x,y,paint)
            }
        }
        c.save();c.translate(vw/2f,vh/2f);c.scale(scale,-scale);c.translate(-centerX,-centerY)
        val overview = stops.size > 180 && scale < homeScale * 2.2f
        val routeWidth = (if (overview) 2.1f else 3.6f) * density * displaySettings.lineWidth / scale
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        for ((style, path) in infrastructure) {
            val stroke = (if (style.tracks >= 4) 4.8f else if (style.tracks == 1) 2.6f else 3.5f) * density / scale
            paint.strokeWidth = stroke
            paint.color = when {
                style.level < 0 -> 0xff82768b.toInt()
                style.level > 0 -> 0xff366d74.toInt()
                style.kind >= 2 -> 0xffa66c39.toInt()
                else -> 0xff485c64.toInt()
            }
            c.drawPath(path,paint)
            paint.strokeWidth = stroke * .38f
            paint.color = if (style.level < 0) 0xffdcd5e2.toInt() else 0xffe0e9df.toInt()
            c.drawPath(path,paint)
        }
        if (overlay.track == "line") {
            paint.strokeWidth = routeWidth + 1.8f * density / scale
            paint.color = 0xfffaf6e9.toInt()
            for ((_, line) in lines) c.drawPath(line, paint)
            paint.strokeWidth = routeWidth
            for ((color, line) in lines) {
                paint.color = color
                c.drawPath(line, paint)
            }
        } else {
            paint.strokeWidth = routeWidth
            if (overlay.track == "height" || overlay.track == "speed") {
                paint.color = if (overlay.track == "height") 0xff58b04a.toInt() else 0xff879194.toInt()
                for ((_,path) in lines) c.drawPath(path,paint)
            }
            if (overlay.track != "traffic") for (piece in pieces) {
                paint.color = overlay.railColor(piece.id,piece.info.low,piece.info.speed)
                c.drawPath(piece.path,paint)
            }
            overlay.drawRoutes(c,routeWidth)
        }
        paint.color=0xff327cd6.toInt()
        paint.strokeWidth=3*density/scale
        paint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(8*density/scale,5*density/scale),0f)
        c.drawPath(blueprintPath,paint)
        paint.pathEffect=null
        for((_,p) in plannedStops) c.drawCircle(p.x,p.y,6*density/scale,paint)
        c.restore()
        if (!isDrafting && overlay.active) overlay.circles(c,centerX,centerY,scale,density)
        paint.style=Paint.Style.FILL
        var labelX=-10000f;var labelY=-10000f
        val labelCells=mutableSetOf<Long>()
        val stationCells = if (overview) mutableSetOf<Long>() else null
        val stationZoom = stationZoom()
        for(s in stops){
            if (stationZoom < 9f) break
            val originX=sx(s.x); val originY=sy(s.y)
            val platform=s.platform
            val box=stationZoom >= 14f && platform != null
            val margin=if (box) platform!!.reach*scale+12*density else 6*density
            if (originX < -margin || originX > vw+margin || originY < -margin || originY > vh+margin) continue
            val x=originX+(platform?.midX ?: 0f)*scale
            val y=originY-(platform?.midY ?: 0f)*scale
            if (stationCells != null) {
                val cell = ((x / (9*density)).toLong() shl 32) or (y / (9*density)).toLong()
                if (!stationCells.add(cell)) continue
            }
            if (box) {
                platform!!.draw(c,paint,originX,originY,scale,density,trainPainter.mercatorPerFrame,inspectingStation?.id == s.id)
            } else {
                val ring = (if (overview) 2f else 3.6f) * density * displaySettings.circleSize
                paint.color=0xff26474b.toInt();c.drawCircle(x,y,ring,paint)
                paint.color=0xfffff9e7.toInt();c.drawCircle(x,y,ring*.6f,paint)
            }
            val col=(x/(103*density)).toLong();val row=(y/(60*density)).toLong()
            if(displaySettings.names && labelCells.add((col shl 32) or row) && (kotlin.math.abs(x-labelX)>78*density || kotlin.math.abs(y-labelY)>20*density)){
                paint.textSize = (if (overview) 9f else 11f) * density
                val tx = x + (if (box) 9 else 5)*density
                val ty = y - (if (box) 9 else 4)*density
                paint.color = 0xfff7f5e9.toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.6f*density
                c.drawText(s.name,tx,ty,paint)
                paint.style = Paint.Style.FILL
                paint.color = 0xff243f46.toInt()
                c.drawText(s.name,tx,ty,paint)
                labelX=x;labelY=y
            }
        }
    }

    /** Screen radius of [metres] of ground at plane y: local frames are in metres, the Mercator frame
     *  (`frame_kind` 2) stretches ground by cosh(y/R) (sim `merc_k`), about 1 for small local y. */
    private fun groundPx(metres: Float, y: Float) = metres * kotlin.math.cosh(y / 6371008.8f) * scale

    private fun ring(c: Canvas, x: Float, y: Float, metres: Float, label: String, fill: Int) {
        val r = groundPx(metres, y)
        val cx = sx(x); val cy = sy(y)
        if (r < 6 * density || cx + r < 0 || cx - r > width || cy + r < 0 || cy - r > height) return
        if (fill != 0) { paint.style = Paint.Style.FILL; paint.color = fill; c.drawCircle(cx, cy, r, paint) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * density; paint.color = 0x8c163f44.toInt()
        c.drawCircle(cx, cy, r, paint)
        paint.textSize = 11 * density
        val tx = cx - paint.measureText(label) / 2; val ty = cy - r - 4 * density
        paint.strokeWidth = 3 * density; paint.color = 0xccf7f5e9.toInt(); c.drawText(label, tx, ty, paint)
        paint.style = Paint.Style.FILL; paint.color = 0xb3163f44.toInt(); c.drawText(label, tx, ty, paint)
    }

    /** The web draws reach on the map, not only in panels: the inspected station's 1 km (passage and
     *  platform links, `LINK_MAX_M`) and the 1 and 2 km rings round a station being placed. */
    private fun drawStationReach(c: Canvas) {
        if (isDrafting) {
            val spot = if (placingStation) draft.lastOrNull() else null
            if (spot != null) {
                ring(c, spot.x, spot.y, 2000f, "2 km", 0)
                ring(c, spot.x, spot.y, 1000f, "1 km", 0x142f6fe0)
            }
            return
        }
        val s = inspectingStation ?: return
        ring(c, s.x + (s.platform?.midX ?: 0f), s.y + (s.platform?.midY ?: 0f), 1000f, "1 km", 0x1a2f6fe0)
    }

    private fun drawDynamic(c:Canvas){
        drawStationReach(c)
        paint.style=Paint.Style.FILL
        val frameTime=SystemClock.elapsedRealtime()
        trainPainter.time = trainTime(frameTime)
        trainPainter.update(centerX,centerY,scale,width,height)
        if (frameTime-trainAt < trainWindow) { removeCallbacks(redrawTask); postDelayed(redrawTask,50) }
        if (displaySettings.trains) trainPainter.draw(c,scale,width,height,displaySettings)
        if (!overlay.active && !previewRequested && requiresStudioClock && trainPainter.needsLoads && onTrainPreviewNeeded!=null) {
            previewRequested=true
            post { onTrainPreviewNeeded?.invoke() }
        }
        if (draft.isNotEmpty()) {
            curveCircle?.let { v ->
                paint.style=Paint.Style.STROKE; paint.strokeWidth=density
                paint.color=if(refused) 0x99d22e28.toInt() else 0x99538996.toInt()
                c.drawCircle(sx(v[0]),sy(v[1]),kotlin.math.hypot(draft.last().x-v[0],draft.last().y-v[1])*scale,paint)
                paint.style=Paint.Style.FILL
            }
            paint.color=if(refused) 0xffc32525.toInt() else 0xffcc502e.toInt()
            paint.strokeWidth=3.5f*density
            paint.pathEffect = draftDash
            val shape=draftShape
            if(shape!=null) {
                for(i in 1 until shape.size) {
                    val a=shape[i-1]; val b=shape[i]
                    c.drawLine(sx(a.x),sy(a.y),sx(b.x),sy(b.y),paint)
                }
            } else draft.forEachIndexed { i,start ->
                var last=start
                for ((leg,p) in bends) if (leg==i) {
                    c.drawLine(sx(last.x),sy(last.y),sx(p.x),sy(p.y),paint)
                    c.drawCircle(sx(p.x),sy(p.y),3*density,paint)
                    last=p
                }
                draft.getOrNull(i+1)?.let { c.drawLine(sx(last.x),sy(last.y),sx(it.x),sy(it.y),paint) }
            }
        }
        paint.pathEffect = null
        val occupied=mutableListOf<RectF>()
        for((p,label) in junctionMarks) {
            val x=sx(p.x); var y=sy(p.y)-24*density
            paint.textSize=12*density
            val half=paint.measureText(label)/2+5*density
            val box=RectF(x-half,y-10*density,x+half,y+8*density)
            while(occupied.any { RectF.intersects(it,box) }) {
                y-=20*density; box.offset(0f,-20*density)
            }
            occupied.add(box)
            paint.color=when(label) { "Sambung" -> 0xff836000.toInt(); "Wesel" -> 0xffa72c28.toInt(); else -> 0xff623319.toInt() }
            c.drawRoundRect(box,6*density,6*density,paint)
            paint.color=Color.WHITE
            c.drawText(label,x-paint.measureText(label)/2,y+4*density,paint)
        }
        railTool?.let { kind ->
            paint.style=Paint.Style.STROKE; paint.strokeWidth=2*density; paint.color=0xffe5ad12.toInt()
            for((p,family) in looseEnds) {
                val x=sx(p.x); val y=sy(p.y)
                if((kind>=2)==(family>=2) && x in 0f..width.toFloat() && y in 0f..height.toFloat()) c.drawCircle(x,y,8*density,paint)
            }
            paint.style=Paint.Style.FILL
        }
        for ((index,p) in draft.withIndex()) {
            val x=sx(p.x);val y=sy(p.y)
            paint.color = 0xfffffcf1.toInt()
            c.drawCircle(x,y,12*density,paint)
            paint.color = if(railTool==null) 0xffab4227.toInt() else when(railSnaps.getOrNull(index)) { 1 -> 0xffffc400.toInt(); 2 -> 0xffdc3235.toInt(); else -> 0xff237cbd.toInt() }
            c.drawCircle(x,y,10*density,paint)
            paint.textSize = 12*density
            paint.color = if(railTool!=null && railSnaps.getOrNull(index)==1) Color.BLACK else Color.WHITE
            val label = (index + 1).toString()
            c.drawText(label,x-paint.measureText(label)/2,y-(paint.ascent()+paint.descent())/2,paint)
        }
        if (isDrafting && isFocused) {
            paint.strokeWidth = 2f * density
            paint.color = 0xff163f44.toInt()
            val x = width / 2f
            val y = height / 2f
            c.drawLine(x-8*density, y, x+8*density, y, paint)
            c.drawLine(x, y-8*density, x, y+8*density, paint)
        }
        paint.style=Paint.Style.FILL;paint.textSize=9*density
        val credit = "© OpenStreetMap contributors · OpenMapTiles · OpenFreeMap" + if(cityLayer==null)" · US Census" else ""
        val note = if (!isDrafting) overlay.legend() ?: baseLayer?.status else baseLayer?.status
        paint.color=0xdcffffff.toInt()
        c.drawRect(0f,height-15*density,paint.measureText(credit)+10*density,height.toFloat(),paint)
        paint.color=0xff38575c.toInt()
        c.drawText(credit,5*density,height-4*density,paint)
        if (note != null) {
            val bottom = height - max(15*density, statusBottomInset.toFloat())
            paint.textSize = 12*density
            paint.color = 0xf2ffffff.toInt()
            c.drawRoundRect(8*density,bottom-24*density,paint.measureText(note)+28*density,bottom,6*density,6*density,paint)
            paint.color = 0xff38575c.toInt()
            c.drawText(note,18*density,bottom-7*density,paint)
        }
    }
    private var previousFocusX = 0f
    private var previousFocusY = 0f
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            previousFocusX = d.focusX
            previousFocusY = d.focusY
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom(d.scaleFactor, previousFocusX, previousFocusY)
            centerX += (previousFocusX - d.focusX) / scale
            centerY -= (previousFocusY - d.focusY) / scale
            previousFocusX = d.focusX
            previousFocusY = d.focusY
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    /** The station under a finger at screen x/y: the drawn platform at z14+, else its dot, within a
     *  24 dp reach (a 48 dp target). Null below z9, where stations are not drawn. */
    private fun stationAt(x: Float, y: Float): Station? {
        val range = 24f * density
        val zoom=stationZoom()
        if (zoom < 9f) return null
        var nearest: Station? = null
        var closest = range
        for (station in stops) {
            if (station.id < 0) continue
            val dx = x-sx(station.x); val dy = sy(station.y)-y
            val platform=station.platform
            val distance = if (platform != null && zoom >= 14f) {
                val margin=platform.reach*scale+range+12*density
                if (kotlin.math.abs(dx)>margin || kotlin.math.abs(dy)>margin) continue
                platform.distance(dx/scale,dy/scale,scale,density,trainPainter.mercatorPerFrame)
            } else kotlin.math.hypot(dx-(platform?.midX ?: 0f)*scale,dy-(platform?.midY ?: 0f)*scale)
            if (distance <= closest) {
                nearest = station
                closest = distance
            }
        }
        return nearest
    }

    private fun selectAt(x: Float, y: Float) {
        if (!isDrafting) {
            // A station beats the lens discs drawn over it (station riders up to 42 dp, homes and
            // destinations up to 24 dp): those used to swallow the tap with a ridership sheet.
            val station = stationAt(x, y)
            if (station != null && onStationTap?.invoke(station.id) == true) return
            if (overlay.inspect(x,y,stops){ id -> onStationTap?.invoke(id) }) return
        }
        onLocation?.invoke(PointF(centerX + (x-width/2f)/scale, centerY - (y-height/2f)/scale))
    }

    override fun performClick(): Boolean {
        super.performClick()
        selectAt(width/2f, height/2f)
        return true
    }
    private fun moveMap(horizontal: Int, vertical: Int) {
        focusAt(centerX + horizontal * width * .24f / scale, centerY + vertical * height * .24f / scale)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveMap(0, 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveMap(0, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveMap(1, 0)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveMap(-1, 0)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
            if (isDrafting) "Letakkan stasiun di tengah peta" else "Periksa stasiun di tengah peta"))
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            android.R.id.accessibilityActionScrollUp -> moveMap(0, 1)
            android.R.id.accessibilityActionScrollDown -> moveMap(0, -1)
            android.R.id.accessibilityActionScrollRight -> moveMap(1, 0)
            android.R.id.accessibilityActionScrollLeft -> moveMap(-1, 0)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }
    private val pan = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!isDrafting) return false
            super@NetworkView.performClick()
            selectAt(e.x, e.y)
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isDrafting) return true
            super@NetworkView.performClick()
            selectAt(e.x, e.y)
            return true
        }
        override fun onScroll(a: MotionEvent?, b: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!pinch.isInProgress && b.pointerCount == 1) focusAt(centerX + dx/scale, centerY - dy/scale)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            onBend?.invoke(PointF(centerX+(e.x-width/2f)/scale,centerY-(e.y-height/2f)/scale))
        }
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (isDrafting && e.actionMasked == MotionEvent.ACTION_UP) {
                super@NetworkView.performClick()
                selectAt(e.x, e.y)
            }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isDrafting) return true
            if (doubleTapZoom) zoomStep(true, e.x, e.y) else resetCamera()
            return true
        }
    })
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) zoomAnimation?.cancel()
        if(railTool!=null && draft.size==2 && e.actionMasked==MotionEvent.ACTION_DOWN) {
            val tip=draft.last()
            holdingRail=kotlin.math.hypot(e.x-sx(tip.x),e.y-sy(tip.y))<=24*density
            railDown=PointF(e.x,e.y); draggedRail=false
        }
        if(holdingRail) {
            when(e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if(kotlin.math.hypot(e.x-railDown.x,e.y-railDown.y)>android.view.ViewConfiguration.get(context).scaledTouchSlop) draggedRail=true
                    if(draggedRail) onRailDrag?.invoke(PointF(centerX+(e.x-width/2f)/scale,centerY-(e.y-height/2f)/scale))
                }
                MotionEvent.ACTION_UP -> {
                    holdingRail=false
                    if(!draggedRail) onLocation?.invoke(rawRailTip ?: draft.last())
                }
                MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN -> holdingRail=false
            }
            return true
        }
        pinch.onTouchEvent(e)
        pan.onTouchEvent(e)
        return true
    }
}

/** Small progress ring for the forecast chip; a green tick when done. */
internal class WarmRing(c: Context) : View(c) {
    var fraction = 0f
        set(v) { field = v; invalidate() }
    var done = false
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val box = RectF()
    override fun onDraw(canvas: Canvas) {
        val w = width * .16f
        paint.strokeWidth = w
        box.set(w / 2, w / 2, width - w / 2, height - w / 2)
        if (done) {
            paint.style = Paint.Style.FILL; paint.color = 0xff2fb36d.toInt()
            canvas.drawOval(box, paint)
            paint.style = Paint.Style.STROKE; paint.color = Color.WHITE
            val path = Path().apply { moveTo(width * .3f, height * .52f); lineTo(width * .45f, height * .67f); lineTo(width * .72f, height * .36f) }
            canvas.drawPath(path, paint)
            return
        }
        paint.color = 0x2622204a; canvas.drawArc(box, 0f, 360f, false, paint)
        paint.color = StudioUi.GREEN; canvas.drawArc(box, -90f, 360f * fraction, false, paint)
    }
}
