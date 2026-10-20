# 海外点播 CDN 自动加速

## 架构与参考

当前 fork 基线：`cd09bd903e6d0c22f5986580c2105796d8df6471`。
参考 realzza/bilibili-accelerator `a74ec390c203560a16584aeecb8d081a10db7f59`，主要是
`src/core/routing.js`、`src/core/rewrite.js` 和 `docs/vod-routing.md`。采用新版按视频、按实际下载表现的策略，
而不是旧版开播测全池、按固定排名换域。MIT 声明见 THIRD_PARTY_NOTICES.md，APK assets 同时包含声明。

blbl 的 PlayURL 解析已提供各音视频 representation 的主备完整 URL、签名、码率和请求 profile。
原有 `CdnFailoverDataSource` 仅在 open 失败后轮换。新增 `core/cdn` 是不依赖 Android UI 的 Kotlin 包，
分别负责候选、策略/估速、限量 Range 测速、播放源级会话和字节流续传；没有新增 Gradle 依赖。
`AcceleratedCdnDataSource` 是薄 Media3 适配层，由 ExoPlayerEngine 仅为 PlayURL 媒体 URL 注册。
无缝 DASH 清晰度模式同样按 representation 路由。API、登录、弹幕、字幕、直播、IjkPlayer 不接入加速器。

## 行为

- 默认自动：先使用已有 CDN 线路设置排序后的 PlayURL URL，正常播放不主动测速。
- 下载样本至少 16KB、总量至少 128KB，双 EWMA 半衰期 2/5 秒，取较低估计，包含首字节等待；不把播放器暂停/缓冲已满的空闲时间算作下载时间。
- 检测持续低于所需码率（考虑倍速）、低缓冲的慢请求/无首字节、30 秒内重复错误，或至少 4 秒且缓冲不足 3 秒的 BUFFERING。
- 同时只进行一场 Race：当前节点加两个候选，同文件同偏移，最多各 768KiB、各 4 秒，失败节点休息 60 秒。
  优先未测过的 PlayURL 备用节点，再按本播放源实测速度排序；未知节点有探索机会。没有固定供应商胜者。
- 自动全局换路须至少达到参考速度 1.5 倍；单次慢连接不会直接抹掉既有良好估速。
  连续缓冲/重复失败时降低对既有速度估计的信任。当前节点也测速，以同区间直接比较。
- 对返回 200 全文件、错误/缺失 Content-Range、错误长度、截断、403、超时均不采用。
  当前节点能完整测速时还比较 SHA-256，拒绝同区间内容不一致的候选。
- 保留每个 CDN 原发完整签名；只有标准端口、非已知重定向 UPOS 主机可生成 UPOS 换域候选。
  Akamai 只接受其原发 URL，不拿 UPOS 签名换域到 Akamai，也不从 Akamai 构造 UPOS URL。
- 获胜后在下一次读取/请求时用 `DataSpec.subrange(consumed)` 在同一字节位置续传，不重建播放器、不跳播放时间。
  已卡住的读取最多等待加速媒体客户端的 5 秒读超时，再续传。原客户端超时不受影响。
- 新节点 open/read 失败（包括签名拒绝）恢复原发 URL/备用 URL；失败生成节点本源内停用。
  数据源内最多 4 次恢复，全部失败则向现有播放器错误处理返回错误，不无限重试。
- 切换后冷却 10/20/40/80 秒；无改善后 15/30/60/120 秒。每源最多 4 次切换、12 场 Race，
  最坏测速流量约 27MiB（通常更少）；换集/更新 PlayURL/释放播放器时清理和取消测速。
- 手动：首个合适媒体请求期间验证所选主机，无需达到 1.5 倍提速；验证失败或没有兼容签名就保留原线路。
  手动模式不自动改选其他生成节点。模式和主机设置下次播放生效。
- 关闭：完全沿用原有数据源和原有 PlayURL 主备失败轮换行为。

## Fire TV 安装与验证

1. Fire TV 设置 → 我的 Fire TV → 关于，必要时连续点击设备名称 7 次显示开发者选项。
   在开发者选项打开 ADB 调试；在网络页面查看电视局域网 IP。
2. 电脑安装 Android platform-tools，与电视在同一局域网。按电视提示授权电脑：

   ```sh
   adb connect FIRE_TV_IP:5555
   adb install -r blbl-cdn-debug.apk
   ```

   此包 applicationId 仍为 `blbl.cat3399`。如果原应用由另一签名签发，Android 会拒绝覆盖；
   请先导出需要保留的配置，明确接受本地数据丢失后再卸载旧包安装，不要直接无提示卸载。
   当前 APK 是开发调试签名，不是上游 release 签名。

