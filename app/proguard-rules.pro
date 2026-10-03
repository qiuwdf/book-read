# ============================================================
# R8 代码裁剪 / 混淆规则
# ------------------------------------------------------------
# 只作用于 release 包，源码一行不动；debug 包与测试（Robolectric）不走这里。
# 本应用无反射、无 Parcelable/Serializable，清单与布局引用的类（Activity、
# 自定义 View）由 AGP 自动生成的规则保留，因此这里几乎不需要写 keep。
# ============================================================

-dontwarn android.support.**

# 保留行号信息，崩溃堆栈才能定位到具体代码行。
# 类名会被混淆成 a.b.c 这类短名，反解时用构建产物里的
# app/build/outputs/mapping/release/mapping.txt（每次出包都应一起备份）。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
