package com.camnexus.rtsp

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.SecureRandom

/**
 * 网页控制台。
 *
 * 鉴权：先 POST /api/login 用账号密码换取会话令牌（Cookie），
 * 之后所有接口都需要该令牌；闲置超过 idleTimeoutMs 自动失效，需要重新登录。
 *
 * GET  /api/status        运行状态（轮询用，不刷新会话有效期）
 * GET  /api/settings      当前配置
 * GET  /api/capabilities  相机 / 分辨率能力
 * POST /api/settings      修改配置（JSON，部分字段即可）
 * POST /api/login         登录，返回 Set-Cookie
 * GET  /logo.png          App 图标
 * GET  /                  状态 + 设置页面
 */
class HttpStatusServer(
    port: Int,
    private val user: String,
    private val pass: String,
    private val idleTimeoutMs: Long,
    private val statusJson: () -> String,
    private val settingsJson: () -> String,
    private val capabilitiesJson: () -> String,
    private val applySettings: (String) -> String,
    private val requestStream: (Boolean) -> String,
    private val setTorchMinutes: (Int) -> String,
    private val appVersion: () -> String,
    private val logoBytes: () -> ByteArray?
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/api/login" && session.method == Method.POST -> login(session)
                session.uri == "/" -> page(session)
                session.uri == "/logo.png" -> logo()
                !checkSession(session) -> unauthorizedJson()
                session.uri == "/api/status" -> json(statusJson())
                session.uri == "/api/settings" && session.method == Method.POST -> apply(session)
                session.uri == "/api/stream" && session.method == Method.POST -> streamControl(session)
                session.uri == "/api/torch" && session.method == Method.POST -> torchControl(session)
                session.uri == "/api/settings" -> json(settingsJson())
                session.uri == "/api/capabilities" -> json(capabilitiesJson())
                session.uri == "/api/logout" -> logout(session)
                else -> json("{\"error\":\"not found\"}", Response.Status.NOT_FOUND)
            }
        } catch (t: Throwable) {
            json("{\"error\":\"${escape(t.message ?: "internal error")}\"}", Response.Status.INTERNAL_ERROR)
        }
    }

    // ---------------- 会话 ----------------

    private fun login(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        val body = files["postData"] ?: ""
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return json("{\"ok\":false,\"error\":\"请求体不是合法 JSON\"}", Response.Status.BAD_REQUEST)
        val inputUser = json.optString("user")
        val inputPass = json.optString("pass")
        if (inputUser != user || inputPass != pass) {
            return json("{\"ok\":false,\"error\":\"账号或密码错误\"}", Response.Status.UNAUTHORIZED)
        }
        val token = newToken()
        synchronized(sessions) {
            pruneLocked()
            sessions[token] = System.currentTimeMillis()
        }
        val response = json("{\"ok\":true}")
        response.addHeader(
            "Set-Cookie",
            "cnxs=$token; Path=/; HttpOnly; SameSite=Strict"
        )
        return response
    }

    private fun logout(session: IHTTPSession): Response {
        tokenOf(session)?.let { token ->
            synchronized(sessions) { sessions.remove(token) }
        }
        val response = json("{\"ok\":true}")
        response.addHeader("Set-Cookie", "cnxs=; Path=/; HttpOnly; Max-Age=0")
        return response
    }

    /** 校验会话；轮询状态（/api/status）不续期，避免页面一直开着就永不过期。 */
    private fun checkSession(session: IHTTPSession): Boolean {
        if (user.isEmpty()) return true
        val token = tokenOf(session) ?: return false
        val refresh = session.uri != "/api/status"
        val now = System.currentTimeMillis()
        synchronized(sessions) {
            pruneLocked()
            val last = sessions[token] ?: return false
            if (now - last > idleTimeoutMs) {
                sessions.remove(token)
                return false
            }
            if (refresh) sessions[token] = now
            return true
        }
    }

    private fun pruneLocked() {
        val now = System.currentTimeMillis()
        val expired = sessions.filterValues { now - it > idleTimeoutMs }.keys
        expired.forEach { sessions.remove(it) }
    }

    private fun tokenOf(session: IHTTPSession): String? {
        val cookie = session.headers["cookie"] ?: return null
        return cookie.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("cnxs=") }
            ?.substringAfter("cnxs=")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun unauthorizedJson(): Response =
        json("{\"ok\":false,\"error\":\"未登录或会话已过期\"}", Response.Status.UNAUTHORIZED)

    // ---------------- 其它接口 ----------------

    private fun page(session: IHTTPSession): Response {
        val lang = session.headers["cookie"].orEmpty()
            .split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("cnx_lang=") }
            ?.substringAfter("cnx_lang=")
        val html = (if (lang == "en") PAGE_EN else PAGE)
            .replace("__APP_VERSION__", runCatching { appVersion() }.getOrDefault(""))
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun logo(): Response {
        val bytes = runCatching { logoBytes() }.getOrNull()
            ?: return json("{\"error\":\"logo not found\"}", Response.Status.NOT_FOUND)
        return newFixedLengthResponse(
            Response.Status.OK,
            "image/png",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        ).apply { addHeader("Cache-Control", "public, max-age=86400") }
    }

    private fun torchControl(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        val body = files["postData"] ?: ""
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return json("{\"ok\":false,\"error\":\"请求体不是合法 JSON\"}", Response.Status.BAD_REQUEST)
        val minutes = json.optInt("minutes", 0)
        val error = setTorchMinutes(minutes)
        return if (error.isEmpty()) json("{\"ok\":true}") else
            json("{\"ok\":false,\"error\":\"${escape(error)}\"}", Response.Status.BAD_REQUEST)
    }

    private fun streamControl(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        val body = files["postData"] ?: ""
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return json("{\"ok\":false,\"error\":\"请求体不是合法 JSON\"}", Response.Status.BAD_REQUEST)
        val enabled = json.optBoolean("running", false)
        val error = requestStream(enabled)
        return if (error.isEmpty()) json("{\"ok\":true}") else
            json("{\"ok\":false,\"error\":\"${escape(error)}\"}", Response.Status.BAD_REQUEST)
    }

    private fun apply(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        val body = files["postData"] ?: ""
        if (body.isBlank()) return json("{\"ok\":false,\"error\":\"请求体为空\"}", Response.Status.BAD_REQUEST)
        val error = applySettings(body)
        return if (error.isEmpty()) {
            json("{\"ok\":true}")
        } else {
            json("{\"ok\":false,\"error\":\"${escape(error)}\"}", Response.Status.BAD_REQUEST)
        }
    }

    private fun json(body: String, status: Response.IStatus = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")

    companion object {
        /** 会话表放在 companion 里：改设置会重建 HTTP 服务，登录状态不应因此丢失。 */
        private val sessions = HashMap<String, Long>()

        // 注意：这里刻意不使用 Kotlin 字符串插值，避免与 JS 语法冲突
        private val PAGE = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>CamNexus RTSP</title>
<style>
  :root{
    --bg:#0b0f14; --bg2:#111823; --card:#141b24; --card2:#182231;
    --line:#243043; --text:#e8eef6; --muted:#8a99ad; --accent:#3ddc84;
    --accent-dim:rgba(61,220,132,.15); --danger:#ff7b72;
  }
  *{box-sizing:border-box}
  body{
    margin:0;color:var(--text);
    font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",sans-serif;
    background:
      radial-gradient(1100px 500px at 12% -10%, rgba(61,220,132,.13), transparent 60%),
      radial-gradient(900px 500px at 95% 0%, rgba(64,132,255,.10), transparent 55%),
      linear-gradient(180deg,var(--bg) 0%, var(--bg2) 100%);
    background-attachment:fixed;
    min-height:100vh;
  }
  .wrap{max-width:1080px;margin:0 auto;padding:28px 20px 90px}
  header{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:26px}
  .brand{display:flex;align-items:center;gap:14px}
  .logo{width:46px;height:46px;border-radius:14px;background:#0e141c;border:1px solid var(--line);
        object-fit:contain;padding:4px;box-shadow:0 8px 24px rgba(0,0,0,.35)}
  h1{margin:0;font-size:20px;font-weight:700;letter-spacing:.2px}
  .sub{color:var(--muted);font-size:13px;margin-top:2px}
  .headRight{display:flex;align-items:center;gap:12px}
  .lang{color:var(--muted);text-decoration:none;font-size:12px;border:1px solid var(--line);
    border-radius:999px;padding:6px 12px;letter-spacing:.5px}
  .lang:hover{color:var(--text);border-color:var(--text)}
  .pill{display:inline-flex;align-items:center;gap:8px;padding:8px 14px;border-radius:99px;
        background:rgba(61,220,132,.10);border:1px solid rgba(61,220,132,.28);
        color:var(--accent);font-size:13px;font-weight:600;white-space:nowrap}
  .pill.off{background:rgba(255,123,114,.08);border-color:rgba(255,123,114,.28);color:var(--danger)}
  .dot{width:8px;height:8px;border-radius:50%;background:currentColor;box-shadow:0 0 10px currentColor}

  .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:26px}
  .card{background:linear-gradient(160deg,var(--card2),var(--card));border:1px solid var(--line);
        border-radius:16px;padding:14px 16px;transition:transform .18s ease, border-color .18s ease}
  .card:hover{transform:translateY(-2px);border-color:#31445c}
  .card .k{color:var(--muted);font-size:12px;letter-spacing:.3px}
  .card .v{font-size:20px;font-weight:700;color:var(--accent);margin-top:4px;overflow-wrap:anywhere}

  .panel{background:linear-gradient(160deg,var(--card2),var(--card));border:1px solid var(--line);
         border-radius:18px;padding:6px 18px 18px;margin-bottom:18px}
  .panel h2{margin:16px 0 4px;font-size:13px;letter-spacing:1px;text-transform:uppercase;color:var(--muted)}
  .row{display:flex;align-items:center;gap:14px;padding:13px 0;border-bottom:1px solid rgba(36,48,67,.7);flex-wrap:wrap}
  .row:last-child{border-bottom:none}
  .row>label.lbl{width:132px;flex:none;color:#c9d5e4;font-size:14px}
  .row .hint{color:var(--muted);font-size:12px}

  select,input[type=number],input[type=text],input[type=password]{
    appearance:none;background:#0d131b;color:var(--text);border:1px solid #2b3a4d;border-radius:10px;
    padding:9px 34px 9px 12px;font:inherit;min-width:130px;cursor:pointer;
    transition:border-color .15s ease, box-shadow .15s ease;
  }
  input[type=number],input[type=text],input[type=password]{appearance:auto;padding-right:12px;cursor:text;width:130px}
  select:focus,input:focus{outline:none;border-color:var(--accent);box-shadow:0 0 0 3px var(--accent-dim)}

  .switch{position:relative;display:inline-block;width:48px;height:27px;flex:none}
  .switch input{opacity:0;width:0;height:0;position:absolute}
  .switch .slider{position:absolute;inset:0;background:#2b3a4d;border-radius:99px;transition:.22s}
  .switch .slider:before{content:"";position:absolute;width:21px;height:21px;left:3px;top:3px;
    background:#fff;border-radius:50%;transition:.22s;box-shadow:0 2px 6px rgba(0,0,0,.35)}
  .switch input:checked + .slider{background:var(--accent);box-shadow:0 0 16px rgba(61,220,132,.35)}
  .switch input:checked + .slider:before{transform:translateX(21px)}

  input[type=range]{-webkit-appearance:none;appearance:none;flex:1;min-width:130px;height:6px;border-radius:99px;
    background:#26313f;outline:none}
  input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:20px;height:20px;border-radius:50%;
    background:#fff;border:3px solid var(--accent);cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.4)}
  input[type=range]::-moz-range-thumb{width:16px;height:16px;border-radius:50%;background:#fff;border:3px solid var(--accent);cursor:pointer}
  #dnValue{width:52px;text-align:right;color:var(--accent);font-weight:600;font-variant-numeric:tabular-nums}

  .savebar{position:sticky;bottom:16px;display:flex;align-items:center;gap:16px;flex-wrap:wrap;
    background:rgba(15,21,29,.86);backdrop-filter:blur(10px);border:1px solid var(--line);
    border-radius:16px;padding:14px 18px;box-shadow:0 12px 40px rgba(0,0,0,.45)}
  button{background:linear-gradient(135deg,#4ae891,#2fbf72);color:#04220f;border:none;border-radius:12px;
    padding:12px 26px;font-weight:800;font-size:15px;cursor:pointer;letter-spacing:.3px;
    transition:transform .12s ease, filter .12s ease;box-shadow:0 8px 22px rgba(61,220,132,.25)}
  button:hover{filter:brightness(1.05)}
  button:active{transform:scale(.98)}
  button:disabled{opacity:.55;cursor:default;filter:none}
  .msg{font-size:13px;min-height:20px;flex:1}
  .ok{color:var(--accent)}
  .bad{color:var(--danger)}
  .foot{margin:20px 0 6px;text-align:center;color:var(--muted);font-size:11px;letter-spacing:.4px}

  .login{position:fixed;inset:0;display:none;align-items:center;justify-content:center;
    background:rgba(6,9,13,.72);backdrop-filter:blur(12px);z-index:50;padding:20px}
  .login.show{display:flex}
  .loginBox{width:100%;max-width:360px;background:linear-gradient(160deg,var(--card2),var(--card));
    border:1px solid var(--line);border-radius:20px;padding:26px;box-shadow:0 24px 60px rgba(0,0,0,.55)}
  .loginBox .logo{width:52px;height:52px;margin-bottom:12px}
  .loginBox h2{margin:0 0 4px;font-size:18px}
  .loginBox p{margin:0 0 18px;color:var(--muted);font-size:13px}
  .loginBox input{width:100%;margin-bottom:12px;padding:12px 14px}
  .loginBox button{width:100%;margin-top:4px}
  .loginMsg{margin-top:12px;font-size:13px;min-height:18px}

  @media (max-width:560px){
    .wrap{padding:20px 14px 80px}
    .row>label.lbl{width:100%;margin-bottom:-4px}
    .brand{gap:10px}
    h1{font-size:18px}
  }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="brand">
      <img class="logo" src="/logo.png" alt="logo">
      <div>
        <h1>CamNexus RTSP</h1>
        <div class="sub" id="sub">读取中…</div>
      </div>
    </div>
    <div class="headRight">
      <a class="lang" href="#" onclick="toggleLang();return false;">EN</a>
      <div class="pill" id="pill"><span class="dot"></span><span id="pillText">连接中</span></div>
    </div>
  </header>

  <section class="panel" style="margin-bottom:14px">
    <div class="row" style="padding:10px 0;border-bottom:none">
      <label class="lbl">RTSP 推流</label>
      <label class="switch"><input id="streamOn" type="checkbox"><span class="slider"></span></label>
      <span class="hint" id="streamHint">读取中…</span>
    </div>
  </section>

  <section class="cards" id="grid"></section>

  <section class="panel">
    <h2>画质</h2>
    <div class="row"><label class="lbl">相机</label><select id="cam"></select></div>
    <div class="row"><label class="lbl">分辨率</label><select id="res"></select></div>
<div class="row"><label class="lbl">帧率</label>
      <select id="fps">
        <option>10</option><option>15</option><option>20</option>
        <option>25</option><option>30</option><option>60</option>
      </select>
      <span class="hint">增强夜景会强制降到 15fps</span>
    </div>
    <div class="row"><label class="lbl">码率</label>
      <input id="bitrate" type="number" min="200" max="20000" step="100">
      <span class="hint">kbps</span>
    </div>
    <div class="row"><label class="lbl">编码格式</label>
      <select id="codec"><option value="H264">H.264</option><option value="H265">H.265</option></select>
    </div>
    <div class="row"><label class="lbl">RTSP 端口</label>
      <input id="port" type="number" min="1024" max="65535">
      <span class="hint">修改后播放器需用新端口重连</span>
    </div>
  </section>

  <section class="panel">
    <h2>夜景与降噪</h2>
    <div class="row"><label class="lbl">夜景模式</label>
      <select id="night">
        <option value="0">关闭</option>
        <option value="1">标准（提亮）</option>
        <option value="2">增强（提亮 + 曝光 + 15fps）</option>
      </select>
    </div>
    <div class="row"><label class="lbl">降噪</label>
      <label class="switch"><input id="dnOn" type="checkbox"><span class="slider"></span></label>
      <input id="dnLevel" type="range" min="0" max="100">
      <span id="dnValue">60%</span>
    </div>
  </section>

  <section class="panel">
    <h2>画面叠加</h2>
    <div class="row"><label class="lbl">显示叠加</label>
      <label class="switch"><input id="osdOn" type="checkbox"><span class="slider"></span></label>
    </div>
    <div class="row"><label class="lbl">日期时间</label>
      <label class="switch"><input id="osdDate" type="checkbox"><span class="slider"></span></label>
      <select id="osdFmt">
        <option value="0">yyyy-MM-dd HH:mm:ss</option>
        <option value="1">yyyy/MM/dd HH:mm:ss</option>
        <option value="2">MM/dd HH:mm:ss</option>
        <option value="3">HH:mm:ss</option>
      </select>
    </div>
    <div class="row"><label class="lbl">电量</label>
      <label class="switch"><input id="osdBattery" type="checkbox"><span class="slider"></span></label>
      <span class="hint">显示图标</span>
      <label class="switch"><input id="osdBatteryIcon" type="checkbox"><span class="slider"></span></label>
    </div>
    <div class="row"><label class="lbl">位置</label>
      <select id="osdPos">
        <option value="0">左上角</option><option value="1">右上角</option>
        <option value="2">左下角</option><option value="3">右下角</option>
      </select>
      <select id="osdRot">
        <option value="0">0°</option><option value="90">90°</option>
        <option value="180">180°</option><option value="270">270°</option>
      </select>
      <select id="osdFlip">
        <option value="0">不翻转</option><option value="1">水平翻转</option>
        <option value="2">垂直翻转</option><option value="3">水平+垂直</option>
      </select>
    </div>
  </section>

  <section class="panel">
    <h2>运行</h2>
    <div class="row"><label class="lbl">省电模式</label>
      <label class="switch"><input id="power" type="checkbox"><span class="slider"></span></label>
      <span class="hint">无人观看 60 秒后自动降档</span>
    </div>
    <div class="row"><label class="lbl">补光灯</label>
      <select id="torch">
        <option value="0">关闭</option>
        <option value="1">1 分钟</option>
        <option value="5">5 分钟</option>
        <option value="10">10 分钟</option>
        <option value="20">20 分钟</option>
        <option value="30">30 分钟</option>
        <option value="60">60 分钟</option>
      </select>
      <span class="hint" id="torchHint"></span>
    </div>
  </section>

  <div class="savebar">
    <button id="save">保存并应用</button>
    <div class="msg" id="msg"></div>
  </div>

  <div class="foot" id="foot">CamNexus RTSP · v__APP_VERSION__</div>
</div>

<div class="login" id="login">
  <div class="loginBox">
    <img class="logo" src="/logo.png" alt="logo">
    <h2>登录 CamNexus</h2>
    <p>闲置超过 15 分钟会自动退出，需要重新登录</p>
    <input id="lgUser" type="text" placeholder="账号" autocomplete="username">
    <input id="lgPass" type="password" placeholder="密码" autocomplete="current-password">
    <button id="lgBtn">登录</button>
    <div class="loginMsg bad" id="lgMsg"></div>
  </div>
</div>

<script>
function el(id) { return document.getElementById(id); }
function fmt(v) { return (v === undefined || v === null || v === '') ? '-' : v; }
function size(b) { b = b || 0; return b < 1073741824 ? (b / 1048576).toFixed(1) + ' MB' : (b / 1073741824).toFixed(2) + ' GB'; }
function dur(sec) {
  sec = sec || 0;
  var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  return h > 0 ? (h + ' 时 ' + m + ' 分') : (m > 0 ? (m + ' 分 ' + s + ' 秒') : (s + ' 秒'));
}
function card(k, v) {
  return '<div class="card"><div class="k">' + k + '</div><div class="v">' + fmt(v) + '</div></div>';
}
function showLogin(text) {
  var wasHidden = !el('login').classList.contains('show');
  el('login').classList.add('show');
  if (text) el('lgMsg').textContent = text;
  loginShown = true;
  if (!wasHidden) return;
  // 只在遮罩首次出现时聚焦账号框；否则轮询每次 401 都会把焦点从密码框抢走
  setTimeout(function () {
    var active = document.activeElement;
    if (active !== el('lgPass') && active !== el('lgUser')) el('lgUser').focus();
  }, 60);
}
function hideLogin() {
  el('login').classList.remove('show');
  el('lgMsg').textContent = '';
  loginShown = false;
}

var caps = null;
var loginShown = false;
var streamLockUntil = 0;
var torchLockUntil = 0;

async function api(url, options) {
  var r = await fetch(url, options);
  if (r.status === 401) {
    showLogin('会话已过期，请重新登录');
    throw new Error('unauthorized');
  }
  return r;
}

async function loadStatus() {
  if (loginShown) return;
  try {
    var r = await fetch('/api/status?t=' + Date.now());
    if (r.status === 401) { showLogin('会话已过期，请重新登录'); return; }
    var s = await r.json();
    var sv = s.server || {}, st = s.stream || {}, sta = s.stats || {}, cl = s.clients || {}, ap = s.app || {};
    el('sub').textContent = fmt(sv.ip) + ':' + fmt(sv.rtspPort) +
      ' · 网页端口 ' + fmt(sv.httpPort) + ' · 已运行 ' + dur(ap.uptimeSec);
    if (ap.version) el('foot').textContent = 'CamNexus RTSP · v' + ap.version;
    var pill = el('pill');
    pill.className = 'pill' + (sv.running ? '' : ' off');
    el('pillText').textContent = sv.running ? '推流中' : '待机';
    if (Date.now() > streamLockUntil) {
      el('streamOn').checked = !!sv.running;
      el('streamHint').textContent = sv.running ? '运行中' : '已停止（网页可随时开启）';
    }
    if (Date.now() > torchLockUntil) {
      var torchNow = (st.torchMinutes === undefined) ? 0 : st.torchMinutes;
      el('torch').value = String(torchNow);
    }
    var items = [
      ['分辨率', (st.width || '-') + ' × ' + (st.height || '-')],
      ['编码', st.codec],
      ['帧率' + (st.fpsConfigured && st.fpsConfigured !== st.fps ? '（配置 ' + st.fpsConfigured + '）' : ''), st.fps ? st.fps + ' fps' : '-'],
      ['配置码率', st.bitrate ? Math.round(st.bitrate / 1000) + ' kbps' : '-'],
      ['实测码率', sta.bitrate ? Math.round(sta.bitrate / 1000) + ' kbps' : '空闲'],
      ['客户端', cl.count],
      ['已发送', size(sta.bytesSent)],
      ['夜景', ['关闭', '标准', '增强'][st.nightMode || 0]],
      ['降噪', st.denoiseEnabled ? ('开 ' + st.denoiseLevel + '%') : '关闭'],
      ['叠加', st.osdEnabled ? '开启' : '关闭']
    ];
    el('grid').innerHTML = items.map(function (it) { return card(it[0], it[1]); }).join('');
  } catch (e) {
    if (e && e.message === 'unauthorized') return;
    el('pill').className = 'pill off';
    el('pillText').textContent = '连接失败';
  }
}

async function loadCaps() {
  caps = await (await api('/api/capabilities')).json();
  var cam = el('cam');
  cam.innerHTML = '';
  (caps.cameras || []).forEach(function (c) {
    var o = document.createElement('option');
    o.value = c.id;
    o.textContent = c.label + ' (' + c.id + ')';
    cam.appendChild(o);
  });
}

function fillRes(camId, selected) {
  var cam = null;
  (caps.cameras || []).forEach(function (c) { if (c.id === camId) cam = c; });
  var sel = el('res');
  sel.innerHTML = '';
  if (!cam) return;
  (cam.resolutions || []).forEach(function (p) {
    var o = document.createElement('option');
    o.value = p[0] + 'x' + p[1];
    o.textContent = p[0] + ' × ' + p[1];
    if (selected && p[0] === selected[0] && p[1] === selected[1]) o.selected = true;
    sel.appendChild(o);
  });
}

function paintRange() {
  var r = el('dnLevel');
  var p = Math.max(0, Math.min(100, parseInt(r.value, 10) || 0));
  r.style.background = 'linear-gradient(90deg,var(--accent) 0%,var(--accent) ' + p + '%,#26313f ' + p + '%,#26313f 100%)';
  el('dnValue').textContent = p + '%';
}

async function loadSettings() {
  var s = await (await api('/api/settings')).json();
  el('cam').value = s.cameraId;
  fillRes(s.cameraId, [s.width, s.height]);
  el('fps').value = String(s.fps);
  if (el('fps').value !== String(s.fps)) {
    var fo = document.createElement('option');
    fo.textContent = s.fps;
    fo.value = String(s.fps);
    el('fps').appendChild(fo);
    el('fps').value = String(s.fps);
  }
  el('bitrate').value = Math.round(s.bitrate / 1000);
  el('codec').value = s.codec;
  el('port').value = s.port;
  el('power').checked = !!s.powerSaving;
  el('night').value = String(s.nightMode);
  el('dnOn').checked = !!s.denoiseEnabled;
  el('dnLevel').value = s.denoiseLevel;
  paintRange();
  el('osdOn').checked = !!s.osdEnabled;
  el('osdDate').checked = !!s.osdDate;
  el('osdFmt').value = String(s.osdDateFormat);
  el('osdBattery').checked = !!s.osdBattery;
  el('osdBatteryIcon').checked = !!s.osdBatteryIcon;
  el('osdPos').value = String(s.osdPosition);
  el('osdRot').value = String(s.osdRotation);
  el('osdFlip').value = String(s.osdFlip);
}

async function save() {
  var res = el('res').value.split('x');
  var body = {
    cameraId: el('cam').value,
    width: parseInt(res[0], 10),
    height: parseInt(res[1], 10),
    fps: parseInt(el('fps').value, 10),
    bitrate: parseInt(el('bitrate').value, 10) * 1000,
    codec: el('codec').value,
    port: parseInt(el('port').value, 10),
    powerSaving: el('power').checked,
    nightMode: parseInt(el('night').value, 10),
    denoiseEnabled: el('dnOn').checked,
    denoiseLevel: parseInt(el('dnLevel').value, 10),
    osdEnabled: el('osdOn').checked,
    osdDate: el('osdDate').checked,
    osdDateFormat: parseInt(el('osdFmt').value, 10),
    osdBattery: el('osdBattery').checked,
    osdBatteryIcon: el('osdBatteryIcon').checked,
    osdPosition: parseInt(el('osdPos').value, 10),
    osdRotation: parseInt(el('osdRot').value, 10),
    osdFlip: parseInt(el('osdFlip').value, 10)
  };
  var msg = el('msg');
  var btn = el('save');
  btn.disabled = true;
  msg.className = 'msg';
  msg.textContent = '正在应用…';
  try {
    var r = await api('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    var j = await r.json();
    if (r.ok && j.ok) {
      msg.className = 'msg ok';
      msg.textContent = '已应用，推流将在 1 秒内重启';
      setTimeout(function () { loadSettings(); loadStatus(); }, 1500);
      setTimeout(function () { loadSettings(); loadStatus(); }, 3500);
    } else {
      msg.className = 'msg bad';
      msg.textContent = '失败：' + (j.error || ('HTTP ' + r.status));
    }
  } catch (e) {
    if (e && e.message === 'unauthorized') {
      msg.className = 'msg bad';
      msg.textContent = '会话已过期，请重新登录';
    } else {
      msg.className = 'msg bad';
      msg.textContent = '请求失败：' + e.message;
    }
  }
  btn.disabled = false;
}

async function doLogin() {
  var btn = el('lgBtn');
  btn.disabled = true;
  el('lgMsg').textContent = '';
  try {
    var r = await fetch('/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ user: el('lgUser').value, pass: el('lgPass').value })
    });
    var j = await r.json();
    if (r.ok && j.ok) {
      hideLogin();
      el('lgPass').value = '';
      await loadCaps();
      await loadSettings();
      loadStatus();
    } else {
      el('lgMsg').textContent = j.error || ('HTTP ' + r.status);
    }
  } catch (e) {
    el('lgMsg').textContent = '登录失败：' + e.message;
  }
  btn.disabled = false;
}

el('streamOn').addEventListener('change', async function () {
  var want = el('streamOn').checked;
  streamLockUntil = Date.now() + 5000;
  el('streamHint').textContent = want ? '正在启动推流…' : '正在停止推流…';
  try {
    var r = await api('/api/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ running: want })
    });
    var j = await r.json();
    if (!(r.ok && j.ok)) {
      el('streamHint').textContent = '失败：' + (j.error || ('HTTP ' + r.status));
      streamLockUntil = 0;
    }
  } catch (e) {
    if (e && e.message !== 'unauthorized') el('streamHint').textContent = '请求失败：' + e.message;
    streamLockUntil = 0;
  }
});
el('cam').addEventListener('change', function () { fillRes(el('cam').value, null); });
el('dnLevel').addEventListener('input', paintRange);
el('torch').addEventListener('change', async function () {
  var minutes = parseInt(el('torch').value, 10) || 0;
  torchLockUntil = Date.now() + 4000;
  el('torchHint').textContent = '';
  try {
    var r = await api('/api/torch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ minutes: minutes })
    });
    var j = await r.json();
    if (!(r.ok && j.ok)) {
      el('torchHint').style.color = 'var(--danger)';
      el('torchHint').textContent = '失败：' + (j.error || ('HTTP ' + r.status));
      torchLockUntil = 0;
    } else if (minutes >= 20) {
      el('torchHint').style.color = 'var(--danger)';
      el('torchHint').textContent = '长时间开启会增加机身温度，缩短 LED 寿命';
      setTimeout(function () {
        if (Date.now() > torchLockUntil) el('torchHint').textContent = '';
      }, 3200);
    }
  } catch (e) {
    if (e && e.message !== 'unauthorized') {
      el('torchHint').style.color = 'var(--danger)';
      el('torchHint').textContent = '请求失败：' + e.message;
    }
    torchLockUntil = 0;
  }
});
el('save').addEventListener('click', save);
el('lgBtn').addEventListener('click', doLogin);
el('lgPass').addEventListener('keydown', function (e) { if (e.key === 'Enter') doLogin(); });
el('lgUser').addEventListener('keydown', function (e) { if (e.key === 'Enter') el('lgPass').focus(); });

(async function init() {
  try {
    await loadCaps();
    await loadSettings();
    hideLogin();
  } catch (e) {
    if (!(e && e.message === 'unauthorized')) {
      el('msg').className = 'msg bad';
      el('msg').textContent = '加载配置失败：' + e.message;
    }
  }
  loadStatus();
  setInterval(loadStatus, 2000);
})();
function toggleLang(){
  var en = document.documentElement.lang === 'en';
  document.cookie = 'cnx_lang=' + (en ? 'zh' : 'en') + ';path=/;max-age=31536000';
  location.reload();
}
</script>
</body>
</html>
        """.trimIndent()

    /**
     * 网页控制台的中英文切换：服务端按 cookie 输出英文版。
     * 只翻译用户可见文案，注释与代码保持不变。
     */
    private val PAGE_I18N = linkedMapOf(
        "RTSP 推流" to "RTSP stream",
        "RTSP 端口" to "RTSP port",
        "增强夜景会强制降到 15fps" to "Enhanced night mode forces 15 fps",
        "修改后播放器需用新端口重连" to "Players must reconnect on the new port",
        "无人观看 60 秒后自动降档" to "Downgrades after 60s with no viewers",
        "闲置超过 15 分钟会自动退出，需要重新登录" to "Signs out after 15 minutes of inactivity",
        "增强（提亮 + 曝光 + 15fps）" to "Enhanced (brighter + exposure + 15 fps)",
        "已停止（网页可随时开启）" to "Stopped (can be started from the web)",
        "已应用，推流将在 1 秒内重启" to "Applied; streaming restarts within a second",
        "长时间开启会增加机身温度，缩短 LED 寿命" to "Long use increases heat and shortens LED life",
        "会话已过期，请重新登录" to "Session expired, please sign in again",
        "正在启动推流…" to "Starting stream…",
        "正在停止推流…" to "Stopping stream…",
        "加载配置失败：" to "Failed to load settings: ",
        "登录 CamNexus" to "Sign in to CamNexus",
        "读取中…" to "Loading…",
        "连接中" to "Connecting",
        "连接失败" to "Connection failed",
        "标准（提亮）" to "Standard (brighter)",
        "保存并应用" to "Save & apply",
        "夜景与降噪" to "Night mode & denoise",
        "夜景模式" to "Night mode",
        "画面叠加" to "Overlay",
        "显示叠加" to "Show overlay",
        "显示图标" to "Show icon",
        "日期时间" to "Date & time",
        "省电模式" to "Power saving",
        "配置码率" to "Configured bitrate",
        "实测码率" to "Measured bitrate",
        "请求失败：" to "Request failed: ",
        "登录失败：" to "Sign-in failed: ",
        "失败：" to "Failed: ",
        "正在应用…" to "Applying…",
        "左上角" to "Top left",
        "右上角" to "Top right",
        "左下角" to "Bottom left",
        "右下角" to "Bottom right",
        "不翻转" to "None",
        "水平翻转" to "Horizontal",
        "垂直翻转" to "Vertical",
        "水平+垂直" to "Horizontal + vertical",
        "分辨率" to "Resolution",
        "编码格式" to "Codec",
        "帧率" to "Frame rate",
        "码率" to "Bitrate",
        "相机" to "Camera",
        "画质" to "Video",
        "运行" to "Runtime",
        "补光灯" to "Flashlight",
        "客户端" to "Viewers",
        "已发送" to "Sent",
        "已运行 " to "up ",
        "网页端口 " to "web port ",
        "推流中" to "Live",
        "运行中" to "Running",
        "待机" to "Idle",
        "空闲" to "Idle",
        "标准" to "Standard",
        "增强" to "Enhanced",
        "开启" to "On",
        "叠加" to "Overlay",
        "夜景" to "Night",
        "降噪" to "Denoise",
        "电量" to "Battery",
        "位置" to "Position",
        "登录" to "Sign in",
        "账号" to "Username",
        "密码" to "Password",
        "关闭" to "Off",
        "编码" to "Codec",
        "开 " to "On ",
        "1 分钟" to "1 min",
        "5 分钟" to "5 min",
        "10 分钟" to "10 min",
        "20 分钟" to "20 min",
        "30 分钟" to "30 min",
        "60 分钟" to "60 min",
        " 时 " to " h ",
        " 分 " to " m ",
        " 分" to " m",
        " 秒" to " s",
        "（配置 " to " (configured ",
        "）" to ")",
    ).entries.sortedByDescending { it.key.length }.associate { it.key to it.value }

    private val PAGE_EN: String by lazy { localizePage(PAGE) }

    private fun localizePage(zh: String): String {
        var text = zh.replace("<html lang=\"zh-CN\">", "<html lang=\"en\">")
        for ((cn, en) in PAGE_I18N) {
            if (cn == "READ_ME") continue
            text = text.replace(cn, en)
        }
        // 英文页面的切换按钮显示“中文”，点一下切回中文
        return text.replace(">EN</a>", ">中文</a>")
    }

    }
}
