package com.example.angelwatch

import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

/**
 * Free price history from Yahoo Finance, used for brokers whose own API has no candle data (Kotak Neo).
 * NSE cash stocks and the main NSE/BSE indices are supported.
 */
internal object Yahoo {
    private val IST = ZoneId.of("Asia/Kolkata")
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val IDX = mapOf(
        "NIFTY50" to "^NSEI", "NIFTYBANK" to "^NSEBANK", "SENSEX" to "^BSESN", "NIFTYIT" to "^CNXIT",
        "NIFTYAUTO" to "^CNXAUTO", "NIFTYFMCG" to "^CNXFMCG", "NIFTYMETAL" to "^CNXMETAL", "NIFTYPHARMA" to "^CNXPHARMA",
        "NIFTYREALTY" to "^CNXREALTY", "NIFTYPSUBANK" to "^CNXPSUBANK", "NIFTYENERGY" to "^CNXENERGY",
        "NIFTYFINSERVICE" to "^CNXFIN", "NIFTYMIDCAP100" to "^CNXMIDCAP", "NIFTYSMLCAP100" to "^CNXSC",
        "NIFTYNEXT50" to "^NSMIDCP", "NIFTYMEDIA" to "^CNXMEDIA", "NIFTYINFRA" to "^CNXINFRA", "INDIAVIX" to "^INDIAVIX"
    )

    fun symbolFor(exch: String, token: String): String {
        if (exch == "BSE" && token == "99919000") return "^BSESN"
        if (exch == "NSE") {
            Instruments.indexNames(token)?.forEach { n -> IDX[n]?.let { return it } }
            if (Instruments.indexNames(token) != null) throw IOException("No price history source for this index on this broker")
            val ins = Instruments.byToken(token)
            if (ins != null) return ins.symbol + ".NS"
        }
        throw IOException("Price history for futures, options and commodities is not available on this broker")
    }

    private fun get(url: String): JSONObject {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
        Http.client.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Price history failed (HTTP ${r.code})")
            return JSONObject(t)
        }
    }

    private fun result(url: String): JSONObject {
        val j = get(url).optJSONObject("chart") ?: throw IOException("Bad price history reply")
        j.optJSONObject("error")?.let { throw IOException(it.optString("description", "Price history error")) }
        return j.optJSONArray("result")?.optJSONObject(0) ?: throw IOException("No price history")
    }

    fun daily(exch: String, token: String): List<Candle> {
        val y = URLEncoder.encode(symbolFor(exch, token), "UTF-8")
        val now = System.currentTimeMillis() / 1000
        val r = result("https://query1.finance.yahoo.com/v8/finance/chart/$y?period1=0&period2=$now&interval=1d&events=history")
        val ts = r.optJSONArray("timestamp") ?: return emptyList()
        val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val o = q.optJSONArray("open"); val h = q.optJSONArray("high"); val l = q.optJSONArray("low"); val c = q.optJSONArray("close")
        if (o == null || h == null || l == null || c == null) return emptyList()
        val out = ArrayList<Candle>()
        for (i in 0 until ts.length()) {
            if (c.isNull(i) || o.isNull(i) || h.isNull(i) || l.isNull(i)) continue
            out.add(Candle(Instant.ofEpochSecond(ts.getLong(i)).atZone(IST).toLocalDate(),
                o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i)))
        }
        return out.distinctBy { it.date }.sortedBy { it.date }
    }

    fun intraday5(exch: String, token: String): List<Bar> {
        val y = URLEncoder.encode(symbolFor(exch, token), "UTF-8")
        val r = result("https://query1.finance.yahoo.com/v8/finance/chart/$y?range=5d&interval=5m")
        val ts = r.optJSONArray("timestamp") ?: return emptyList()
        val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val h = q.optJSONArray("high"); val l = q.optJSONArray("low"); val c = q.optJSONArray("close")
        if (h == null || l == null || c == null) return emptyList()
        val out = ArrayList<Bar>()
        for (i in 0 until ts.length()) {
            if (c.isNull(i) || h.isNull(i) || l.isNull(i)) continue
            out.add(Bar(Instant.ofEpochSecond(ts.getLong(i)).atZone(IST).toLocalDateTime(), h.getDouble(i), l.getDouble(i), c.getDouble(i)))
        }
        return out.sortedBy { it.t }
    }
}
