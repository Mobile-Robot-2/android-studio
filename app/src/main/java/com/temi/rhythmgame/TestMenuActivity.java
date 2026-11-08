package com.temi.rhythmgame;

import android.os.Bundle;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;

public class TestMenuActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_menu);

        findViewById(R.id.fallTestButton).setOnClickListener(view -> openTest("fall"));
        findViewById(R.id.rhythmTestButton).setOnClickListener(view -> openTest("rhythm"));
    }

    private void openTest(String mode) {
        Intent intent = new Intent(this, TestCameraActivity.class);
        intent.putExtra("mode", mode);
        startActivity(intent);
    }
}
