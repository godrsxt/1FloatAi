package com.example.floatingai;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AiClient {
    private OkHttpClient client;

    public AiClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void sendMessage(String apiKey, String apiUrl, String modelName, String message, AiCallback callback) {
        // Defaults to OpenAI format, but works with Groq, DeepSeek, Together etc.
        if (apiUrl == null || apiUrl.isEmpty()) apiUrl = "https://api.openai.com/v1/chat/completions";
        if (modelName == null || modelName.isEmpty()) modelName = "gpt-3.5-turbo";

        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", modelName);

            JSONArray messagesArray = new JSONArray();
            JSONObject messageObj = new JSONObject();
            messageObj.put("role", "user");
            messageObj.put("content", message);
            messagesArray.put(messageObj);

            jsonBody.put("messages", messagesArray);

            RequestBody body = RequestBody.create(
                    jsonBody.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onError("Network Error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        callback.onError("API Error: " + response.code() + " " + response.message());
                        return;
                    }
                    try {
                        String responseData = response.body().string();
                        JSONObject json = new JSONObject(responseData);
                        String reply = json.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content");
                        callback.onSuccess(reply.trim());
                    } catch (Exception e) {
                        callback.onError("Parsing Error: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            callback.onError("Request Error: " + e.getMessage());
        }
    }

    public interface AiCallback {
        void onSuccess(String response);
        void onError(String error);
    }
}
