package com.example.angelwatch

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Kotak Neo (Trade API). Prices, holdings and orders use Kotak's API. Kotak has no candle data,
 * so price history for the screeners comes from Yahoo Finance (NSE stocks and main indices).
 * Angel One's instrument token for a stock or contract is the exchange token, which Kotak uses too.
 */
object KotakBroker : Broker {
    override val id = "kotak"
    override val label = "Kotak Neo"
    override val fields = listOf(
        Field("tok", "Access token (Neo app > TradeAPI)", secret = true),
        Field("mob", "Mobile number (+91...)"),
        Field("ucc", "Client code (UCC)"),
        Field("mpin", "MPIN", secret = true, numeric = true),
        Field("totp", "TOTP secret", secret = true))
    override val help = "In the Neo app open Invest > TradeAPI to get the access token and register TOTP (copy the secret text, not the 6-digit number). Orders only work from your registered static IP. Stop-loss GTT is not available on Kotak; price history for F&O is not available."

    private const val LOGIN = "https://mis.kotaksecurities.com/login/1.0"
    private const val FIN_KEY = "neotradeapi"
    private val JSON = "application/json".toMediaType()
    private val lastCall = HashMap<String, Long>()

    @Volatile private var auth = ""
    @Volatile private var sid = ""
    @Volatile private var base = ""
    @Volatile private var trdSyms: Map<String, String> = emptyMap()   // "nse_fo|token" -> trading symbol, per downloaded segment
    private val loadedSeg = HashSet<String>()

    private fun v(k: String) = Brokers.get(this, k)
    override fun hasCreds() = fields.all { v(it.key).isNotBlank() }
    override fun reset() { auth = ""; sid = ""; base = ""; trdSyms = emptyMap(); loadedSeg.clear() }

    private fun throttle(key: String, gap: Long) {
        synchronized(lastCall) {
            val wait = (lastCall[key] ?: 0L) + gap - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall[key] = System.currentTimeMillis()
        }
    }

    private fun parse(t: String, code: Int): JSONObject =
        try { JSONObject(t) } catch (e: Exception) { Http.fail("Kotak: HTTP $code ${t.take(150)}") }

    private fun errText(j: JSONObject) =
        listOf("emsg", "errMsg", "message", "errorMessage", "Error Message", "error").map { j.optString(it) }.firstOrNull { it.isNotBlank() }
            ?: j.toString().take(150)

    @Synchronized
    private fun login() {
        reset()
        val tok = v("tok")
        val r1 = Request.Builder().url("$LOGIN/tradeApiLogin")
            .header("Authorization", tok).header("neo-fin-key", FIN_KEY).header("Content-Type", "application/json")
            .post(JSONObject().put("mobileNumber", v("mob")).put("ucc", v("ucc")).put("totp", Totp.now(v("totp"))).toString().toRequestBody(JSON))
            .build()
        val view: JSONObject = Http.client.newCall(r1).execute().use { r ->
            val j = parse(r.body?.string().orEmpty(), r.code)
            j.optJSONObject("data") ?: Http.fail("Kotak login failed: " + errText(j))
        }
        val r2 = Request.Builder().url("$LOGIN/tradeApiValidate")
            .header("Authorization", tok).header("neo-fin-key", FIN_KEY).header("Content-Type", "application/json")
            .header("sid", view.optString("sid")).header("Auth", view.optString("token"))
            .post(JSONObject().put("mpin", v("mpin")).toString().toRequestBody(JSON)).build()
        Http.client.newCall(r2).execute().use { r ->
            val j = parse(r.body?.string().orEmpty(), r.code)
            val d = j.optJSONObject("data") ?: Http.fail("Kotak MPIN check failed: " + errText(j))
            auth = d.optString("token"); sid = d.optString("sid"); base = d.optString("baseUrl").trimEnd('/')
            if (auth.isEmpty() || base.isEmpty()) Http.fail("Kotak login failed: " + errText(j))
        }
    }

    private fun session() { if (auth.isEmpty()) login() }

    private fun isAuthError(code: Int, j: JSONObject?): Boolean {
        if (code == 401) return true
        val m = (j?.let { errText(it) } ?: "").lowercase()
        return m.contains("invalid session") || m.contains("session expired") || m.contains("invalid token") || m.contains("unauthor")
    }

