package com.temi.rhythmgame;

import android.net.Uri;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RobotApiClient {
    private static final String TAG = "RobotApiClient";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType JPEG = MediaType.parse("image/jpeg");
    private static final MediaType TEXT = MediaType.parse("text/plain");

    private final OkHttpClient client;
    private final String baseUrl;

    public RobotApiClient(String baseUrl) {
        this.client = new OkHttpClient();
        this.baseUrl = baseUrl;
    }

    public void getCommand(String robotId, String lastCommandId, JsonCallback callback) {
        Uri.Builder uriBuilder = Uri.parse(baseUrl + "/robot/command").buildUpon()
                .appendQueryParameter("robot_id", robotId);
        if (lastCommandId != null && !lastCommandId.isEmpty()) {
            uriBuilder.appendQueryParameter("last_command_id", lastCommandId);
        }

        Request request = new Request.Builder()
                .url(uriBuilder.build().toString())
                .get()
                .build();
        enqueueJson(request, callback);
    }

    public void postStatus(JSONObject status, JsonCallback callback) {
        RequestBody body = RequestBody.create(JSON, status.toString());
        Request request = new Request.Builder()
                .url(baseUrl + "/robot/status")
                .post(body)
                .build();
        enqueueJson(request, callback);
    }

    public void reset(JsonCallback callback) {
        RequestBody body = RequestBody.create(TEXT, "");
        Request request = new Request.Builder()
                .url(baseUrl + "/reset")
                .post(body)
                .build();
        enqueueJson(request, callback);
    }

    public void analyzeFrame(byte[] jpegBytes, Long elapsedMillis, JsonCallback callback) {
        RequestBody imageBody = RequestBody.create(JPEG, jpegBytes);
        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "frame.jpg", imageBody);

        if (elapsedMillis != null) {
            bodyBuilder.addFormDataPart("elapsed_time", String.valueOf(elapsedMillis / 1000.0));
        }

        Request request = new Request.Builder()
                .url(baseUrl + "/analyze_frame")
                .post(bodyBuilder.build())
                .build();
        enqueueJson(request, callback);
    }

    private void enqueueJson(Request request, JsonCallback callback) {
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onFailure(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    callback.onFailure(new IOException("HTTP " + response.code() + ": " + body));
                    return;
                }

                try {
                    callback.onSuccess(body.isEmpty() ? new JSONObject() : new JSONObject(body));
                } catch (JSONException e) {
                    Log.e(TAG, "Invalid JSON: " + body);
                    callback.onFailure(e);
                }
            }
        });
    }

    public interface JsonCallback {
        void onSuccess(JSONObject json);

        void onFailure(Exception e);
    }
}
