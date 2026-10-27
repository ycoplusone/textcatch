# 프로젝트 규칙 — textcatch (SMS/MMS 즉시 캐치 & API 전송)

안드로이드 스튜디오 없이 CLI로 개발하는 안드로이드 앱 프로젝트다.
SMS/MMS를 수신하면 **즉시 캐치**하고 REST API URL로 실시간 전송한다.

## 환경

- Windows 11 / PowerShell 기준
- AGP 9.3.1, Gradle 9.8.0(wrapper), compileSdk 36, minSdk 24, targetSdk 36
- AGP 9는 Kotlin 지원이 내장이므로 org.jetbrains.kotlin.android
  플러그인을 추가하지 않는다 (추가하면 빌드 실패)
- JDK: 이 PC에 JDK 17이 없어 **Android Studio JBR(JDK 25)** 로 빌드한다.
  `gradle.properties` 의 `org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr` 로 고정.
- 전송은 **WorkManager** 로 처리한다 (백그라운드 실행 보장 + 재시도/백오프 + 네트워크 복구 대기).

## 필수 권한 (AndroidManifest.xml)

```xml
<!-- SMS/MMS 수신 -->
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.RECEIVE_MMS" />

<!-- 기존 메시지 접근 (MMS 이미지) -->
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.READ_MMS" />

<!-- MMS(WAP Push) 수신 -->
<uses-permission android:name="android.permission.RECEIVE_WAP_PUSH" />

<!-- 기기 전화번호 읽기 (수신자) -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.READ_PHONE_NUMBERS" />

<!-- 네트워크 -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Foreground Service (WorkManager expedited 대비) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

> ⚠️ **평문 HTTP 주의:** targetSdk 28+ 는 http(평문) 을 기본 차단한다.
> API 서버가 http 이면 `res/xml/network_security_config.xml` 에 해당 도메인을
> cleartext 허용으로 등록하고 매니페스트 `application` 에 연결해야 한다.
> (현재 `enfish.duckdns.org` 등록됨)

## 핵심 컴포넌트

- **SMSReceiver (BroadcastReceiver)**: SMS 수신 즉시 감지 (SMS_RECEIVED)
- **MmsReceiver (SMSReceiver 상속)**: MMS(WAP_PUSH_RECEIVED) 감지.
  같은 클래스를 매니페스트에 중복 선언할 수 없어 별도 클래스로 등록 (권한이 서로 다름)
- **Forwarder (object)**: SMS 는 `forwardSms` 로 **선저장(inflight 파일) 후** 직접 POST 1회(goAsync, 최대 ~8초) →
  성공이면 inflight 삭제, 실패(일시/4xx)면 RetryQueue(지연 대기열)로. MMS/HEARTBEAT 는 바로 큐잉 (enqueueSms / enqueueMms / enqueueHeartbeat)
- **RetryQueue (object)**: 파일 보관소 (filesDir 아래, 모두 임시파일→rename 원자적 쓰기). **메시지는 성공 전엔 삭제하지 않는다.**
  - `inflight/` 직접 전송 중 / `retry_queue/` 재발송 대기 / `hold/` 보류 / `corrupt/` 손상 격리
  - `recoverInflight`: 1분 넘게 남은 inflight(전송 중 프로세스 사망) → 대기열로 복구. `ensureScheduled` 가 호출
    (앱 화면 진입 / SMS 수신 / heartbeat 알람 / 부팅)
  - 재발송 간격 1→2→4→…최대 600초 (대기열 공통 단계, 비면 초기화). `flushNow` = "지연메세지발송" 버튼
  - 보류: 4xx 가 6시간, 일시 실패가 24시간 넘게 계속되면 `hold/` 로 (30일 보관). `releaseHold` = "보류메세지재발송" 버튼
  - `status()` = heartbeat 에 싣는 대기/보류/손상 현황. payload 의 `_` 로 시작하는 키는 내부용(Api.post 가 제거)
- **RetryWorker (CoroutineWorker)**: 대기 중인 모든 건을 오래된 순으로 일괄 재발송. 16초 이하 간격은 워커 내부 대기,
  그 이상은 지연 작업 예약. 네트워크 예외 시 라운드 중단. 대기열 진입(`_since`) 후 4xx 6시간 / 일시 실패 24시간 초과 시 보류
- **MmsStore (object)**: content://mms 조회 공용부 — 미전송 MMS id(`_id > last_mms_id`, m_type=132, 오래된 순),
  다운로드 대기(m_type=130), MMS msg_id(`mms-{device_id}-{_id}-{date}` 고정값), heartbeat 용 MMS 현황
- **WatchdogWorker (주기 작업 15분)**: `RetryQueue.ensureScheduled`(중단 전송 복구) + `Forwarder.sweepMms`(미전송 MMS 전송).
  `Forwarder.scheduleWatchdog` 로 앱 실행/부팅 시 예약(KEEP)
- **Api (object)**: OkHttp POST + 응답 판정(성공/영구 실패/일시 실패) + payload 공통 필드. 직접 전송과 워커가 공용
- **ForwardWorker (CoroutineWorker)**: 데이터 추출 → REST API POST.
  MMS 는 content provider 폴링, 콘텐츠 미도착 시 `Result.retry()` (20초 선형 백오프, 최대 30회).
  MMS 체인은 직렬이므로 콘텐츠 대기는 수신 후 10분(다운로드 중 m_type=130 이 있으면 60분)까지만 하고 종료
  (늦게 다운로드된 건은 정기 점검이 저장소에서 찾아 보냄).
  **MMS 는 건너뛰지 않는다**: 항상 저장소의 가장 오래된 미전송 건부터 보내고(첫 설치 때만 최근 5분 제한),
  보낸 뒤 남은 미전송 건이 있으면 정리 작업(sweep)을 이어 붙인다. 파트가 비어 있으면 잠시 기다린 뒤 빈 본문으로라도 전송.
  `Forwarder.sweepMms` = 체인이 멈췄으면 kickMms, 비어 있고 미전송 MMS 가 있으면 정리 작업 시작
  (WatchdogWorker 15분 / heartbeat 알람 / 부팅 / SMS 수신 / 앱 화면 진입 시).
  `Forwarder.kickMms` = 다음 실행이 15분 넘게 밀린 체인을 취소하고 정리 작업(sweep, 24시간 내 미전송 MMS 를
  한 건씩 이어서 전송)으로 대체 — MMS 수신 시/앱 화면 진입 시 자동, "지연메세지발송" 버튼은 무조건 실행
- **LogStore (object)**: 송신 이력 저장 (SharedPreferences, "시간 + 제목 10글자", 최신순 최대 300건)
- **LogActivity**: 송신 이력 화면 (새로고침 / 지우기)
- **MainActivity**: 설정 UI & 테스트 (API URL 설정, 권한 요청, 테스트 전송, 로그 보기,
  앱 버전 표기, 전송 대기열 실시간 표시 — 발송 대기/재시도 대기/전송 중/순서 대기)
- **Utils (object)**: API URL 저장/조회, 기기 전화번호(수신자) 추출

> 참고: 이전의 `SMSForwardService (IntentService)` 는 제거되었고 WorkManager 로 대체되었다.

## 데이터 흐름

```
SMS/MMS 수신 (안드로이드 시스템)
    ↓
