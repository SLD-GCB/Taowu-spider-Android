package com.taowu.backend

import android.content.Context
import android.util.Log
import com.taowu.backend.download.Downloader
import com.taowu.backend.download.Naming
import com.taowu.backend.download.Throttle
import com.taowu.backend.grab.Grabber
import com.taowu.backend.media.Remuxer
import com.taowu.backend.net.OkHttpNetEngine
import com.taowu.backend.registry.RecipeInfo
import com.taowu.backend.registry.RecipeRegistry
import com.taowu.backend.registry.loadRecipeText
import com.taowu.backend.registry.recipeInfoOf
import com.taowu.backend.registry.registerSpec
import com.taowu.backend.run.RecipeEngine
import com.taowu.backend.schema.validateSpec
import com.taowu.backend.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** 后端容器：装配引擎 + 注册表 + 解析器 + 下载器，并从 assets 载入配方。 */
class BackendContainer(private val context: Context) {

    init { AppSettings.init(context) }

    private val log: (String) -> Unit = { Log.d("taowu", it) }

    val net = OkHttpNetEngine()
    val engine = RecipeEngine(net, log)
    val registry = RecipeRegistry()
    val resolver = MediaResolver(registry, engine, net)

    /** 下载器：注入 Media3 Transformer 合成器（多路 → 单文件）。 */
    val downloader = Downloader(
        net,
        mux = { files, out -> Remuxer.combine(context, files, out) },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 通用视频抓取编排层（主力）。 */
    val grabber = Grabber(resolver, downloader, net, log)

    init {
        // 启动自适应并发控制器（全局 + 单 host 闸门按吞吐自动升降）
        Throttle.startController(scope)
    }

    var loadedRecipeCount: Int = 0
        private set

    /** 已载入配方元信息（按分类排序，供设置页展示）。 */
    var recipeInfos: List<RecipeInfo> = emptyList()
        private set

    /** 下载落盘目录（App 私有）。 */
    val downloadDir: File by lazy {
        File(context.filesDir, "downloads").apply { mkdirs() }
    }

    /** 用户配方目录（App 私有，可增删）。 */
    val userRecipeDir: File by lazy {
        File(context.filesDir, "recipes").apply { mkdirs() }
    }

    /** 已载入的配方名（去重排序，供设置页展示）。 */
    val loadedRecipeNames: List<String>
        get() = registry.snapshot().map { it.name }.distinct().sorted()

    /** 载入配方（内置 assets + 用户导入）。 */
    fun initRecipes() {
        reloadRecipes()
    }

    /** 重建配方表：assets 内置 → 用户配方（同 host_suffix 覆盖内置）。 */
    fun reloadRecipes() {
        registry.reset()
        val infos = ArrayList<RecipeInfo>()
        var n = 0
        // ① 内置（assets/recipes）
        val names = try {
            context.assets.list("recipes") ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
        for (name in names.sorted()) {
            if (!name.endsWith(".json") || name.startsWith("_")) continue
            val text = try {
                context.assets.open("recipes/$name").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                log("读取配方 $name 失败：${e.message}"); continue
            }
            val stem = name.removeSuffix(".json")
            val (spec, err) = loadRecipeText(text, stem)
            if (spec == null) { log("配方 $name 跳过：$err"); continue }
            if (registerSpec(registry, engine, spec)) {
                n++
                infos.add(recipeInfoOf(spec, stem, "builtin", ""))
            } else log("配方 $name 校验未通过")
        }
        // ② 用户导入（filesDir/recipes）
        val files = userRecipeDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name } ?: emptyList()
        for (f in files) {
            val text = try { f.readText() } catch (e: Exception) { continue }
            val stem = f.name.removeSuffix(".json")
            val (spec, err) = loadRecipeText(text, stem)
            if (spec == null) { log("用户配方 ${f.name} 跳过：$err"); continue }
            if (registerSpec(registry, engine, spec)) {
                n++
                infos.add(recipeInfoOf(spec, stem, "user", f.name))
            } else log("用户配方 ${f.name} 校验未通过")
        }
        loadedRecipeCount = n
        recipeInfos = infos.sortedWith(compareBy({ if (it.category == "video") 0 else 1 }, { it.order }, { it.name }))
        log("已载入 $n 份配方（用户 ${files.size}）")
    }

    /** 导入一份配方文本。成功返回 null，失败返回错误信息。 */
    fun importRecipe(fileName: String?, text: String): String? {
        if (text.toByteArray(Charsets.UTF_8).size > 512 * 1024) return "配方文件超过 512KB"
        val stem = (fileName ?: "recipe").substringBeforeLast(".json").ifEmpty { "recipe" }
        val (spec, err) = loadRecipeText(text, stem)
        if (spec == null) return err ?: "JSON 解析失败"
        val errs = validateSpec(spec)
        if (errs.isNotEmpty()) return "校验未通过：" + errs.joinToString("；")
        val safe = Naming.fsClean(stem, 60).ifEmpty { "recipe_${System.currentTimeMillis()}" }
        val dst = File(userRecipeDir, "$safe.json")
        return try {
            dst.writeText(text)
            reloadRecipes()
            null
        } catch (e: Exception) {
            "写入失败：${e.message}"
        }
    }

    /** 删除一份用户配方（按文件名）。成功返回 true。 */
    fun deleteRecipe(file: String): Boolean {
        if (file.isEmpty()) return false
        val f = File(userRecipeDir, file)
        if (!f.exists()) return false
        val ok = try { f.delete() } catch (e: Exception) { false }
        if (ok) reloadRecipes()
        return ok
    }
}
