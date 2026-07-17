package com.floatingai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.UUID

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    private val client = OkHttpClient.Builder()
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
        
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())

    // --- Chat State Variables ---
    private var currentChatId = UUID.randomUUID().toString()
    private var currentChatTitle = "New Chat"
    private var currentMessagesArray = JSONArray()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_chat, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 0
        layoutParams.y = 100

        windowManager.addView(floatingView, layoutParams)
        setupInteractions()
    }

    private fun setWindowFocusable(focusable: Boolean) {
        if (focusable) {
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(floatingView.windowToken, 0)
        }
        windowManager.updateViewLayout(floatingView, layoutParams)
    }

    private fun setupInteractions() {
        val dragHeader = floatingView.findViewById<View>(R.id.dragHeader)
        val resizeHandle = floatingView.findViewById<View>(R.id.resizeHandle)
        val btnClose = floatingView.findViewById<Button>(R.id.btnClose)
        val btnSend = floatingView.findViewById<Button>(R.id.btnSend)
        val etInput = floatingView.findViewById<EditText>(R.id.etInput)
        val tvChat = floatingView.findViewById<TextView>(R.id.tvChat)
        val scrollView = floatingView.findViewById<ScrollView>(R.id.scrollView)
        
        val btnHistory = floatingView.findViewById<Button>(R.id.btnHistory)
        val btnNewChat = floatingView.findViewById<Button>(R.id.btnNewChat)
        val chatUIArea = floatingView.findViewById<View>(R.id.chatUIArea)
        val historyUIArea = floatingView.findViewById<View>(R.id.historyUIArea)
        val historyContainer = floatingView.findViewById<LinearLayout>(R.id.historyContainer)
        val tvHeaderTitle = floatingView.findViewById<TextView>(R.id.tvHeaderTitle)

        btnClose.setOnClickListener { stopSelf() }

        // Give focus to window when tapping input OR textview (allows copying text)
        val focusTouchListener = View.OnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                setWindowFocusable(true)
            }
            false
        }
        etInput.setOnTouchListener(focusTouchListener)
        tvChat.setOnTouchListener(focusTouchListener)

        // Drop focus when tapping header
        dragHeader.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) setWindowFocusable(false)
            false
        }

        // Drag window logic
        var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f
        dragHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    setWindowFocusable(false)
                    initialX = layoutParams.x; initialY = layoutParams.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                else -> false
            }
        }

        // Resize window logic
        var initialWidth = 0; var initialHeight = 0
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = floatingView.width; initialHeight = floatingView.height
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.width = maxOf(400, initialWidth + (event.rawX - initialTouchX).toInt())
                    layoutParams.height = maxOf(400, initialHeight + (event.rawY - initialTouchY).toInt())
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                else -> false
            }
        }

        // Send Message
        btnSend.setOnClickListener {
            val query = etInput.text.toString().trim()
            if (query.isNotEmpty()) {
                // Auto-name chat if it's the first message
                if (currentMessagesArray.length() == 0) {
                    currentChatTitle = if (query.length > 20) query.substring(0, 20) + "..." else query
                }

                // Add to memory
                val userMsg = JSONObject().apply { put("role", "user"); put("content", query) }
                currentMessagesArray.put(userMsg)
                saveCurrentChatToPrefs()

                tvChat.append("\nYou: $query\n")
                etInput.text.clear()
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                fetchAiResponseStream(query, tvChat, scrollView, 0)
            }
        }

        // --- History / New Chat Logic ---
        btnNewChat.setOnClickListener {
            currentChatId = UUID.randomUUID().toString()
            currentChatTitle = "New Chat"
            currentMessagesArray = JSONArray()
            tvChat.text = "New Chat Ready!\n"
            chatUIArea.visibility = View.VISIBLE
            historyUIArea.visibility = View.GONE
            tvHeaderTitle.text = "AI (Tap to drop focus)"
        }

        btnHistory.setOnClickListener {
            if (historyUIArea.visibility == View.GONE) {
                chatUIArea.visibility = View.GONE
                historyUIArea.visibility = View.VISIBLE
                tvHeaderTitle.text = "Saved Chats"
                loadHistoryUI(historyContainer, tvChat, chatUIArea, historyUIArea, tvHeaderTitle)
            } else {
                chatUIArea.visibility = View.VISIBLE
                historyUIArea.visibility = View.GONE
                tvHeaderTitle.text = "AI (Tap to drop focus)"
            }
        }
    }

    // --- Data Persistence ---
    private fun saveCurrentChatToPrefs() {
        if (currentMessagesArray.length() == 0) return
        val prefs = getSharedPreferences("AiPrefs", Context.MODE_PRIVATE)
        val allChatsStr = prefs.getString("SAVED_CHATS", "[]") ?: "[]"
        val allChats = JSONArray(allChatsStr)
        
        var found = false
        for (i in 0 until allChats.length()) {
            val chat = allChats.getJSONObject(i)
            if (chat.getString("id") == currentChatId) {
                chat.put("messages", currentMessagesArray)
                chat.put("title", currentChatTitle)
                found = true
                break
            }
        }
        
        if (!found) {
            val newChat = JSONObject()
            newChat.put("id", currentChatId)
            newChat.put("title", currentChatTitle)
            newChat.put("messages", currentMessagesArray)
            allChats.put(newChat)
        }
        
        prefs.edit().putString("SAVED_CHATS", allChats.toString()).apply()
    }

    private fun loadHistoryUI(container: LinearLayout, tvChat: TextView, chatArea: View, historyArea: View, titleHeader: TextView) {
        container.removeAllViews()
        val prefs = getSharedPreferences("AiPrefs", Context.MODE_PRIVATE)
        val allChatsStr = prefs.getString("SAVED_CHATS", "[]") ?: "[]"
        val allChats = JSONArray(allChatsStr)

        if (allChats.length() == 0) {
            val emptyTxt = TextView(this).apply { 
                text = "No saved chats yet."; setTextColor(Color.WHITE); setPadding(16,16,16,16) 
            }
            container.addView(emptyTxt)
            return
        }

        for (i in allChats.length() - 1 downTo 0) {
            val chatObj = allChats.getJSONObject(i)
            val chatId = chatObj.getString("id")
            val chatTitle = chatObj.getString("title")
            val chatMessages = chatObj.getJSONArray("messages")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 16)
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvTitle = TextView(this).apply {
                text = chatTitle
                setTextColor(Color.WHITE)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnLoad = Button(this).apply {
                text = "Load"
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    currentChatId = chatId
                    currentChatTitle = chatTitle
                    currentMessagesArray = chatMessages
                    
                    // Rebuild chat screen text
                    val sb = java.lang.StringBuilder()
                    for (j in 0 until chatMessages.length()) {
                        val msg = chatMessages.getJSONObject(j)
                        val role = if (msg.getString("role") == "user") "You: " else "AI: "
                        sb.append("\n$role${msg.getString("content")}\n")
                    }
                    tvChat.text = sb.toString()
                    
                    // Switch UI back to chat
                    chatArea.visibility = View.VISIBLE
                    historyArea.visibility = View.GONE
                    titleHeader.text = "AI (Tap to drop focus)"
                }
            }

            val btnDel = Button(this).apply {
                text = "X"
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    // Delete Logic
                    val newArray = JSONArray()
                    for (k in 0 until allChats.length()) {
                        if (allChats.getJSONObject(k).getString("id") != chatId) {
                            newArray.put(allChats.getJSONObject(k))
                        }
                    }
                    prefs.edit().putString("SAVED_CHATS", newArray.toString()).apply()
                    loadHistoryUI(container, tvChat, chatArea, historyArea, titleHeader) // Refresh list
                }
            }

            row.addView(tvTitle)
            row.addView(btnLoad)
            row.addView(btnDel)
            container.addView(row)
        }
    }

    private fun fetchAiResponseStream(query: String, tvChat: TextView, scrollView: ScrollView, retryCount: Int) {
        val prefs = getSharedPreferences("AiPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("API_KEY", "") ?: ""

        val jsonObj = JSONObject().apply {
            put("model", "nvidia/nemotron-3-ultra-550b-a55b")
            put("temperature", 1.0)
            put("top_p", 0.95)
            put("max_tokens", 16384)
            put("reasoning_budget", 16384)
            put("stream", true)
            
            val chatTemplateKwargs = JSONObject().apply { put("enable_thinking", true) }
            put("chat_template_kwargs", chatTemplateKwargs)

            // Send ENTIRE conversation history for context!
            put("messages", currentMessagesArray)
        }

        val requestBody = jsonObj.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .post(requestBody)
            .build()

        coroutineScope.launch {
            val aiResponseBuilder = StringBuilder() // Used to capture the full AI response

            try {
                if (retryCount == 0) {
                    withContext(Dispatchers.Main) { tvChat.append("\nAI: ") }
                }

                val response = client.newCall(request).execute()
                
                if (response.code == 429) {
                    if (retryCount < 3) {
                        withContext(Dispatchers.Main) { 
                            tvChat.append("[Network busy, retrying in 3s...]\nAI: ") 
                            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                        }
                        delay(3000)
                        fetchAiResponseStream(query, tvChat, scrollView, retryCount + 1)
                    } else {
                        withContext(Dispatchers.Main) { tvChat.append("[Error: Too many requests. Please try again later.]\n") }
                    }
                    return@launch
                }

                val body = response.body
                if (response.isSuccessful && body != null) {
                    val reader = body.charStream().buffered()
                    var line: String?
                    
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.startsWith("data: ")) {
                            val data = line!!.substring(6)
                            if (data.trim() == "[DONE]") break
                            try {
                                val json = JSONObject(data)
                                val delta = json.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
                                if (delta.has("content")) {
                                    val chunk = delta.getString("content")
                                    aiResponseBuilder.append(chunk) // Save to memory builder
                                    withContext(Dispatchers.Main) {
                                        tvChat.append(chunk)
                                        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { tvChat.append("[Error]: Network code ${response.code}\n") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvChat.append("\n[Exception]: ${e.message}\n") }
            } finally {
                withContext(Dispatchers.Main) { tvChat.append("\n") }
                
                // When stream is finished, save AI response to our history!
                if (aiResponseBuilder.isNotEmpty()) {
                    val aiMsg = JSONObject().apply { put("role", "assistant"); put("content", aiResponseBuilder.toString()) }
                    currentMessagesArray.put(aiMsg)
                    saveCurrentChatToPrefs() // Commit to storage
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        coroutineScope.cancel()
    }
}
