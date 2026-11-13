# PawPilot

## 1. 앱 이름 및 한 줄 소개

- **앱 이름:** PawPilot
- **한 줄 소개:** 반려동물과의 차량 이동에서 휴식 시점, 내비 경로와 쉼터 탐색을 연결하는 Pleos Connect 앱.
- **팀:** 이스타이엔지SW개발팀
- **제출 버전:** 차량 앱 0.4.0 (versionCode 4)

## 2. 실행 방법

### 차량 앱

제출 APK 실행:

1. 저장소를 내려받고 Pleos Connect Emulator를 시작합니다. 기존 debug 앱과 서명이 다를 수 있으므로 제출 APK 확인에는 별도 테스트 AVD를 사용합니다.
2. `adb devices -l`로 차량 기기의 serial을 확인합니다. 아래 `<차량 serial>`과 `<사용자번호>`는 실제 값으로 바꿉니다.
3. `adb -s <차량 serial> install "apk/이스타이엔지SW개발팀_PawPilot_release.apk"`로 설치합니다.
4. `adb -s <차량 serial> shell am get-current-user`로 활성 사용자를 확인한 뒤 `adb -s <차량 serial> shell am start --user <사용자번호> -n com.estareng.pawpilot/.MainActivity`로 실행합니다.
5. 프로필·휴식 간격을 설정하고 데모 주행/정차 또는 차량 속도 관측 모드를 선택합니다. 데모는 실제 차량 데이터가 아닙니다. 조작 제한 상태를 알 수 없으면 설정·경로 변경이 제한될 수 있습니다.

소스에서 개발판을 빌드하려면 Android Studio에서 프로젝트를 열고 Gradle Sync 후 `app` 구성으로 실행합니다. 명령줄 빌드는 `./gradlew :app:assembleDebug`입니다. Android SDK 35와 JDK 17 이상(JBR 21 검증), Google/Maven Central/Pleos Maven 접근이 필요합니다. 차량 APK는 일반 휴대폰용 앱이 아닙니다.

`./gradlew :app:assembleRelease :app:lintRelease`는 unsigned release를 만듭니다. 자신의 키로 Android SDK apksigner를 사용해 별도 서명해야 합니다.

### 휴대폰 동반 웹앱(PWA)

프로젝트 최상위에서 Node.js 22 이상으로 실행합니다.

```sh
node backend/server.mjs
```

컴퓨터 브라우저에서 `http://127.0.0.1:8787/mobile`을 엽니다. Android 휴대폰 에뮬레이터에서는 `adb -s <휴대폰 serial> reverse tcp:8787 tcp:8787`을 실행한 뒤 Chrome에서 같은 주소를 엽니다. 차량용 APK와 별도인 웹앱이며, 자세한 설정은 [휴대폰 앱 안내](backend/mobile/README.md)를 참고하세요.

## 3. 테스트 환경 및 필요한 설정

| 구분 | 사용 환경 및 설정 |
| --- | --- |
| 차량 개발·실행 | macOS / Android Studio / Pleos Connect Emulator. 버전과 화면 규격은 아래 4번 참조 |
| 빌드 도구 | Android SDK 35 / AGP 8.8.2 / Gradle 8.10.2 / Kotlin 2.0.0 |
| Java | JDK 17 이상, JBR 21 검증 / Java target 17 |
| Android 지원 설정 | minSdk 28 / compileSdk 35 / targetSdk 35 |
| Pleos SDK | Vehicle 2.3.2 / NaviHelper 3.0.5 / FusedLocation 1.1.3 |
| 휴대폰 웹앱 실행 | Node.js 22 이상 / Chrome / 로컬 서버 `127.0.0.1:8787` |
| 위치·알림 권한 | 위치 기능 사용 시 위치 권한, 백그라운드 휴식 알림 사용 시 알림 권한 필요 |

위치 기능은 지원 환경에서 사용자가 활성화한 앱 전경에서만 동작합니다. 알림 권한이 없으면 백그라운드 휴식 알림이 표시되지 않습니다.

외부 서버는 기본 미설정입니다. 차량·휴대폰 연결에는 `-PpawpilotCompanionUrl=https://승인된-호스트`, AI 추천에는 `-PpawpilotRecommendationUrl=https://승인된-호스트/v1/recommendations`를 지정해 빌드해야 합니다. 실제 서버 운영과 인증 설정은 [backend 안내](backend/README.md)와 [휴대폰 앱 안내](backend/mobile/README.md)를 확인하세요. API 키는 서버 환경변수로만 관리합니다.

## 4. 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격

| 항목 | 실제 개발·시연 환경 |
| --- | --- |
| 에뮬레이터 | **Pleos Connect Emulator 2.1.0** |
| AVD 이름 | `Pleos_Connect_v2.1.0` |
| Android / CPU | **API 34 / ARM64** |
| 전체 화면 규격 | **2560 × 1440 px**, 가로 화면 |
| 화면 밀도 | **160 dpi** |
| 앱 표시 영역 | 차량 시스템 UI와 함께 멀티윈도우로 표시되며 창 크기에 따라 달라짐 |

별도 휴대폰 시연 환경은 Android Studio **Medium Phone / API 37.0 / ARM64 / 1080 × 2400 px**의 Chrome입니다. 제출 영상은 **1920 × 1080 px**로 편집했으며, 위의 에뮬레이터 화면 규격과 구분합니다.

## 5. APK 파일 위치 또는 GitHub Release 링크

APK는 이 저장소의 `apk/` 폴더에 포함했습니다.

