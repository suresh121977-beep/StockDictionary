package com.example.angelwatch

import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One login field shown on the Account screen for a broker. */
data class Field(val key: String, val label: String, val secret: Boolean = false, val numeric: Boolean = false)

/**
 * A trading account the app can run on. All screens talk to [Api], which forwards to the selected broker.
 * Instruments are identified the Angel One way (exchange + token, from the public scrip master);
 * each broker translates that to its own ids. Blocking calls: use a background thread.
 */
interface Broker {
    val id: String
    val label: String
    val fields: List<Field>
    val help: String
    fun hasCreds(): Boolean
    /** Forget the current login session (credentials changed, or the broker was switched). */
    fun reset()

    /** items = (exchange, angelToken). Returns Angel-shaped rows: symbolToken, exchange, tradingSymbol, ltp, netChange, percentChange, close. */
    fun quotes(items: List<Pair<String, String>>): List<JSONObject>
    fun candles(exch: String, token: String): List<Candle>
    fun candles5(exch: String, token: String): List<Bar>
    fun holdings(): List<Holding>
    fun orders(): List<OrderRow>
    /** product: DELIVERY, INTRADAY or CARRYFORWARD. orderType null = MARKET/LIMIT; STOPLOSS_LIMIT otherwise. */
    fun order(i: Instrument, side: String, qty: Int, limit: Double?, product: String,
              orderType: String? = null, trigger: Double? = null, variety: String = "NORMAL"): String
    fun gttSell(sym: String, token: String, qty: Int, trigger: Double, price: Double): String
}

/** The brokers in the dropdown, their saved logins and the active choice. */
object Brokers {
    val builtIn: List<Broker> get() = listOf(AngelBroker, DhanBroker, KotakBroker)
    /** Built-in brokers plus every broker file the user added. */
    val all: List<Broker> get() = builtIn + ProfileStore.list()

    fun byId(id: String?): Broker = all.firstOrNull { it.id == id } ?: AngelBroker
    fun current(): Broker = byId(Store.broker)

    /** Saved values for a broker's fields (Angel One keeps its original storage so existing logins still work). */
    fun load(b: Broker): Map<String, String> =
        if (b.id == "angel") Store.creds().let { mapOf("k" to it.apiKey, "c" to it.client, "p" to it.pin, "t" to it.totp) }
        else b.fields.associate { it.key to Store.sget(b.id + "." + it.key) }

    fun save(b: Broker, v: Map<String, String>) {
        if (b.id == "angel") Store.saveCreds(Creds(v["k"] ?: "", v["c"] ?: "", v["p"] ?: "", v["t"] ?: ""))
        else b.fields.forEach { Store.sput(b.id + "." + it.key, (v[it.key] ?: "").trim()) }
        b.reset()
    }

    fun get(b: Broker, key: String): String = load(b)[key].orEmpty()

    /** Makes [b] the active account and drops everything cached from the previous one. */
    fun activate(b: Broker) {
        all.forEach { it.reset() }
        Store.broker = b.id
        Repo.clearAccountData()
    }
}

/** Every screen calls this; it forwards to whichever broker is selected. */
object Api {
    private val b: Broker get() = Brokers.current()
    fun quotes(items: List<Pair<String, String>>) = b.quotes(items)
    fun candles(exch: String, token: String) = b.candles(exch, token)
    fun candles5(exch: String, token: String) = b.candles5(exch, token)
    fun holdings() = b.holdings()
    fun orders() = b.orders()
    fun order(i: Instrument, side: String, qty: Int, limit: Double?, product: String,
              orderType: String? = null, trigger: Double? = null, variety: String = "NORMAL") =
        b.order(i, side, qty, limit, product, orderType, trigger, variety)
    fun buy(sym: String, token: String, qty: Int, limit: Double?): String =
        b.order(Instrument(sym, token, sym), "BUY", qty, limit, "DELIVERY")
    fun gttSell(sym: String, token: String, qty: Int, trigger: Double, price: Double) = b.gttSell(sym, token, qty, trigger, price)
}

internal object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** Split one CSV line, honouring "quoted, fields". */
    fun csv(line: String, delim: Char = ','): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) when {
            ch == '"' -> q = !q
            ch == delim && !q -> { out.add(sb.toString()); sb.setLength(0) }
            else -> sb.append(ch)
        }
        out.add(sb.toString()); return out
    }

    fun fail(msg: String): Nothing = throw IOException(Net.explain(msg))
}

/** Connection check for the Account screen: tries login + price, history, holdings and orders and says what worked. */
fun testBroker(b: Broker): List<String> {
    val out = ArrayList<String>()
    fun step(n: String, f: () -> String) {
        try { out.add("OK    $n: " + f()) } catch (e: Throwable) { out.add("FAIL  $n: " + (e.message ?: e.toString()).take(240)) }
    }
    val ins = Instruments.get("RELIANCE") ?: Instruments.get("TCS")
    if (ins == null) return listOf("FAIL  Stock list not loaded yet. Open Account > Refresh stock list, then test again.")
    if (!b.hasCreds()) return listOf("FAIL  Fill in all required fields first.")
    b.reset()
    step("Login + price (${ins.symbol})") {
        val q = b.quotes(listOf(ins.exch to ins.token))
        if (q.isEmpty()) throw IOException("The broker returned no price. Check the quotes section and the id mapping.")
        "Rs " + f2(q[0].optDouble("ltp", 0.0))
    }
    step("Daily history") { val c = b.candles(ins.exch, ins.token); if (c.isEmpty()) throw IOException("no candles"); "${c.size} days, last ${c.last().date}" }
    step("5-minute history") { "${b.candles5(ins.exch, ins.token).size} candles" }
    step("Holdings") { "${b.holdings().size} found" }
    step("Orders") { "${b.orders().size} today" }
    return out
}
