package com.example.stocksgnal.longterm

import java.time.LocalDate

/*
 * 계곡다이빙 장부: 계좌 설정, 체결 기록, 캠페인별 예산 배율.
 * 저장소는 KeyValueStore로 추상화 (앱: SharedPreferences, 테스트: 메모리).
 */

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class MemoryStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

enum class FillKind(val label: String) {
    CAMPAIGN("1-1/1-2 매수"),
    RECOVERY("1-3 매수"),
    SELL("매도 2")
}

data class LedgerFill(
    val id: Long,
    val account: Int,          // 1 또는 2
    val date: LocalDate,       // 체결일 (미국 거래일)
    val kind: FillKind,
    val amountKrw: Double,
    val shares: Double,        // 모르면 0
    val price: Double          // 모르면 0 (QLD 달러 가격 등 메모용)
)

data class AccountHolding(
    val id: Long,
    val account: Int,
    val ticker: String,
    val shares: Double,
    val avgPriceUsd: Double
)

data class AccountSettings(
    val account: Int,
    /** 1번: 총자산 기준금액(원) — 예산 25%는 이 금액의 25%. 2번: 매수 1 예산 금액(원) 자체 */
    val baseKrw: Double,
    /** 매수용 예수금(원). 1번은 고정 5% 제외 금액 */
    val cashKrw: Double,
    /** 보유 수량 (매수 종목 기준, 필드 이름은 호환 때문에 유지) */
    val qldShares: Double,
    /** 평균 매수가(달러). 계좌 화면 수익률 표시용 */
    val avgPriceUsd: Double = 0.0,
    /** 매도 2 방식: true = 이번 캠페인 매수분 전량, false = 부족 예수금만큼 (1번 계좌 방식) */
    val sellAll: Boolean = account != 1
) {
    /** 25pp에 해당하는 원화 예산 */
    val budgetKrw: Double get() = if (account == 1) baseKrw * 0.25 else baseKrw
    fun ppToKrw(pp: Double) = if (budgetKrw <= 0) 0.0 else pp / LongStrategyParams.BUDGET * budgetKrw
    fun krwToPp(krw: Double) = if (budgetKrw <= 0) 0.0 else krw / budgetKrw * LongStrategyParams.BUDGET
}

/**
 * 계곡다이빙 인스턴스: 신호 종목(판정) → 매수 종목(체결).
 * 규칙(매수 1·매도 2)은 같고, 예산·계좌만 따로 둔다.
 * @param validated 정답 백테스트로 검증된 조합인지 (나스닥만 true)
 */
data class StrategyInstance(
    val id: String,
    val name: String,
    val signalTicker: String,
    val tradeTicker: String,
    val accounts: List<Int>,
    val validated: Boolean
) {
    companion object {
        val NASDAQ = StrategyInstance("nasdaq", "나스닥", "QQQ", "QLD", listOf(1, 2), validated = true)
    }

    fun accountName(account: Int): String = when {
        id == "nasdaq" && account == 1 -> "총자산 기준 계좌"
        id == "nasdaq" && account == 2 -> "직접입력 예산 계좌"
        else -> "$name 계좌"
    }
}

data class LedgerSnapshot(
    val accounts: Map<Int, AccountSettings>,
    val fills: List<LedgerFill>,
    /** (계좌, 캠페인 시작일) → 예산 배율 f */
    val scales: Map<Pair<Int, LocalDate>, Double>,
    /** 이 날짜 이전 체결은 '규칙대로 체결'로 간주 */
    val recordStart: LocalDate,
    val sellDropPct: Double
)

class LongStrategyLedger(private val store: KeyValueStore, private val today: () -> LocalDate = { LocalDate.now() }) {

    fun snapshot(): LedgerSnapshot = LedgerSnapshot(
        accounts = mapOf(1 to account(1), 2 to account(2)),
        fills = fills(),
        scales = scales(),
        recordStart = recordStart(),
        sellDropPct = store.getString("sell_drop_pct")?.toDoubleOrNull() ?: LongStrategyParams.DEFAULT_SELL_DROP_PCT
    )

    fun account(n: Int) = AccountSettings(
        account = n,
        baseKrw = num("acct${n}_base"),
        cashKrw = num("acct${n}_cash"),
        qldShares = num("acct${n}_shares"),
        avgPriceUsd = num("acct${n}_avg_price"),
        sellAll = store.getString("acct${n}_sellall")?.toBooleanStrictOrNull() ?: (n != 1)
    )

