package com.kotlin_livez.core.common

/**
 * 播放器错误分类。
 * 对应 Dart `PlayerErrorClassifier`（lib/src/common/player_error_classifier.dart）。
 */
object PlayerErrorClassifier {
    fun isRecoverableAudioDiagnostic(error: String): Boolean {
        val normalized = error.trim().lowercase()
        return normalized.contains("error decoding audio.") || normalized.contains("no sound.")
    }
}