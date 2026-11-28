# Broker file format (Trading Hub)

Reply with ONE JSON file for the broker. Use the broker's OFFICIAL API documentation (search it). The app turns this file into a working broker: login, prices, history, holdings, orders, place order. Never invent endpoints: if the docs do not have something, leave that section out.

## Placeholders
- `{{key}}` is replaced by a login field, a `vars` value, or a value saved by a login step.
- `{{n:qty}}` sends a NUMBER, `{{j:idsByExchange}}` sends raw JSON.
- `{{totp}}` = current 6-digit code, made from the field named in `totpField`.

## Top level
- `schema`: 1. `id`: lowercase letters/digits (2-24). `name`: shown in the dropdown. `help`: one or two lines on where to get the keys.
- `fields`: what the user types. `{"key","label","secret":true/false,"numeric":true/false,"optional":true/false}`. Keep the TOTP SECRET in a field named e.g. `totpSecret` (never name a field `totp`). If the broker gives a daily access token that must be pasted, add a field `token` and leave `login` out.
- `values` (optional): `{"fieldKey":"value"}` credentials to import; they are stored encrypted and removed from the file.
- `totpField`: key of the field holding the TOTP secret (only if login needs a code).
- `vars`: constants, e.g. `{"base":"https://api.broker.com"}`.
- `login`: list of steps run in order. Step = `{method,url,headers,query,json|form,save}`. `save` copies values from the reply into session variables: `{"token":"data.jwtToken"}` (dot path, `*` = any key). Later steps and all later calls can use `{{token}}`.
- `headers`: sent with every call after login (e.g. `"Authorization":"Bearer {{token}}"`).
- `ok`: `{"path":"status","values":["true","success","Ok"]}` - reply is a success when that field equals one of the values. `errorPaths`: list of paths with the error text, e.g. `["message"]`.
- `authErrors`: `{"status":[401],"textContains":["invalid token","session expired"]}` - when seen, the app logs in again once.

## ids (VERY IMPORTANT: instrument ids differ per broker)
The app knows every stock from Angel One's public list (symbol + Angel token). Each broker names the same stock differently, so say how to convert:
- `"mode":"angelToken"`: the broker uses the NSE/BSE exchange token (same number as Angel One). Add `exchangeMap` if the broker names exchanges differently, e.g. `{"NSE":"NSE_EQ","NFO":"NSE_FO","BSE":"BSE_EQ","BFO":"BSE_FO","MCX":"MCX_FO"}`, and `equitySuffix` (e.g. `"-EQ"`) for the trading symbol.
- `"mode":"symbol"`: the broker uses a text symbol, e.g. `"symbolTemplate":"NSE:{{symbol}}-EQ"`. Optional `indexMap`: `{"NIFTY50":"NSE:NIFTY50-INDEX"}` (names upper case, letters and digits only). Futures/options are not available in this mode.
- `"mode":"master"`: the broker has its own instrument list (CSV or JSON array). Give `master`: `{"url","format":"csv|json","delimiter":",","auth":false,"columns":{"id","exchange","symbol","tsym","type","expiry","strike","option","underlying","series"},"expiryFormat":"yyyy-MM-dd | epochMs | epochSec","strikeScale":1,"indexTypes":["INDEX"],"equityTypes":["EQUITY"],"equitySeries":"EQ"}`. `columns` maps our names to the file's column names (leave out what does not exist). Always also give `exchangeMap`. Stocks are matched by symbol, F&O/MCX by underlying + expiry + strike + CE/PE.

## quotes (required) - live price for many instruments
`{method,url,query|json|form,chunk,minGapMs,idsNumeric,idSeparator,rows,map}`
Placeholders for the request: `{{ids}}` (comma list), `{{j:idsJson}}` (JSON array), `{{j:idsByExchange}}` (`{"NSE_EQ":[ids]}`).
`rows`: path to the list in the reply; use `*` when the reply is keyed by id, e.g. `data.*.*`. `map`: `id` (path to the id inside each row, or `"$key"` if the id is the key), `ltp`, `close` (previous close), optional `change`, `pct`.

## daily / intraday (optional) - candles
`{method,url,query|json,dateFormat,years,maxChunks,minGapMs,rows,format,map,timeFormat}`
Placeholders: `{{id}}`, `{{exchange}}`, `{{tsym}}`, `{{from}}`, `{{to}}`. `format`: `arrays` (map = positions `{"t":0,"o":1,"h":2,"l":3,"c":4}`), `columns` (map = names of arrays), or `objects` (map = field names). `timeFormat`: `iso`, `epochSec` or `epochMs`. `years` = years per request, `maxChunks` = requests going back in time (daily needs about 20 years). Use `"source":"yahoo"` instead to take free NSE stock/index history from Yahoo Finance when the broker has no history API. If a section is left out, Yahoo is used.

## holdings, orders (optional)
`{method,url,rows,map,emptyTextContains}`. holdings map: `symbol`,`qty`,`avg`,`ltp`,`pnl` (ltp/pnl optional). orders map: `symbol`,`side`,`qty`,`price`,`status`,`type`,`time`. `emptyTextContains`: error texts that just mean "nothing there", e.g. `["no holdings"]`.

