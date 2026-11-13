package com.example.otgkeymapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F131A"))
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "OTG Free Fire KeyMapper"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        statusView = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }

        val guide = TextView(this).apply {
            text = """
                चलाने का तरीका:
                1. 'Service ON / OFF करें' दबाकर OTG KeyMapper चालू करें।
                2. 'Open Free Fire / MAX' दबाएं (गोल बटन्स सिर्फ गेम के अंदर दिखेंगे)।
                3. गेम में गोल आइकन (🎮) दबाकर बटन्स सेट करें और 'LOCK & PLAY' दबाएं।
                4. गेम से बाहर आते ही सारे बटन्स अपने आप छिप जाएंगे।
            """.trimIndent()
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 12, 0, 32)
        }

        val serviceBtn = Button(this).apply {
            text = "1. KeyMapper Service ON / OFF करें"
            setBackgroundColor(Color.parseColor("#FF5722"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val spacer1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 24)
        }

        val openGameBtn = Button(this).apply {
            text = "2. Open Free Fire / MAX"
            setBackgroundColor(Color.parseColor("#00C853"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                launchFreeFire()
            }
        }

        val spacer2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 24)
        }

        val hideOverlayBtn = Button(this).apply {
            text = "3. डिस्प्ले से गोल बटन्स बंद करें (Hide)"
            setBackgroundColor(Color.parseColor("#37474F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                OtgKeyMapperService.forceHide()
                Toast.makeText(this@MainActivity, "सारे गोल बटन्स बंद कर दिए गए हैं", Toast.LENGTH_SHORT).show()
            }
        }

        root.addView(title)
        root.addView(statusView)
        root.addView(guide)
        root.addView(serviceBtn)
        root.addView(spacer1)
        root.addView(openGameBtn)
        root.addView(spacer2)
        root.addView(hideOverlayBtn)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        OtgKeyMapperService.forceHide()
        if (isAccessibilityServiceEnabled()) {
            statusView.text = "Status: Service ON (गेम खोलने के लिए तैयार)"
            statusView.setTextColor(Color.parseColor("#00E676"))
        } else {
            statusView.text = "Status: Service OFF (पहले नीचे से ON करें)"
            statusView.setTextColor(Color.parseColor("#FF5252"))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, OtgKeyMapperService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            if (colonSplitter.next().equals(expected, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun launchFreeFire() {
        val packages = listOf("com.dts.freefiremax", "com.dts.freefireth")
        for (pkg in packages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                OtgKeyMapperService.forceShow()
                startActivity(intent)
                return
            }
        }
        Toast.makeText(this, "फोन में Free Fire या Free Fire MAX नहीं मिला!", Toast.LENGTH_LONG).show()
    }
}

class OtgKeyMapperService : AccessibilityService() {

    companion object {
        var instance: OtgKeyMapperService? = null

        fun forceShow() {
            instance?.setGameOverlayVisible(true)
        }

        fun forceHide() {
            instance?.setGameOverlayVisible(false)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingBubble: TextView
    private lateinit var topMenuBar: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var isOverlayVisible = false
    private var isEditOpen = false

    private val nodes = mutableListOf<KeyNode>()
    private val nodeViews = mutableMapOf<String, TextView>()
    private val nodeParams = mutableMapOf<String, WindowManager.LayoutParams>()

    private var currentWalkStroke: GestureDescription.StrokeDescription? = null
    private var activeWalkKey: Int = -1
    private var walkTargetX: Float = 0f
    private var walkTargetY: Float = 0f
    private var walkRunnable: Runnable? = null

    private var currentHoldStroke: GestureDescription.StrokeDescription? = null
    private var activeHoldKey: Int = -1
    private var holdTargetX: Float = 0f
    private var holdTargetY: Float = 0f
    private var holdRunnable: Runnable? = null

    private val resetBubbleRunnable = Runnable {
        if (::floatingBubble.isInitialized) {
            floatingBubble.text = "🎮"
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadSavedNodes()
        setupFloatingBubble()
        setupTopMenuBar()
        renderAllNodes()
        setGameOverlayVisible(false)
        Toast.makeText(this, "OTG KeyMapper तैयार! अब Free Fire खोलें", Toast.LENGTH_SHORT).show()
    }

    fun setGameOverlayVisible(visible: Boolean) {
        handler.post {
            isOverlayVisible = visible
            if (!visible) {
                isEditOpen = false
                stopWalkLoop()
                stopHoldLoop()
            }
            applyModeState()
        }
    }

    private fun createOverlayParams(width: Int, height: Int, startX: Int, startY: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun setupFloatingBubble() {
        floatingBubble = TextView(this).apply {
            text = "🎮"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6FF5722"))
                setStroke(4, Color.WHITE)
            }
        }

        val params = createOverlayParams(115, 115, 24, 180)
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        floatingBubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = initX + dx
                    params.y = initY + dy
                    windowManager.updateViewLayout(floatingBubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        isEditOpen = !isEditOpen
                        applyModeState()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingBubble, params)
    }

    private fun setupTopMenuBar() {
        topMenuBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#DD111622"))
            setPadding(16, 10, 16, 10)
        }

        val addBtn = Button(this).apply {
            text = "+ ADD KEY"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            setOnClickListener { addNewCustomNode() }
        }

        val savePlayBtn = Button(this).apply {
            text = "LOCK & PLAY"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#00C853"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                saveAllNodes()
                isEditOpen = false
                applyModeState()
                Toast.makeText(this@OtgKeyMapperService, "सेव हो गया! अब कीबोर्ड से खेलें", Toast.LENGTH_SHORT).show()
            }
        }

        val closeBtn = Button(this).apply {
            text = "HIDE"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#D50000"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                setGameOverlayVisible(false)
            }
        }

        topMenuBar.addView(addBtn)
        topMenuBar.addView(savePlayBtn)
        topMenuBar.addView(closeBtn)

        val barParams = createOverlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            550,
            20
        )
        windowManager.addView(topMenuBar, barParams)
    }

    private fun applyModeState() {
        if (!::floatingBubble.isInitialized || !::topMenuBar.isInitialized) return

        if (!isOverlayVisible) {
            floatingBubble.visibility = View.GONE
            topMenuBar.visibility = View.GONE
            for (node in nodes) {
                nodeViews[node.id]?.visibility = View.GONE
            }
            return
        }

        floatingBubble.visibility = View.VISIBLE
        topMenuBar.visibility = if (isEditOpen) View.VISIBLE else View.GONE

        for (node in nodes) {
            val view = nodeViews[node.id] ?: continue
            val params = nodeParams[node.id] ?: continue
            view.visibility = View.VISIBLE
            if (isEditOpen) {
                view.alpha = 1.0f
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                view.alpha = 0.25f
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun renderAllNodes() {
        for (node in nodes) {
            createNodeView(node)
        }
    }

    private fun createNodeView(node: KeyNode) {
        val badge = TextView(this).apply {
            text = formatNodeLabel(node)
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(node.colorHex))
                setStroke(3, Color.WHITE)
            }
        }

        val params = createOverlayParams(115, 115, node.x, node.y)
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        badge.setOnTouchListener { _, event ->
            if (!isEditOpen) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) isDragging = true
                    params.x = initX + dx
                    params.y = initY + dy
                    node.x = params.x
                    node.y = params.y
                    windowManager.updateViewLayout(badge, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        saveAllNodes()
                    } else {
                        showKeyAssignDialog(node)
                    }
                    true
                }
                else -> false
            }
        }

        nodeViews[node.id] = badge
        nodeParams[node.id] = params
        windowManager.addView(badge, params)
    }

    private fun formatNodeLabel(node: KeyNode): String {
        return if (node.isJoystick) "MOVE\n[WASD]" else "${node.name}\n[${node.keyLabel}]"
    }

    private fun addNewCustomNode() {
        val id = "CUSTOM_${System.currentTimeMillis()}"
        val num = nodes.size + 1
        val newNode = KeyNode(
            id = id,
            name = "BTN$num",
            keyCode = KeyEvent.KEYCODE_1,
            keyLabel = "1",
            x = 900,
            y = 500,
            colorHex = "#7700BCD4",
            isJoystick = false,
            isHoldable = false
        )
        nodes.add(newNode)
        createNodeView(newNode)
        applyModeState()
        saveAllNodes()
        showKeyAssignDialog(newNode)
    }

    private fun showKeyAssignDialog(node: KeyNode) {
        if (node.isJoystick) {
            Toast.makeText(this, "यह Joystick (W-A-S-D) है, इसे चलने वाले बटन पर रखें", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 28, 44, 12)
        }

        val infoText = TextView(this).apply {
            text = "अभी सेट है: [${node.keyLabel}]\nकीबोर्ड का कोई बटन दबाएं या नीचे लिखें (जैसे F, Q, E, 1, 2, SPACE):"
            textSize = 14f
        }

        val inputBox = EditText(this).apply {
            setText(node.keyLabel)
            filters = arrayOf(InputFilter.LengthFilter(10))
            setSelection(text.length)
        }

        container.addView(infoText)
        container.addView(inputBox)

        var selectedKeyCode = node.keyCode
        var selectedLabel = node.keyLabel

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${node.name} बटन सेट करें")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val typed = inputBox.text.toString().trim().uppercase()
                if (typed.isNotEmpty() && typed != selectedLabel) {
                    val parsed = parseKeyCodeFromText(typed)
                    if (parsed != KeyEvent.KEYCODE_UNKNOWN) {
                        selectedKeyCode = parsed
                        selectedLabel = typed
                    }
                }
                node.keyCode = selectedKeyCode
                node.keyLabel = selectedLabel
                nodeViews[node.id]?.text = formatNodeLabel(node)
                saveAllNodes()
                Toast.makeText(this, "${node.name} = [${node.keyLabel}] सेव हो गया", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)

        if (node.id.startsWith("CUSTOM_")) {
            builder.setNeutralButton("Delete") { _, _ ->
                nodeViews[node.id]?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
                nodeViews.remove(node.id)
                nodeParams.remove(node.id)
                nodes.remove(node)
                saveAllNodes()
            }
        }

        val dialog = builder.create()
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_BACK) {
                val readable = getReadableKeyName(keyCode)
                if (readable.isNotEmpty()) {
                    selectedKeyCode = keyCode
                    selectedLabel = readable
                    inputBox.setText(readable)
                    inputBox.setSelection(readable.length)
                    return@setOnKeyListener true
                }
            }
            false
        }

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.show()
    }

    private fun parseKeyCodeFromText(label: String): Int {
        return when (label) {
            "SPACE" -> KeyEvent.KEYCODE_SPACE
            "SHIFT" -> KeyEvent.KEYCODE_SHIFT_LEFT
            "CTRL" -> KeyEvent.KEYCODE_CTRL_LEFT
            "ALT" -> KeyEvent.KEYCODE_ALT_LEFT
            "TAB" -> KeyEvent.KEYCODE_TAB
            "ENTER" -> KeyEvent.KEYCODE_ENTER
            else -> KeyEvent.keyCodeFromString("KEYCODE_$label")
        }
    }

    private fun getReadableKeyName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> "SPACE"
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "SHIFT"
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "CTRL"
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "ALT"
            KeyEvent.KEYCODE_TAB -> "TAB"
            KeyEvent.KEYCODE_ENTER -> "ENTER"
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }
    }

    private fun showKeyFeedback(keyCode: Int) {
        val label = getReadableKeyName(keyCode)
        if (label.isNotEmpty() && ::floatingBubble.isInitialized) {
            floatingBubble.text = label
            handler.removeCallbacks(resetBubbleRunnable)
            handler.postDelayed(resetBubbleRunnable, 600)
        }
    }

    private fun getNodeScreenCenter(node: KeyNode): Pair<Float, Float> {
        val view = nodeViews[node.id]
        if (view != null && view.isAttachedToWindow && view.width > 0) {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            return Pair(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
        }
        return Pair(node.x + 57f, node.y + 57f)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isOverlayVisible || isEditOpen) return super.onKeyEvent(event)

        val keyCode = event.keyCode
        val action = event.action

        if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            showKeyFeedback(keyCode)
        }

        if (event.repeatCount > 0) return true

        val joyNode = nodes.firstOrNull { it.isJoystick }
        if (joyNode != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_W -> { handleContinuousWalk(joyNode, keyCode, 0f, -145f, action); return true }
                KeyEvent.KEYCODE_S -> { handleContinuousWalk(joyNode, keyCode, 0f, 145f, action); return true }
                KeyEvent.KEYCODE_A -> { handleContinuousWalk(joyNode, keyCode, -145f, 0f, action); return true }
                KeyEvent.KEYCODE_D -> { handleContinuousWalk(joyNode, keyCode, 145f, 0f, action); return true }
            }
        }

        val matchedNode = nodes.firstOrNull { !it.isJoystick && it.keyCode == keyCode }
        if (matchedNode != null) {
            if (matchedNode.isHoldable) {
                handleContinuousHold(matchedNode, keyCode, action)
            } else if (action == KeyEvent.ACTION_DOWN) {
                tapNode(matchedNode)
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun tapNode(node: KeyNode) {
        val (cx, cy) = getNodeScreenCenter(node)
        val path = Path().apply {
            moveTo(cx, cy)
            lineTo(cx + 2f, cy + 2f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun handleContinuousWalk(joy: KeyNode, keyCode: Int, dx: Float, dy: Float, action: Int) {
        val (cx, cy) = getNodeScreenCenter(joy)

        if (action == KeyEvent.ACTION_DOWN) {
            stopWalkLoop()
            activeWalkKey = keyCode
            walkTargetX = cx + dx
            walkTargetY = cy + dy

            val startPath = Path().apply {
                moveTo(cx, cy)
                lineTo(walkTargetX, walkTargetY)
            }
            val firstStroke = GestureDescription.StrokeDescription(startPath, 0, 50, true)
            currentWalkStroke = firstStroke
            dispatchGesture(GestureDescription.Builder().addStroke(firstStroke).build(), null, null)

            val loop = object : Runnable {
                override fun run() {
                    val prev = currentWalkStroke ?: return
                    if (activeWalkKey != keyCode) return
                    val holdPath = Path().apply {
                        moveTo(walkTargetX, walkTargetY)
                        lineTo(walkTargetX + 1f, walkTargetY + 1f)
                    }
                    val nextStroke = prev.continueStroke(holdPath, 0, 50, true)
                    currentWalkStroke = nextStroke
                    dispatchGesture(GestureDescription.Builder().addStroke(nextStroke).build(), null, null)
                    handler.postDelayed(this, 45)
                }
            }
            walkRunnable = loop
            handler.postDelayed(loop, 45)

        } else if (action == KeyEvent.ACTION_UP && activeWalkKey == keyCode) {
            stopWalkLoop()
        }
    }

    private fun stopWalkLoop() {
        walkRunnable?.let { handler.removeCallbacks(it) }
        walkRunnable = null
        val prev = currentWalkStroke
        if (prev != null) {
            val endPath = Path().apply {
                moveTo(walkTargetX, walkTargetY)
                lineTo(walkTargetX + 1f, walkTargetY + 1f)
            }
            val endStroke = prev.continueStroke(endPath, 0, 20, false)
            dispatchGesture(GestureDescription.Builder().addStroke(endStroke).build(), null, null)
        }
        currentWalkStroke = null
        activeWalkKey = -1
    }

    private fun handleContinuousHold(node: KeyNode, keyCode: Int, action: Int) {
        val (cx, cy) = getNodeScreenCenter(node)

        if (action == KeyEvent.ACTION_DOWN) {
            stopHoldLoop()
            activeHoldKey = keyCode
            holdTargetX = cx
            holdTargetY = cy

            val startPath = Path().apply {
                moveTo(cx, cy)
                lineTo(cx + 1f, cy + 1f)
            }
            val firstStroke = GestureDescription.StrokeDescription(startPath, 0, 50, true)
            currentHoldStroke = firstStroke
            dispatchGesture(GestureDescription.Builder().addStroke(firstStroke).build(), null, null)

            val loop = object : Runnable {
                override fun run() {
                    val prev = currentHoldStroke ?: return
                    if (activeHoldKey != keyCode) return
                    val holdPath = Path().apply {
                        moveTo(holdTargetX, holdTargetY)
                        lineTo(holdTargetX + 1f, holdTargetY + 1f)
                    }
                    val nextStroke = prev.continueStroke(holdPath, 0, 50, true)
                    currentHoldStroke = nextStroke
                    dispatchGesture(GestureDescription.Builder().addStroke(nextStroke).build(), null, null)
                    handler.postDelayed(this, 45)
                }
            }
            holdRunnable = loop
            handler.postDelayed(loop, 45)

        } else if (action == KeyEvent.ACTION_UP && activeHoldKey == keyCode) {
            stopHoldLoop()
        }
    }

    private fun stopHoldLoop() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
        val prev = currentHoldStroke
        if (prev != null) {
            val endPath = Path().apply {
                moveTo(holdTargetX, holdTargetY)
                lineTo(holdTargetX + 1f, holdTargetY + 1f)
            }
            val endStroke = prev.continueStroke(endPath, 0, 20, false)
            dispatchGesture(GestureDescription.Builder().addStroke(endStroke).build(), null, null)
        }
        currentHoldStroke = null
        activeHoldKey = -1
    }

    private fun loadSavedNodes() {
        nodes.clear()
        val prefs = getSharedPreferences("otg_mapper_v3", Context.MODE_PRIVATE)
        val raw = prefs.getString("nodes_json", null)
        if (raw != null) {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                nodes.add(
                    KeyNode(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        keyCode = obj.getInt("keyCode"),
                        keyLabel = obj.getString("keyLabel"),
                        x = obj.getInt("x"),
                        y = obj.getInt("y"),
                        colorHex = obj.getString("colorHex"),
                        isJoystick = obj.getBoolean("isJoystick"),
                        isHoldable = obj.getBoolean("isHoldable")
                    )
                )
            }
            return
        }

        nodes.addAll(
            listOf(
                KeyNode("JOY", "MOVE", KeyEvent.KEYCODE_W, "WASD", 220, 720, "#662196F3", true, false),
                KeyNode("FIRE", "FIRE", KeyEvent.KEYCODE_F, "F", 1740, 740, "#88F44336", false, true),
                KeyNode("SCOPE", "SCOPE", KeyEvent.KEYCODE_R, "R", 1840, 470, "#88FF9800", false, false),
                KeyNode("JUMP", "JUMP", KeyEvent.KEYCODE_SPACE, "SPACE", 1840, 310, "#884CAF50", false, false),
                KeyNode("CROUCH", "SIT", KeyEvent.KEYCODE_C, "C", 1640, 860, "#889C27B0", false, false),
                KeyNode("GLOO", "GLOO", KeyEvent.KEYCODE_G, "G", 1440, 860, "#8800BCD4", false, false),
                KeyNode("RUN", "RUN", KeyEvent.KEYCODE_SHIFT_LEFT, "SHIFT", 240, 420, "#88E91E63", false, false)
            )
        )
    }

    private fun saveAllNodes() {
        val arr = JSONArray()
        for (n in nodes) {
            val obj = JSONObject().apply {
                put("id", n.id)
                put("name", n.name)
                put("keyCode", n.keyCode)
                put("keyLabel", n.keyLabel)
                put("x", n.x)
                put("y", n.y)
                put("colorHex", n.colorHex)
                put("isJoystick", n.isJoystick)
                put("isHoldable", n.isHoldable)
            }
            arr.put(obj)
        }
        getSharedPreferences("otg_mapper_v3", Context.MODE_PRIVATE)
            .edit()
            .putString("nodes_json", arr.toString())
            .apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) {
                return
            }
            val isFreeFire = (pkg == "com.dts.freefiremax" || pkg == "com.dts.freefireth")
            if (isFreeFire && !isOverlayVisible) {
                setGameOverlayVisible(true)
            } else if (!isFreeFire && isOverlayVisible) {
                setGameOverlayVisible(false)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopWalkLoop()
        stopHoldLoop()
        if (::floatingBubble.isInitialized && floatingBubble.isAttachedToWindow) {
            windowManager.removeView(floatingBubble)
        }
        if (::topMenuBar.isInitialized && topMenuBar.isAttachedToWindow) {
            windowManager.removeView(topMenuBar)
        }
        for ((_, v) in nodeViews) {
            if (v.isAttachedToWindow) windowManager.removeView(v)
        }
    }

    private data class KeyNode(
        val id: String,
        val name: String,
        var keyCode: Int,
        var keyLabel: String,
        var x: Int,
        var y: Int,
        val colorHex: String,
        val isJoystick: Boolean,
        val isHoldable: Boolean
    )
}
