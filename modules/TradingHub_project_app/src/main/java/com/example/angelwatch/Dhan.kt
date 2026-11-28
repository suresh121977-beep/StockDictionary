package com.example.angelwatch

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Dhan security ids, matched to Angel's instrument list by symbol (stocks, indices) or underlying+expiry+strike (F&O, MCX). */
internal object DhanMaster {
    class Ref(val secId: String, val seg: String, val instr: String)

    private const val URL = "https://images.dhan.co/api-data/api-scrip-master.csv"
    private const val MAX_AGE_MS = 2 * 86_400_000L
    @Volatile private var map: Map<String, Ref> = emptyMap()

    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }
    fun dkey(exch: String, und: String, date: LocalDate, strike: Double, opt: String) =
        "D|$exch|${und.uppercase()}|$date|${String.format(Locale.US, "%.2f", strike)}|$opt"

    @Synchronized
    fun ensure() {
        if (map.isNotEmpty()) return
        val f = File(Store.appCtx.filesDir, "dhan_map.tsv")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) {
            try { download(f) } catch (e: Exception) { if (!f.exists()) throw IOException("Dhan instrument list: ${e.message}") }
        }
        val m = HashMap<String, Ref>()
        f.forEachLine { l -> val p = l.split('\t'); if (p.size >= 4) m[p[0]] = Ref(p[1], p[2], p[3]) }
        map = m
    }

    private fun download(out: File) {
        val req = Request.Builder().url(URL).build()
        Http.client.newBuilder().readTimeout(300, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val tmp = File(out.path + ".tmp")
            val today = LocalDate.now()
            r.body!!.charStream().buffered().use { rd ->
                val head = Http.csv(rd.readLine() ?: throw IOException("empty list")).map { it.trim() }
                fun ix(n: String) = head.indexOf(n)
                val iEx = ix("SEM_EXM_EXCH_ID"); val iId = ix("SEM_SMST_SECURITY_ID"); val iIn = ix("SEM_INSTRUMENT_NAME")
                val iTs = ix("SEM_TRADING_SYMBOL"); val iExp = ix("SEM_EXPIRY_DATE"); val iSt = ix("SEM_STRIKE_PRICE")
                val iOp = ix("SEM_OPTION_TYPE"); val iSe = ix("SEM_SERIES"); val iCu = ix("SEM_CUSTOM_SYMBOL")
                if (iEx < 0 || iId < 0 || iIn < 0 || iTs < 0) throw IOException("unexpected list format")
                tmp.bufferedWriter().use { w ->
                    fun put(k: String, id: String, seg: String, instr: String) { w.write("$k\t$id\t$seg\t$instr\n") }
                    while (true) {
                        val line = rd.readLine() ?: break
                        val c = Http.csv(line)
                        fun g(i: Int) = if (i in c.indices) c[i].trim() else ""
                        val ex = g(iEx); val id = g(iId); val ins = g(iIn); val ts = g(iTs)
                        if (id.isEmpty() || ts.isEmpty()) continue
                        when {
                            ins == "EQUITY" && (ex == "NSE" || ex == "BSE") -> {
                                val se = g(iSe)
                                if (ex == "NSE" && (se.isEmpty() || se == "EQ")) put("EQ|$ts", id, "NSE_EQ", "EQUITY")
                            }
                            ins == "INDEX" -> {
                                val seg = "IDX_I"
                                put("IDX|" + norm(ts), id, seg, "INDEX")
                                val cu = g(iCu); if (cu.isNotEmpty()) put("IDX|" + norm(cu), id, seg, "INDEX")
                            }
                            (ins.startsWith("FUT") || ins.startsWith("OPT")) && (ex == "NSE" || ex == "BSE" || ex == "MCX") -> {
                                val exp = try { LocalDate.parse(g(iExp).take(10)) } catch (e: Exception) { null } ?: continue
                                if (exp.isBefore(today)) continue
                                val isOpt = ins.startsWith("OPT")
                                val parts = ts.split('-')
                                val drop = if (isOpt) 3 else 2
                                if (parts.size <= drop) continue
                                val und = parts.dropLast(drop).joinToString("-")
                                val opt = if (isOpt) g(iOp).uppercase() else "FUT"
                                val strike = if (isOpt) (g(iSt).toDoubleOrNull() ?: 0.0) else 0.0
                                val seg = when (ex) { "NSE" -> "NSE_FNO"; "BSE" -> "BSE_FNO"; else -> "MCX_COMM" }
                                put(dkey(if (ex == "NSE") "NFO" else if (ex == "BSE") "BFO" else "MCX", und, exp, strike, opt), id, seg, ins)
                            }
                        }
                    }
                }
            }
            tmp.renameTo(out)
        }
    }

    /** Dhan reference for an Angel (exchange, token), or null if it is not listed at Dhan. */
    fun refFor(exch: String, token: String): Ref? {
        ensure()
        if (exch == "NSE" || exch == "BSE") {
            Instruments.indexNames(token)?.forEach { n -> map["IDX|$n"]?.let { return it } }
            if (Instruments.indexNames(token) != null) return null
        }
        val i = Instruments.resolve(exch, token) ?: return null
        return refFor(i)
    }

    fun refFor(i: Instrument): Ref? {
        ensure()
        if (!i.isDeriv) return map["EQ|" + i.symbol.removeSuffix("-EQ")]
        val e = i.expiry ?: return null
        val opt = if (i.isOption) (if (i.symbol.endsWith("PE")) "PE" else "CE") else "FUT"
        return map[dkey(i.exch, i.name, e, if (i.isOption) i.strike else 0.0, opt)]
    }
}

