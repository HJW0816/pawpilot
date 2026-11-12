# 후기 근거 기반 추천 게이트웨이

`node backend/server.mjs` — Node 22 이상, 외부 패키지 없이 실행. 기본은 loopback:8787이며 외부 배포하지 않았다.
`GET /health`, `POST /v1/recommendations`를 제공한다. `OPENAI_API_KEY`, `OPENAI_MODEL`을 서버 환경에 설정한 경우에만 AI를 호출한다. 키를 APK·저장소·채팅에 넣지 않는다. 비용 발생 호출은 이번 검증에서 실행하지 않았다.

요청: `profile: {kind: dog|cat|other, breed, size, quietPreferred}`, `restMinutes: 0..120`, `candidates: [{id, name, directionConfirmed}]`.
현대 내비 안내에 나타난 후보만 앱이 보낸다. 전체 경로·진입 가능성이 검증된 목록이라는 뜻은 아니다. 사용자가 설정한 경로를 모델이 바꾸지 않는다. 휴식 시간이 되면 일반 휴게소 검색을 항상 제공한다.

시설 자료는 서버가 공식 카탈로그에서 가져온다. 블로그·커뮤니티 후기 데이터는 현재 **0건**이다. `data/evidence.json`에 아래 항목을 가진, 사용 권한을 확인한 자료를 넣는다.

- `id`: 중복 없는 출처 ID, `placeId`: 카탈로그의 방향 포함 이름 또는 별도 검증된 시설 ID
- `type`: review / blog / facility, `petKind`: dog / cat / other / unknown
- `summary`: 600자 이하로 요약한 내용, `url`: 원문 HTTPS 주소, `publishedAt`: 작성·갱신 일자
- `rating`: 1..5 또는 null. 블로그 감상을 임의 별점으로 변환하지 않는다.
- `rights`: licensed / user_consent / owned. 외부 원문 전체를 무단 수집하지 않는다.

다른 사이트를 자동 크롤링하는 기능은 없다. 허용된 API·제휴 데이터 또는 이용자가 동의해 제출한 후기를 정규화해 적재하는 구조다. Android의 내 기기 후기 초안은 자동 업로드하지 않는다. 공유용 계정·동의·수정/삭제·신고·중복/광고 검토·접근 제어는 실제 커뮤니티 서버를 붙일 때 필요하다.

모델은 후보 ID와 근거 ID만 반환하며 서버가 소속·중복·길이를 검증한다. 원문 URL·날짜는 서버 자료로 복원한다. 같은 반려동물 종류의 후기 수와 평균 별점도 서버에서 계산한다. 출처 일치 검사는 문장 내용의 정확성을 보장하지 않으므로 실제 데이터 기반 평가가 추가로 필요하다.

키 없음, 근거 없음, 잘못된 출처, 거절/불완전 응답, 25초 초과, API 실패에서는 추천을 꾸며내지 않는다. 입력 64KiB, 분당 10회, 출력 토큰 상한 1800, 요청당 1회 호출이며 재시도하지 않는다. 비용은 선택 모델과 입력량에 따라 달라 금액을 임의 산정하지 않았다. 운영 전 인증된 HTTPS 게이트웨이·사용자별 한도·비용 한도·데이터 보존/삭제 정책이 필요하다.

앱 빌드 시 Gradle 속성 `pawpilotRecommendationUrl`을 HTTPS `/v1/recommendations` 주소로 설정한다. 기본값은 비어 있어 외부로 전송되지 않는다. 공개 서버에 대한 인증 연동은 현재 미구현이므로 배포하지 않는다.

검사: `node --test backend/recommendation.test.mjs` (외부 API 호출 없음).
API 형식 출처: https://developers.openai.com/api/docs/guides/structured-outputs
