package com.temi.rhythmgame;

import android.os.Bundle;
import android.content.Intent;
import android.graphics.drawable.AnimationDrawable;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

public class TestMenuActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_menu);

        View root = findViewById(R.id.testMenuRoot);
        AnimationDrawable background = (AnimationDrawable) root.getBackground();
        background.setEnterFadeDuration(2000);
        background.setExitFadeDuration(4000);
        background.start();
        findViewById(R.id.testMenuBack).setOnClickListener(view -> finish());
        findViewById(R.id.fallTestButton).setOnClickListener(view -> openTest("fall"));
        findViewById(R.id.fallCallTestButton).setOnClickListener(view -> openTest("fall_call"));
        findViewById(R.id.rhythmTestButton).setOnClickListener(view -> openTest("rhythm"));
    }

    private void openTest(String mode) {
        Intent intent = new Intent(this, TestCameraActivity.class);
        intent.putExtra("mode", mode);
        startActivity(intent);
    }
}