/** Dhan (DhanHQ API v2). Login = client id + access token, or client id + PIN + TOTP secret for an automatic daily token. */
object DhanBroker : Broker {
    override val id = "dhan"
    override val label = "Dhan"
    override val fields = listOf(
        Field("cid", "Client ID"),
        Field("tok", "Access token (from web.dhan.co > API)", secret = true),
        Field("pin", "PIN (optional, for automatic token)", secret = true, numeric = true),
        Field("totp", "TOTP secret (optional, for automatic token)", secret = true))
    override val help = "Either paste the access token from web.dhan.co (valid 24 hours), or fill PIN and TOTP secret so the app creates a fresh token itself. Dhan needs the data API enabled and orders only work from your registered static IP."

    private const val BASE = "https://api.dhan.co/v2"
    private val JSON = "application/json".toMediaType()
    private val IST = ZoneId.of("Asia/Kolkata")
    private val lastCall = HashMap<String, Long>()
    @Volatile private var genToken = ""

    private fun v(k: String) = Brokers.get(this, k)
    override fun hasCreds() = v("cid").isNotBlank() && (v("tok").isNotBlank() || (v("pin").isNotBlank() && v("totp").isNotBlank()))
    override fun reset() { genToken = "" }

    private fun throttle(key: String, gap: Long) {
        synchronized(lastCall) {
            val wait = (lastCall[key] ?: 0L) + gap - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall[key] = System.currentTimeMillis()
        }
    }

    private fun auto() = v("pin").isNotBlank() && v("totp").isNotBlank()

    private fun token(force: Boolean = false): String {
        if (!auto()) return v("tok")
        if (!force && genToken.isNotEmpty()) return genToken
        if (!force) Store.sget("dhan.gen").let { if (it.isNotEmpty()) { genToken = it; return it } }
        val url = "https://auth.dhan.co/app/generateAccessToken?dhanClientId=${v("cid")}&pin=${v("pin")}&totp=${Totp.now(v("totp"))}"
        Http.client.newCall(Request.Builder().url(url).post("".toRequestBody(JSON)).build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            val j = try { JSONObject(t) } catch (e: Exception) { Http.fail("Dhan login failed (HTTP ${r.code})") }
            val tk = j.optString("accessToken")
            if (tk.isEmpty()) Http.fail("Dhan login failed: " + (j.optString("message").ifBlank { j.optString("errorMessage", t.take(150)) }))
            genToken = tk; Store.sput("dhan.gen", tk)
            return tk
        }
    }

