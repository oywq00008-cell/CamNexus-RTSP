package com.camnexus.rtsp

/**
 * 固定方向的设置页入口。
 *
 * Manifest 里给这两个子类分别声明了 portrait / landscape，
 * 系统在创建启动窗口时就能拿到正确方向，避免"先竖后横"的闪烁。
 */
class SettingsPortraitActivity : SettingsActivity()

class SettingsLandscapeActivity : SettingsActivity()
