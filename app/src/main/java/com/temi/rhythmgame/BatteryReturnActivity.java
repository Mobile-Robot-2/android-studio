package com.temi.rhythmgame;

import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;

public class BatteryReturnActivity extends AppCompatActivity {

    private Robot robot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_battery_return);

        // 화면 켜짐 유지
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        robot = Robot.getInstance();

        // 1. TTS로 말하기
        robot.speak(TtsRequest.create("배터리가 부족하여 충전소로 복귀합니다.", false));

        // 2. 홈 베이스로 복귀 명령
        robot.goTo("홈베이스");
    }
}
