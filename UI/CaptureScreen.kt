package com.taowu.ui

import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.taowu.backend.AdFilter
import com.taowu.backend.ResolveMode
import com.taowu.backend.UrlHistory
import com.taowu.backend.field.CollectRow
import com.taowu.backend.field.CollectStore
import com.taowu.backend.field.FieldCtx
import com.taowu.backend.field.FieldSchema
import com.taowu.backend.field.RecruitExtract
import com.taowu.backend.field.SchemaExtract
import com.taowu.backend.settings.AppSettings
import com.taowu.ui.theme.UiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 捕获动作：解析出轨道 / 分集列表 / 整部下载。 */
enum class CapAction { RESOLVE, EPISODES, SERIES }

/** 移动版 Chrome UA。WebView 默认 UA 带 `; wv` 与 `Version/4.0` 标记，
 * 百度 / 哔哩哔哩等站会据此判定为「App 内嵌浏览器」而拦截或跳转 —— 换掉它。
 */
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/** 取当前页正文文本 + 标题候选 + 脚本里的结构化线索（SPA 常把岗位/公司等塞在 <script> JSON 里）。 */
private const val EXTRACT_PAGE_JS = """
(function(){
  var t = (document.body && document.body.innerText) ? document.body.innerText : "";
  var titles = [];
  var hints = {};
  try {
    var h1 = document.querySelector('h1');
    if (h1 && h1.innerText) titles.push(h1.innerText.trim());
    var og = document.querySelector('meta[property="og:title"]');
    if (og && og.content) titles.push(og.content.trim());
    if (document.title) titles.push(document.title.trim());
  } catch(e){}
  try {
    var all = "";
    var ss = document.querySelectorAll('script');
    for (var i=0;i<ss.length;i++){ var tx = ss[i].textContent; if (tx && tx.length > 40) all += tx + "\n"; }
    var grab = function(keys){
      for (var j=0;j<keys.length;j++){
        var m = all.match(new RegExp('"'+keys[j]+'"\\s*:\\s*"([^"]{1,160})"'));
        if (m && m[1]) { try { return JSON.parse('"'+m[1]+'"'); } catch(e) { return m[1]; } }
      }
      return "";
    };
    hints.job = grab(["jobName","positionName","jobTitle","CJobName","positionTitle"]);
    hints.company = grab(["comName","companyName","companyFullName","brandName","companyShortName"]);
    hints.salary = grab(["salary","salary60","salaryMonth","positionSalary"]);
    hints.education = grab(["degree","education","eduLevel"]);
    hints.experience = grab(["workExp","workExperience","expRequirement","experience"]);
    hints.location = grab(["workPlace","workCity","positionCityDistrict","cityName","positionCity"]);
  } catch(e){}
  return JSON.stringify({text: t, titles: titles, hints: hints});
})();
"""

/**
 * 捕获：内置浏览器。
 *
 * 注意：这个 WebView 在**空状态**下会铺满整屏（截图 1.jpg 已证实），把同级的顶部栏全盖住。
 * 所以这里采用「**浏览器铺底 + 所有 UI 叠在它上面**」的层叠结构——Compose 内容叠在 AndroidView
 * 之上是可行的（原先的提示文字就能盖在 WebView 上），这样不管 WebView 怎么铺，外壳永远可见。
 */
