package com.camnexus.rtsp

/**
 * 固定方向的检测页入口。
 *
 * 和设置页一样：Manifest 里固定 portrait / landscape，
 * 启动窗口一出现就是正确方向，不会先错后对。
 */
class DiagnosePortraitActivity : DiagnoseActivity()

class DiagnoseLandscapeActivity : DiagnoseActivity()
