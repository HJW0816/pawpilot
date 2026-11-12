# PawPilot

반려동물과의 차량 이동에서 휴식 시점, 내비 경로와 쉼터 탐색을 연결하는 Pleos Connect 앱.

팀: 이스타이엔지SW개발팀 · 차량 앱 0.4.0 (versionCode 4)

## APK

[release APK 다운로드](apk/이스타이엔지SW개발팀_PawPilot_release.apk)

경로: `apk/이스타이엔지SW개발팀_PawPilot_release.apk`

SHA-256: `01807aaa043c86e3dc56057c6ef8802e192b166b1201a6f8fe37a2ac11e25adb`

검토용 키로 서명한 non-debuggable release입니다. 최신 서명본의 설치·실행은 추가 검증 대상입니다. 과거 debug 앱과 서명이 다르면 덮어쓰지 말고 별도 테스트 AVD를 사용하세요. 기존 앱 삭제 시 저장 데이터가 사라질 수 있습니다. 서명 개인키는 공개하지 않습니다.

## 실행 방법

1. Pleos Connect Emulator를 설치하고 AVD를 시작합니다. 일반 휴대폰용 APK가 아닙니다.
2. Android Studio에서 프로젝트를 열고 Gradle Sync를 실행합니다. Android SDK 35와 JDK 17 이상(JBR 21 검증)을 준비합니다. Google/Maven Central/Pleos Maven 접근이 필요합니다.
3. `app` 구성으로 실행하거나 `./gradlew :app:assembleDebug` 후 debug APK를 설치합니다.
4. 제출 APK는 `adb install apk/이스타이엔지SW개발팀_PawPilot_release.apk`로 설치합니다. `adb shell am get-current-user`로 활성 사용자를 확인한 뒤 `adb shell am start --user <사용자번호> -n com.estareng.pawpilot/.MainActivity`로 실행합니다.
5. 프로필·휴식 간격을 설정하고 데모 주행/정차 또는 차량 속도 관측 모드를 선택합니다. 데모는 실제 차량 데이터가 아닙니다. 조작 제한 상태를 알 수 없으면 설정·경로 변경이 제한될 수 있습니다.

`./gradlew :app:assembleRelease :app:lintRelease`는 unsigned release를 만듭니다. 자신의 키로 Android SDK apksigner를 사용해 별도 서명해야 합니다.

## 테스트 환경과 설정

- Pleos Connect Emulator **2.1.0**, Android API **34**, ARM64.
- 개발 시 전체 디스플레이 **2560×1440**, density **160**. 앱은 멀티윈도우 영역에서 표시되므로 전체 화면과 앱 영역은 다릅니다.
- minSdk 28 / compile·targetSdk 35 / AGP 8.8.2 / Gradle 8.10.2 / Kotlin 2.0.0 / Java target 17.
- Vehicle SDK 2.3.2 / NaviHelper 3.0.5 / FusedLocation 1.1.3.
- 위치 기능은 지원 환경 및 권한이 필요하며 사용자가 활성화한 앱 전경에서만 동작합니다. 알림 권한이 없으면 백그라운드 휴식 알림이 표시되지 않습니다.
- 외부 서버는 기본 미설정입니다. `-PpawpilotCompanionUrl=https://...` 및 `-PpawpilotRecommendationUrl=https://...`로 운영할 HTTPS 주소를 지정해 빌드합니다. 서버 실행·설정은 [backend 안내](backend/README.md)와 [휴대폰 앱 안내](backend/mobile/README.md)를 확인하세요. API 키는 서버 환경변수로만 관리하세요.

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
