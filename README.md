# ClassPlayer Android (클래스플레이어)

초등 수업용 영상 플레이어의 안드로이드 폰 버전입니다. 유튜브 UI·광고·추천 영상 없이 **영상만** 보여 주고,
기기 하드웨어 디코더로 로컬 영상을 재생하며, 360도 영상과 소리만 백그라운드 재생을 지원합니다.
데스크톱(맥/윈도우) 버전은 [classplayer](https://github.com/janghwansangai/classplayer) 저장소에 있습니다.

> 유튜브 광고 우회는 유튜브 약관상 플레이 스토어 배포가 불가능합니다. 이 앱은 APK를 직접 설치(사이드로드)하는 것을 전제로 합니다.

## 기능

| 기능 | 구현 |
|---|---|
| 로컬 영상 재생 (HEVC/AV1 등) | Media3 ExoPlayer + 기기 하드웨어 디코더(실패 시 소프트웨어 폴백) |
| 유튜브 링크 재생 (광고·추천 없음) | `youtubedl-android`(yt-dlp 포팅)로 스트림 주소를 풀어 영상+소리를 합쳐 재생 |
| 공유 메뉴에서 열기 | 유튜브 앱 → 공유 → 클래스플레이어, 또는 링크 붙여넣기 |
| 360도 등장방형 영상 | OpenGL ES 2.0 구면 셰이더, 드래그로 둘러보기, 핀치로 확대/축소 |
| 소리만 백그라운드 재생 | 포그라운드 서비스(MediaSessionService) + 알림 컨트롤, 영상 트랙은 꺼서 배터리 절약 |
| 재생목록·즐겨찾기 | 유튜브 링크(단일/재생목록 링크)와 내 영상으로 재생목록 만들기, 길게 눌러 순서 바꾸기, 하트로 즐겨찾기 |
| 음악 플레이어 조작 | 곡 단위 이전/다음, 반복(전체/한 곡/끄기), 섞기, 배속, 10초 이동, 재생 큐 보기 |
| 썸네일 | 유튜브는 ytimg 썸네일, 내 영상은 첫 프레임(Coil) |
| 가로/세로 전환 | 재생 화면의 회전 버튼으로 가로 전체화면 ↔ 세로 |
| 화면 꺼져도 재생 | 앱이 뒤로 가거나 화면이 꺼져도 소리는 계속, 영상 디코딩만 멈춰 배터리 절약 |
| 한국어 UI, 다크 뮤직 디자인 | Jetpack Compose, 어두운 남색 바탕 + 밝은 파란 포인트(시안 B) |

## 요구 사항

- Android 8.0(API 26) 이상, arm64 또는 x86_64 기기(에뮬레이터 포함)
- 빌드: JDK 17 이상, Android SDK 35 (Android Studio 또는 commandline-tools)

## 빌드

### Android Studio
1. Android Studio에서 이 폴더를 엽니다(File → Open).
2. Gradle 동기화가 끝나면 Run ▶ 으로 에뮬레이터나 USB 연결 기기에 설치합니다.
3. 배포용 APK: Build → Build Bundle(s)/APK(s) → Build APK(s) → `app/build/outputs/apk/release/app-release.apk`

### 명령줄 (맥)
```bash
brew install openjdk@21 android-commandlinetools
```
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
yes | sdkmanager --sdk_root=$ANDROID_HOME --licenses
sdkmanager --sdk_root=$ANDROID_HOME "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```
결과물: `app/build/outputs/apk/release/app-release.apk` (약 130MB, yt-dlp 파이썬 런타임 포함)

## 설치 (사이드로드)

1. 폰에서 설정 → 보안 → "출처를 알 수 없는 앱 설치" 허용 (브라우저/파일 관리자에 대해).
2. APK 파일을 폰에 옮겨 열거나, USB로 연결해 설치:
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```
3. 첫 실행 때 유튜브 엔진(yt-dlp)이 자동 준비·업데이트됩니다(인터넷 필요, 몇 초~수십 초).

## 사용법

- **유튜브**: 홈 화면 아래 입력칸에 링크 붙여넣기 → ▶. 또는 유튜브 앱에서 공유 → 클래스플레이어. 유튜브 재생목록 링크(list=…)를 넣으면 전체 곡을 큐로 재생합니다.
- **재생목록**: 라이브러리 → "새로 만들기" → 이름 입력 → 링크 추가(유튜브 단일/재생목록 링크) 또는 폴더 버튼으로 내 영상 추가. 길게 눌러 끌면 순서가 바뀝니다.
- **즐겨찾기**: 재생 화면의 하트. 라이브러리 "즐겨찾기" 탭에 모입니다. 재생 화면 위 "재생목록에 추가" 버튼으로 지금 곡을 목록에 넣을 수 있습니다.
- **내 영상**: "내 영상 열기"로 파일 선택. 파일 관리자에서 영상을 "클래스플레이어로 열기"도 됩니다.
- **360도**: 파일명이나 제목에 360이 있으면 자동, 아니면 재생 화면 위 `360°` 버튼으로 전환. 드래그로 보고 핀치로 확대.
- **소리만**: 재생 화면 위 `소리만` 버튼. 홈 버튼을 눌러 나가도 알림에서 재생/일시정지 가능.
  (소리만 모드가 아니면 앱이 뒤로 갈 때 자동으로 일시정지됩니다 — 수업 중 실수 방지)
- **유튜브가 안 열릴 때**: 홈 화면 "엔진 업데이트"를 눌러 yt-dlp를 최신으로 올립니다. 하루 한 번은 자동으로 합니다.

## 코드 구조

```
app/src/main/java/com/classplayer/android/
├── ClassPlayerApp.kt        yt-dlp 초기화·자동 업데이트
├── MainActivity.kt          공유/열기 인텐트 처리, 화면 전환, 전체화면
├── player/PlaybackService.kt   ExoPlayer를 가진 포그라운드 서비스, 영상+소리 합치기
├── player/PlayerViewModel.kt   화면 상태, MediaController 조작, 최근 목록
├── youtube/StreamResolver.kt   yt-dlp로 스트림 주소 풀기
├── vr/SphereRenderer.kt     360도 구면 OpenGL ES 렌더러
├── vr/VrVideoView.kt        드래그/핀치 입력
└── ui/                      Compose 화면(HomeScreen, PlayerScreen)과 테마
```

## 검증 기록 (2026-11-13, Pixel 7 에뮬레이터 Android 15)

v0.2.0
- 새 재생목록 → 유튜브 링크 2개 추가(제목·썸네일 자동) → 처음부터 재생 → 다음 곡(지연 해석) → 가로 전환 → 홈 버튼 후에도 재생 유지 확인

v0.1.0

- 로컬 H.264 mp4 재생, 컨트롤(재생/일시정지/10초 이동/배속) 동작 확인
- 색상 라벨 360도 합성 영상으로 정면·좌우·상하 방향 확인(오른쪽으로 끌면 왼쪽 면이 나옴)
- 유튜브 링크 재생 확인. 번들된 yt-dlp(구버전)로는 403이 났고, 업데이트(2026.10.26) 후 정상 → 자동 업데이트 추가
- 소리만 모드 + 홈 버튼: 재생 유지, 알림 컨트롤 표시 확인
- 실기기(폰) 검증과 HEVC/AV1 실기 확인은 아직 하지 않았습니다.
