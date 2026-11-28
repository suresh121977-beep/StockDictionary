package com.example.angelwatch

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Broker files the user added (Account > Add broker). Stored in the app's private folder. */
object ProfileStore {
    @Volatile private var cache: List<ProfileBroker>? = null
    private fun dir() = File(Store.appCtx.filesDir, "brokers").also { it.mkdirs() }

    @Synchronized
    fun list(): List<ProfileBroker> {
        cache?.let { return it }
        val l = ArrayList<ProfileBroker>()
        try {
            dir().listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
                try { l.add(ProfileBroker(JSONObject(f.readText()))) } catch (e: Exception) { }
            }
        } catch (e: Throwable) { }
        cache = l
        return l
    }

    /** Checks the file text, saves it and returns the new broker. Throws IOException with a readable reason. */
    @Synchronized
    fun add(text: String, nameOverride: String = ""): ProfileBroker {
        val j = try { JSONObject(text.trim()) } catch (e: Exception) { throw IOException("This is not valid JSON: ${e.message}") }
        if (nameOverride.isNotBlank()) j.put("name", nameOverride.trim())
        val id = j.optString("id").lowercase()
        if (!Regex("[a-z0-9_]{2,24}").matches(id)) throw IOException("\"id\" must be 2-24 lowercase letters, digits or _")
        if (id in setOf("angel", "dhan", "kotak")) throw IOException("\"$id\" is already built in. Use another id.")
        j.put("id", id)
        if (j.optString("name").isBlank()) throw IOException("The file has no \"name\"")
        val fl = j.optJSONArray("fields") ?: throw IOException("The file has no \"fields\" list")
        for (i in 0 until fl.length()) if (fl.optJSONObject(i)?.optString("key").isNullOrBlank()) throw IOException("Every field needs a \"key\"")
        for (sec in listOf("ids", "quotes")) if (j.optJSONObject(sec) == null) throw IOException("The file has no \"$sec\" section")
        // credentials inside the file go to encrypted storage and are NOT kept in the saved copy
        j.optJSONObject("values")?.let { v ->
            v.keys().forEach { k -> Store.sput("$id.$k", v.optString(k)) }
            j.remove("values")
        }
        val b = ProfileBroker(j)   // constructor re-checks the structure
        File(dir(), "$id.json").writeText(j.toString(2))
        cache = null
        return b
    }

    @Synchronized
    fun remove(id: String) {
        File(dir(), "$id.json").delete()
        File(Store.appCtx.filesDir, "master_$id.tsv").delete()
        cache = null
    }
}

/**
 * A broker described by a JSON file instead of code. The file says how to log in, how to ask for prices,
 * history, holdings, orders, how to place orders, and how to turn a stock into that broker's own instrument id.
 * See assets/broker_file_guide.md for the file format.
 */
class ProfileBroker(val p: JSONObject) : Broker {
    private class AuthRejected(m: String) : IOException(m)
    class Id(val id: String, val exch: String, val tsym: String)

