package com.example.stocksgnal.longterm

/*
 * 반도체 지수(DJUSSC) 환산지수.
 *
 * 나무증권 시세 API에는 DJUSSC가 없고(폰 실사용에서 .DJUSSC·DJUSSC 모두 실패), 이 지수를 1배로 따라가는 ETF도 없다.
 * 그래서 이 지수의 2배 ETF인 USD(ProShares Ultra Semiconductors)의 일봉으로 환산지수를 만든다.
 *   환산지수의 하루 등락 = USD 하루 등락 ÷ 2   (첫날 값 = 100)
 *
 * 검증(2010~2026, 실제 DJUSSC 일봉과 같은 규칙을 돌려 비교): 252일 고점 대비 낙폭 차이 평균 0.3%p,
 * 매수 구간 시작일 46/49·도달 단계 43/49·매도 신호일 41/49(-4% 기준) 일치, RSI40·MA60 판정 99% 이상 일치.
 * 어긋난 구간은 모두 가격이 직전 고점(회복 기준선) 근처에서 맴돌 때였다.
 * 환산지수는 실제 지수에서 해마다 1~3%씩 서서히 벌어진다(배당·레버리지 비용 때문).
 * USD 가격을 그대로 쓰면 낙폭이 2배로 커져 전혀 다른 신호가 나오므로 반드시 ÷2로 환산해서 쓴다.
 *
 * 안전장치: USD는 2024-11, 2025-11에 2:1 액면분할을 했다. 분할이 반영되지 않은 일봉이 섞이면
 * 하루 -50%로 보여 가짜 폭락 신호가 나므로, 하루 등락이 비정상이면 환산하지 않고 이유를 돌려준다.
 */
object SemiIndexProxy {

    const val INDEX_SYMBOL = "DJUSSC"
    const val ETF_SYMBOL = "USD"

    /** USD의 실제 일간 최대 낙폭은 약 -33%(비율 0.67), 최대 상승은 약 +36%. 2:1 분할 미반영이면 비율이 0.5 안팎. */
    private const val RATIO_LOW = 0.60
    private const val RATIO_HIGH = 1.70

    /** 환산 결과. problem이 있으면 bars는 비어 있다. */
    class Derived(val bars: List<QqqBar>, val problem: String?)

    fun isIndex(symbol: String): Boolean =
        symbol.trim().uppercase().removePrefix(".").removePrefix("^") == INDEX_SYMBOL

    /** 신호 종목의 일봉을 실제로 받아 저장해야 하는 종목 (DJUSSC → USD, 그 외는 그대로) */
    fun dataSymbol(symbol: String): String = if (isIndex(symbol)) ETF_SYMBOL else symbol

    /** [etf] = USD 일봉(날짜 오름차순, 완성된 봉만) */
    fun fromEtf(etf: List<QqqBar>): Derived {
        if (etf.isEmpty()) return Derived(emptyList(), null)
        val out = ArrayList<QqqBar>(etf.size)
        var level = 100.0
        out.add(QqqBar(etf[0].date, level))
        for (i in 1 until etf.size) {
            val prev = etf[i - 1].close
            val cur = etf[i].close
            if (prev <= 0.0 || cur <= 0.0) {
                return Derived(emptyList(), "USD ${etf[i].date} 종가가 0 이하입니다")
            }
            val ratio = cur / prev
            if (ratio < RATIO_LOW || ratio > RATIO_HIGH) {
                return Derived(
                    emptyList(),
                    "USD ${etf[i].date} 하루 등락 %+.0f%% — 액면분할이 반영되지 않은 일봉으로 의심되어 반도체 신호를 계산하지 않습니다".format((ratio - 1) * 100)
                )
            }
            level *= 1.0 + 0.5 * (ratio - 1.0)
            out.add(QqqBar(etf[i].date, level))
        }
        return Derived(out, null)
    }
}
