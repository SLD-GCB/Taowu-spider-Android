# ================================================================
# 梼杌 release 的 R8 规则（混淆 + 压缩 + 资源压缩）
# 说明：本项目几乎没有反射/序列化实体，绝大多数依赖自带 consumer 规则；
#       这里只做「必要的 keep」与「缺失类的 dontwarn」，尽量不削弱混淆。
# ================================================================

# 保留行号，崩溃栈可读（配合 build/outputs/mapping/release/mapping.txt 还原）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- kotlinx.serialization（我们只用 JsonElement 动态解析；兜底规则，防万一）----
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-dontnote kotlinx.serialization.**

# ---- OkHttp / Okio / Conscrypt（OkHttp 会按运行时探测平台实现）----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Media3 ----
-dontwarn androidx.media3.**

# ---- 其它可选依赖的告警（不影响运行）----
-dontwarn javax.annotation.**
-dontwarn kotlinx.coroutines.debug.**
