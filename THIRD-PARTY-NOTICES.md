# Third-Party Notices

本项目的源代码以 [MIT](LICENSE) 协议发布，但**运行时链接的第三方库仍归各自作者所有，
并遵循各自的许可证**。分发编译产物（APK）时，请保留本文件与 `LICENSES/` 目录。

## 运行时依赖

| 组件 | 版本 | 许可证 | 版权 |
|---|---|---|---|
| [RootEncoder](https://github.com/pedroSG94/RootEncoder) | 2.8.1 | Apache-2.0 | Copyright 2021 pedroSG94 |
| [RTSP-Server](https://github.com/pedroSG94/RTSP-Server) | 1.4.3 | Apache-2.0 | Copyright 2021 pedroSG94 |
| [AndroidX AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat) | 1.7.1 | Apache-2.0 | Copyright The Android Open Source Project |
| [AndroidX Core KTX](https://developer.android.com/jetpack/androidx/releases/core) | 1.13.1 | Apache-2.0 | Copyright The Android Open Source Project |

Apache License 2.0 全文见 [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt)。

### 我们对依赖做了什么

- 未修改上述库的源码，仅以 Gradle 依赖方式引用。
- 排除了 `org.bouncycastle` 传递依赖（本项目不使用 WHIP/DTLS，通过
  `configurations.configureEach { exclude(group = "org.bouncycastle") }` 实现）。

## 不包含在 MIT 授权范围内的内容

以下资产为项目所有者的品牌资产，随本仓库分发，但**不授予商用或衍生使用许可**：

- 应用名称 "CamNexusRTSP"
- 应用图标与 Logo：`app/src/main/res/mipmap-*`、`app/src/main/res/drawable-nodpi/logo_watermark.png`、
  `app/src/main/res/drawable-nodpi/ic_launcher_foreground.png`

如果你要基于本项目做二次发布，请替换名称与图标，避免与原项目或第三方商标混淆。

> 备注：本项目仅供个人自用。公开分发前请自行评估 "CamNexus" 与已有商标的冲突风险
> （美国商标注册号 85848866、英国公司 CAMNEXUS LTD 等）。
