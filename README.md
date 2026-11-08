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