    /** Authenticated call to the account's base URL. [form] = jData payload for POST. Retries once after a fresh login. */
    private fun call(path: String, form: JSONObject?, retry: Boolean = true): JSONObject {
        session()
        val rb = Request.Builder().url(base + path)
            .header("Auth", auth).header("Sid", sid).header("neo-fin-key", FIN_KEY).header("accept", "application/json")
        if (form != null) rb.post(FormBody.Builder().add("jData", form.toString()).build()) else rb.get()
        Http.client.newCall(rb.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            val j = if (t.trimStart().startsWith("[")) JSONObject().put("data", JSONArray(t)) else parse(t, r.code)
            if (isAuthError(r.code, j)) {
                if (retry) { login(); return call(path, form, false) }
                Http.fail("Kotak: session rejected - check your login on the Account tab")
            }
            val st = j.optString("stat")
            if (st.equals("Not_Ok", true) || (!r.isSuccessful && st.isEmpty())) Http.fail("Kotak: " + errText(j))
            return j
        }
    }

    private fun seg(exch: String) = when (exch) { "NSE" -> "nse_cm"; "NFO" -> "nse_fo"; "BSE" -> "bse_cm"; "BFO" -> "bse_fo"; "MCX" -> "mcx_fo"; else -> exch.lowercase() }

    /** Kotak's name for the thing to quote: the exchange token, or the index name. */
    private fun quoteKey(exch: String, token: String): String? {
        Instruments.indexSymbol(token)?.let { return seg(if (token == "99919000") "BSE" else exch) + "|" + it }
        return seg(exch) + "|" + token
    }

