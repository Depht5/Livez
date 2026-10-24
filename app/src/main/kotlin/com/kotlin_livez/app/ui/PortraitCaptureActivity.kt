package com.kotlin_livez.app.ui

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * 竖屏扫码 Activity（task 10-24-lan-sync-ui）—— 局域网同步「扫一扫」的相机页。
 *
 * zxing-android-embedded 的 [CaptureActivity] 在其库清单里锁定横屏；
 * 子类化并在本 App 清单中显式声明 `screenOrientation="portrait"` 覆盖之，
 * 与应用内其余页面方向一致。扫码参数（仅 QR / 关提示音）由调用方的
 * `ScanOptions.setCaptureActivity` 指回本类。
 */
class PortraitCaptureActivity : CaptureActivity()
