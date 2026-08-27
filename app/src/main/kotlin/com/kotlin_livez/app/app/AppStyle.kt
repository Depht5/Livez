package com.kotlin_livez.app.app

/**
 * 应用样式常量 —— 对应 Dart `AppStyle`（lib/app/app_style.dart）中与 UI 无关的
 * 数值/颜色定义。Flutter 主题与间距（ThemeData/EdgeInsets）由 UI 层（Compose MaterialTheme）
 * 落地时结合本文件常量实现；此处仅登记品牌种子色与冻结可见文案。
 */
object AppColors {
    /** 品牌种子色（对应 Dart seedColor 0xff3498db）。 */
    const val seedColor = 0xFF3498DB

    /** 对应 Dart black333。 */
    const val black333 = 0xFF333333
}

/** 布局间距（dp）—— 对应 Dart AppStyle 的 vGap/hGap/edgeInsets 常量族。 */
object AppSpacing {
    const val v4 = 4
    const val v8 = 8
    const val v12 = 12
    const val v24 = 24
    const val v32 = 32
    const val v48 = 48

    const val h4 = 4
    const val h8 = 8
    const val h12 = 12
    const val h16 = 16
    const val h24 = 24
    const val h32 = 32
    const val h48 = 48

    const val radius4 = 4f
    const val radius8 = 8f
    const val radius12 = 12f
    const val radius24 = 24f
    const val radius32 = 32f
    const val radius48 = 48f
}