3. blbl 设置 → 其他设置 → 播放器内核选 ExoPlayer；CDN 自动加速选自动；显示视频调试信息打开。
   重新打开点播视频（不是直播），选较高码率。观察当前 VIDEO/AUDIO CDN、实际 goodput、race/switch 计数及最近记录。
4. 使用同一视频、相同清晰度和编码对比关闭与自动。正常播放应保持 race=0；观察卡顿后
   race 的触发原因、各节点测速、是否换到实测更快节点，以及位置连续、音画/弹幕正常。
   也测试暂停、快进、倍速、切集、返回退出、无缝清晰度切换、音频轨道、手动节点和重新打开视频。
5. 验证回退：手动挑选当前网络不可达/拒绝此签名的节点（如确有此情况），应看到失败或保持原线路，
   而不是永久黑屏。不要伪造/修改签名来验证真实视频。
6. 收集事件日志（日志只含主机、速率和状态，不输出签名 URL）：

   ```sh
   adb logcat | grep CdnAcceleration
   ```

   实际应用日志还可通过现有日志导出功能查看。测试后可关闭 ADB 调试。

## 验证命令

```sh
source /workspace/environment/android-env.sh
cd /workspace/blblOverseas
./gradlew --no-daemon --max-workers=4 :app:testDebugUnitTest --tests 'blbl.cat3399.core.cdn.*'
./gradlew --no-daemon --max-workers=4 :app:assembleDebug :app:testDebugUnitTest
```

单元测试覆盖候选/签名、可用性/速度判定、冷却、健康/暂停不干预、手动回退、Race 流量上限、
本地 HTTP Range 成功与拒绝响应，以及部分读取后换路、截断和错误恢复的字节连续性。

## 限制

这是插件策略的 Android 适配，不是完整移植：不做浏览器请求拦截、P2P/MCDN 代理、跨视频持久排名或单个 fragment 的临时路由。
速度历史只存于当前 PlayURL 会话；不把某地区某节点一次测速推广到其他视频。
未申请账号凭据，不保证 VIP、地区限制、失效 PlayURL 等非 CDN 问题可恢复。
只改善媒体传输问题，不能修复解码器负载/不兼容格式；所有节点都差时会保留原线路。
已缓冲画面不重取；切换生效可能需等当前阻塞的网络读取超时。
不支持 IjkPlayer、直播、仅 Akamai 签名时凭空生成 UPOS 路由，也不提供任意自定义主机输入。
宿主 JVM 测试与 APK 编译不能替代 Fire TV 上的真实海外网络实测；没有声称已复现特定地区的 26.5Mbps。

仓库基线有一个与本功能无关的失败：PlayerAutoNextTitleTest 期待 12 字符，但生产常量为 18；
本任务保留该逻辑和原测试，完整测试报告应如实显示该失败。

## 本次检查结果

- `assembleDebug` 完成；分发文件 `build/distributions/blbl-cdn-debug.apk`，15,233,407 字节。
- APK ZIP 完整性、内置 MIT 声明、v1/v2 签名校验通过。
- 新增 CDN 单元测试 29 项，全部通过；全套 120 项，119 通过、1 失败、0 跳过。
- 唯一失败为上文说明的原有 `PlayerAutoNextTitleTest`，未修改该测试或对应业务逻辑。
- `lintDebug` 未通过：37 errors / 234 warnings。37 个错误在未修改的
  `SeamlessQualityTrackSelection.java`（32 个 UnsafeOptInUsageError）、`item_video_card.xml`
  和 `view_player_touch_overlay.xml`（5 个 UseAppTint）中；新增 CDN 文件无 lint 报告。
- 组合验证命令因上述测试/lint 返回非零；没有将完整检查描述为全部通过。
- 结果元数据见 `build/distributions/validation.json`；完整报告在
  `app/build/reports/tests/testDebugUnitTest/` 和 `app/build/reports/lint-results-debug.html`。
- 未进行 Fire TV 实机或真实 B 站海外线路测速；请按上面的步骤做网络与设备验收。

## 文件清单