    /** JSON call with one automatic token refresh. Returns a JSONObject or JSONArray. */
    private fun call(path: String, body: JSONObject?, retry: Boolean = true): Any {
        val rb = Request.Builder().url(BASE + path)
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .header("access-token", token()).header("client-id", v("cid"))
        if (body != null) rb.post(body.toString().toRequestBody(JSON)) else rb.get()
        Http.client.newCall(rb.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            val parsed: Any = try { if (t.trimStart().startsWith("[")) JSONArray(t) else JSONObject(t) } catch (e: Exception) { Http.fail("Dhan: HTTP ${r.code} ${t.take(150)}") }
            if (parsed is JSONObject) {
                val code = parsed.optString("errorCode")
                val msg = parsed.optString("errorMessage")
                if (r.code == 401 || code == "DH-901" || code == "DH-902" || msg.contains("token", true)) {
                    if (retry && auto()) { token(force = true); return call(path, body, false) }
                    Http.fail("Dhan: " + msg.ifBlank { "access token rejected - paste a fresh token on the Account tab" })
                }
                if (code.isNotEmpty() || parsed.optString("status") == "failed") Http.fail("Dhan: " + msg.ifBlank { code })
            } else if (!r.isSuccessful) Http.fail("Dhan: HTTP ${r.code}")
            return parsed
        }
    }

    private fun obj(path: String, body: JSONObject?) = call(path, body) as? JSONObject ?: JSONObject()

    private fun ref(exch: String, token: String) = DhanMaster.refFor(exch, token)
        ?: throw IOException("Not listed at Dhan: " + (Instruments.resolve(exch, token)?.symbol ?: Instruments.indexSymbol(token) ?: token))

    override fun quotes(items: List<Pair<String, String>>): List<JSONObject> {
        data class Req(val exch: String, val token: String, val ref: DhanMaster.Ref, val sym: String)
        val reqs = items.mapNotNull { (e, t) ->
            val r = DhanMaster.refFor(e, t) ?: return@mapNotNull null
            Req(e, t, r, Instruments.resolve(e, t)?.symbol ?: Instruments.indexSymbol(t) ?: t)
        }
        val out = ArrayList<JSONObject>()
        reqs.chunked(500).forEachIndexed { n, chunk ->
            val body = JSONObject()
            chunk.groupBy({ it.ref.seg }, { it.ref.secId.toLong() }).forEach { (s, ids) -> body.put(s, JSONArray(ids)) }
            throttle("q", 1100)
            val data = obj("/marketfeed/quote", body).optJSONObject("data") ?: JSONObject()
            for (q in chunk) {
                val o = data.optJSONObject(q.ref.seg)?.optJSONObject(q.ref.secId) ?: continue
                val ltp = o.optDouble("last_price", 0.0)
                val close = o.optJSONObject("ohlc")?.optDouble("close", 0.0) ?: 0.0
                val chg = if (o.has("net_change")) o.optDouble("net_change", 0.0) else if (close > 0) ltp - close else 0.0
                val pct = if (close > 0) chg / close * 100.0 else 0.0
                out.add(JSONObject().put("symbolToken", q.token).put("exchange", q.exch).put("tradingSymbol", q.sym)
                    .put("ltp", ltp).put("netChange", chg).put("percentChange", pct).put("close", close))
            }
        }
        return out
    }

    private fun series(j: JSONObject): List<DoubleArray> {
        val ts = j.optJSONArray("timestamp") ?: return emptyList()
        val o = j.optJSONArray("open"); val h = j.optJSONArray("high"); val l = j.optJSONArray("low"); val c = j.optJSONArray("close")
        if (o == null || h == null || l == null || c == null) return emptyList()
        return (0 until ts.length()).map { doubleArrayOf(ts.getDouble(it), o.getDouble(it), h.getDouble(it), l.getDouble(it), c.getDouble(it)) }
    }

    override fun candles(exch: String, token: String): List<Candle> {
        val r = ref(exch, token)
        var to = LocalDate.now().plusDays(1)
        val all = ArrayList<Candle>()
        for (i in 0 until 4) {
            val from = to.minusYears(5)
            throttle("h", 300)
            val body = JSONObject().put("securityId", r.secId).put("exchangeSegment", r.seg).put("instrument", r.instr)
                .put("expiryCode", 0).put("oi", false).put("fromDate", from.toString()).put("toDate", to.toString())
            val rows = try { series(obj("/charts/historical", body)) } catch (e: IOException) { if (i == 0) throw e else break }
            if (rows.isEmpty()) break
            rows.forEach { a -> all.add(Candle(Instant.ofEpochSecond(a[0].toLong()).atZone(IST).toLocalDate(), a[1], a[2], a[3], a[4])) }
            to = from
        }
        return all.distinctBy { it.date }.sortedBy { it.date }
    }

