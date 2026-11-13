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
import android.os.Bundle
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
            setPadding(0, 24, 0, 24)
        }

        val guide = TextView(this).apply {
            text = """
                कैसे चलाएं:
                1. पहले नीचे 'Service ON करें' दबाकर OTG KeyMapper चालू करें।
                2. स्क्रीन के किनारे एक गोल तैरता हुआ आइकन (🎮) आ जाएगा।
                3. अब 'Open Free Fire' दबाएं।
                4. गेम की सेटिंग खोलकर गोल आइकन को दबाएं, बटन्स को अपनी जगह पर खींचें और किसी भी बटन पर टच करके अपनी Key (जैसे F, Q, 1, SPACE) सेव करें।
                5. 'LOCK & PLAY' दबाएं और कीबोर्ड से खेलें!
            """.trimIndent()
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 12, 0, 36)
        }

        val serviceBtn = Button(this).apply {
            text = "1. KeyMapper Service ON करें"
            setBackgroundColor(Color.parseColor("#FF5722"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(
                    this@MainActivity,
                    "Downloaded/Installed Apps में जाकर OTG KeyMapper को ON करें",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 28)
        }

        val openGameBtn = Button(this).apply {
            text = "2. Open Free Fire / MAX"
            setBackgroundColor(Color.parseColor("#00C853"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                launchFreeFire()
            }
        }

        root.addView(title)
        root.addView(statusView)
        root.addView(guide)
        root.addView(serviceBtn)
        root.addView(spacer)
        root.addView(openGameBtn)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (isAccessibilityServiceEnabled()) {
            statusView.text = "Status: Service ON (तैयार है)"
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
                startActivity(intent)
                return
            }
        }
        Toast.makeText(this, "फोन में Free Fire या Free Fire MAX इंस्टॉल नहीं मिला!", Toast.LENGTH_LONG).show()
    }
}

class OtgKeyMapperService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingBubble: TextView
    private lateinit var topMenuBar: LinearLayout

    private var isEditOpen = false
    private val nodes = mutableListOf<KeyNode>()
    private val nodeViews = mutableMapOf<String, TextView>()
    private val nodeParams = mutableMapOf<String, WindowManager.LayoutParams>()

    private var activeWalkStroke: GestureDescription.StrokeDescription? = null
    private var activeHoldStroke: GestureDescription.StrokeDescription? = null
    private var activeHoldKeyCode: Int = -1

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadSavedNodes()
        setupFloatingBubble()
        setupTopMenuBar()
        renderAllNodes()
        applyModeState()
        Toast.makeText(this, "OTG KeyMapper चालू! किनारे गोल आइकन दबाएं", Toast.LENGTH_SHORT).show()
    }

    private fun setupFloatingBubble() {
        floatingBubble = TextView(this).apply {
            text = "🎮"
            textSize = 20f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6FF5722"))
                setStroke(4, Color.WHITE)
            }
        }

        val bubbleSize = 115
        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
        }

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
            setOnClickListener {
                addNewCustomNode()
            }
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

        topMenuBar.addView(addBtn)
        topMenuBar.addView(savePlayBtn)

        val barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 20
        }

        windowManager.addView(topMenuBar, barParams)
    }

    private fun applyModeState() {
        topMenuBar.visibility = if (isEditOpen) View.VISIBLE else View.GONE
        for (node in nodes) {
            val view = nodeViews[node.id] ?: continue
            val params = nodeParams[node.id] ?: continue
            if (isEditOpen) {
                view.visibility = View.VISIBLE
                view.alpha = 1.0f
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            } else {
                view.visibility = View.VISIBLE
                view.alpha = 0.22f
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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

        val sizePx = 115
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = node.x
            y = node.y
        }

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

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (isEditOpen) return super.onKeyEvent(event)
        if (event.repeatCount > 0) return true

        val keyCode = event.keyCode
        val action = event.action

        // Joystick WASD
        val joyNode = nodes.firstOrNull { it.isJoystick }
        if (joyNode != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_W -> { handleWalk(joyNode, 0f, -135f, action); return true }
                KeyEvent.KEYCODE_S -> { handleWalk(joyNode, 0f, 135f, action); return true }
                KeyEvent.KEYCODE_A -> { handleWalk(joyNode, -135f, 0f, action); return true }
                KeyEvent.KEYCODE_D -> { handleWalk(joyNode, 135f, 0f, action); return true }
            }
        }

        // Custom & Standard Mapped Keys
        val matchedNode = nodes.firstOrNull { !it.isJoystick && it.keyCode == keyCode }
        if (matchedNode != null) {
            if (matchedNode.isHoldable) {
                handleHoldNode(matchedNode, action)
            } else if (action == KeyEvent.ACTION_DOWN) {
                tapNode(matchedNode)
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun tapNode(node: KeyNode) {
        val cx = node.x + 57f
        val cy = node.y + 57f
        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 45)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun handleHoldNode(node: KeyNode, action: Int) {
        val cx = node.x + 57f
        val cy = node.y + 57f
        if (action == KeyEvent.ACTION_DOWN) {
            val path = Path().apply { moveTo(cx, cy) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50, true)
            activeHoldStroke = stroke
            activeHoldKeyCode = node.keyCode
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } else if (action == KeyEvent.ACTION_UP && activeHoldKeyCode == node.keyCode) {
            val prev = activeHoldStroke ?: return
            val endPath = Path().apply { moveTo(cx, cy) }
            val endStroke = prev.continueStroke(endPath, 0, 20, false)
            activeHoldStroke = null
            activeHoldKeyCode = -1
            dispatchGesture(GestureDescription.Builder().addStroke(endStroke).build(), null, null)
        }
    }

    private fun handleWalk(joy: KeyNode, dx: Float, dy: Float, action: Int) {
        val cx = joy.x + 57f
        val cy = joy.y + 57f
        if (action == KeyEvent.ACTION_DOWN) {
            val path = Path().apply {
                moveTo(cx, cy)
                lineTo(cx + dx, cy + dy)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 60, true)
            activeWalkStroke = stroke
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } else if (action == KeyEvent.ACTION_UP) {
            val prev = activeWalkStroke ?: return
            val endPath = Path().apply {
                moveTo(cx + dx, cy + dy)
                lineTo(cx, cy)
            }
            val endStroke = prev.continueStroke(endPath, 0, 30, false)
            activeWalkStroke = null
            dispatchGesture(GestureDescription.Builder().addStroke(endStroke).build(), null, null)
        }
    }

    private fun loadSavedNodes() {
        nodes.clear()
        val prefs = getSharedPreferences("otg_mapper_v2", Context.MODE_PRIVATE)
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
        getSharedPreferences("otg_mapper_v2", Context.MODE_PRIVATE)
            .edit()
            .putString("nodes_json", arr.toString())
            .apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
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
