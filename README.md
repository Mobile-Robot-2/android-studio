# Temi 앱과 pose-game 테스트 서버 실행

이 프로젝트는 Temi에서 실행하는 Android 앱입니다. 낙상 감지와 리듬게임의 인식 테스트를 하려면 노트북에서 별도의 `pose-game` 테스트 서버도 실행해야 합니다.

## 1. Temi 연결 확인

Temi와 노트북을 연결한 뒤 Android Studio의 **Terminal**에서 다음 명령을 실행합니다.

```text
adb devices -l
```

목록에서 Temi의 기기 ID와 `device` 상태를 확인합니다. 이후 명령의 `<Temi의_기기_ID>`를 목록에 나온 값으로 바꾸고 꺾쇠괄호는 입력하지 않습니다. 예를 들어 무선 연결에서는 `192.168.0.10:5555` 같은 형식일 수 있습니다.

## 2. 기존 앱 삭제가 필요한 경우

기존 앱과 같은 서명키로 설치한다면 보통 삭제할 필요가 없습니다. 설치할 때 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`과 같이 **서명이 다르다는 오류**가 발생하면, Temi에 설치된 기존 앱을 삭제한 뒤 다시 설치하세요.

**앱을 삭제하면 해당 앱의 로컬 데이터와 설정도 삭제됩니다.** 필요한 데이터가 있다면 먼저 확인하세요. ADB 연결을 삭제하는 것과 앱을 삭제하는 것은 다릅니다.

```text
adb -s <Temi의_기기_ID> uninstall com.temi.rhythmgame
```

`Success`가 표시되면 Android Studio에서 Temi를 선택하고 **Run**을 눌러 앱을 다시 설치합니다. ADB에 기기가 여러 개 연결된 경우에도 위처럼 ID를 지정해야 합니다.

## 3. pose-game 테스트 서버 실행

VS Code에서 별도로 받은 `pose-game` 프로젝트를 열고 **Command Prompt** 터미널을 실행합니다. 터미널의 현재 위치가 `pose-game` 폴더인지 확인하세요.

처음 한 번 가상환경을 만들고 패키지를 설치합니다.

```cmd
python -m venv .venv
.venv\Scripts\activate.bat
python -m pip install -r requirements.txt
```

다음부터는 새 터미널에서 가상환경을 활성화하고 테스트 서버를 실행합니다.

```cmd
.venv\Scripts\activate.bat
python -m uvicorn test_server:app --host 0.0.0.0 --port 8000
```

서버 실행 중에는 해당 터미널을 열어 두세요. 노트북에서 [http://127.0.0.1:8000/docs](http://127.0.0.1:8000/docs)가 열리는지 확인할 수 있습니다. 기존 서버가 8000번 포트를 사용 중이라면 먼저 종료하세요. `models/pose_landmarker_lite.task` 모델 파일도 필요합니다.

## 4. 앱과 서버 연결

1. 노트북의 별도 터미널에서 `ipconfig`를 실행하고 Wi-Fi의 IPv4 주소를 확인합니다.
2. Android 프로젝트의 `app/src/main/java/com/temi/rhythmgame/ServerConfig.java`에서 `BASE_URL`을 `http://노트북의_IPv4_주소:8000`으로 설정합니다.
3. Android Studio에서 앱을 다시 빌드하고 Temi에 설치합니다. 인식 테스트 화면에서 낙상 감지 또는 리듬게임 테스트를 선택합니다.

Temi에서 `localhost`는 노트북이 아니라 Temi를 가리킵니다. 연결되지 않으면 서버 실행 여부, IP 주소, 방화벽 및 네트워크의 기기 간 통신 허용 여부를 확인하세요. 테스트 서버는 Firebase에 결과를 저장하지 않습니다.

자세한 서버 설정과 기존 서비스 서버 실행 방법은 `pose-game` 프로젝트의 `README.md`를 참고하세요.

## 5. 낙상 감지 및 보호자 연결 시간 실험

테스트 서버를 실행하고 Temi의 **인식 테스트 → 낙상 감지 및 보호자 연결 테스트**를 선택합니다. 이 메뉴는 등록된 관리자에게 실제 영상통화를 요청합니다. 기존 낙상 감지 테스트에서는 통화를 요청하지 않습니다.