## placeOrder (optional), gtt (optional)
`{method,url,json|form,typeMap,productMap,sideMap,orderIdPath,minGapMs}`
Placeholders: `{{id}} {{exchange}} {{tsym}} {{symbol}} {{side}} {{n:qty}} {{n:price}} {{n:trigger}} {{product}} {{type}} {{variety}}`.
Our values: side BUY/SELL; product DELIVERY / INTRADAY / CARRYFORWARD; type MARKET / LIMIT / STOPLOSS_LIMIT / STOPLOSS_MARKET. `typeMap`/`productMap` translate them to the broker's words; a type missing from `typeMap` is treated as not supported. `gtt` (stop-loss that waits for a trigger, sells at `{{n:trigger}}`/`{{n:price}}`) uses `{{id}} {{exchange}} {{tsym}} {{n:qty}} {{n:trigger}} {{n:price}}`.

## Example (an Angel One style broker)
```json
{
  "schema": 1, "id": "examplebroker", "name": "Example Broker",
  "help": "Create an API app at the broker site to get the key.",
  "fields": [
    {"key":"apiKey","label":"API key","secret":true},
    {"key":"client","label":"Client code"},
    {"key":"pin","label":"PIN","secret":true,"numeric":true},
    {"key":"totpSecret","label":"TOTP secret","secret":true}
  ],
  "totpField": "totpSecret",
  "vars": {"base":"https://apiconnect.angelone.in"},
  "login": [{
    "method":"POST","url":"{{base}}/rest/auth/angelbroking/user/v1/loginByPassword",
    "headers":{"X-PrivateKey":"{{apiKey}}","X-UserType":"USER","X-SourceID":"WEB","X-ClientLocalIP":"127.0.0.1","X-ClientPublicIP":"127.0.0.1","X-MACAddress":"00:00:00:00:00:00","Content-Type":"application/json"},
    "json":{"clientcode":"{{client}}","password":"{{pin}}","totp":"{{totp}}"},
    "save":{"token":"data.jwtToken"}
  }],
  "headers": {"Authorization":"Bearer {{token}}","X-PrivateKey":"{{apiKey}}","X-UserType":"USER","X-SourceID":"WEB","X-ClientLocalIP":"127.0.0.1","X-ClientPublicIP":"127.0.0.1","X-MACAddress":"00:00:00:00:00:00","Content-Type":"application/json"},
  "ok": {"path":"status","values":["true"]},
  "errorPaths": ["message"],
  "authErrors": {"status":[401],"textContains":["AG8001","AG8002","AG8003","Invalid Token"]},
  "ids": {"mode":"angelToken","equitySuffix":"-EQ"},
  "quotes": {
    "method":"POST","url":"{{base}}/rest/secure/angelbroking/market/v1/quote/",
    "json":{"mode":"FULL","exchangeTokens":"{{j:idsByExchange}}"},
    "chunk":50,"minGapMs":1100,"rows":"data.fetched",
    "map":{"id":"symbolToken","ltp":"ltp","close":"close","change":"netChange","pct":"percentChange"}
  },
  "daily": {
    "method":"POST","url":"{{base}}/rest/secure/angelbroking/historical/v1/getCandleData",
    "json":{"exchange":"{{exchange}}","symboltoken":"{{id}}","interval":"ONE_DAY","fromdate":"{{from}}","todate":"{{to}}"},
    "dateFormat":"yyyy-MM-dd HH:mm","years":5,"maxChunks":4,"rows":"data","format":"arrays",
    "map":{"t":0,"o":1,"h":2,"l":3,"c":4},"timeFormat":"iso","minGapMs":450
  },
  "intraday": {
    "method":"POST","url":"{{base}}/rest/secure/angelbroking/historical/v1/getCandleData",
    "json":{"exchange":"{{exchange}}","symboltoken":"{{id}}","interval":"FIVE_MINUTE","fromdate":"{{from}}","todate":"{{to}}"},
    "dateFormat":"yyyy-MM-dd HH:mm","rows":"data","format":"arrays","map":{"t":0,"o":1,"h":2,"l":3,"c":4},"timeFormat":"iso","minGapMs":450
  },
  "holdings": {"method":"GET","url":"{{base}}/rest/secure/angelbroking/portfolio/v1/getAllHolding","rows":"data.holdings",
    "map":{"symbol":"tradingsymbol","qty":"quantity","avg":"averageprice","ltp":"ltp","pnl":"profitandloss"}},
  "orders": {"method":"GET","url":"{{base}}/rest/secure/angelbroking/order/v1/getOrderBook","rows":"data",
    "map":{"symbol":"tradingsymbol","side":"transactiontype","qty":"quantity","price":"price","status":"status","type":"ordertype","time":"updatetime"}},
  "placeOrder": {
    "method":"POST","url":"{{base}}/rest/secure/angelbroking/order/v1/placeOrder",
    "json":{"variety":"{{variety}}","tradingsymbol":"{{tsym}}","symboltoken":"{{id}}","transactiontype":"{{side}}","exchange":"{{exchange}}","ordertype":"{{type}}","producttype":"{{product}}","duration":"DAY","price":"{{price}}","triggerprice":"{{trigger}}","quantity":"{{qty}}"},
    "typeMap":{"MARKET":"MARKET","LIMIT":"LIMIT","STOPLOSS_LIMIT":"STOPLOSS_LIMIT","STOPLOSS_MARKET":"STOPLOSS_MARKET"},
    "orderIdPath":"data.orderid"
  }
}
```

## Output rules
Reply with the JSON only (in one code block), plus one line listing anything the broker does not offer. Do not put real passwords or keys in the file unless the user asks you to add them under `values`.
