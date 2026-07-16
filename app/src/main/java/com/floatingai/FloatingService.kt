package com.floatingai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    private val client = OkHttpClient.Builder()
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
        
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())

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

        btnClose.setOnClickListener { stopSelf() }

        etInput.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                setWindowFocusable(true)
            }
            false
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    setWindowFocusable(false)
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
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

        var initialWidth = 0
        var initialHeight = 0
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = floatingView.width
                    initialHeight = floatingView.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
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

        btnSend.setOnClickListener {
            val query = etInput.text.toString().trim()
            if (query.isNotEmpty()) {
                tvChat.append("\nYou: $query\n")
                etInput.text.clear()
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                fetchAiResponseStream(query, tvChat, scrollView, 0)
            }
        }
    }

    private fun fetchAiResponseStream(query: String, tvChat: TextView, scrollView: ScrollView, retryCount: Int) {
        val prefs = getSharedPreferences("AiPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("API_KEY", "") ?: ""

        val jsonObj = JSONObject().apply {
            put("model", "google/gemma-4-31b-it:free")
            put("stream", true)
            val messages = JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", query)
            })
            put("messages", messages)
        }

        val requestBody = jsonObj.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        coroutineScope.launch {
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
                            if (data == "[DONE]") break
                            try {
                                val json = JSONObject(data)
                                val delta = json.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
                                if (delta.has("content")) {
                                    val chunk = delta.getString("content")
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
        // Touching the drag header releases the keyboard back to background apps
        dragHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    setWindowFocusable(false) // Release keyboard to background app
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
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

        var initialWidth = 0
        var initialHeight = 0
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = floatingView.width
                    initialHeight = floatingView.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
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

        btnSend.setOnClickListener {
            val query = etInput.text.toString().trim()
            if (query.isNotEmpty()) {
                tvChat.append("\nYou: $query\n")
                etInput.text.clear()
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                fetchAiResponseStream(query, tvChat, scrollView, 0)
            }
        }
    }

    private fun fetchAiResponseStream(query: String, tvChat: TextView, scrollView: ScrollView, retryCount: Int) {
        val prefs = getSharedPreferences("AiPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("API_KEY", "") ?: ""

        val jsonObj = JSONObject().apply {
            put("model", "google/gemma-4-31b-it:free")
            put("stream", true)
            val messages = JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", query)
            })
            put("messages", messages)
        }

        val requestBody = jsonObj.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        coroutineScope.launch {
            try {
                if (retryCount == 0) {
                    withContext(Dispatchers.Main) { tvChat.append("\nAI: ") }
                }

                val response = client.newCall(request).execute()
                
                // --- Handle 429 Rate Limit ---
                if (response.code == 429) {
                    if (retryCount < 3) { // Retry up to 3 times
                        withContext(Dispatchers.Main) { 
                            tvChat.append("[Network busy, retrying in 3s...]\nAI: ") 
                            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                        }
                        delay(3000) // Wait 3 seconds
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
                            if (data == "[DONE]") break
                            try {
                                val json = JSONObject(data)
                                val delta = json.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
                                if (delta.has("content")) {
                                    val chunk = delta.getString("content")
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
        var initialHeight = 0
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = floatingView.width
                    initialHeight = floatingView.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newWidth = initialWidth + (event.rawX - initialTouchX).toInt()
                    val newHeight = initialHeight + (event.rawY - initialTouchY).toInt()
                    layoutParams.width = maxOf(400, newWidth) // Minimum width constraint
                    layoutParams.height = maxOf(400, newHeight) // Minimum height constraint
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
                tvChat.append("\nYou: $query\n")
                etInput.text.clear()
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                fetchAiResponse(query, tvChat, scrollView)
            }
        }
    }

    private fun fetchAiResponse(query: String, tvChat: TextView, scrollView: ScrollView) {
        val prefs = getSharedPreferences("AiPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("API_KEY", "") ?: ""

        val jsonObj = JSONObject().apply {
            put("model", "google/gemma-4-31b-it:free")
            val messages = JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", query)
            })
            put("messages", messages)
        }

        val requestBody = jsonObj.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        coroutineScope.launch {
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                
                if (response.isSuccessful && responseBody != null) {
                    val jsonResponse = JSONObject(responseBody)
                    val choices = jsonResponse.getJSONArray("choices")
                    val message = choices.getJSONObject(0).getJSONObject("message").getString("content")
                    
                    withContext(Dispatchers.Main) {
                        tvChat.append("\nAI: $message\n")
                        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        tvChat.append("\n[Error]: Network code ${response.code}\n")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvChat.append("\n[Exception]: ${e.message}\n")
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
