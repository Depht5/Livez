package com.kotlin_livez.core.model

import kotlinx.serialization.Serializable

/**
 * 站点分类。
 * 对应 Dart `LiveCategory` / `LiveSubCategory`（lib/src/model/live_category.dart）。
 */
@Serializable
data class LiveCategory(
    val name: String,
    val id: String,
    val children: List<LiveSubCategory>,
)

@Serializable
data class LiveSubCategory(
    val name: String,
    val pic: String? = null,
    val id: String,
    val parentId: String,
)