- **파일 위치:** [`apk/이스타이엔지SW개발팀_PawPilot_release.apk`](apk/이스타이엔지SW개발팀_PawPilot_release.apk)
- **직접 다운로드:** [PawPilot release APK](https://github.com/HJW0816/pawpilot/raw/refs/heads/main/apk/이스타이엔지SW개발팀_PawPilot_release.apk)
- **버전:** 0.4.0 (versionCode 4)
- **SHA-256:** `01807aaa043c86e3dc56057c6ef8802e192b166b1201a6f8fe37a2ac11e25adb`

검토용 키로 서명한 non-debuggable release입니다. 최신 서명본의 설치·실행은 추가 검증 대상입니다. 과거 debug 앱과 서명이 다르면 덮어쓰지 말고 별도 테스트 AVD를 사용하세요. 기존 앱 삭제 시 저장 데이터가 사라질 수 있습니다. 서명 개인키는 공개하지 않습니다.

## 기능과 검증 범위

| 기능 | 현재 범위 |
| --- | --- |
| 휴식 알림 | 경로·반려견 시설 유무와 독립적인 사용자 설정 타이머, 휴식 완료 기록 |
| 반려동물 UI | 종별 캐릭터·배경, 프로필·테마·사진 입력. 실제 사진 선택은 환경 지원 필요 |
| 내비 | 검색, 목적지·경유지·TBT 조회, 즐겨찾기·최근 목적지, POI 기반 목적지/최대 두 경유지 요청, 경로 관리 |
| 위치 | 최근 위치, 지속 수신, 선택 POI 200m 접근 이벤트. 방문·진입 가능 여부를 확정하지 않음 |
| 후기 | 차량 기기 내 후기, 휴대폰 커뮤니티 후기 CRUD·신고. 양쪽 자동 동기화는 미구현 |
| 휴대폰 | PWA 사진·테마·장소·후기, 일회용 연결과 차량 확인 후 검색. 운영 HTTPS 종단 검증 전 |
| AI 추천 | 근거·권한·종별 데이터 검증 구조. 기본 서버/외부 후기 미연결로 실제 추천 미제공 |

2026-11-09 Android Studio release 빌드 및 lint 성공(오류 0, 경고 32). 2026-11-13 Kotlin 회귀 7종, Node 테스트 13건 통과. 신규 SDK API와 최신 서명 release의 실제 실행 검증은 남아 있습니다. 자동 검사와 실차 검증을 구분합니다.

### 2026-11-13 실제 조작 및 시연 자료

- 차량: Pleos Connect 2.1.0에 설치된 개발판에서 체크리스트, 휴식 간격, 종별 캐릭터·배경, 시설 목록, 휴식 완료, 화면 테마를 직접 조작했습니다. 113초 MP4를 접수 이메일에 별도 첨부합니다. 설치된 개발판의 패키지 표시는 0.1.0이며, 저장소의 release 0.4.0 실행 증거와 구별합니다.
- 휴대폰: Android API 37.0 / ARM64 / 1080×2400 에뮬레이터 Chrome에서 PWA와 로컬 Node 서버를 실행했습니다. 프로필·테마 저장, 대비 부족 색 거부, 장소 등록·검색, 후기 작성·수정·삭제, 잘못된 연결 코드와 미연결 안내를 확인했습니다. 69초 MP4를 이메일에 별도 첨부합니다.
- 휴대폰 기본 이름·캐릭터: 강아지 **은별이**, 고양이 **유키**. 종류 변경 시 기본 이름과 미리보기가 갱신되고 직접 입력한 다른 이름은 유지됩니다. 실제 저장 사진이 있으면 사진을 우선합니다. 현재는 활성 프로필 한 개입니다.
- 에뮬레이터 카메라는 촬영 후 클라이언트 미리보기까지만 확인했습니다. 실제 휴대폰 촬영·사진 업로드 지속성, HTTPS 운영, 차량 수신, 실제 내비 경로 생성은 추가 검증 대상입니다.

휴대폰 검증은 로컬 시험이며 외부 배포가 아닙니다. 은별이·유키 변경은 PWA에 적용했으며 차량 APK의 버전과 SHA-256은 유지됩니다.

내비 후보는 TBT 안내 문구 기반으로, 전체 경로 주변 검색·우회시간 계산이 아닙니다. `requestTrip`은 미노출입니다. 사용자 생성 장소에 검증된 내비 POI가 없으면 검색으로 연결합니다. 휴식 간격은 의학적 권장값이 아니며 건강·감정을 진단하지 않습니다.

## 검사

```sh
./gradlew :app:assembleRelease :app:lintRelease
python3 scripts/check-regressions.py
node --test backend/recommendation.test.mjs backend/companion.test.mjs backend/mobile.test.mjs
```

Kotlin 검사 스크립트는 Gradle이 받은 컴파일러 캐시를 사용하며 필요 시 `PAWPILOT_JAVA_HOME`을 설정합니다. 실제 SDK 응답·차량 제한·터치 조작은 에뮬레이터에서 별도로 확인해야 합니다.

## 참고

- [Pleos Navi 공식 샘플](https://github.com/Pleos-Playground/pleos-sample-navi-app)
- [Pleos FusedLocation 공식 샘플](https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app)
- [시설 데이터 출처](https://www.data.go.kr/data/15064250/fileData.do) — 앱 자산의 출처·기준일 확인, 실시간 영업 정보 아님.

개인 연락처가 있는 접수양식과 서명 동의서는 이메일로 별도 제출하며 이 저장소에 포함하지 않습니다.
