# 나무 API 재확인 및 임시 수동 매수 — 2026-10-02

사용자가 가이드 링크를 제공했고 미국주식 API 주문 접수 후 체결 확인을 요청했다. 에이전트는 실제 주문을 실행하지 않는다. APK에서 사용자가 직접 주문 내용을 입력·확인·인증·전송한다.

## 확인한 명세

- 인증: 기존 OAuth 토큰 24시간 재사용.
- 지수 일봉: POST `/gbstock/quote/v1/symbolIndexFxPeriod`. `Input_0.iem_cd/end_dt/array_cnt/maxavg/gubun/today_cls`, `gubun=1`. 일봉은 `Output_1.bsop_date/ovrs_oprc/ovrs_hgpr/ovrs_lwpr/ovrs_prpr/vol`. 공식 예제는 `.DJI`이며 DJUSSC 지원 코드 목록은 없다.
  - 가이드: https://www.nhplug.com/apiservice?group_id=8f861cae-7e6c-4caa-b580-a5bd4d7835c3&api_id=22ec86d2-705d-4b6f-9116-eb4e667865cd
- 미국주식 매수: POST `/gbstock/order/v1/buy`. 계좌·티커·수량·지정가·시간대·증거금통화는 사용자 입력. 국가 `200`, 지정가 시간대 `00/61/62/63`, 증거금통화 `1` 달러 또는 `2` 원화. 성공 예제 `rsp_cd=00171` + `Output_0.orr_no`는 주문 접수이며 체결이 아니다.
  - 가이드: https://www.nhplug.com/apiservice?group_id=4775f634-2df5-4930-a198-18c5335f2538&api_id=3dbb5c2d-2486-47a7-8693-1b23622a486e
- 주문·체결 조회: POST `/gbstock/inquiry/v1/unexecuted`. 주문일자·계좌·티커·매수구분 `2`·전체체결구분 `0`, 접수번호가 있으면 `orr_no`도 사용. 조회 성공코드 `00000/00166`, `Output_0[].cns_qty`를 주문수량과 비교하며 부분 체결을 구분한다.
  - 가이드: https://www.nhplug.com/apiservice?group_id=aeab423a-0de9-469e-af35-036020aef53b&api_id=ae395aa3-fa1e-430e-a087-eb9ebae0a7bf

## 임시 매수 구현

- 설정 → 임시 미국주식 매수, 기본 꺼짐. 주문 확인 → 지문/잠금화면 인증 → 사용자가 직접 매수 전송. 정수 수량, 지정가만 지원.
- 조회용 `NamuHttp`의 자동 재시도를 매수에 재사용하지 않는다. 주문 POST 한 번, 리디렉션 금지, 고정 길이 스트리밍. 통신 결과 불명확 시 새 매수를 막는다.
- 전송 전 주문 상태를 암호화 저장하며 기존 백업 제외 저장소를 사용한다. 거부 응답 또는 사용자의 별도 미접수 확인 후에만 재작성할 수 있다. 체결/정정/취소 결과는 증권사 응답이 기준이다.
- 정정·취소는 나무 앱에서 직접 수행. 임시 화면은 한 건의 매수 결과 확인용이다. 실제 이벤트 자격은 공식 행사 약관 확인 전 미검증.

## DJUSSC 데이터 후보

나무의 `.DJUSSC/DJUSSC`가 현재 실패하며 가이드에서 대체 코드는 확인되지 않았다. 임의로 SOX/SOXX로 바꾸지 않는다. 나무에 지원 코드 여부를 확인한 뒤 미지원이면 별도 지수 출처가 필요하다.

설정 → 데이터관리 → DJUSSC 연결 진단에서 `.DJI/.DJUSSC/DJUSSC`의 응답코드·오류 원문·종목명·일봉 개수·최근 날짜를 확인할 수 있다. 키나 계좌번호를 출력하지 않는다. 실제 조회는 사용자가 버튼을 누를 때만 한다.

Massive는 지수 종목 목록 API `/v3/reference/tickers?market=indices`와 지수 OHLC API를 제공한다. DJUSSC 포함 여부는 아직 키로 조회하지 않았다. 공식 지수 OHLC 기록은 2023-02-14부터로 안내되어 기존 장기 전략 초기 5000거래일 요구에는 충분하지 않다.
- https://massive.com/docs/rest/indices/tickers/all-tickers
- https://massive.com/docs/rest/indices/aggregates/custom-bars

## 검증 범위

실주문 전송은 수행하지 않는다. 요청 필드·숫자 검증·응답 판정·부분체결 판정은 회귀 테스트로 검증하고 APK를 빌드한다. 실제 기기 인증과 계좌별 주문 권한 및 체결은 사용자가 확인해야 한다.

## 1.0.3 주문 가격 전송 오류 수정

- 사용자의 실기기 주문 거부: `fc_orr_uit_pr 길이나 데이터타입을 확인하세요`.
- 원인: `JSONObject(Map)`가 Android에서 `BigDecimal`을 문자열로 wrap한다. 필드 모델 단위 테스트만으로 실제 JSON 타입을 검증하지 못했다.
- 수정: `ManualBuyDraft.requestJson()`이 검증된 계좌/티커/선택 코드를 문자열로, 수량과 가격을 정확한 십진 JSON 숫자로 직렬화한다. 가격에 Double 변환, 지수 표기, 따옴표를 사용하지 않는다. 해당 함수 반환값이 실제 HTTP 전송 본문이다.
- 테스트: 완성된 전송 본문, 정수/센트/최대가격/지수 입력/후행 0/최대수량/티커 주입 거부를 포함한 전체 19개 테스트 통과, debug APK 빌드 성공. 1.0.2와 서명 일치, versionCode 4 / versionName 1.0.3 확인.
- 실제 주문 전송 및 수정 버전의 증권사 주문 접수는 수행하지 않았다. 이전 주문을 자동 재전송하지 않는다. 기존 거부 기록은 사용자가 화면의 '거부된 주문 다시 작성'으로 해제한다.
- 근거: https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java (`JSONObject(Map)`와 `wrap`). 나무 공식 매수 가이드의 단가 타입은 Number / 소수점 두 자리.