1. 한 시행마다 테스트 메뉴에서 보호자 연결 테스트를 새로 엽니다. 새 화면마다 고유한 `session_id`가 발급됩니다. 화면에 ID 앞 8자리가 표시됩니다. 참가자 ID, 행동 종류와 이 ID를 실험 기록에 연결합니다.
2. 실험자가 실제 낙상 시점에 **낙상 시점 표시 (T0)** 버튼을 누릅니다. 버튼의 시간은 관찰자의 입력 시간이며 실제 낙상 시각과 차이가 날 수 있습니다. 더 정확한 T0가 필요하면 영상 등 독립적인 기준으로 확인해야 합니다.
3. 낙상 확정 응답을 처음 받은 시점은 T1로 기록됩니다. 신뢰할 수 있는 낙상 확정이 연속 두 번 수신되면 보호자 영상통화 요청을 한 번 시도합니다. T2는 해당 SDK 요청 시각이며, 보호자가 통화에 응답하거나 연결된 시각이 아닙니다.
4. 통화 이후에는 메뉴로 돌아가 새 시행을 시작합니다. 낙상이 감지되지 않거나 보호자 정보가 없으면 T1 또는 T2가 없을 수 있습니다. 실패를 0초로 계산하지 말고 별도로 기록합니다.

시행별 이벤트는 Temi의 앱 전용 저장소에 `fall_call_events.csv`로 누적됩니다. ADB로 Temi를 연결한 뒤 노트북 터미널에서 다음을 실행하세요. `<Temi의_기기_ID>`는 `adb devices`에서 확인한 값으로 바꿉니다.

```text
adb -s <Temi의_기기_ID> pull /sdcard/Android/data/com.temi.rhythmgame/files/fall_call_events.csv .
```

CSV 열은 `session_id,event,wall_time_epoch_ms,elapsed_realtime_ms`입니다. 같은 세션 내 T0·T1·T2의 `elapsed_realtime_ms`를 이용해 `(T1-T0)/1000`, `(T2-T0)/1000`을 초 단위로 계산합니다. `T0_OBSERVER_MARK`는 관찰자 표시, `T1_FALL_CONFIRMED_RECEIVED`는 Android의 첫 확정 응답 수신, `T2_CALL_REQUESTED`는 SDK 요청 시각입니다. `T2_CALL_REQUEST_FAILED`는 요청 실패이며 성공 기록으로 계산하지 않습니다. T0가 T1보다 늦게 표시된 시행은 수동 표시 오차를 확인하세요. 시간 측정에 사용된 단조 시계 값은 기기 재부팅 간 직접 비교하지 않습니다.

저장 실패 시 화면에 안내하고 Logcat의 `FallCallTest` 태그에도 이벤트를 남깁니다. 앱을 삭제하면 이 CSV도 삭제되므로 **앱 삭제 전에 파일을 복사**하세요.

## 6. 테스트 화면의 프레임 처리 시간 측정

테스트 앱을 새로 빌드해 Temi에 설치한 후, Android Studio 터미널에서 아래 명령을 실행합니다. `<Temi의_기기_ID>`는 `adb devices`에서 확인한 값으로 바꾸고, 꺾쇠괄호는 입력하지 않습니다.

```cmd
adb -s <Temi의_기기_ID> logcat -v threadtime -s PoseTiming:I *:S > pose-timing.txt
```

명령을 실행한 상태에서 Temi의 낙상 또는 리듬게임 테스트를 진행하고, 끝나면 터미널에서 `Ctrl+C`를 누릅니다. `pose-timing.txt`가 터미널의 현재 폴더에 저장됩니다. 테스트 화면을 다시 열면 새 `session`이 생성됩니다. Logcat에 남아 있던 이전 기록도 파일에 포함될 수 있으므로 실험 시간과 세션을 확인하세요. 영상이나 관절 좌표는 이 로그에 저장되지 않습니다.

- `request_interval_ms`: 앱에서 두 프레임 요청을 시작한 간격. 첫 요청은 이전 요청이 없어 `-1`입니다.
- `conversion_ms`: 앱이 프레임을 JPEG로 변환하는 데 걸린 시간.
- `round_trip_ms`: 앱이 요청을 시작한 뒤 응답을 읽고 처리하기까지의 시간. 네트워크와 서버 처리가 함께 포함됩니다.
- `total_ms`: 앱의 프레임 처리 시작부터 응답 처리 완료까지의 시간.
- `result`: HTTP 응답 상태, 네트워크 오류 또는 응답 처리 오류. 영상 변환 오류는 `conversion_error`로 별도 기록합니다.

낙상 서버의 실제 수신 간격과 추론 시간을 함께 보려면 VS Code의 **Command Prompt**에서 서버를 실행하기 전에 `set FALL_DEBUG=csv`를 입력합니다. `pose-game/data/fall_debug` 아래의 `frames.csv`에서 `session_id`, `frame_interval_ms`, `inference_ms`, `handler_ms`를 확인합니다. 서버 CSV 기록은 낙상 테스트용이며, 사진 없이 기록합니다. 리듬게임의 서버 추론 시간은 이 CSV에 기록되지 않습니다.

대시보드 사용 여부와 사진 저장 설정에 따라 부하가 달라질 수 있으므로 비교할 때 조건을 함께 기록하세요. 진단 기록도 처리에 약간의 부담을 줄 수 있습니다.
