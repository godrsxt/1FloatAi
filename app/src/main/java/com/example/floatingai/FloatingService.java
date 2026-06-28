package com.example.floatingai;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class FloatingService extends Service {
    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;
    private boolean isLargeSize = true;

    private List<ChatModel> chatList;
    private ChatAdapter chatAdapter;
    private AiClient aiClient;
    private SharedPreferences prefs;
    private Handler mainHandler;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_layout, null);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.9),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.5),
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, 
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 100;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowManager.addView(floatingView, params);

        setupLogic();
        setupDrag();
    }

    private void setupLogic() {
        prefs = getSharedPreferences("AiPrefs", MODE_PRIVATE);
        aiClient = new AiClient();
        mainHandler = new Handler(Looper.getMainLooper());
        
        chatList = new ArrayList<>();
        chatAdapter = new ChatAdapter(chatList);
        RecyclerView recycler = floatingView.findViewById(R.id.recyclerChat);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(chatAdapter);

        EditText editMsg = floatingView.findViewById(R.id.editMessage);
        Button btnSend = floatingView.findViewById(R.id.btnSend);

        btnSend.setOnClickListener(v -> {
            String msg = editMsg.getText().toString().trim();
            if (msg.isEmpty()) return;

            addChat(msg, true);
            editMsg.setText("");

            String key = prefs.getString("api_key", "");
            String url = prefs.getString("api_url", "");
            String model = prefs.getString("model", "");

            aiClient.sendMessage(key, url, model, msg, new AiClient.AiCallback() {
                @Override
                public void onSuccess(String response) {
                    mainHandler.post(() -> addChat(response, false));
                }

                @Override
                public void onError(String error) {
                    mainHandler.post(() -> addChat("Error: " + error, false));
                }
            });
        });

        floatingView.findViewById(R.id.btnClose).setOnClickListener(v -> stopSelf());

        floatingView.findViewById(R.id.btnSize).setOnClickListener(v -> {
            isLargeSize = !isLargeSize;
            params.height = (int) (getResources().getDisplayMetrics().heightPixels * (isLargeSize ? 0.5 : 0.25));
            windowManager.updateViewLayout(floatingView, params);
        });
    }

    private void addChat(String message, boolean isUser) {
        chatList.add(new ChatModel(message, isUser));
        chatAdapter.notifyItemInserted(chatList.size() - 1);
        RecyclerView recycler = floatingView.findViewById(R.id.recyclerChat);
        recycler.scrollToPosition(chatList.size() - 1);
    }

    private void setupDrag() {
        View handle = floatingView.findViewById(R.id.dragHandle);
        handle.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                }
                return false;
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null) windowManager.removeView(floatingView);
    }
}
