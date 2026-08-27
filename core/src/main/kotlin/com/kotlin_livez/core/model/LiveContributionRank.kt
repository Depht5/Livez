package com.kotlin_livez.core.model

import kotlinx.serialization.Serializable

/**
 * 贡献榜项。
 * 对应 Dart `LiveContributionRankItem`（lib/src/model/live_contribution_rank.dart）。
 */
@Serializable
data class LiveContributionRankItem(
    val rank: Int,
    val userName: String,
    val avatar: String,
    val scoreText: String,
    val scoreDetail: String? = null,
    val userLevel: Int? = null,
    val userLevelText: String? = null,
    val userLevelIcon: String? = null,
    val fansLevel: Int? = null,
    val fansName: String? = null,
    val fansIcon: String? = null,
)