    fun saveAccount(a: AccountSettings) {
        store.putString("acct${a.account}_base", a.baseKrw.toString())
        store.putString("acct${a.account}_cash", a.cashKrw.toString())
        store.putString("acct${a.account}_shares", a.qldShares.toString())
        store.putString("acct${a.account}_avg_price", a.avgPriceUsd.toString())
        store.putString("acct${a.account}_sellall", a.sellAll.toString())
    }

    fun holdings(account: Int): List<AccountHolding> =
        store.getString("holdings").orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("|")
            runCatching {
                AccountHolding(
                    id = p[0].toLong(),
                    account = p[1].toInt(),
                    ticker = p[2],
                    shares = p[3].toDouble(),
                    avgPriceUsd = p[4].toDouble()
                )
            }.getOrNull()
        }.filter { it.account == account }

    private fun allHoldings(): List<AccountHolding> =
        store.getString("holdings").orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("|")
            runCatching { AccountHolding(p[0].toLong(), p[1].toInt(), p[2], p[3].toDouble(), p[4].toDouble()) }.getOrNull()
        }

    private fun saveHoldings(list: List<AccountHolding>) = store.putString(
        "holdings",
        list.joinToString("\n") { "${it.id}|${it.account}|${it.ticker.uppercase()}|${it.shares}|${it.avgPriceUsd}" }
    )

    fun addHolding(account: Int, ticker: String, shares: Double, avgPriceUsd: Double): AccountHolding {
        val list = allHoldings()
        val holding = AccountHolding(
            id = (list.maxOfOrNull { it.id } ?: 0L) + 1,
            account = account,
            ticker = ticker.trim().uppercase(),
            shares = shares,
            avgPriceUsd = avgPriceUsd
        )
        saveHoldings(list + holding)
        return holding
    }

    fun addToHolding(id: Long, addShares: Double, addTotalUsd: Double) {
        val list = allHoldings()
        saveHoldings(list.map { h ->
            if (h.id != id) h else {
                val newShares = h.shares + addShares
                val newCost = h.shares * h.avgPriceUsd + addTotalUsd
                h.copy(shares = newShares, avgPriceUsd = if (newShares > 0) newCost / newShares else 0.0)
            }
        })
    }

    fun replaceHolding(id: Long, shares: Double, avgPriceUsd: Double) {
        saveHoldings(allHoldings().map { if (it.id == id) it.copy(shares = shares, avgPriceUsd = avgPriceUsd) else it })
    }

    fun deleteHolding(id: Long) = saveHoldings(allHoldings().filter { it.id != id })

    fun accountLabel(account: Int): String? = store.getString("acct${account}_label")?.takeIf { it.isNotBlank() }

    fun saveAccountLabel(account: Int, label: String) {
        store.putString("acct${account}_label", label.trim())
    }

    fun logoUrl(account: Int, ticker: String): String =
        store.getString("acct${account}_logo_url")?.takeIf { it.isNotBlank() } ?: defaultLogoUrl(ticker)

    fun saveLogoUrl(account: Int, url: String) {
        store.putString("acct${account}_logo_url", url.trim())
    }

    fun defaultLogoUrl(ticker: String): String =
        "https://eodhd.com/img/logos/US/${ticker.trim().uppercase()}.png"

    /* ---------- 전략 인스턴스 ---------- */

    /** 나스닥(QQQ→QLD, 계좌 1·2) + 사용자가 추가한 전략 */
    fun instances(): List<StrategyInstance> = listOf(StrategyInstance.NASDAQ) + customInstances()

    fun instance(id: String): StrategyInstance? = instances().firstOrNull { it.id == id }

    fun instanceForTrade(ticker: String): StrategyInstance? =
        instances().firstOrNull { it.tradeTicker.equals(ticker.trim(), ignoreCase = true) }

    private fun customInstances(): List<StrategyInstance> {
        val saved = store.getString("instances")
        if (saved == null) {
            val semis = StrategyInstance("c10", "반도체", "DJUSSC", "USD", listOf(10), validated = false)
            saveCustom(listOf(semis))
            return listOf(semis)
        }
        // 이전 기본값(SOXX)으로 저장된 반도체 전략은 기초지수(DJUSSC)로 바꿔 둔다
        if (saved.lines().any { it == "c10|반도체|SOXX|USD|10" }) {
            val migrated = saved.lines().joinToString("\n") { if (it == "c10|반도체|SOXX|USD|10") "c10|반도체|DJUSSC|USD|10" else it }
            store.putString("instances", migrated)
            return customInstances()
        }
        return saved.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("|")
            runCatching { StrategyInstance(p[0], p[1], p[2], p[3], listOf(p[4].toInt()), validated = false) }.getOrNull()
        }
    }

    private fun saveCustom(list: List<StrategyInstance>) = store.putString("instances",
        list.joinToString("\n") { "${it.id}|${it.name.replace("|", " ")}|${it.signalTicker}|${it.tradeTicker}|${it.accounts.first()}" })

    fun addInstance(name: String, signalTicker: String, tradeTicker: String): StrategyInstance {
        val list = customInstances()
        val account = maxOf(9, list.maxOfOrNull { it.accounts.first() } ?: 9) + 1
        val inst = StrategyInstance("c$account", name.trim().ifBlank { tradeTicker.uppercase() },
            signalTicker.trim().uppercase(), tradeTicker.trim().uppercase(), listOf(account), validated = false)
        saveCustom(list + inst)
        return inst
    }

    fun updateInstance(id: String, name: String, signalTicker: String, tradeTicker: String) {
        saveCustom(customInstances().map {
            if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, signalTicker = signalTicker.trim().uppercase(),
                tradeTicker = tradeTicker.trim().uppercase()) else it
        })
    }

    fun removeInstance(id: String) = saveCustom(customInstances().filter { it.id != id })

    /** 인스턴스 하나에 해당하는 장부 */
    fun snapshotFor(inst: StrategyInstance): LedgerSnapshot {
        val all = snapshot()
        return all.copy(
            accounts = inst.accounts.associateWith { account(it) },
            fills = all.fills.filter { it.account in inst.accounts }
        )
    }

    fun recordStart(): LocalDate {
        val saved = store.getString("record_start")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (saved != null) return saved
        val d = today(); store.putString("record_start", d.toString()); return d
    }

    fun setRecordStart(d: LocalDate) = store.putString("record_start", d.toString())

    fun setSellDropPct(v: Double) = store.putString("sell_drop_pct", v.toString())

    private fun num(key: String) = store.getString(key)?.toDoubleOrNull() ?: 0.0

    /* ---------- 체결 ---------- */

    fun fills(): List<LedgerFill> =
        store.getString("fills").orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("|")
            runCatching {
                LedgerFill(p[0].toLong(), p[1].toInt(), LocalDate.parse(p[2]), FillKind.valueOf(p[3]),
                    p[4].toDouble(), p[5].toDouble(), p[6].toDouble())
            }.getOrNull()
        }.sortedWith(compareBy({ it.date }, { it.id }))

    private fun saveFills(list: List<LedgerFill>) = store.putString("fills",
        list.joinToString("\n") { "${it.id}|${it.account}|${it.date}|${it.kind.name}|${it.amountKrw}|${it.shares}|${it.price}" })

    /** 체결 기록 + 예수금·수량 자동 반영 */
    fun addFill(account: Int, date: LocalDate, kind: FillKind, amountKrw: Double, shares: Double, price: Double): LedgerFill {
        val list = fills()
        val fill = LedgerFill((list.maxOfOrNull { it.id } ?: 0L) + 1, account, date, kind, amountKrw, shares, price)
        saveFills(list + fill)
        val a = account(account)
        saveAccount(
            if (kind == FillKind.SELL) a.copy(cashKrw = a.cashKrw + amountKrw, qldShares = a.qldShares - shares)
            else a.copy(cashKrw = a.cashKrw - amountKrw, qldShares = a.qldShares + shares)
        )
        return fill
    }

    /** 기록 삭제 + 예수금·수량 되돌림 */
    fun deleteFill(id: Long) {
        val list = fills()
        val fill = list.firstOrNull { it.id == id } ?: return
        saveFills(list.filter { it.id != id })
        val a = account(fill.account)
        saveAccount(
            if (fill.kind == FillKind.SELL) a.copy(cashKrw = a.cashKrw - fill.amountKrw, qldShares = a.qldShares + fill.shares)
            else a.copy(cashKrw = a.cashKrw + fill.amountKrw, qldShares = a.qldShares - fill.shares)
        )
    }

    /* ---------- 예산 배율 ---------- */

    fun scales(): Map<Pair<Int, LocalDate>, Double> =
        store.getString("scales").orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("|")
            runCatching { (p[0].toInt() to LocalDate.parse(p[1])) to p[2].toDouble() }.getOrNull()
        }.toMap()

    fun saveScales(map: Map<Pair<Int, LocalDate>, Double>) = store.putString("scales",
        map.entries.joinToString("\n") { "${it.key.first}|${it.key.second}|${it.value}" })

    fun putScales(newOnes: Map<Pair<Int, LocalDate>, Double>) {
        if (newOnes.isEmpty()) return
        saveScales(scales() + newOnes)
    }

    /** 현재 캠페인 예산 배율을 지우면 다음 계산 때 현재 예수금으로 다시 확정 */
    fun clearScale(account: Int, start: LocalDate) = saveScales(scales() - (account to start))
}