    override fun quotes(items: List<Pair<String, String>>): List<JSONObject> {
        session()
        val out = ArrayList<JSONObject>()
        items.chunked(40).forEach { chunk ->
            val keys = chunk.map { it to quoteKey(it.first, it.second)!! }
            val path = "/script-details/1.0/quotes/neosymbol/" +
                keys.joinToString(",") { it.second }.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20").replace("%2C", ",").replace("%7C", "|") } + "/all"
            throttle("q", 600)
            val rb = Request.Builder().url(base + path).header("Authorization", v("tok")).header("accept", "application/json").get()
            val arr: JSONArray = Http.client.newCall(rb.build()).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (r.code == 401) { login(); throw IOException("Kotak session refreshed, retrying") }
                try { if (t.trimStart().startsWith("[")) JSONArray(t) else parse(t, r.code).let { it.optJSONArray("data") ?: it.optJSONArray("message") ?: JSONArray() } }
                catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("Kotak quotes: ${t.take(120)}") }
            }
            val rows = (0 until arr.length()).map { arr.getJSONObject(it) }
            keys.forEachIndexed { n, pair ->
                val (exch, token) = pair.first
                val name = Instruments.indexSymbol(token)
                val row = rows.firstOrNull { o -> o.optString("exchange_token") == token || o.optString("instrument_token") == token }
                    ?: rows.firstOrNull { o -> name != null && o.optString("display_symbol").equals(name, true) }
                    ?: rows.getOrNull(n)?.takeIf { rows.size == keys.size }
                    ?: return@forEachIndexed
                val ltp = row.optString("ltp").toDoubleOrNull() ?: row.optString("last_traded_price").toDoubleOrNull() ?: 0.0
                var close = row.optJSONObject("ohlc")?.optString("close")?.toDoubleOrNull() ?: 0.0
                val chg = row.optString("change").toDoubleOrNull() ?: (if (close > 0) ltp - close else 0.0)
                if (close <= 0) close = ltp - chg
                val pct = row.optString("per_change").toDoubleOrNull() ?: (if (close > 0) chg / close * 100 else 0.0)
                out.add(JSONObject().put("symbolToken", token).put("exchange", exch)
                    .put("tradingSymbol", Instruments.resolve(exch, token)?.symbol ?: name ?: token)
                    .put("ltp", ltp).put("netChange", chg).put("percentChange", pct).put("close", close))
            }
        }
        return out
    }

    override fun candles(exch: String, token: String): List<Candle> = Yahoo.daily(exch, token)
    override fun candles5(exch: String, token: String): List<Bar> = Yahoo.intraday5(exch, token)

    override fun holdings(): List<Holding> {
        val j = call("/portfolio/v1/holdings", null)
        val arr = j.optJSONArray("data") ?: return emptyList()
        data class H(val sym: String, val qty: Int, val avg: Double, val last: Double)
        val hs = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            val sym = (it.optString("displaySymbol").ifBlank { it.optString("symbol") }).removeSuffix("-EQ")
            H(sym, (it.optString("quantity").toDoubleOrNull() ?: 0.0).toInt(),
                it.optString("averagePrice").toDoubleOrNull() ?: 0.0, it.optString("closingPrice").toDoubleOrNull() ?: 0.0)
        }.filter { it.qty > 0 && it.sym.isNotBlank() }
        val tok = hs.associate { it.sym to (Instruments.get(it.sym)?.token ?: "") }
        val q = try { quotes(hs.mapNotNull { h -> tok[h.sym]?.takeIf { it.isNotEmpty() }?.let { "NSE" to it } }) } catch (e: Exception) { emptyList() }
        val ltpBy = q.associate { it.optString("symbolToken") to it.optDouble("ltp", 0.0) }
        return hs.map { h ->
            val t = tok[h.sym].orEmpty()
            val ltp = (ltpBy[t] ?: 0.0).takeIf { it > 0 } ?: h.last
            Holding(h.sym, t, h.qty, h.avg, ltp, if (ltp > 0) (ltp - h.avg) * h.qty else 0.0,
                if (h.avg > 0 && ltp > 0) (ltp / h.avg - 1) * 100 else 0.0)
        }
    }

    override fun orders(): List<OrderRow> {
        val arr = call("/quick/user/orders", null).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            OrderRow(it.optString("trdSym").ifBlank { it.optString("sym") },
                if (it.optString("trnsTp") == "B") "BUY" else "SELL",
                it.optString("qty"), it.optString("prc"), it.optString("ordSt"), it.optString("prcTp"),
                it.optString("ordDtTm").ifBlank { it.optString("hsUpTm") })
        }.reversed()
    }

    /** Kotak trading symbol for a contract (equity: SYMBOL-EQ; F&O / MCX: looked up in Kotak's scrip master by token). */
    private fun tradingSymbol(i: Instrument): String {
        if (!i.isDeriv) return i.symbol.removeSuffix("-EQ") + "-EQ"
        val s = seg(i.exch)
        trdSyms["$s|${i.token}"]?.let { return it }
        if (loadedSeg.add(s)) loadSegment(s)
        return trdSyms["$s|${i.token}"] ?: throw IOException("${i.symbol} not found in Kotak's scrip master")
    }

    private fun loadSegment(s: String) {
        session()
        val rb = Request.Builder().url("$base/script-details/1.0/masterscrip/file-paths").header("Authorization", v("tok")).get()
        val paths = Http.client.newCall(rb.build()).execute().use { r ->
            val j = parse(r.body?.string().orEmpty(), r.code)
            val d = j.optJSONObject("data")
            val a = d?.optJSONArray("filesPaths") ?: JSONArray()
            (0 until a.length()).map { a.getString(it) }
        }
        val url = paths.firstOrNull { it.lowercase().contains(s) } ?: throw IOException("Kotak scrip master for $s not found")
        val m = HashMap(trdSyms)
        Http.client.newBuilder().readTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Kotak scrip master HTTP ${r.code}")
                r.body!!.charStream().buffered().use { rd ->
                    val head = Http.csv(rd.readLine() ?: "").map { it.trim().trimEnd(';') }
                    val iS = head.indexOf("pSymbol"); val iT = head.indexOf("pTrdSymbol")
                    if (iS < 0 || iT < 0) throw IOException("Unexpected Kotak scrip master format")
                    while (true) {
                        val c = Http.csv(rd.readLine() ?: break)
                        if (c.size > maxOf(iS, iT)) m["$s|${c[iS].trim()}"] = c[iT].trim()
                    }
                }
            }
        trdSyms = m
    }

    override fun order(i: Instrument, side: String, qty: Int, limit: Double?, product: String,
                       orderType: String?, trigger: Double?, variety: String): String {
        val pt = when {
            orderType == "STOPLOSS_LIMIT" -> "SL"
            orderType == "STOPLOSS_MARKET" -> "SL-M"
            limit == null -> "MKT"
            else -> "L"
        }
        val pc = when (product) { "DELIVERY" -> "CNC"; "INTRADAY" -> "MIS"; "CARRYFORWARD" -> "NRML"; else -> product }
        val j = JSONObject().put("am", "NO").put("dq", "0").put("es", seg(i.exch)).put("mp", "0").put("pc", pc).put("pf", "N")
            .put("pr", limit?.toString() ?: "0").put("pt", pt).put("qt", qty.toString()).put("rt", "DAY")
            .put("tp", trigger?.toString() ?: "0").put("ts", tradingSymbol(i)).put("tt", if (side == "BUY") "B" else "S")
        throttle("o", 400)
        val r = call("/quick/order/rule/ms/place", j)
        return r.optString("nOrdNo").ifBlank { r.optString("orderId") }
    }

    override fun gttSell(sym: String, token: String, qty: Int, trigger: Double, price: Double): String =
        throw IOException("Kotak Neo has no GTT in its API. Place the stop-loss as a normal order instead.")
}
