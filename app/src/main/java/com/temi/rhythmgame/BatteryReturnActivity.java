package com.temi.rhythmgame;

import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;

public class BatteryReturnActivity extends AppCompatActivity implements Robot.TtsListener {

    private static final String TAG = "BatteryReturnActivity";
    private Robot robot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_battery_return);

        // 배경 애니메이션 실행 코드
        ConstraintLayout rootLayout = findViewById(R.id.rootLayout);
        if (rootLayout != null && rootLayout.getBackground() instanceof AnimationDrawable) {
            AnimationDrawable animationDrawable = (AnimationDrawable) rootLayout.getBackground();
            animationDrawable.setEnterFadeDuration(2000);
            animationDrawable.setExitFadeDuration(4000);
            animationDrawable.start();
        }

        // 화면 켜짐 유지
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        robot = Robot.getInstance();

        // ⭐️ 2. TTS 리스너 등록 (내가 말 끝나는 걸 듣겠다!)
        robot.addTtsListener(this);

        // 3. TTS로 말하기 시작
        robot.speak(TtsRequest.create("배터리가 부족하여 충전소로 복귀합니다.", false));
    }

    // ⭐️ 4. TTS 상태가 변할 때마다 호출되는 콜백 메서드
    @Override
    public void onTtsStatusChanged(TtsRequest ttsRequest) {
        // 방금 한 말이 '성공적으로 끝났는지(COMPLETED)' 확인
        if (ttsRequest.getStatus() == TtsRequest.Status.COMPLETED) {
            Log.d(TAG, "음성 안내 완료 -> 홈 베이스로 복귀 시작");

            // 💡 주의: 테미 기기에 저장된 장소 이름과 대소문자/띄어쓰기까지 100% 똑같아야 합니다.
            // 안 간다면 "home base" 인지 "Home Base" 인지 테미 설정화면에서 꼭 확인해 보세요!
            robot.goTo("home base");

            // 한 번 명령을 내렸으므로 리스너 해제 (중복 호출 방지)
            robot.removeTtsListener(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ⭐️ 안전장치: 액티비티가 종료될 때 리스너를 꼭 지워줍니다.
        if (robot != null) {
            robot.removeTtsListener(this);
        }
    }
}
