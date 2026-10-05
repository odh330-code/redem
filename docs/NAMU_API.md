# 나무증권(NH PLUG) API 메모

출처: https://www.nhplug.com/apiservice (2026-10-01 확인). 모든 요청은 `POST https://api.nhplug.com:8443<경로>`,
헤더 `Content-Type: application/json; charset=UTF-8`, `Authorization: Bearer <토큰>`. 본문은 `{"Input_0": {...}}`.
응답 `rsp_cd`가 `"00000"`(또는 `"00166"`)이면 정상. 대부분 초당 5회 한도.

## 인증
| 이름 | 경로 | 비고 |
|---|---|---|
| 접근토큰발급 | `/oauth2/token` (form) | `appkey, appsecretkey, grant_type=client_credentials, scope=oob`. 24시간 유효, 만료 전 재발급 금지(초당 1회) |

## 계좌 (앱에서 쓸 것)
| 이름 | 경로 | 주요 입력 | 주요 출력 |
|---|---|---|---|
| 계좌목록 | `/n2/acctinfo` | (없음) | `Output_0[].acct_no, acct_type` |
| 해외주식 잔고 | `/gbstock/inquiry/v1/balance` | `act_no, qut_iqr_dit_cd=9, fc_sec_trd_nat_cd=200, cur_cd=KRW, xns_dit_cd=0` | `Output_0`: `krw_dca`(원화예수금), `fc_dca`(외화예수금), `tot_aet_amt`, `eal_amt_sum` / `Output_1[]`: `iem_cd, iem_nm, cns_bse_bnc_qty`(잔고수량), `fc_phs_uit_pr`(외화매입단가), `fc_sec_end_pr`(종가), `krw_eal_amt` |
| 해외주식 체결 | `/gbstock/inquiry/v1/unexecuted` | `orr_dt, act_no, oss_sby_dit_cd=0, sot_dit=0, ost_cns_dit=1(체결)` | `Output_0[]`: `orr_no, sby_dit_nm(매수/매도), iem_cd, cns_qty, cns_pr, orr_dt` — 하루 단위 |
| 해외주식 일별거래내역 | `/gbstock/inquiry/v1/dailyTransaction` | `act_no, iqr_sta_dt, iqr_end_dt, act_trd_cfc_cd(05 매수/06 매도/00 전체), iem_mlf_cd=00001` | `Output_0[]`: `trd_dt, act_trd_tp_nm, iem_cd("QLD US"), trd_qty, trd_uit_pr, fc_trd_amt, aly_xcg_rt` — 기간 조회 가능 → 매매기록 미제용 |
| 종합거래내역 | `/common/inquiry/v1/totalTransaction` | `act_no, iqr_sta_dt, iqr_end_dt, iem_llf_cd=15(해외주식), act_trd_dtl_cd=03(매매)` | 결제기준 |

## 시세
| 이름 | 경로 | 주요 입력 | 주요 출력 |
|---|---|---|---|
| 해외주식 기간별(일봉) | `/gbstock/quote/v1/period` | `iem_cd=QQQ, end_dt, count, gubun=3(일), today_cls=0, market_cls=1` | `Output_1[]`: `trade_date, open_prc, high, low, close_prc, movolume(천주)` |
| 해외주식 현재가 | `/gbstock/quote/v1/current` | `iem_cd` | `trdprc, pctchng` |
| 종목/지수/환율 기간별 | `/gbstock/quote/v1/symbolIndexFxPeriod` | `iem_cd=.DJI, end_dt, array_cnt, gubun=1(일), today_cls=0` | `Output_0.ovrs_prpr`(현재가, 문자열), `prdy_ctrt`, `prdy_vrss_sign` / `Output_1[]` 일봉 |
| 해외선물 현재가 | `/gbfuture/quote/v1/current` | `exnm=FCME, iem_cd=NQZ26` | `last, rate, setp, pcls` |
| 해외선물 일봉 | `/gbfuture/quote/v1/executionTrendDaily` | `exnm, iem_cd, ssymd, quotyn=Y` | `Output_0[]`: `tymd, open, high, low, clos` |

## 종목코드
- 해외선물(CME 지수선물): 종목정보파일 `https://www.nhplug.com/instruments/fucode_h.mst` (cp949, 고정길이). 나스닥100 = `NQ`, S&P500 = `ES` + 월코드(H/M/U/Z) + 연도 2자리. 예: `NQZ26`.
  해외선물은 CME 지수선물·OPRA·홍콩만 있음 → **미국 국채 선물은 없음**.
- 해외 지수: 가이드 예제는 다우존스 `.DJI`뿐. 환율·국채금리 코드는 공개 목록 없음 → 앱이 후보 코드를 차례로 시도(`WatchIndexAccess.kt`).
- 해외주식 종목: `https://www.nhplug.com/instruments/m_gtsstock.mst`
