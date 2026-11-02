# 横竖屏实现说明

## 结论

推流画面的方向由 **界面方向** 决定：切到横屏界面，相机管线随之输出横画面；切回竖屏则输出竖画面。
因此「横屏推流」不需要旋转帧、不需要改编码尺寸，只需要切换 Activity 方向。

## 实现

| 位置 | 做法 |
|---|---|
| 设置项 | `StreamConfig.uiLandscape`，持久化在 SharedPreferences |
| 首页 | 「横屏界面」开关，切换后延迟 1.2 秒再转方向，先弹出「请将设备横向/竖向放置」提示 |
| 方向应用 | 顶部 `applyUiOrientation()` 设置 `requestedOrientation` |
| 预览 | 布局尺寸变化时重新挂载 Surface，避免旋转后只显示一条 |
| 设置页 / 检测页 | `SettingsPortraitActivity` / `SettingsLandscapeActivity`、`DiagnosePortraitActivity` / `DiagnoseLandscapeActivity` 四个固定方向子类，Manifest 中写明 `screenOrientation`，启动窗口不会先错后对 |
| OSD | 位图按界面方向合成（横屏用横版位图），并随方向变化重建缓冲 |
| 网页端 | 不提供方向切换（避免远程旋转手机界面） |

## 走过的弯路（已放弃）

- `Camera2Source.setRotation()`：只存字段，`create()` 不读取，无效。
- `GlStreamInterface.setStreamRotation()`：整帧在宽高适配后旋转，不交换宽高，横屏输出会被裁切或压扁。
- `autoHandleOrientation=false` + 横版编码尺寸：画面被拉伸（比例反了）。
- GL 滤镜链整体旋转：滤镜画布跟随相机预览尺寸，不是编码尺寸，转出来仍是竖条。

最终采用「改界面方向」后，上述问题全部消失，且预览与推流方向天然一致。

## 注意

- 手机物理姿态决定取景方向：界面横屏但手机竖放时，推流是「横画面里的竖向内容」，属于正常现象。
- Android 16+ 在大屏设备上会忽略固定方向（lint `DiscouragedApi`）；手机尺寸不受影响。
