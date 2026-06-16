package org.techtown.temi_test;

public class AlarmFlowController {

    public static final String LOCATION_BEDROOM = "안방";
    public static final String LOCATION_LIVING_ROOM = "거실";
    public static final String LOCATION_HOME_BASE = "홈 베이스";

    public interface Listener {
        void onGoToRequested(String location);

        void onSpeakRequested(String text);

        void onStartRhythmGameRequested();

        void onReturnHomeBaseRequested();

        void onCallGuardianRequested(String reason);
    }

    private final Listener listener;

    public AlarmFlowController(Listener listener) {
        this.listener = listener;
    }

    public void onExerciseAlarm() {
        // TODO: 실제 알람 스케줄러가 추가되면 호출합니다.
        // 운동 알람 -> 지정 위치 이동 -> 음성 안내 -> 화면 클릭 대기 -> 게임 시작/미응답 복귀.
        listener.onGoToRequested(LOCATION_LIVING_ROOM);
        listener.onSpeakRequested("운동 시간입니다. 화면을 눌러 리듬게임을 시작해주세요.");
    }

    public void onMedicationAlarm() {
        // TODO: 실제 알람 스케줄러가 추가되면 호출합니다.
        // 복약 알람 -> 지정 위치 이동 -> 60초 확인 대기 -> 미응답 시 보호자 연결.
        listener.onGoToRequested(LOCATION_BEDROOM);
        listener.onSpeakRequested("약 드실 시간입니다. 복약 후 확인 버튼을 눌러주세요.");
    }

    public void onExerciseNoResponse() {
        listener.onSpeakRequested("대기 장소로 복귀합니다.");
        listener.onReturnHomeBaseRequested();
    }

    public void onMedicationNoResponse() {
        listener.onSpeakRequested("응답이 없어 보호자에게 연락합니다.");
        listener.onCallGuardianRequested("복약 알람 미응답");
    }

    public void onLowBattery() {
        listener.onSpeakRequested("배터리가 15% 이하입니다. 홈 베이스로 복귀합니다.");
        listener.onReturnHomeBaseRequested();
    }
}
