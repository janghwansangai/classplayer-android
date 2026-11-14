# ClassPlayer Android 작업 메모 (AI 세션용)

- 목적: 초등 수업용 영상 플레이어의 안드로이드 버전. 유튜브 UI·광고 없이 영상만, 로컬 영상 하드웨어 디코딩, 360도, 소리만 백그라운드 재생. 플레이 스토어 배포 불가(약관) → APK 사이드로드 전제.
- 스택: Kotlin + Jetpack Compose + Media3(ExoPlayer, MediaSessionService) + youtubedl-android 0.18.1. AGP 8.13 / Gradle 8.14 / compileSdk 35. (androidx 최신판은 compileSdk 36+ 와 AGP 9를 요구해서 2025.01 BOM, media3 1.5.1로 고정했다.)
- UI 문구와 주석은 한국어.
- 빌드 환경(이 맥): JDK는 brew openjdk@21, SDK는 ~/Library/Android/sdk (Android Studio 없음). `JAVA_HOME`, `ANDROID_HOME` 설정 후 `./gradlew assembleDebug`. 에뮬레이터 AVD 이름 `classplayer`(Pixel 7, API 35 arm64).
- 유튜브 재생 경로: StreamResolver(yt-dlp getInfo, -f로 avc1 1080p + m4a 우선) → MediaItem.requestMetadata(extras에 audioUrl/mime/headers) → 서비스 Callback.onAddMediaItems가 uri 복원 → ClassMediaSourceFactory가 MergingMediaSource로 영상+소리 합침.
- 번들 yt-dlp는 구버전이라 403이 났다. 앱 시작 시 하루 한 번 자동 업데이트 + 홈 화면 수동 버튼.
- 360도: SphereRenderer(u = 1 - lon, v = 1 - lat, view = rotX(-pitch)·rotY(-yaw)). 2026-11-13 색상 라벨 영상으로 방향 검증. 바꾸면 반드시 다시 확인.
- 검증 방법: `adb shell am start -a android.intent.action.VIEW -d <content uri 또는 유튜브 링크> -n com.classplayer.android/.MainActivity` 후 `adb exec-out screencap -p`로 캡처해서 직접 본다.
- 아직 없는 것: 실기기 검증, HEVC/AV1 실기 확인, 유튜브 생방송 테스트, 다운로드(미리 받기) 기능.
- v0.2.0(시안 B 다크 뮤직형): 재생목록/즐겨찾기/최근은 `data/LibraryRepository`(files/library.json). 유튜브 큐는 `player/LazyYoutubeMediaSource`가 재생 직전에 yt-dlp로 푼다(ExoPlayer가 다음 곡을 미리 준비하므로 곡 넘김이 빠름). 링크 제목·썸네일은 `youtube/YoutubeMeta`(oEmbed, 재생목록 링크는 yt-dlp --flat-playlist). 앱이 뒤로 가면 영상 트랙만 끄고 계속 재생(`onAppBackground`).
- 디자인 시안 캔버스: https://claude.ai/artifact/W1fDqmSqS5ZZZ7tvy2LZch (A 밝은 카드형, B 다크 뮤직형(채택), C 한 손 미니멀).
- v0.3.0: 설정 화면(Screen.Settings), 녹화(record/RecordManager), 예약(schedule/), 업데이트(update/Updater, 깃허브 releases/latest의 .apk), 오류 신고(PlayerViewModel.openBugReport, 프로그램 ID class-player-app). 화면 가장자리는 MainActivity의 safeDrawingPadding으로 처리(가로 전체화면 제외).
- 유튜브 속도: youtubedl-android는 --cache-dir가 없으면 --no-cache-dir을 붙인다(withCache로 지정). 그래도 yt-dlp 2026.10은 JS 시험 답을 디스크에 안 남겨서 매번 QuickJS로 푼다. 대신 StreamResolver가 푼 주소를 cacheDir/streams.json에 만료 전까지 저장하고, yt-dlp는 한 번에 하나만 돌린다(Semaphore). 403이면 한 번 지우고 다시 푼다.
- LazyYoutubeMediaSource는 TimelineWithUpdatedMediaItem으로 원래 MediaItem을 돌려줘야 제목·큐 동기화가 맞는다.
- 예약 시작(안드로이드 15): 알람으로 시작한 포그라운드 서비스는 오디오 포커스 요청이 막힌다(HardeningEnforcer, procState 4여도). 그래서 예약 재생 때만 handleAudioFocus=false, 사용자가 직접 재생을 누르면 다시 켠다. playWhenReady를 prepare 전에 켜야 Media3가 포그라운드를 유지한다.
- 예약 알람은 앱 업데이트/재설치 때 지워지므로 앱 시작과 MY_PACKAGE_REPLACED, BOOT_COMPLETED에서 Scheduler.restore.
- 자동 업데이트는 같은 서명 키가 필요하다. 지금은 이 맥의 ~/.android/debug.keystore로 릴리스를 서명하므로 다른 컴퓨터에서 빌드하면 업데이트 설치가 실패한다.