SMSReceiver / MmsReceiver 트리거 (onReceive)
    ↓
SMS: Forwarder.forwardSms → 직접 POST (path=direct) → 성공/4xx 면 끝, 일시 실패면 ↓
MMS: Forwarder.enqueueMms  (WorkManager 큐잉)
    ↓
ForwardWorker.doWork():
  - SMS: intent 데이터로 즉시 payload 구성
  - MMS: content://mms 를 1.5s x6회 폴링(콘텐츠 도착 대기) 후 파싱
      · 발신자(sender) / 수신자(receiver, 기기 번호) / 내용(body)
      · 이미지(images - Base64)
      · 콘텐츠 미도착 시 Result.retry()
    ↓
REST API 호출 (POST) — OkHttp
    ↓
성공: LogStore 에 이력 저장 → Result.success()
일시 실패(네트워크 예외 / 408 / 429 / 5xx): RetryQueue 로 이동 → RetryWorker 가 대기 건 전부를
  1→2→4→…→600초(상한) 간격으로 일괄 재발송. 메인 화면 "지연메세지발송" 버튼으로 즉시 일괄 발송
  새 메시지 전송이 성공하면(서버 복구) 대기 중인 지연 메시지도 즉시 일괄 발송 (RetryQueue.onServerReachable)