| 文件（仓库相对路径） | 改动 |
| --- | --- |
| `app/src/main/java/blbl/cat3399/core/cdn/CdnRouting.kt` | 候选 URL/签名保留、EWMA、触发策略、挑战者排序、提速门槛、冷却 |
| `app/src/main/java/blbl/cat3399/core/cdn/CdnRangeProbe.kt` | 有界 HTTP Range 测速、响应校验、内容校验与取消 |
| `app/src/main/java/blbl/cat3399/core/cdn/CdnSession.kt` | 每播放源的路由、主机健康、Race 生命周期、频率限制与诊断 |
| `app/src/main/java/blbl/cat3399/core/cdn/CdnResumableStream.kt` | 换路和读失败后的精确字节续传/原址恢复 |
| `app/src/main/java/blbl/cat3399/feature/player/engine/AcceleratedCdnDataSource.kt` | Media3 适配，非匹配 URL 走原客户端 |
| `app/src/main/java/blbl/cat3399/feature/player/engine/ExoPlayerEngine.kt` | 点播接线、码率/缓冲状态观察、生命周期取消 |
| `app/src/main/java/blbl/cat3399/feature/player/PlayerActivityDebug.kt` | 显示当前节点、速率、测速/切换记录 |
| `app/src/main/java/blbl/cat3399/core/prefs/AppPrefs.kt` | 持久化模式和手动主机设置 |
| `app/src/main/java/blbl/cat3399/feature/settings/SettingEntry.kt` | 新设置项标识 |
| `app/src/main/java/blbl/cat3399/feature/settings/SettingsRenderer.kt` | 设置列表 |
| `app/src/main/java/blbl/cat3399/feature/settings/SettingsInteractionHandler.kt` | 遥控器选择框 |
| `app/src/test/java/blbl/cat3399/core/cdn/CdnRoutingTest.kt` | 路由/签名/估速/触发/提速门槛回归 |
| `app/src/test/java/blbl/cat3399/core/cdn/CdnRangeProbeTest.kt` | 本地 HTTP Range 与异常响应测试 |
| `app/src/test/java/blbl/cat3399/core/cdn/CdnResumableStreamTest.kt` | 中断、换路、截断与回退的字节连续性 |
| `app/src/test/java/blbl/cat3399/core/cdn/CdnSessionTest.kt` | 健康不干预、自动/手动选择、冷却、次数上限和取消 |
| `THIRD_PARTY_NOTICES.md`、`app/src/main/assets/cdn-accelerator-LICENSE.txt` | 来源与 MIT 许可；随 APK 分发 |
| `CDN_ACCELERATION.md` | 架构、算法、限制、测试结果、安装说明和文件清单 |

## 独立海外测试版 release

新增构建类型 `overseasRelease`，继承原 release 的 R8 混淆和资源压缩：

- 应用 ID：`blbl.cat3399.overseas`，与原版 `blbl.cat3399` 共存。
- 桌面名称：`blbl 海外测试版`。
- `debuggable=false`；安装和使用不需要 ADB 或电脑调试授权。
- FileProvider 使用现有 `${applicationId}.fileprovider`，不会与原版冲突。
- 数据、登录和偏好独立，需要在测试版重新登录与设置。
- 使用专用测试签名，不使用上游发行签名或 Android debug keystore。
  密钥与密码环境文件保存在 Git 忽略的 `keystore/` 中，未写入源代码。
  后续覆盖更新此测试版必须沿用同一个密钥，请安全保留这两个文件，勿公开上传。

本工作区重新构建：

```bash
source /workspace/environment/android-env.sh
cd /workspace/blblOverseas
source keystore/overseas-signing.env
./gradlew --no-daemon --max-workers=4 :app:assembleOverseasRelease
```

其他构建环境需安全提供专用测试 keystore 与 `OVERSEAS_STORE_PASSWORD`。
可通过 `OVERSEAS_STORE_FILE` 指定 keystore 路径；alias 为 `overseas-test`。
普通 `release` / `debug` 保持原应用 ID，安装测试版时应选择 overseas-release 分发包。

用 Downloader 下载新包并允许安装未知应用即可，无需卸载原版或解决局域网 ADB 连通性。
原版内置更新源不是这个独立测试版的更新渠道；后续请安装使用同一测试签名的新测试 APK。

独立版构建与校验已完成：`assembleOverseasRelease` 成功，APK 为 `build/distributions/blbl-overseas-test-release.apk`（5,253,063 字节）。包名、名称、非 debuggable / 非 testOnly、独立 provider authority、Fire TV Leanback 启动入口、ZIP 完整性及 v1/v2 签名均已核对。对应校验信息为 `build/distributions/overseas-release-validation.json`。此阶段改动 `app/build.gradle.kts` 和新增 `app/src/overseasRelease/res/values/strings.xml`；未修改 CDN 业务逻辑。
