package com.temi.rhythmgame;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

/**
 * StartActivity - 앱의 시작 화면
 * "리듬게임 시작!" 버튼을 클릭하면 PrepareActivity로 이동합니다.
 */
public class StartActivity extends AppCompatActivity {

    private static final String TAG = "StartActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start);
        Log.d(TAG, "StartActivity 생성됨");

        Button btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v -> {
            Log.d(TAG, "시작 버튼 클릭 → PrepareActivity로 전환");
            startActivity(new Intent(StartActivity.this, PrepareActivity.class));
        });
    }
}