@Composable
fun CaptureScreen(
    onSubmit: (url: String, mode: ResolveMode, action: CapAction) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(ResolveMode.GENERAL) }
    var modeExpanded by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }        // 地址栏内容（可编辑）
    var currentUrl by rememberSaveable { mutableStateOf("") }     // 浏览器当前页
    var canGoBack by rememberSaveable { mutableStateOf(false) }
    var canGoForward by rememberSaveable { mutableStateOf(false) }
    var pageError by rememberSaveable { mutableStateOf("") }      // 加载失败提示
    var bottomExpanded by rememberSaveable { mutableStateOf(true) }   // 底部抓取面板是否展开
    var progress by rememberSaveable { mutableIntStateOf(100) }   // 0..100
    val webView = remember { mutableStateOf<WebView?>(null) }

    // 浏览记录（存 filesDir，最近的在前，上限 60）
    val context = LocalContext.current
    var history by remember { mutableStateOf(UrlHistory.load(context)) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    // 地址栏焦点：用 interactionSource 跟踪（比 onFocusChanged 可靠——之前它卡在"正在输入"，
    // 导致页面跳转后地址栏再也不更新）。焦点在输入框时不覆盖用户正在打的内容。
    val addrInteraction = remember { MutableInteractionSource() }
    val addrFocused by addrInteraction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // 文字采集：模板列表 + 当前选中模板 + 结果提示
    val fieldTemplates = remember { FieldSchema.templateList() }
    var fieldTmpl by rememberSaveable {
        mutableStateOf(AppSettings.fieldTemplate.ifBlank { fieldTemplates.firstOrNull()?.name ?: "" })
    }
    var tmplExpanded by rememberSaveable { mutableStateOf(false) }
    var collectMsg by rememberSaveable { mutableStateOf("") }

    // 适配屏幕（手机版）：把没做手机适配的页面缩小到手机屏宽；并允许捏合缩放
    var fitMode by rememberSaveable { mutableStateOf(AppSettings.browserFit) }
    var fitApplied by remember { mutableStateOf(false) }
    fun applyFit() {
        val wv = webView.value ?: return
        if (!fitMode) { wv.setInitialScale(0); wv.reload(); return }
        // 量出内容宽度，算出"整宽可见"的缩放比；只设一次（setInitialScale 对该 WebView 后续加载也生效）
        wv.evaluateJavascript(
            "Math.max(document.documentElement.scrollWidth||0, document.body?document.body.scrollWidth:0)"
        ) { raw ->
            val cw = raw.trim('"').toDoubleOrNull()?.toInt() ?: 0
            val vw = wv.width
            if (cw > 0 && vw > 0 && cw > vw) {
                wv.setInitialScale((vw * 100.0 / cw).toInt().coerceIn(20, 100))
            } else {
                wv.setInitialScale(0)
            }
            wv.reload()
        }
    }
    fun setFitMode(on: Boolean) {
        fitMode = on
        AppSettings.browserFit = on
        fitApplied = on
        applyFit()
    }

    /** 抓当前页正文 → 按选中模板抽字段 → 追加一条到采集结果。 */
    fun grabText() {
        val wv = webView.value ?: return
        val tmpl = FieldSchema.template(fieldTmpl) ?: fieldTemplates.firstOrNull()
        if (tmpl == null) { collectMsg = "没有可用的字段模板"; return }
        if (!currentUrl.startsWith("http")) { collectMsg = "当前不是网页"; return }
        val url = currentUrl
        wv.evaluateJavascript(EXTRACT_PAGE_JS) { raw ->
            val jsonStr = runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull()
            if (jsonStr.isNullOrEmpty()) { collectMsg = "取正文失败"; return@evaluateJavascript }
            val obj = runCatching { Json.parseToJsonElement(jsonStr).jsonObject }.getOrNull()
            if (obj == null) { collectMsg = "取正文失败"; return@evaluateJavascript }
            val text = obj["text"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: ""
            val titles = obj["titles"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList()
            val hints = obj["hints"]?.let { runCatching { it.jsonObject }.getOrNull() }
                ?.mapNotNull { (k, v) -> runCatching { v.jsonPrimitive.content }.getOrNull()?.let { k to it } }
                ?.toMap() ?: emptyMap()
            val cols = tmpl.columns
            val host = hostOf(url)
            scope.launch {
                val values = withContext(Dispatchers.Default) {
                    if (tmpl.engine == "recruit") {
                        RecruitExtract.extract(text, url, host, hints)
                    } else {
                        SchemaExtract.extract(text, cols, FieldCtx(url = url, site = host, titles = titles))
                    }
                }
                val row = CollectRow(url, host, tmpl.name, System.currentTimeMillis(), values)
                val list = CollectStore.add(context, row)
                val anyValue = values.count { it.key != "job_url" && it.value.isNotBlank() }
                collectMsg = "已采集「${tmpl.name}」（${anyValue} 个字段）· 共 ${list.size} 条"
            }
        }
    }

    fun normalize(u: String): String {
        val t = u.trim()
        if (t.isEmpty()) return ""
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    fun navigate(target: String) {
        val u = normalize(target)
        if (u.isEmpty()) return
        address = u
        focusManager.clearFocus()          // 清焦点，之后页面怎么跳地址栏都跟着走
        webView.value?.loadUrl(u)
    }

    fun goHome() {
        // 回到"刚进 App"的空白状态：清空地址栏与当前页，显示空状态提示
        address = ""
        currentUrl = ""
        canGoBack = false
        canGoForward = false
        pageError = ""
        progress = 100
        focusManager.clearFocus()
        webView.value?.loadUrl("about:blank")
    }
    fun goBack() { webView.value?.takeIf { it.canGoBack() }?.goBack() }
    fun goForward() { webView.value?.takeIf { it.canGoForward() }?.goForward() }

    fun grab(action: CapAction) {
        val u = currentUrl.trim()
        if (u.startsWith("http")) onSubmit(u, mode, action)
    }

    // 系统返回键：先在网页内后退，不直接退出
    BackHandler(enabled = canGoBack) { webView.value?.goBack() }

    DisposableEffect(Unit) {
        onDispose { webView.value?.destroy(); webView.value = null }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(UiColors.bg),
    ) {
        // ── 第 1 层：浏览器，铺底（内容区上下留出外壳高度，页面别被外壳压住） ──
        val bottomPadDp = if (bottomExpanded) 200f else 0f   // 收起后底部不占位，网页弹窗可点
        val topPadDp = 52f                                    // 金色栏 60dp 基础上再上移 8dp
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // 浏览器底色用浅青：网页默认假设浅底，深青底会让页面内容（尤其中间透明区块）看不清
                    setBackgroundColor(UiColors.browserBg.toArgb())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.loadsImagesAutomatically = true
                    // 大量动漫页混用 http 资源，禁止会让页面加载不全
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // 关键：换掉 WebView 默认 UA（含 "; wv" / "Version/4.0"），否则百度/B站等会拦
                    settings.userAgentString = MOBILE_UA
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        // 广告过滤：命中广告域/广告路径的请求直接返回空，不发出去（页面更干净）
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            if (AppSettings.adFilterEnabled &&
                                AdFilter.isAd(request.url.toString(), AdFilter.parseExtra(AppSettings.adFilterExtra))
                            ) {
                                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                            }
                            return null
                        }

                        // 只让 http(s) 留在浏览器内；bilibili:// 之类的外链/深链直接拦掉，避免"点了没反应"
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val s = request.url.scheme?.lowercase()
                            return !(s == "http" || s == "https")
                        }

                        override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                            // 只跟踪 http(s)；about:blank（主页/空白）不覆盖，保持空状态
                            url?.let {
                                if (it.startsWith("http")) {
                                    currentUrl = it
                                    if (!addrFocused) address = it   // 地址栏跟着页面走
                                }
                            }
                            pageError = ""
                            progress = 5
                        }

                        override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                            url?.let {
                                if (it.startsWith("http")) {
                                    currentUrl = it
                                    if (!addrFocused) address = it
                                }
                            }
                            canGoBack = v.canGoBack()
                            canGoForward = v.canGoForward()
                        }

                        override fun onPageFinished(v: WebView, url: String?) {
                            progress = 100
                            url?.let {
                                if (it.startsWith("http")) {
                                    currentUrl = it
                                    if (!addrFocused) address = it
                                }
                                // 记一条浏览记录（只记 http(s) 主文档）
                                if (it.startsWith("http")) history = UrlHistory.add(context, it)
                            }
                            canGoBack = v.canGoBack()
                            canGoForward = v.canGoForward()
                            // 适配屏幕开着但还没对当前页应用（如 WebView 重建过）→ 应用一次
                            if (fitMode && !fitApplied) { fitApplied = true; applyFit() }
                        }

                        override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) {
                                progress = 100
                                pageError = "加载失败：${error.description}（${request.url.host ?: ""}）"
                            }
                        }

                        override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame) {
                                pageError = "服务器返回 ${response.statusCode} ${response.reasonPhrase ?: ""}"
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(v: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    // 切页签回来时恢复上次浏览的页面
                    if (currentUrl.isNotBlank()) loadUrl(currentUrl)
                }.also { webView.value = it }
            },
            // 关键：用 Compose 的 padding 把 WebView **本体**缩进，让整页（含站点 position:fixed 顶栏）
            // 落在金色搜栏下方。之前用 WebView.setPadding 对固定定位顶栏不生效，会被金色栏压住点不到。
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadDp.dp, bottom = bottomPadDp.dp),
        )

        // ── 第 2 层：外壳 UI（叠在 WebView 上，永远可见） ──
        Column(Modifier.fillMaxSize()) {
            // 顶部：固定高度金色地址栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .offset(y = (-8).dp)
                    .background(UiColors.accent)
                    .padding(horizontal = 6.dp),
            ) {
                // 主页 / 后退 / 前进
                NavTap("⌂", enabled = true) { goHome() }
                NavTap("‹", enabled = canGoBack) { goBack() }
                NavTap("›", enabled = canGoForward) { goForward() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .background(UiColors.bg, RoundedCornerShape(21.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (address.isEmpty()) {
                        Text("输入网址…", color = UiColors.muted, fontSize = 15.sp)
                    }
                    BasicTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        textStyle = TextStyle(color = UiColors.accent, fontSize = 15.sp),
                        cursorBrush = SolidColor(UiColors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { navigate(address) }),
                        interactionSource = addrInteraction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = { navigate(address) }) {
                    Text("前往", color = UiColors.onAccent, fontWeight = FontWeight.Bold)
                }
                // 面板收起后，从这里再展开（底部完全让给网页）
                if (!bottomExpanded) {
                    TextButton(onClick = { bottomExpanded = true }) {
                        Text("抓取 ▴", color = UiColors.onAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 标题栏已去掉；模式切换挪到底部抓取面板里
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 中间留空，让底下的浏览器露出来
            Spacer(Modifier.weight(1f))

            // 底部：抓取面板（可收起；收起后这里什么都不占，方便点网页底部的弹窗）
            if (bottomExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UiColors.bg),
                ) {
                    HorizontalDivider(color = UiColors.outline)
                    val page = currentUrl.trim()
                    val canGrab = page.startsWith("http")
                    // 模式切换（从标题栏挪进来的）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp),
                    ) {
                        Text("模式", color = UiColors.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Box {
                            OutlinedButton(onClick = { modeExpanded = true }) {
                                Text(
                                    when (mode) {
                                        ResolveMode.GENERAL -> "通用模式 ▾"
                                        ResolveMode.RECIPE -> "配方模式 ▾"
                                    }
                                )
                            }
                            DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("通用模式（页内扫描，任意站）") },
                                    onClick = { mode = ResolveMode.GENERAL; modeExpanded = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("配方模式（用站点配方规则）") },
                                    onClick = { mode = ResolveMode.RECIPE; modeExpanded = false },
                                )
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Button(onClick = { grab(CapAction.RESOLVE) }, enabled = canGrab, modifier = Modifier.weight(1f)) {
                            Text("解析")
                        }
                        Button(onClick = { grab(CapAction.EPISODES) }, enabled = canGrab, modifier = Modifier.weight(1f)) {
                            Text("分集列表")
                        }
                    }
                    Button(
                        onClick = { grab(CapAction.SERIES) },
                        enabled = canGrab,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    ) {
                        Text("整部抓取")
                    }
                    // 文字采集：选字段模板 → 抓当前页正文抽字段（累加到「采集」页）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
                    ) {
                        Text("文字", color = UiColors.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Box {
                            OutlinedButton(onClick = { tmplExpanded = true }) {
                                Text((fieldTmpl.ifBlank { "选模板" }) + " ▾")
                            }
                            DropdownMenu(expanded = tmplExpanded, onDismissRequest = { tmplExpanded = false }) {
                                if (fieldTemplates.isEmpty()) {
                                    DropdownMenuItem(text = { Text("（无模板）") }, onClick = { tmplExpanded = false })
                                }
                                for (t in fieldTemplates) {
                                    DropdownMenuItem(
                                        text = { Text(t.name + if (t.source == "user") "（用户）" else "") },
                                        onClick = {
                                            fieldTmpl = t.name
                                            AppSettings.fieldTemplate = t.name
                                            tmplExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { grabText() }, enabled = canGrab && fieldTmpl.isNotBlank()) {
                            Text("抓正文")
                        }
                    }
                    if (collectMsg.isNotEmpty()) {
                        Text(
                            collectMsg,
                            color = UiColors.link,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
                        )
                    }
                    // 底部一行：浏览记录（左） + 收起抓手（右）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    ) {
                        TextButton(onClick = {
                            history = UrlHistory.load(context)
                            showHistory = true
                        }) {
                            Text("浏览记录", color = UiColors.link, fontSize = 13.sp)
                        }
                        TextButton(onClick = { setFitMode(!fitMode) }) {
                            Text(
                                if (fitMode) "手机版 ✓" else "手机版",
                                color = if (fitMode) UiColors.accent else UiColors.link,
                                fontSize = 13.sp,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { bottomExpanded = false }) {
                            Text("收起 ▾", color = UiColors.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 空状态提示
        if (currentUrl.isBlank()) {
            Text(
                "输入网址开始浏览；走到要抓的页面后，点下方按钮开始抓取。",
                color = UiColors.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        // 加载失败提示
        if (pageError.isNotEmpty()) {
            Text(
                pageError,
                color = UiColors.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        // 浏览记录弹窗：点一条就跳回去
        if (showHistory) {
            AlertDialog(
                onDismissRequest = { showHistory = false },
                title = { Text("浏览记录") },
                text = {
                    if (history.isEmpty()) {
                        Text("（暂无记录）", color = UiColors.muted)
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            for (h in history) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { navigate(h); showHistory = false }
                                        .padding(vertical = 6.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            hostOf(h),
                                            color = UiColors.link,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            h,
                                            color = UiColors.muted,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    TextButton(onClick = { history = UrlHistory.remove(context, h) }) {
                                        Text("删", color = UiColors.error, fontSize = 12.sp)
                                    }
                                }
                                HorizontalDivider(color = UiColors.outline)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { history = UrlHistory.clear(context) }) {
                        Text("清空", color = UiColors.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showHistory = false }) { Text("关闭") }
                },
            )
        }
    }
}

/** 取 URL 的主机名（记录列表里当标题）。 */
private fun hostOf(url: String): String = try {
    java.net.URI(url).host ?: url
} catch (e: Exception) {
    url
}

/** 地址栏里的紧凑导航按钮（主页/后退/前进）。 */
@Composable
private fun NavTap(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        glyph,
        color = if (enabled) UiColors.onAccent else UiColors.onAccent.copy(alpha = 0.35f),
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(28.dp)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 6.dp),
    )
}
