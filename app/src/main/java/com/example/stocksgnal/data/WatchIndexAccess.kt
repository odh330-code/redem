package com.example.stocksgnal.data

import android.content.Context
import org.json.JSONArray

/**
 * 관심 › 지수 탭의 지수·선물·금리·환율.
 * 목록은 고정이고(추가·삭제 없음) 켜기/끄기만 한다. 켜진 항목만 홈 › 주요지수에 표시된다.
 * 값은 나무증권 API로 받는다. Yahoo는 쓰지 않는다.
 */
enum class IndexSource {
    /** CME 지수선물. codes[0] = 상품 코드(NQ, ES). 근월물은 NamuMarketApi가 날짜로 계산 */
    CME_FUTURE,
    /** 해외 지수·환율·금리. codes = 나무증권 종목코드 후보(앞에서부터 시도) */
    INDEX_FX,
    /** 원/달러 환율: 지수 코드 후보를 먼저 시도, 안 되면 해외주식 현재가의 환율 */
    USD_KRW,
    /** 미국 국채금리: 미국 재무부 공식 일별 금리. codes[0] = 재무부 CSV 열 이름("2 Yr" 등) */
    US_TREASURY
}

data class WatchIndex(
    val name: String,              // 화면 표시 이름. 켜기/끄기 저장 키로도 쓴다
    val source: IndexSource,
    val codes: List<String>,
    val enabled: Boolean = true
) {
    val hasSource: Boolean get() = codes.isNotEmpty()
    /** 화면에 보여줄 코드 */
    val symbol: String get() = codes.firstOrNull().orEmpty()
}

/**
 * 고정 목록. 순서 = 관심 › 지수, 홈 › 주요지수 표시 순서.
 * 다우존스(.DJI)는 나무증권 가이드 예제에 있는 코드. 나무 웹 시세창은 같은 지수를 EP.DJI, 원/달러를 EXKRW,
 * 나스닥종합을 EPCOMP로 부르므로 API 코드 = 앞 두 글자를 뗀 값으로 보인다(.DJI, KRW, COMP).
 * 미국 국채금리는 나무 공개 자료(가이드·종목정보파일·웹 시세창)에 없어서 미국 재무부 공식 일별 금리를 쓴다.
 */
val FIXED_INDICES: List<WatchIndex> = listOf(
    WatchIndex("나스닥100 선물", IndexSource.CME_FUTURE, listOf("NQ")),
    WatchIndex("S&P500 선물", IndexSource.CME_FUTURE, listOf("ES")),
    WatchIndex("미국 2년물 금리", IndexSource.US_TREASURY, listOf("2 Yr")),
    WatchIndex("미국 10년물 금리", IndexSource.US_TREASURY, listOf("10 Yr")),
    WatchIndex("미국 20년물 금리", IndexSource.US_TREASURY, listOf("20 Yr")),
    WatchIndex("다우존스", IndexSource.INDEX_FX, listOf(".DJI")),
    WatchIndex("원/달러 환율", IndexSource.USD_KRW, listOf("KRW", "KRW=", "USDKRW"))   // 나무 웹 시세창 코드 EXKRW → API 코드 KRW (추정)
)

private const val PREFS = "stock_signal_settings"
/** 꺼 둔 항목 이름 목록(JSON 배열). 처음 설치하면 모두 켜짐 */
private const val KEY_OFF = "watch_indices_off"

fun loadWatchIndices(context: Context): List<WatchIndex> {
    val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OFF, null)
    val off: Set<String> = runCatching {
        val array = JSONArray(saved ?: "[]")
        (0 until array.length()).map { array.getString(it) }.toSet()
    }.getOrDefault(emptySet())
    return FIXED_INDICES.map { it.copy(enabled = it.name !in off) }
}

fun saveWatchIndices(context: Context, indices: List<WatchIndex>) {
    val array = JSONArray()
    indices.filter { !it.enabled }.forEach { array.put(it.name) }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OFF, array.toString()).apply()
}
