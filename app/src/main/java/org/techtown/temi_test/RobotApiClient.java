package org.techtown.temi_test;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RobotApiClient {

    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.parse("application/json; charset=utf-8");

    public interface CommandCallback {
        void onCommand(RobotCommand command);

        void onNoCommand();

        void onError(String message);
    }

    public interface ResultCallback {
        void onSuccess(String body);

        void onError(String message);
    }

    public static class RobotCommand {
        public final String commandId;
        public final String command;
        public final String location;

        RobotCommand(String commandId, String command, String location) {
            this.commandId = commandId;
            this.command = command;
            this.location = location;
        }
    }

    private final String baseUrl;
    private final OkHttpClient client;

    public RobotApiClient(String baseUrl, OkHttpClient client) {
        this.baseUrl = baseUrl;
        this.client = client;
    }

    public void getCommand(String robotId, String lastCommandId, CommandCallback callback) {
        try {
            String url = baseUrl + "/robot/command?robot_id="
                    + URLEncoder.encode(robotId, "UTF-8")
                    + "&last_command_id="
                    + URLEncoder.encode(lastCommandId == null ? "" : lastCommandId, "UTF-8");

            Request request = new Request.Builder().url(url).get().build();
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onError(e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body() != null ? response.body().string() : "";
                    try {
                        if (!response.isSuccessful()) {
                            callback.onError("HTTP " + response.code() + ": " + body);
                            return;
                        }
                        JSONObject root = new JSONObject(body);
                        if (!root.optBoolean("has_command", false) || root.isNull("command")) {
                            callback.onNoCommand();
                            return;
                        }
                        JSONObject commandJson = root.getJSONObject("command");
                        callback.onCommand(new RobotCommand(
                                commandJson.optString("command_id", ""),
                                commandJson.optString("command", ""),
                                commandJson.optString("location", "")
                        ));
                    } catch (JSONException e) {
                        callback.onError("명령 JSON 파싱 실패: " + e.getMessage());
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    public void postStatus(JSONObject status, ResultCallback callback) {
        RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, status.toString());
        Request request = new Request.Builder()
                .url(baseUrl + "/robot/status")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                try {
                    if (response.isSuccessful()) {
                        callback.onSuccess(responseBody);
                    } else {
                        callback.onError("HTTP " + response.code() + ": " + responseBody);
                    }
                } finally {
                    response.close();
                }
            }
        });
    }
}