    override val id: String = p.getString("id")
    override val label: String = p.optString("name", id)
    override val help: String = p.optString("help")
    override val fields: List<Field> = (p.optJSONArray("fields") ?: JSONArray()).let { a ->
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Field(o.getString("key"), o.optString("label", o.getString("key")), o.optBoolean("secret"), o.optBoolean("numeric"))
        }
    }
    private val optional: Set<String> = (p.optJSONArray("fields") ?: JSONArray()).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("optional") }.map { it.getString("key") }.toSet()
    }
    private val IST = ZoneId.of("Asia/Kolkata")
    private val JSON = "application/json".toMediaType()
    private val session = HashMap<String, String>()
    private val lastCall = HashMap<String, Long>()
    @Volatile private var loggedIn = false

    override fun hasCreds() = Brokers.load(this).let { v -> fields.filter { it.key !in optional }.all { !v[it.key].isNullOrBlank() } }
    override fun reset() { synchronized(session) { session.clear() }; loggedIn = false }

    // ---------------- templates ----------------
    private val PLACEHOLDER = Regex("""\{\{([^}]+)\}\}""")

    private fun ctx(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val m = HashMap<String, String>()
        p.optJSONObject("vars")?.let { v -> v.keys().forEach { k -> m[k] = v.optString(k) } }
        Brokers.load(this).forEach { (k, v) -> m[k] = v }
        synchronized(session) { m.putAll(session) }
        val tf = p.optString("totpField")
        if (tf.isNotEmpty()) { val s = m[tf].orEmpty(); if (s.isNotBlank()) m["totp"] = Totp.now(s) }
        m.putAll(extra)
        return m
    }

    private fun tpl(s: String, c: Map<String, String>): String =
        PLACEHOLDER.replace(s) { c[it.groupValues[1].trim().removePrefix("n:").removePrefix("j:")] ?: "" }

    private fun num(s: String?): Any {
        val d = s?.toDoubleOrNull() ?: return 0
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong() else d
    }

    /** {{n:qty}} = number, {{j:idsByExchange}} = raw JSON, anything else = text. */
    private fun tplAny(x: Any?, c: Map<String, String>): Any? = when (x) {
        is String -> {
            val t = x.trim()
            if (t.startsWith("{{n:") && t.endsWith("}}") && t.count { it == '{' } == 2) num(c[t.removePrefix("{{n:").removeSuffix("}}").trim()])
            else if (t.startsWith("{{j:") && t.endsWith("}}") && t.count { it == '{' } == 2)
                JSONTokener(c[t.removePrefix("{{j:").removeSuffix("}}").trim()] ?: "null").nextValue()
            else tpl(x, c)
        }
        is JSONObject -> JSONObject().also { o -> x.keys().forEach { k -> o.put(k, tplAny(x.get(k), c)) } }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until x.length()) a.put(tplAny(x.get(i), c)) }
        else -> x
    }

    // ---------------- JSON paths ----------------
    /** "a.b.*.c" -> every match as (last key or index, node). "*" walks all children. Empty path = the whole reply. */
    private fun walk(root: Any?, path: String): List<Pair<String, Any?>> {
        val out = ArrayList<Pair<String, Any?>>()
        val segs = if (path.isBlank()) emptyList() else path.split('.')
        fun rec(n: Any?, i: Int, key: String) {
            if (n == null || n == JSONObject.NULL) return
            if (i == segs.size) { out.add(key to n); return }
            val s = segs[i]
            when {
                s == "*" && n is JSONObject -> n.keys().forEach { k -> rec(n.opt(k), i + 1, k) }
                s == "*" && n is JSONArray -> for (k in 0 until n.length()) rec(n.opt(k), i + 1, k.toString())
                n is JSONArray -> s.toIntOrNull()?.let { rec(n.opt(it), i + 1, s) }
                n is JSONObject -> rec(n.opt(s), i + 1, s)
            }
        }
        rec(root, 0, "")
        return out
    }

    private fun str(root: Any?, path: String): String {
        if (path.isBlank()) return ""
        val n = walk(root, path).firstOrNull()?.second ?: return ""
        return if (n == JSONObject.NULL) "" else n.toString()
    }
    private fun dbl(root: Any?, path: String) = str(root, path).toDoubleOrNull() ?: 0.0

    /** Rows of a list reply: an array gives its objects; "*" paths give each matched object with its key. */
    private fun rows(res: Any?, path: String): List<Pair<String, JSONObject>> {
        val out = ArrayList<Pair<String, JSONObject>>()
        for ((k, n) in walk(res, path)) {
            if (n is JSONArray) for (i in 0 until n.length()) n.optJSONObject(i)?.let { out.add(i.toString() to it) }
            else if (n is JSONObject) out.add(k to n)
        }
        return out
    }

    // ---------------- HTTP ----------------
    private fun throttle(key: String, gap: Long) {
        if (gap <= 0) return
        synchronized(lastCall) {
            val wait = (lastCall[key] ?: 0L) + gap - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall[key] = System.currentTimeMillis()
        }
    }

    private fun errMsg(parsed: Any?, text: String): String {
        val ep = p.optJSONArray("errorPaths")
        if (ep != null) for (i in 0 until ep.length()) { val m = str(parsed, ep.getString(i)); if (m.isNotBlank()) return m }
        return text.take(160)
    }

    private fun isAuthErr(code: Int, text: String): Boolean {
        val a = p.optJSONObject("authErrors")
        val sts = a?.optJSONArray("status")
        if (sts == null) { if (code == 401) return true }
        else for (i in 0 until sts.length()) if (sts.optInt(i) == code) return true
        val tc = a?.optJSONArray("textContains")
        if (tc != null) for (i in 0 until tc.length()) if (text.contains(tc.getString(i), true)) return true
        return false
    }

    private fun buildRequest(spec: JSONObject, c: Map<String, String>, auth: Boolean): Request.Builder {
        val method = spec.optString("method", if (spec.has("json") || spec.has("form")) "POST" else "GET").uppercase()
        val base = tpl(spec.getString("url"), c)
        val hu = base.toHttpUrlOrNull() ?: Http.fail("Bad web address in the broker file: $base")
        val ub = hu.newBuilder()
        spec.optJSONObject("query")?.let { q -> q.keys().forEach { k -> ub.addQueryParameter(k, tpl(q.optString(k), c)) } }
        val rb = Request.Builder().url(ub.build()).header("Accept", "application/json")
        if (auth) p.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> rb.header(k, tpl(h.optString(k), c)) } }
        spec.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> rb.header(k, tpl(h.optString(k), c)) } }
        var body: okhttp3.RequestBody? = null
        if (spec.has("json")) body = tplAny(spec.get("json"), c).toString().toRequestBody(JSON)
        else if (spec.has("form")) {
            val f = spec.getJSONObject("form"); val fb = FormBody.Builder()
            f.keys().forEach { k -> fb.add(k, tpl(f.optString(k), c)) }
            body = fb.build()
        }
        if (body == null && method in setOf("POST", "PUT", "PATCH")) body = "".toRequestBody(JSON)
        if (method == "GET") body = null
        return rb.method(method, body)
    }

    /** One request. Returns the parsed JSON (JSONObject / JSONArray) or null if the reply was not JSON. */
    private fun doRequest(spec: JSONObject, c: Map<String, String>, auth: Boolean): Any? {
        val rb = buildRequest(spec, c, auth)
        Http.client.newCall(rb.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            val s = t.trim()
            val parsed: Any? = try { if (s.startsWith("[")) JSONArray(s) else if (s.startsWith("{")) JSONObject(s) else null } catch (e: Exception) { null }
            if (auth && isAuthErr(r.code, t)) throw AuthRejected("session rejected: " + errMsg(parsed, t))
            val ok = p.optJSONObject("ok")
            if (ok != null && parsed != null) {
                val node = walk(parsed, ok.optString("path")).firstOrNull()?.second
                if (node != null) {
                    val vals = ok.optJSONArray("values") ?: JSONArray()
                    val good = (0 until vals.length()).any { vals.get(it).toString().equals(node.toString(), true) }
                    if (!good) Http.fail(label + ": " + errMsg(parsed, t))
                }
            }
            if (!r.isSuccessful && (ok == null || parsed == null || walk(parsed, ok.optString("path")).isEmpty()))
                Http.fail("$label: HTTP ${r.code} " + errMsg(parsed, t))
            return parsed
        }
    }

    @Synchronized
    private fun login() {
        reset()
        val steps = p.optJSONArray("login")
        if (steps != null) for (i in 0 until steps.length()) {
            val st = steps.getJSONObject(i)
            val res = try { doRequest(st, ctx(), false) } catch (e: IOException) { throw IOException("Login step ${i + 1} failed: ${e.message}") }
            val save = st.optJSONObject("save")
            save?.keys()?.forEach { k ->
                val v = str(res, save.getString(k))
                if (v.isEmpty()) Http.fail("Login step ${i + 1}: \"${save.getString(k)}\" not found in the broker's reply")
                synchronized(session) { session[k] = v }
            }
        }
        loggedIn = true
    }

    private fun call(spec: JSONObject, extra: Map<String, String> = emptyMap()): Any? {
        if (!loggedIn) login()
        return try { doRequest(spec, ctx(extra), true) } catch (e: AuthRejected) {
            login()
            try { doRequest(spec, ctx(extra), true) } catch (e2: AuthRejected) { Http.fail("$label: ${e2.message}. Check your login details.") }
        }
    }

    private fun sec(name: String): JSONObject = p.optJSONObject(name) ?: Http.fail("This broker file has no \"$name\" section")

    // ---------------- instrument ids ----------------
    private val ids: JSONObject = p.optJSONObject("ids") ?: JSONObject()
    private val exMap: Map<String, String> = ids.optJSONObject("exchangeMap")?.let { o -> o.keys().asSequence().associateWith { k -> o.getString(k) } } ?: emptyMap()
    private fun bex(e: String) = exMap[e] ?: e
    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }
    private val master = Master()

    fun idFor(exch: String, token: String): Id? {
        val idxName = Instruments.indexSymbol(token)
        if (idxName != null && (exch == "NSE" || exch == "BSE")) {
            return when (ids.optString("mode", "angelToken")) {
                "angelToken" -> Id(token, bex(exch), idxName)
                "symbol" -> {
                    val m = ids.optJSONObject("indexMap") ?: return null
                    val t = Instruments.indexNames(token)?.firstNotNullOfOrNull { n -> m.optString(n).takeIf { it.isNotEmpty() } } ?: return null
                    Id(t, bex("NSEIDX"), t)
                }
                else -> master.refForIndex(token)
            }
        }
        val ins = Instruments.resolve(exch, token) ?: return null
        return idFor(ins)
    }

    fun idFor(i: Instrument): Id? = when (ids.optString("mode", "angelToken")) {
        "angelToken" -> Id(i.token, bex(i.exch), if (i.isDeriv) i.symbol else i.symbol.removeSuffix("-EQ") + ids.optString("equitySuffix", ""))
        "symbol" -> if (i.isDeriv) null else {
            val t = tpl(ids.optString("symbolTemplate", "{{symbol}}"), mapOf("symbol" to i.symbol.removeSuffix("-EQ"), "exchange" to bex(i.exch)))
            Id(t, bex(i.exch), t)
        }
        else -> master.refFor(i)
    }

    private fun need(exch: String, token: String): Id = idFor(exch, token)
        ?: throw IOException("$label does not list " + (Instruments.resolve(exch, token)?.symbol ?: Instruments.indexSymbol(token) ?: token) +
            " (instrument id could not be found). Check the \"ids\" section.")

    /** Downloads the broker's instrument file once and keeps a small lookup file. */
    private inner class Master {
        @Volatile private var map: Map<String, Id> = emptyMap()
        private val cfg: JSONObject get() = ids.optJSONObject("master") ?: Http.fail("$label: ids.mode is \"master\" but there is no ids.master section")
        private fun cols(): JSONObject = cfg.optJSONObject("columns") ?: JSONObject()

        @Synchronized fun ensure() {
            if (map.isNotEmpty()) return
            val f = File(Store.appCtx.filesDir, "master_$id.tsv")
            val maxAge = cfg.optLong("maxAgeDays", 2) * 86_400_000L
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > maxAge) {
                try { download(f) } catch (e: Exception) { if (!f.exists()) throw IOException("$label instrument list: ${e.message}") }
            }
            val m = HashMap<String, Id>()
            f.forEachLine { l -> val a = l.split('\t'); if (a.size >= 4) m[a[0]] = Id(a[1], a[2], a[3]) }
            map = m
        }

        private fun readRows(c: Map<String, String>, wanted: Set<String>, cb: (Map<String, String>) -> Unit) {
            val cf = cfg
            val rb = if (cf.optBoolean("auth")) { if (!loggedIn) login(); buildRequest(cf, c, true) } else Request.Builder().url(tpl(cf.getString("url"), c))
            val client = Http.client.newBuilder().readTimeout(300, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                if (cf.optString("format", "csv").lowercase() == "json") {
                    JsonReader(InputStreamReader(r.body!!.byteStream(), "UTF-8")).use { jr ->
                        jr.beginArray()
                        while (jr.hasNext()) {
                            val row = HashMap<String, String>()
                            jr.beginObject()
                            while (jr.hasNext()) {
                                val n = jr.nextName()
                                if (n in wanted && jr.peek() != JsonToken.BEGIN_OBJECT && jr.peek() != JsonToken.BEGIN_ARRAY) {
                                    row[n] = if (jr.peek() == JsonToken.NULL) { jr.nextNull(); "" } else jr.nextString()
                                } else jr.skipValue()
                            }
                            jr.endObject()
                            cb(row)
                        }
                    }
                } else {
                    val d = (cf.optString("delimiter", ",").firstOrNull() ?: ',')
                    r.body!!.charStream().buffered().use { rd ->
                        val head = Http.csv(rd.readLine() ?: throw IOException("empty file"), d).map { it.trim().trim('"') }
                        val idx = wanted.associateWith { head.indexOf(it) }
                        for ((k, v) in idx) if (v < 0 && k in cols().keys().asSequence().map { cols().getString(it) }.toSet())
                            throw IOException("column \"$k\" not found in the instrument file (columns: ${head.take(25).joinToString(",")})")
                        while (true) {
                            val a = Http.csv(rd.readLine() ?: break, d)
                            val row = HashMap<String, String>()
                            for ((k, v) in idx) if (v >= 0 && v < a.size) row[k] = a[v].trim()
                            cb(row)
                        }
                    }
                }
            }
        }

        private fun expiryOf(s: String): LocalDate? {
            if (s.isBlank()) return null
            return try {
                when (val f = cfg.optString("expiryFormat", "yyyy-MM-dd")) {
                    "epochMs" -> Instant.ofEpochMilli(s.toDouble().toLong()).atZone(IST).toLocalDate()
                    "epochSec" -> Instant.ofEpochSecond(s.toDouble().toLong()).atZone(IST).toLocalDate()
                    else -> LocalDate.parse(s.trim(), java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive()
                        .appendPattern(f).toFormatter(java.util.Locale.ENGLISH))
                }
            } catch (e: Exception) { null }
        }

        private fun jset(k: String, def: List<String>): List<String> =
            cfg.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it).uppercase() } } ?: def

        private fun download(out: File) {
            val col = cols()
            val wanted = col.keys().asSequence().map { col.getString(it) }.toSet()
            fun colName(k: String) = col.optString(k)
            val eqEx = bex("NSE"); val eqSeries = cfg.optString("equitySeries", "EQ"); val scale = cfg.optDouble("strikeScale", 1.0)
            val idxTypes = jset("indexTypes", listOf("INDEX", "AMXIDX", "IDX", "INDICES"))
            val eqTypes = jset("equityTypes", listOf("EQUITY", "EQ", "EQUITIES"))
            val today = LocalDate.now()
            val tmp = File(out.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                fun put(k: String, i: Id) { w.write("$k\t${i.id}\t${i.exch}\t${i.tsym}\n") }
                readRows(ctx(), wanted) { row ->
                    fun g(k: String): String = colName(k).let { if (it.isEmpty()) "" else row[it].orEmpty() }
                    val ex = g("exchange"); val type = g("type").uppercase(); val sym = g("symbol"); val idv = g("id")
                    if (idv.isEmpty() || sym.isEmpty()) return@readRows
                    val me = Id(idv, ex, g("tsym").ifEmpty { sym })
                    when {
                        type in idxTypes -> { put("IDX|" + norm(sym), me); g("underlying").takeIf { it.isNotEmpty() }?.let { put("IDX|" + norm(it), me) } }
                        type.startsWith("FUT") || type.startsWith("OPT") -> {
                            val angelEx = listOf("NFO", "BFO", "MCX").firstOrNull { bex(it) == ex } ?: return@readRows
                            val exp = expiryOf(g("expiry")) ?: return@readRows
                            if (exp.isBefore(today)) return@readRows
                            val isOpt = type.startsWith("OPT")
                            val o = g("option").uppercase().let { if (it.startsWith("C")) "CE" else if (it.startsWith("P")) "PE" else it }
                            val strike = if (isOpt) (g("strike").toDoubleOrNull() ?: 0.0) / scale else 0.0
                            put(dkey(angelEx, g("underlying"), exp, strike, if (isOpt) o else "FUT"), me)
                        }
                        ex == eqEx && (g("series").isEmpty() || g("series") == eqSeries) && (type.isEmpty() || type in eqTypes) -> put("EQ|$sym", me)
                    }
                }
            }
            tmp.renameTo(out)
        }

        private fun dkey(exch: String, und: String, d: LocalDate, strike: Double, opt: String) =
            "D|$exch|${und.uppercase()}|$d|${String.format(java.util.Locale.US, "%.2f", strike)}|$opt"

        fun refForIndex(token: String): Id? {
            ensure()
            Instruments.indexNames(token)?.forEach { n -> map["IDX|$n"]?.let { return it } }
            return null
        }

        fun refFor(i: Instrument): Id? {
            ensure()
            if (!i.isDeriv) return map["EQ|" + i.symbol.removeSuffix("-EQ")]
            val e = i.expiry ?: return null
            val opt = if (i.isOption) (if (i.symbol.endsWith("PE")) "PE" else "CE") else "FUT"
            return map[dkey(i.exch, i.name, e, if (i.isOption) i.strike else 0.0, opt)]
        }
    }

    // ---------------- prices ----------------
    override fun quotes(items: List<Pair<String, String>>): List<JSONObject> {
        val spec = sec("quotes")
        val map = spec.optJSONObject("map") ?: Http.fail("$label: quotes.map is missing")
        val numericIds = spec.optBoolean("idsNumeric")
        val sep = spec.optString("idSeparator", ",")
        data class Req(val exch: String, val token: String, val id: Id, val sym: String)
        val reqs = items.mapNotNull { (e, t) ->
            val i = idFor(e, t) ?: return@mapNotNull null
            Req(e, t, i, Instruments.resolve(e, t)?.symbol ?: Instruments.indexSymbol(t) ?: t)
        }
        val out = ArrayList<JSONObject>()
        val gap = spec.optLong("minGapMs", 1000)
        reqs.chunked(spec.optInt("chunk", 50)).forEach { chunk ->
            val arr = JSONArray(); chunk.forEach { arr.put(if (numericIds) num(it.id.id) else it.id.id) }
            val byEx = JSONObject()
            chunk.groupBy({ it.id.exch }, { it.id.id }).forEach { (e, l) -> byEx.put(e, JSONArray(l.map { if (numericIds) num(it) else it })) }
            throttle("q", gap)
            val res = call(spec, mapOf("ids" to chunk.joinToString(sep) { it.id.id }, "idsJson" to arr.toString(), "idsByExchange" to byEx.toString()))
            val idPath = map.optString("id")
            val byId = chunk.groupBy { it.id.id }
            for ((k, node) in rows(res, spec.optString("rows"))) {
                val rid = if (idPath == "\$key") k else str(node, idPath)
                val q = byId[rid]?.let { l -> l.firstOrNull { it.id.exch == str(node, map.optString("exchange")) } ?: l.first() } ?: continue
                val ltp = dbl(node, map.optString("ltp"))
                var close = dbl(node, map.optString("close"))
                var chg = if (map.has("change") && str(node, map.getString("change")).isNotEmpty()) dbl(node, map.getString("change")) else Double.NaN
                if (chg.isNaN()) chg = if (close > 0) ltp - close else 0.0
                if (close <= 0 && ltp > 0) close = ltp - chg
                val pct = if (map.has("pct") && str(node, map.getString("pct")).isNotEmpty()) dbl(node, map.getString("pct")) else if (close > 0) chg / close * 100.0 else 0.0
                out.add(JSONObject().put("symbolToken", q.token).put("exchange", q.exch).put("tradingSymbol", q.sym)
                    .put("ltp", ltp).put("netChange", chg).put("percentChange", pct).put("close", close))
            }
        }
        return out
    }

    // ---------------- history ----------------
    private fun toDate(x: Any?, tf: String): LocalDate {
        val s = x.toString()
        return when (tf) {
            "epochSec" -> Instant.ofEpochSecond(s.toDouble().toLong()).atZone(IST).toLocalDate()
            "epochMs" -> Instant.ofEpochMilli(s.toDouble().toLong()).atZone(IST).toLocalDate()
            else -> LocalDate.parse(s.take(10))
        }
    }
    private fun toDateTime(x: Any?, tf: String): LocalDateTime {
        val s = x.toString()
        return when (tf) {
            "epochSec" -> Instant.ofEpochSecond(s.toDouble().toLong()).atZone(IST).toLocalDateTime()
            "epochMs" -> Instant.ofEpochMilli(s.toDouble().toLong()).atZone(IST).toLocalDateTime()
            else -> try { OffsetDateTime.parse(s).atZoneSameInstant(IST).toLocalDateTime() } catch (e: Exception) { LocalDateTime.parse(s.replace(' ', 'T')) }
        }
    }

    /** Candle rows as [time, open, high, low, close] lists, in any of the three reply layouts. */
    private fun candleRows(res: Any?, spec: JSONObject): List<List<Any?>> {
        val m = spec.optJSONObject("map") ?: Http.fail("$label: history map is missing")
        val keys = listOf("t", "o", "h", "l", "c")
        val node = walk(res, spec.optString("rows")).firstOrNull()?.second ?: return emptyList()
        return when (spec.optString("format", "arrays")) {
            "columns" -> {
                val o = node as? JSONObject ?: return emptyList()
                val cols = keys.map { o.optJSONArray(m.optString(it)) ?: return emptyList() }
                (0 until cols[0].length()).map { i -> cols.map { it.opt(i) } }
            }
            "objects" -> { val a = node as? JSONArray ?: return emptyList(); (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { r -> keys.map { r.opt(m.optString(it)) } } }
            else -> { val a = node as? JSONArray ?: return emptyList(); (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { r -> keys.map { r.opt(m.optInt(it)) } } }
        }
    }

    private fun d(x: Any?) = x?.toString()?.toDoubleOrNull() ?: 0.0

    override fun candles(exch: String, token: String): List<Candle> {
        val spec = p.optJSONObject("daily")
        if (spec == null || spec.optString("source") == "yahoo") return Yahoo.daily(exch, token)
        val i = need(exch, token)
        val fmt = DateTimeFormatter.ofPattern(spec.optString("dateFormat", "yyyy-MM-dd"))
        val years = spec.optInt("years", 10).toLong(); val tf = spec.optString("timeFormat", "iso")
        var to = LocalDateTime.now(IST).plusDays(1)
        val all = ArrayList<Candle>()
        for (n in 0 until spec.optInt("maxChunks", 1)) {
            val from = to.minusYears(years)
            throttle("h", spec.optLong("minGapMs", 400))
            val res = try { call(spec, mapOf("id" to i.id, "exchange" to i.exch, "tsym" to i.tsym, "from" to from.format(fmt), "to" to to.format(fmt))) }
                      catch (e: IOException) { if (n == 0) throw e else break }
            val r = candleRows(res, spec)
            if (r.isEmpty()) break
            r.forEach { a -> all.add(Candle(toDate(a[0], tf), d(a[1]), d(a[2]), d(a[3]), d(a[4]))) }
            to = from.minusDays(1)
        }
        return all.distinctBy { it.date }.sortedBy { it.date }
    }

    override fun candles5(exch: String, token: String): List<Bar> {
        val spec = p.optJSONObject("intraday")
        if (spec == null || spec.optString("source") == "yahoo") return Yahoo.intraday5(exch, token)
        val i = need(exch, token)
        val fmt = DateTimeFormatter.ofPattern(spec.optString("dateFormat", "yyyy-MM-dd HH:mm:ss"))
        val to = LocalDateTime.now(IST)
        throttle("h", spec.optLong("minGapMs", 400))
        val res = call(spec, mapOf("id" to i.id, "exchange" to i.exch, "tsym" to i.tsym, "from" to to.minusDays(6).format(fmt), "to" to to.format(fmt)))
        val tf = spec.optString("timeFormat", "iso")
        return candleRows(res, spec).map { a -> Bar(toDateTime(a[0], tf), d(a[2]), d(a[3]), d(a[4])) }.sortedBy { it.t }
    }

    // ---------------- account ----------------
    private fun <T> emptyOk(spec: JSONObject, f: () -> List<T>): List<T> = try { f() } catch (e: IOException) {
        val a = spec.optJSONArray("emptyTextContains")
        if (a != null && (0 until a.length()).any { (e.message ?: "").contains(a.getString(it), true) }) emptyList() else throw e
    }

    override fun holdings(): List<Holding> {
        val spec = sec("holdings"); val m = spec.optJSONObject("map") ?: Http.fail("$label: holdings.map is missing")
        return emptyOk(spec) {
            val res = call(spec)
            val hs = rows(res, spec.optString("rows")).map { (_, o) ->
                val sym = str(o, m.optString("symbol")).removeSuffix("-EQ")
                Triple(sym, str(o, m.optString("qty")).toDoubleOrNull()?.toInt() ?: 0, o)
            }.filter { it.second > 0 && it.first.isNotBlank() }
            val tok = hs.associate { it.first to (Instruments.get(it.first)?.token ?: "") }
            val needQ = hs.any { dbl(it.third, m.optString("ltp")) <= 0 }
            val q = if (needQ) try { quotes(hs.mapNotNull { h -> tok[h.first]?.takeIf { it.isNotEmpty() }?.let { "NSE" to it } }) } catch (e: Exception) { emptyList() } else emptyList()
            val ltpBy = q.associate { it.optString("symbolToken") to it.optDouble("ltp", 0.0) }
            hs.map { (sym, qty, o) ->
                val t = tok[sym].orEmpty()
                val avg = dbl(o, m.optString("avg"))
                val ltp = dbl(o, m.optString("ltp")).takeIf { it > 0 } ?: (ltpBy[t] ?: 0.0)
                val pnl = if (m.has("pnl") && str(o, m.getString("pnl")).isNotEmpty()) dbl(o, m.getString("pnl")) else if (ltp > 0) (ltp - avg) * qty else 0.0
                Holding(sym, t, qty, avg, ltp, pnl, if (avg > 0 && ltp > 0) (ltp / avg - 1) * 100 else 0.0)
            }
        }
    }

    override fun orders(): List<OrderRow> {
        val spec = sec("orders"); val m = spec.optJSONObject("map") ?: Http.fail("$label: orders.map is missing")
        return emptyOk(spec) {
            rows(call(spec), spec.optString("rows")).map { (_, o) ->
                val side = str(o, m.optString("side")).uppercase()
                OrderRow(str(o, m.optString("symbol")), if (side.startsWith("B")) "BUY" else if (side.startsWith("S")) "SELL" else side,
                    str(o, m.optString("qty")), str(o, m.optString("price")), str(o, m.optString("status")),
                    str(o, m.optString("type")), str(o, m.optString("time")))
            }
        }
    }

    private fun mapped(spec: JSONObject, key: String, v: String, strict: Boolean): String {
        val mm = spec.optJSONObject(key) ?: return v
        val r = mm.optString(v)
        if (r.isNotEmpty()) return r
        if (strict) throw IOException("$label's broker file has no \"$key\" entry for $v, so this order type is not supported")
        return v
    }

    override fun order(i: Instrument, side: String, qty: Int, limit: Double?, product: String,
                       orderType: String?, trigger: Double?, variety: String): String {
        val spec = sec("placeOrder")
        val id = idFor(i) ?: throw IOException("${i.symbol} is not listed at $label")
        val type = orderType ?: if (limit == null) "MARKET" else "LIMIT"
        val x = mapOf("id" to id.id, "exchange" to id.exch, "tsym" to id.tsym, "symbol" to i.symbol.removeSuffix("-EQ"),
            "side" to mapped(spec, "sideMap", side, false), "qty" to qty.toString(),
            "price" to (limit?.toString() ?: "0"), "trigger" to (trigger?.toString() ?: "0"),
            "product" to mapped(spec, "productMap", product, false), "type" to mapped(spec, "typeMap", type, true), "variety" to variety)
        throttle("o", spec.optLong("minGapMs", 300))
        return str(call(spec, x), spec.optString("orderIdPath"))
    }

    override fun gttSell(sym: String, token: String, qty: Int, trigger: Double, price: Double): String {
        val spec = p.optJSONObject("gtt") ?: throw IOException("$label has no GTT in its broker file. Place the stop-loss as a normal order instead.")
        val id = need("NSE", token)
        throttle("o", spec.optLong("minGapMs", 300))
        return str(call(spec, mapOf("id" to id.id, "exchange" to id.exch, "tsym" to id.tsym, "symbol" to sym, "qty" to qty.toString(),
            "trigger" to trigger.toString(), "price" to price.toString())), spec.optString("orderIdPath"))
    }
}