    override fun candles5(exch: String, token: String): List<Bar> {
        val r = ref(exch, token)
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val to = LocalDateTime.now(IST)
        throttle("h", 300)
        val body = JSONObject().put("securityId", r.secId).put("exchangeSegment", r.seg).put("instrument", r.instr)
            .put("interval", "5").put("oi", false).put("fromDate", to.minusDays(6).format(f)).put("toDate", to.format(f))
        return series(obj("/charts/intraday", body)).map { a ->
            Bar(Instant.ofEpochSecond(a[0].toLong()).atZone(IST).toLocalDateTime(), a[2], a[3], a[4])
        }.sortedBy { it.t }
    }

    override fun holdings(): List<Holding> {
        val d = try { call("/holdings", null) } catch (e: IOException) {
            if ((e.message ?: "").contains("no holding", true)) return emptyList() else throw e
        }
        val arr = d as? JSONArray ?: return emptyList()
        data class H(val sym: String, val qty: Int, val avg: Double, val secId: String)
        val hs = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            H(it.optString("tradingSymbol"), it.optInt("totalQty"), it.optDouble("avgCostPrice", 0.0), it.optString("securityId"))
        }.filter { it.qty > 0 }
        val tok = hs.associate { it.sym to (Instruments.get(it.sym)?.token ?: "") }
        val q = try { quotes(hs.mapNotNull { h -> tok[h.sym]?.takeIf { it.isNotEmpty() }?.let { "NSE" to it } }) } catch (e: Exception) { emptyList() }
        val ltpBy = q.associate { it.optString("symbolToken") to it.optDouble("ltp", 0.0) }
        return hs.map { h ->
            val t = tok[h.sym].orEmpty()
            val ltp = ltpBy[t] ?: 0.0
            val pnl = if (ltp > 0) (ltp - h.avg) * h.qty else 0.0
            Holding(h.sym, t, h.qty, h.avg, ltp, pnl, if (h.avg > 0 && ltp > 0) (ltp / h.avg - 1) * 100 else 0.0)
        }
    }

    override fun orders(): List<OrderRow> {
        val arr = call("/orders", null) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            OrderRow(it.optString("tradingSymbol"), it.optString("transactionType"), it.optString("quantity"),
                it.optString("price"), it.optString("orderStatus"), it.optString("orderType"),
                it.optString("createTime").ifBlank { it.optString("updateTime") })
        }.reversed()
    }

    private fun product(p: String) = when (p) { "DELIVERY" -> "CNC"; "CARRYFORWARD" -> "MARGIN"; else -> p }

    override fun order(i: Instrument, side: String, qty: Int, limit: Double?, product: String,
                       orderType: String?, trigger: Double?, variety: String): String {
        val r = DhanMaster.refFor(i) ?: throw IOException("${i.symbol} is not listed at Dhan")
        val type = when {
            orderType == "STOPLOSS_LIMIT" -> "STOP_LOSS"
            orderType == "STOPLOSS_MARKET" -> "STOP_LOSS_MARKET"
            orderType != null -> orderType
            limit == null -> "MARKET"
            else -> "LIMIT"
        }
        val b = JSONObject().put("dhanClientId", v("cid")).put("transactionType", side)
            .put("exchangeSegment", r.seg).put("productType", product(product)).put("orderType", type)
            .put("validity", "DAY").put("securityId", r.secId).put("quantity", qty).put("disclosedQuantity", 0)
            .put("price", limit ?: 0.0).put("afterMarketOrder", false)
        if (trigger != null) b.put("triggerPrice", trigger)
        throttle("o", 300)
        val j = obj("/orders", b)
        return j.optString("orderId")
    }

    override fun gttSell(sym: String, token: String, qty: Int, trigger: Double, price: Double): String {
        val r = DhanMaster.refFor("NSE", token) ?: throw IOException("$sym is not listed at Dhan")
        val b = JSONObject().put("dhanClientId", v("cid")).put("orderFlag", "SINGLE").put("transactionType", "SELL")
            .put("exchangeSegment", r.seg).put("productType", "CNC").put("orderType", "LIMIT").put("validity", "DAY")
            .put("securityId", r.secId).put("quantity", qty).put("disclosedQuantity", 0)
            .put("price", price).put("triggerPrice", trigger)
        throttle("o", 300)
        return obj("/forever/orders", b).optString("orderId")
    }
}
