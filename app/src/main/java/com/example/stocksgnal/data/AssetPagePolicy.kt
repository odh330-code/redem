package com.example.stocksgnal.data

/** 자산현황 00218/00165는 다음 페이지 안내. 금액이 없는 오류와 구분한다. */
internal class AssetPagePolicy(private val maxPages: Int = 30) {
    private val seen = mutableSetOf<String>()
    private var pages = 0

    fun accept(code: String, message: String, hasMore: Boolean, cts: String?): String? {
        check(code in setOf("00000", "00166", "00165", "00218")) {
            "응답 $code: $message"
        }
        pages++
        val continuing = hasMore || code == "00165" || code == "00218"
        if (!continuing) return null
        val next = cts?.takeIf { it.isNotBlank() }
            ?: error("나무가 다음 페이지를 안내했지만 연속조회 키가 없습니다")
        check(seen.add(next)) { "나무가 같은 연속조회 키를 반복했습니다. 부분 잔고는 합계에 반영하지 않습니다" }
        check(pages < maxPages) { "RP 자산현황 연속조회가 $maxPages 페이지에서 끝나지 않았습니다" }
        return next
    }
}