4xx(408/429 제외): 역시 RetryQueue 로 → 6시간 재시도 후에도 4xx 면 hold/ 로 보류 (삭제 안 함)
일시 실패 24시간 초과: hold/ 로 보류. 보류 건수는 heartbeat status 로 서버에 보고, "보류메세지재발송" 버튼으로 재전송
  → LogStore 에 "[실패 코드 보류] 제목" 기록 → MMS 는 큐잉 시 last_mms_id 넘김
MMS 콘텐츠 대기 한도(30회 / 10~60분) 초과: 종료 → Result.success() (MMS 체인이 막히지 않도록 failure 를 쓰지 않음)
```

## 설정 (build.gradle.kts에 추가)

```gradle
dependencies {
    // OkHttp (REST API 호출)
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // WorkManager (백그라운드 보장 전송 + 재시도/백오프 + 네트워크 복구 대기)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
```

## API 명세 (서버가 받을 데이터)

- 모든 전송에 지연 분석/중복 제거용 필드 포함:
  `msg_id`(메시지 고유값, 직접 전송→워커 재전송 시 동일 → **서버는 msg_id 로 중복 제거**),
  `received_at`(폰 수신 시각 ms, `timestamp` 는 SMS 의 경우 통신사 SMSC 접수 시각),
  `sent_at`(이번 전송 시각), `attempt`(시도 횟수, 1부터), `path`(`direct` | `worker`)
- 모든 전송(SMS/MMS/HEARTBEAT)에 `device_id`(ANDROID_ID, `Utils.getDeviceId`) 포함.
  한 기기의 여러 번호를 같은 기기로 묶는 용도. 공장 초기화·APK 서명 키 변경 시 값이 바뀐다.
- 선저장 복구로 같은 메시지가 다시 올 수 있다(서버는 받았는데 폰이 기록 삭제 전 종료) → **msg_id 중복 제거 필수**
- HEARTBEAT 에는 `status` 포함 — `hold_count > 0` / `corrupt_count > 0` / `hold_dropped > 0` 이면 서버에서 경보:
  ```json
  "status": { "retry_count": 0, "inflight_count": 0, "hold_count": 2, "hold_oldest_received_at": 1695123456789,
              "hold_reasons": {"404": 2}, "hold_dropped": 0, "corrupt_count": 0,
              "mms_unsent": 0, "mms_pending_download": 1, "mms_pending_oldest": 1695123000000 }
  ```
  `mms_unsent` 가 계속 0 이 아니면 MMS 전송이 막힌 것, `mms_pending_download` 는 최근 7일 내 폰에 다운로드되지 않은
  MMS 알림 수 (자동 다운로드 꺼짐/실패 → 내용이 없어 보낼 수 없음 → 기기 점검 대상)
- MMS 는 `msg_id` 가 `mms-{device_id}-{_id}-{date}` 로 고정 (어느 작업이 보내도 같은 값), `timestamp` 는 MMS 저장소 date,
  `images_failed`(읽지 못한 이미지 파트 수), `attachments`(본문/이미지 외 파트 mime 목록, 내용은 미포함) 포함

### SMS 예시
```json
{
  "type": "SMS",
  "sender": "01012345678",
  "receiver": "01099999999",
  "device_id": "a1b2c3d4e5f60789",
  "body": "Hello World",
  "timestamp": 1695123456789,
  "msg_id": "3f1c2a9e-...", "received_at": 1695123457012, "sent_at": 1695123457230,
  "attempt": 1, "path": "direct"
}
```

### MMS 예시
```json
{
  "type": "MMS",
  "sender": "01012345678",
  "receiver": "01099999999",
  "device_id": "a1b2c3d4e5f60789",
  "body": "Check this image",
  "images": [
    {
      "mime_type": "image/jpeg",
      "data": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAA..." // Base64 인코딩
    }
  ],
  "timestamp": 1695123456789,
  "msg_id": "7b0d4e12-...", "received_at": 1695123456789, "sent_at": 1695123466120,
  "attempt": 1, "path": "worker"
}
```

## 명령

### 빌드 & 설치
```bash
# 빌드
.\gradlew.bat assembleDebug

# 설치
.\gradlew.bat installDebug

# 한번에 (빌드 + 설치 + 실행)
.\gradlew.bat installDebug && adb shell am start -n com.enfish.textcatch/.MainActivity
```

### 실기기 설치 스크립트 (USB 연결 → 설치 + 권한 설정)
```bash
# 먼저 빌드 (.\gradlew.bat assembleDebug) 후 실행
scripts\install.bat                     # 기기 1대 연결 시
scripts\install.bat -Serial <시리얼>     # 여러 대 연결 시 (adb devices 로 확인)
scripts\install.bat -Apk D:\경로\app-debug.apk   # 다른 APK 지정
```
- `scripts\setup.bat` : PowerShell 없이 cmd 만으로 같은 폴더의 `app-debug.apk` 설치 →
  설치 확인(versionName/versionCode/lastUpdateTime) → 권한 설정(appops 제한 해제 + pm grant 6개). CRLF + UTF-8(chcp 65001) 유지
- `install.bat` 은 실행 정책 우회(`-ExecutionPolicy Bypass`)로 같은 폴더의 `install.ps1` 을 실행하는 래퍼
- 수행 순서: 기기 확인 → `adb install -r` → 제한된 설정 해제(appops) → `pm grant` 7개 → 권한 상태 출력 → 앱 실행
- 샤오미에서 `INSTALL_FAILED_USER_RESTRICTED` 면 APK 를 `/sdcard/Download/textcatch.apk` 로 복사하고,
  폰에서 직접 설치할 때까지 최대 5분 대기 후 이어서 진행. 앱 정보 화면을 열고 수동 설정(자동 시작/배터리/기타 권한) 안내
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`(서명 다름) 은 데이터 보호를 위해 자동 삭제하지 않고 중단
- `pm grant` 실패 항목(샤오미 보안 설정 꺼짐 등)은 앱의 권한 요청 버튼으로 허용
- APK 기본 경로는 `app\build\outputs\apk\debug\app-debug.apk`.
  `scripts` 폴더만 다른 PC 로 복사해 쓸 때는 APK 를 같은 폴더에 두고
  `install.ps1` 의 `$Apk` 기본값을 주석 처리된 `$PSScriptRoot\app-debug.apk` 줄로 바꾼다
- `install.ps1` 은 Windows PowerShell 5.1 한글 출력을 위해 **UTF-8 BOM** 으로 저장해야 한다 (편집 후 BOM 유지 확인)

### 에뮬레이터(AVD) 실행 스크립트
```bash
scripts\avd.bat                  # AVD 실행 + 부팅 대기 (이미 실행 중이면 재사용)
scripts\avd.bat -Install         # + APK 설치 + pm grant 7개 + 앱 실행 (먼저 assembleDebug)
scripts\avd.bat -Cold            # 스냅샷 무시하고 콜드 부팅 (default_boot 로드 실패 시)
scripts\avd.bat -Avd <이름>       # AVD 지정 (기본: -list-avds 첫 번째, 현재 Small_Phone)
```
- SDK 경로는 `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `%LOCALAPPDATA%\Android\Sdk` 순으로 찾는다
- `avd.ps1` 도 **UTF-8 BOM** 으로 저장 (install.ps1 과 동일)

### SMS/MMS 테스트 (에뮬레이터)

```bash
# 기기 확인
adb devices

# SMS 수신 테스트
telnet localhost 5554
sms send 01012345678 "Test SMS message 1"
sms send 01088888888 "Test SMS message 2"
quit
```

### 로그 & 디버깅

```bash
# 실시간 로그 (전체)
adb logcat

# 필터링된 로그 (수신 감지)
adb logcat -s SMSReceiver:D

# 필터링 (전송 워커)
adb logcat -s ForwardWorker:D

# 수신 + 전송 한번에
adb logcat -s SMSReceiver:D ForwardWorker:D

# 크래시 로그
adb logcat -d -b crash

# 앱 실행
adb shell am start -n com.enfish.textcatch/.MainActivity

# 앱 강제 종료
adb shell am force-stop com.enfish.textcatch

# 앱 프로세스 상태
adb shell ps | grep textcatch
```

### 권한 부여 (런타임)

```bash
# SMS 수신 권한
adb shell pm grant com.enfish.textcatch android.permission.RECEIVE_SMS

# MMS 수신 권한
adb shell pm grant com.enfish.textcatch android.permission.RECEIVE_MMS

# SMS 읽기 (MMS 이미지용)
adb shell pm grant com.enfish.textcatch android.permission.READ_SMS
adb shell pm grant com.enfish.textcatch android.permission.READ_MMS

# 전화번호 읽기
adb shell pm grant com.enfish.textcatch android.permission.READ_PHONE_STATE

# 인터넷
adb shell pm grant com.enfish.textcatch android.permission.INTERNET

# Foreground Service
adb shell pm grant com.enfish.textcatch android.permission.FOREGROUND_SERVICE
```

### 권한 확인

```bash
# 부여된 권한 확인
adb shell dumpsys package com.enfish.textcatch | grep permission

# 또는 앱 설정에서 직접 확인
adb shell cmd appops list | grep textcatch
```

## 프로젝트 구조

```
app/
├── src/main/
│   ├── kotlin/com/enfish/textcatch/
│   │   ├── MainActivity.kt              (설정 UI + 로그 보기)
│   │   ├── LogActivity.kt               (송신 이력 화면)
│   │   ├── SMSReceiver.kt               (BroadcastReceiver - SMS 수신 감지, open class)
│   │   ├── MmsReceiver.kt               (SMSReceiver 상속 - MMS/WAP_PUSH 감지)
│   │   ├── Forwarder.kt                 (SMS 직접 전송 forwardSms + WorkManager 큐잉)
│   │   ├── Api.kt                       (OkHttp POST + 응답 판정 + payload 공통 필드)
│   │   ├── ForwardWorker.kt             (CoroutineWorker - 파싱 + API POST + 재시도)
│   │   ├── LogStore.kt                  (송신 이력 저장 - SharedPreferences)
│   │   └── Utils.kt                     (API URL 저장/조회, 전화번호 추출)
│   ├── AndroidManifest.xml              (권한 + Receiver 2개 + Activity 2개)
│   └── res/
│       ├── layout/activity_main.xml     (설정 UI 레이아웃)
│       ├── layout/activity_log.xml      (송신 이력 레이아웃)
│       └── xml/network_security_config.xml  (평문 http 허용 도메인)
└── build.gradle.kts

scripts/
├── install.bat                          (실행 래퍼 - install.ps1 호출)
├── install.ps1                          (USB 설치 + 권한 설정, UTF-8 BOM)
├── avd.bat                              (실행 래퍼 - avd.ps1 호출)
└── avd.ps1                              (AVD 실행 + 부팅 대기 + 선택적 설치, UTF-8 BOM)
```

## 작업 규칙

### 코드 수정
- 코드를 수정하면 반드시 `assembleDebug`로 빌드해서 성공을 확인한다
- 빌드 오류가 나면 오류 메시지를 읽고 스스로 수정을 시도한다

### 버전 증가 (빌드할 때마다)
- 코드/리소스를 수정하고 빌드할 때마다 `app/build.gradle.kts` 의 버전을 올린다
  - `versionName` 을 **0.1 증가** (1.1 → 1.2 → … → 1.9 → 2.0)
  - `versionCode` 를 **1 증가** (설치 시 업데이트로 인식되도록)
- 같은 변경에 대해 빌드 오류를 고치며 다시 빌드하는 경우는 한 번만 올린다
- 문서(CLAUDE.md 등)/스크립트만 수정해 앱 빌드가 필요 없는 경우는 올리지 않는다
- 현재: versionName 2.6 / versionCode 17

### BroadcastReceiver 수정
- SMSReceiver.kt / MmsReceiver.kt 를 수정하면 반드시 `installDebug`로 다시 설치한다
- (APK를 다시 설치하지 않으면 수신 이벤트가 반영 안 됨)
- SMSReceiver 는 MmsReceiver 가 상속하므로 반드시 `open class` 로 유지

### 전송 로직(Worker) 수정
- Forwarder.kt / ForwardWorker.kt 를 수정하면 `assembleDebug` 후 `installDebug`
- 전송/파싱/재시도 로직은 ForwardWorker 에, 큐잉 정책(제약·백오프)은 Forwarder 에 둔다
- MMS 파싱 이슈는 ForwardWorker 의 폴링(MMS_POLL_*) 값 조정으로 대응

### 권한 수정
- AndroidManifest.xml에서 권한을 추가/제거하면:
  1. `assembleDebug`로 빌드
  2. `installDebug`로 설치
  3. `adb shell pm grant` 명령어로 개별 권한 부여

### API URL 설정
- MainActivity 에서 입력 → `Utils.setApiUrl()` 로 SharedPreferences 에 저장
- 기본값은 `Utils.DEFAULT_API_URL` 에 하드코딩 (현재 `http://enfish.duckdns.org:5000/api/textcatch`)
- 새로 http 도메인으로 바꾸면 network_security_config.xml 에도 도메인 추가 필요

### 송신 이력 로그
- 전송 성공 시 `LogStore.add()` 로 "시간 + 제목 10글자" 저장
- 앱의 "로그 보기" 버튼 → LogActivity 에서 확인/지우기

### 테스트 순서
1. 빌드 및 설치
2. 권한 부여 (모든 권한)
3. Logcat 켜기 (`adb logcat -s SMSReceiver:D ForwardWorker:D`)
4. telnet으로 SMS 전송 (또는 앱의 "테스트 전송")
5. Logcat 및 앱 "로그 보기"에서 확인
6. API 서버에서 요청 수신 확인

### 의존성 추가
- 새 라이브러리를 추가할 때는 이유를 먼저 설명한다
- 예: "이미지 처리를 위해 Glide 추가"

## 테스트 체크리스트

```
[ ] 빌드 성공 (assembleDebug)
[ ] 설치 성공 (installDebug)
[ ] 앱 실행 가능
[ ] SMS 권한 부여됨
[ ] MMS 권한 부여됨
[ ] INTERNET 권한 부여됨
[ ] READ_PHONE_STATE 권한 부여됨
[ ] Logcat에서 "SMSReceiver 트리거" 확인
[ ] SMS 테스트 메시지 전송
[ ] ForwardWorker 로그 확인 ("API 전송 →", "API 응답 code=")
[ ] REST API 요청 수신 확인 (서버 로그)
[ ] 발신자 번호 올바름
[ ] 수신자 번호 올바름
[ ] 메시지 내용 올바름
[ ] MMS 폴링 후 이미지 Base64 인코딩 확인 ("MMS 대기중..." 로그)
[ ] 서버 일시 중단 시 재시도 동작 확인 ("재시도 예정" 로그)
[ ] 앱 "로그 보기"에 송신 이력(시간+제목) 쌓임
```

## 배포

### APK 서명
```bash
# 키스토어 생성 (처음 한 번만)
keytool -genkey -v -keystore my-release-key.keystore ^
  -keyalg RSA -keysize 2048 -validity 10000 ^
  -alias my-key-alias

# build.gradle.kts에 설정 후:
.\gradlew.bat assembleRelease

# 결과: app/build/outputs/apk/release/app-release.apk
```

### 기기에 설치
```bash
adb install app-release.apk
```

## 트러블슈팅

### ❌ "SMS 수신 안 됨"
```
→ SMSReceiver가 AndroidManifest.xml에 등록되었는지 확인
→ 권한이 부여되었는지 확인 (adb shell pm list permissions)
→ 에뮬레이터에서 telnet으로 테스트
→ Logcat에 "SMSReceiver 트리거" 로그 확인
```

### ❌ "API 요청 안 됨"
```
→ INTERNET 권한 부여 확인
→ API URL이 올바른지 확인 (http/https)
→ http(평문)인데 CLEARTEXT 오류 → network_security_config.xml에 도메인 등록 확인
→ 방화벽/프록시 확인
→ Logcat에서 "API 전송 예외" / Exception 메시지 확인
→ WorkManager가 네트워크 대기 중일 수 있음 (오프라인이면 연결 시 자동 전송)
→ 서버 로그 확인
```

### ❌ "권한 그랜트 안 됨"
```
→ 앱을 한 번 이상 실행했는지 확인
→ 앱이 설치되었는지 확인 (adb shell pm list packages | grep textcatch)
→ 권한 이름이 정확한지 확인
→ 기기 설정에서 직접 권한 부여 시도
```

### ❌ "MMS 이미지/내용 안 넘어옴 (또는 늦음)"
```
→ MMS 수신/읽기 권한 확인 (RECEIVE_MMS, READ_MMS)
→ WAP_PUSH 직후엔 콘텐츠 미도착 → ForwardWorker가 폴링 후 전송하는지 확인
   (Logcat "MMS 대기중... attempt=n/6")
→ 폴링 6회(약 9초) 내 미도착 시 Result.retry() → 백오프 후 재시도됨
→ 폰의 MMS 자동 다운로드가 켜져 있는지 (WiFi 전용/꺼짐이면 콘텐츠 안 옴)
→ 폴링 시간이 부족하면 ForwardWorker의 MMS_POLL_TRIES/INTERVAL 상향
→ Base64 인코딩 확인
```

### ❌ "샤오미(HyperOS/MIUI) 설치 실패 / SMS 권한 자동 거부"
```
확인된 기기: Xiaomi 24094RAD4G, Android 15, HyperOS 2.0 (기본 문자앱 Google 메시지)

[설치 실패] INSTALL_FAILED_USER_RESTRICTED
→ 샤오미는 adb 설치가 기본 차단. 개발자 옵션 "USB를 통해 설치" 필요 (Mi 계정 로그인 필수)
→ "USB 디버깅(보안 설정)" 도 Mi 계정 필수 — 없으면 adb pm grant 가 Exception 으로 실패

[SMS 권한 자동 거부] 파일관리자로 APK 직접 설치(사이드로드)한 경우
→ Android 13+ "제한된 설정" 때문에 SMS 권한 요청이 묻지도 않고 거부됨
   (dumpsys 에 USER_SET 없이 granted=false, appops 에 ACCESS_RESTRICTED_SETTINGS: ignore)
→ adb 로 해제 가능 (= 앱 정보 › ⋮ › "제한된 설정 허용"):
   adb shell appops set com.enfish.textcatch ACCESS_RESTRICTED_SETTINGS allow
→ 그 후 앱의 권한 요청 버튼 또는 설정에서 SMS 권한 직접 허용
```

**Mi 계정 없이 설치하는 순서** (`scripts\install.bat` 이 1~2번을 자동 처리)
```bash
# 1. APK 복사 → 폰의 파일관리자 › Download › app-debug.apk 로 설치
adb push app\build\outputs\apk\debug\app-debug.apk /sdcard/Download/

# 2. 제한된 설정 해제 (최초 설치 시 1회)
adb shell appops set com.enfish.textcatch ACCESS_RESTRICTED_SETTINGS allow

# 3. 폰에서 수동 설정 (adb 로 불가)
#    - 앱 권한: SMS / 전화 / 알림 허용
#    - 기타 권한: SMS 받기, 알림 SMS 읽기, MMS 읽기 → "허용" ("묻기" X)
#    - 자동 시작 켜기 (꺼져 있으면 앱 종료 시 SMS 수신 안 됨)
#    - 배터리 절약 → "제한 없음"
```
- 업데이트(같은 서명 덮어쓰기)는 1번만 하면 권한/설정 유지. 삭제 후 재설치 시 2~3번 다시
- Mi 계정 로그인 + "USB를 통해 설치" 를 켜면 `installDebug` 그대로 사용 가능, 제한도 안 걸림
- 기본 문자앱이 Google 메시지이고 RCS 채팅이 켜져 있으면 RCS 메시지는 브로드캐스트가 없어 감지 불가

### ❌ "일부만 전송되거나 늦게 전송됨"
```
→ 서버 응답 지연/일시 중단 → 재시도는 되지만 백오프로 지연될 수 있음
→ Doze/절전 상태에서 네트워크가 지연될 수 있음 (배터리 최적화 예외 등록 검토)
→ 앱 강제 종료(force-stop) 시 재수신 안 됨 → 앱 1회 재실행 필요
```