package com.temi.rhythmgame;

import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatActivity;

import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BaseActivity extends AppCompatActivity {
    private Robot asrRobot;

    private final Robot.AsrListener medicationAsrListener = (asrResult, sttLanguage) -> {
        if (asrResult == null) {
            return;
        }

        String normalized = asrResult.replace(" ", "");
        if (isMedicationQuestion(normalized)) {
            respondToMedicationQuery();
        } else if (asrRobot != null) {
            asrRobot.startDefaultNlu(asrResult, sttLanguage);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        asrRobot = Robot.getInstance();
        asrRobot.addAsrListener(medicationAsrListener);
    }

    @Override
    protected void onStop() {
        if (asrRobot != null) {
            asrRobot.removeAsrListener(medicationAsrListener);
        }
        super.onStop();
    }

    private boolean isMedicationQuestion(String text) {
        boolean hasMedicationKeyword = text.contains("약") || text.contains("복약");
        boolean hasQuestionKeyword = text.contains("먹었")
                || text.contains("먹었어")
                || text.contains("먹었냐")
                || text.contains("먹었나")
                || text.contains("했어")
                || text.contains("했냐")
                || text.contains("했나");
        return hasMedicationKeyword && hasQuestionKeyword;
    }

    private void respondToMedicationQuery() {
        SharedPreferences prefs = getSharedPreferences("medication_prefs", MODE_PRIVATE);
        String status = prefs.getString("status", "UNKNOWN");
        String date = prefs.getString("date", "");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date());

        String response;
        if (!today.equals(date)) {
            response = "오늘 복약 기록이 없습니다.";
        } else if ("TAKEN".equals(status)) {
            response = "네, 오늘 약을 먹었습니다.";
        } else if ("NO_RESPONSE".equals(status)) {
            response = "아직 복약 확인이 되지 않았습니다.";
        } else {
            response = "오늘 복약 기록이 없습니다.";
        }

        if (asrRobot != null) {
            asrRobot.speak(TtsRequest.create(response, false));
        }
    }
}
