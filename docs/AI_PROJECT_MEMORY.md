# AI 项目记忆（AI Project Memory）

> 此文件由 AI 助手（TraeCode）维护，与本地项目记忆同步，随仓库提交。
> 目的：换电脑 / 更换 AI 账号后，AI 助手仍能从本文件恢复项目的全部历史要求、踩坑教训与工程约定，用户无需重复交代。
> **给 AI 的硬规则**：每次更新本地项目记忆后，必须把全文同步拷贝到本文件并随 commit push。

## Working Discipline（常驻工作纪律 —— 适用于之后**每一个**问题，不只是当轮）
用户 2026-09-21 明确要求：「我也并不是针对这次问题 而是以后所面临的所有问题 你都要有这个意识」。以下流程是**每次动手的默认动作，不需要用户再提醒**：
1. **先列举、再动手**：定位/修任何问题后，主动全仓（数据层 + UI 层 + 各站点客户端）grep 同类模式，把「同一类问题的**所有**出现点」一次改完；绝不只修被用户指出的那一个点，也不许等用户逐条报。
2. **结论要有证据，且不能只测一个样本**：涉及站点结构 / 接口 / 登录态的判断必须实测取证（真站点 DOM、真实登录态），不许凭猜测下结论（历史上已因此白改过一轮）；自己测不了就请用户配合，**不许拿匿名会话的结果当结论**。
   ⚠️ **2026-09-24 补充（JOOX 事件）**：判定「某接口/某音源**能不能用**」时，**至少跨多个对象（多首歌/多个关键词）+ 对同一对象重复测几次**，两个维度都要覆盖；
   只测一个样本会把"时有时无"误判成"永远没有"——JOOX 直链就是这么被误判的，错误结论还写进了给用户的答复。测不到时要说明"这是 N 次里的第几次、什么条件"。
3. **修完自检 + 横向对比**：编译通过；主动检查相邻实现是否也要同步 —— 固定扫这几类：本地登录态是否只是启发式、Compose 列表 key 是否唯一、跨线程可变状态是否安全、异常是否被静默吞掉、站点解析是否有整页文本兜底、硬编码地址是否有兜底。
4. **失败要说出来**：不许静默吞异常让界面显示「空/无数据」（用户会当成站点没内容）；必须区分「真的没有」与「请求失败/登录失效」。
5. **不许过度设计**：核查后确认无风险的（如稳定常量、已有双主机切换）就不动，不为「统一」加无意义兜底。
6. **「测不出来」不许当结论**：任何探测/校验（登录态、连通性、分类数、可用线路…）都必须区分「确认失败」与「没测出来」。没测出来时按「不影响用户」的那一侧处理（例：登录态不确定 → 不报失效、但也不挡住重新登录入口），并给探测本身加超时，避免界面卡在"检测中"。
7. **假设必须能被证伪，单一假设要解释全部观测（2026-10-19 图片十轮盲修教训）**：图床图片问题连修十轮的根因是第一个假设（「CDN 按 TLS 指纹给 okhttp 下发随机垃圾」）只解释了部分观测，后续矛盾观测（同批同头/跨批换头、全响应 16B 对齐、PC curl 与设备逐字节一致）出现时被打了补丁绕过而不是推翻假设。固化：① 动手前先写下「本假设如何被证伪」，证伪点出现了就换假设；② 能用对方自己的代码验证的必须先验证（站点 zzz.js 载 Node 沙箱跑它自己的 decryptImage，一测便知密钥指派对错）；③ 结构性特征（密文 16B 对齐、跨端逐字节一致）比表象特征（响应头变化）更可信；④ 密钥/协议类修复默认 TDD + **真实字节 fixture** 锁定指派方向（melon_cover1.cipher 就是这么来的）；⑤ 修复手段要分级可逃逸（如播放器自愈：轻量重载失败就升级整条重建），单一姿势无限重试 = 把自愈变成死循环。

## Hard Constraints
- **第二十九轮（电视「有画面没声音」的**正解**：内置 FFmpeg 软解；含 0.198 的失败尝试与 0.199 回退）= v0.199 + v0.200**：①**用户反馈链**：0.198 装机后「一直显示线路播放出错」→ 追问确认**只有 CCTV3/CCTV5 这类以前没声音的频道**（其它频道正常）→ 坐实是 0.198 那处改动；用户拍板「接 FFmpeg 软解」。②**0.198 的失败尝试（重要教训）**：把 MP2 音轨的 MIME `audio/mpeg-L2` **映射成 `audio/mpeg`** 交给系统解码器（自定义 `DefaultRenderersFactory` + `MediaCodecAudioRenderer`，在 `supportsFormat`/`getDecoderInfos`/`getMediaCodecConfiguration` 三处传改写后的 Format）→ **平台那个"MP3 解码器"根本不接受 Layer II 帧**，解码器初始化/解码直接失败 → ExoPlayer 报错 + 我们的直播自动重连反复重试 = 用户看到的「一直出错」。**结论：MIME 映射救不了 MP2，Android 平台（至少这台机器）没有能解 MP2 帧的解码器 → 只能软件解码**。**核对手段（可复用）**：从本地 Gradle 缓存的 media3 AAR 里 `javap` 出关键签名（`DefaultRenderersFactory.buildAudioRenderers`、`MediaCodecAudioRenderer.supportsFormat/getDecoderInfos/getMediaCodecConfiguration`），并读 media3 源码确认 `getMediaFormat(format, codecInfo.codecMimeType, ...)` 用的是**解码器**的 MIME、`onOutputFormatChanged` 用 mediaFormat 的通道/采样率 —— 所以 relabel 只影响"选哪个解码器"，而选中的解码器解不了 —— 这才是根因。③**0.199（回退 + 把失败说出来）= v0.199**：整体删除该渲染器（回到 media3 默认渲染器，MP2 频道恢复"有画面没声音"而不是报错）；`onPlayerError` 文案细分（新增「本机解不了这个编码」= `ERROR_CODE_DECODER_INIT_FAILED/QUERY_FAILED`、「音频输出失败」= `ERROR_CODE_AUDIO_TRACK_INIT_FAILED/WRITE_FAILED`，兜底文案带上 `error.errorCodeName`）；新增 `Player.Listener.onTracksChanged` 检测「存在 type=audio 且**未被选中**、MIME 是 `audio/mpeg-L1/L2` 的音轨组」→ Toast「该频道音频是 MP2，本机解码器不支持：会有画面没声音，建议换线路或换源」（只弹一次）—— 把"被静默丢掉的音轨"变成明说。④**0.200（正解）= v0.200**：media3 `1.5.1 → 1.7.1`；新增依赖 **`io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0`**（预编译 FFmpeg 解码 AAR；⚠️ **GPL-3.0**，随 App 分发要求整个 App GPL 兼容 —— 已告知用户）；`PlayerScreen` 改用 `NextRenderersFactory(context).setEnableDecoderFallback(true).setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)` —— **ON = 只在平台渲染器不支持时才交给 FFmpeg**，AAC/MP3 照旧走平台/硬件解码（不影响性能）；`defaultConfig` 加 `ndk { abiFilters += listOf("arm64-v8a","armeabi-v7a") }`（AAR 自带 4 个 ABI，去掉 x86/x86_64）。**APK 9.0MB → 19.7MB**。⑤**⚠️ 本轮最值得记的坑**：**(a) 依赖坐标写错会被 Gradle 报 `Could not find`** —— NextLib 的 groupId 是 `io.github.anilbeesetti`（`io.github.anilbeesetti.nextlib` 是包名/仓库名，不是 groupId）；**报错里 Gradle 打印的查找 URL 能直接反推 groupId**（本轮它去查 `.../io/github/anilbeesetti/nextlib/nextlib-media3ext/...` 就说明多写了一层）。**(b) NextLib 版本号格式 = `<media3 版本>-<nextlib 版本>`**（`1.7.1-0.9.0` 对应 media3 1.7.1）→ media3 必须配套升到该版本；**官方 media3 的 FFmpeg 扩展不发 Maven 制品**（要用 media3 源码 + NDK 自己编），所以只能走 NextLib 这类预编译包。**(c) NextLib 的 MIME→FFmpeg 解码器映射**：`audio/mpeg`/`audio/mpeg-L1`/`audio/mpeg-L2` 都映射到 FFmpeg 的 **`mp3`** 解码器（同源实现，能解 Layer I/II 帧）。**(d) `audio/mpeg-L1|L2` 这两个 MIME 是 AOSP 自己定义、且很多设备不注册解码器**（实测这台设备就没有）。⑥**发版**：0.199 = commit `1f5f3d8` / tag `v0.199` / **Release `394241624`** / 资产 `LaoZhengFenXiang-0.199.apk`（8,983,518 字节，SHA256 `BEB9125915AE5B42FF883967A55DE6792F49EEE70EF9BF8E34B54328B8588D87`）；0.200 = commit `e579ac6` / tag `v0.200` / **Release `394258937`** / 资产 `LaoZhengFenXiang-0.200.apk`（19,709,368 字节，SHA256 `FD76408C9A7C8B8750C46CA58C9E2DDFD1BF1C00F7299989E59B3B9A009BD8C0`）；桌面只留中文名一份 `老郑分享-0.200.apk`。⑦**✅ 已装机验证（2026-09-23）**：用户确认「**有声音了**」—— CCTV3/CCTV5 出声，FFmpeg 软解这条路走通了（NextLib 把 `audio/mpeg-L1|L2` 交给 FFmpeg 的 `mp3` 解码器，它确实能吃 Layer II 帧）。**以后遇到「平台没有解码器的音频编码」（MP2 / AC3 / EAC3 / DTS…）就照这套路做：挂 FFmpeg 扩展渲染器（`EXTENSION_RENDERER_MODE_ON` 兜底），不要再尝试改 MIME 映射去骗平台解码器**（0.198 已验证那条路走不通）。
- App name must be '老郑分享' (not 'ShangYin')
- Search feature must not display '豆瓣' in UI text
- Database schema changes require destructive migration (fallbackToDestructiveMigration)
- Image requests to doubanio.com/douban.com must include Referer: https://m.douban.com/ and iPhone UA to bypass anti-hotlinking
- All share buttons must be removed from UI
- List items should use long-press for delete confirmation instead of persistent delete buttons
- Search must require explicit category selection (影视/图书/游戏/人物) to prevent result interference
- All external '在豆瓣打开' links must be removed
- No '添加' function for celebrity/person entries
- No link import feature
- Movie detail navigation must use saveFromDoubanFast (store basic data immediately, background fetch full details)
- Game search must use HTML parsing from www.douban.com/search?cat=3114 (no Cookie required)
- App icon must be solid color background with white '郑' character (PNG format in mipmap directories, no adaptive vector icons)
- App must use bottom tab navigation with two primary sections: '表世界' and '里世界'
- Settings must be accessible via a single top-right button in all screens
- Login functions (豆瓣, 哔咔) must be consolidated in the settings menu under '账号管理'
- UI elements must use specific terminology: H1 renamed to '番号', H2 renamed to '本子'
- '里世界' section must have buttons for '番号', '本子', and '漫画' (漫画 to be implemented later)
- '本子' (H2) interface must be optimized to match `https://github.com/raoxwup/haka_comic`
- Bika comic detail screen must include a favorites button in the top bar
- 表世界搜索结果中已收藏的条目应显示"已收藏在某某"，而非显示收藏加号
- 表世界条目详情右上角需显示收藏状态或加号
- 清单管理应跳转到单独页面
- 番号搜索框输入内容后，需支持输入法确认直接触发搜索，无需额外点击搜索按钮
- 番号收藏到清单功能中，不应显示对号标记
- 里世界清单中的播放器需支持退出功能
- 里世界资源访问需要外网环境，表世界资源可在国内网络环境使用
- 本子页面的"最近更新"按钮和分类加载功能需修复，网络规则应与番号一致（需外网环境）
- **P站模块硬约束**：采用单站原生解析+搜索形态（类似X站但加搜索），全部接口先用真站实测再写代码；播放直链仅需UA即可直连，ExoPlayer HLS硬解直接播放；分类现在是 `/video?c=数字` 格式，旧的 `category=slug` 会被301重定向；搜索接口不接受排序参数会404，搜索态忽略排序档位；列表/搜索/分类/排序/分页/下拉刷新/会话缓存全部复用X站现有成熟实现
- 豆瓣登录必须保持 WebView 与 App 请求的 UA、bid cookie 完全一致，否则会被豆瓣风控判定指纹不匹配导致会话被踢
- 豆瓣登录页进入前必须精确清除豆瓣域的所有 WebView cookie 及本地持久化的死 cookie，避免残留旧会话导致误弹登录成功
- 豆瓣登录态校验必须使用 `/mine/` 重定向判据，不能依赖静态页面中的登录入口标记，避免移动版页面渲染导致旧判据失效
- 豆瓣 qnmob*.doubanio.com 旧图床域已下线，所有该域的图片请求需自动重写到 img2.doubanio.com

## Engineering Conventions
- **版本号规则（2026-09-22 用户指定，取代「读提交次数」，也取代最早的 `2.23.NN`）**：**每个发版轮次 `versionName` = 上一版 +1**（不补零），**从 `0.190` 起**（`0.190` → `0.191` → `0.192`…）。⚠️ **不再读 `git rev-list --count HEAD`**：老规则会因为"每轮固定产生 2 个提交（功能提交 + 记忆同步提交）"而每次 +2（用户 2026-09-22 指出 `0.185 → 0.187` 正是 +2，要求以后 +1）。APK 文件名 = `老郑分享-0.<N>.apk`，GitHub 资产名 = `LaoZhengFenXiang-0.<N>.apk`（必须 ASCII），tag = `v0.<N>`。⚠️ **versionCode 固定取 `1000 + N`**（`0.190` → `1190`；必须保持单调递增，否则系统拒绝覆盖安装）。**版本沿革**：最后一个"提交次数"版本是 `v0.183`（versionCode 1183，Release 392804722，仍在仓库里）；`v0.185`/`v0.187` 的改动与 tag/Release 已按用户要求**全部撤销**（2026-09-22，`git revert` 5 个提交），**版本号从 `0.190` 重新起算**；仓库 `app/build.gradle.kts` 当前为 `0.279 / 1279`（v0.279 发版后；上一版是 `0.278 / 1278`）。
- **项目记忆同步（v2.21.2 起，用户要求"换电脑/换账号也不丢上下文"；2026-10-16 用户再次强调并升级为**每轮必做**）**：
  ⚠️⚠️ **记忆文件有「两份」，每轮改动收尾时两份都必须更新，并且都要 push——不许只更一份、更不许只留在本地**：
  ① 仓库 `docs/AI_PROJECT_MEMORY.md`（随 commit 进 git，供换电脑/换 AI 账号后恢复上下文）；
  ② 本机 `c:\Users\62496\.trae-cn\memory\projects\-d-D-P-ShangYin--p2-bf81374ee92d029c9e64\project_memory.md`（TraeCode 的项目记忆，AI 每轮读取的那份）。
  两份**必须全文一致**，做法：改完仓库那份后用 `[System.IO.File]::Copy` 覆盖本机那份（或反之），再 `git add docs/AI_PROJECT_MEMORY.md` + commit + `git push origin main`。
  **2026-09-24 的教训（用户原话"以后记忆文件也要更新和推送"）**：v0.212 发版时只更新了仓库那份，本机记忆停留在 `0.191` 且完全没有音乐模块/33ve 等近期记录。**这是漏项，不是可选项**。
  历史背景：本机那份曾长期是"源头"、仓库那份是镜像；现在只要两份最终一致即可，方向不限。
- **发版流程（以本会话为准；2026-09-22 起版本号改为「上一版 +1、从 0.190 起」）**：① 算版本号：**上一版 +1** → `versionName = "0.<N>"`、`versionCode = 1000 + N`（规则见上条；**不再读提交次数**；下一版就是当前 `0.190` 之后要发的那个号）→ ② 需要时同步 README 功能描述 → ③ **记忆同步（两份都做，见上条"项目记忆同步"）**：更新仓库 `docs/AI_PROJECT_MEMORY.md` → 用 `[System.IO.File]::Copy` 覆盖本机 `project_memory.md` 使两份一致 → 随本次 commit 一起 push（**绝不只留在本地**）→ ④ `git add <只加自己改过的文件>` + commit → ⑤ `git push origin main` → ⑥ `git tag v0.<N>` + `git push origin v0.<N>` → ⑦ **查一次** Release：`GET /repos/Bug142857/ShangYin/releases/tags/<tag>` —— **404 → 直接由本会话 POST 创建 + 上传 APK**；万一已是 200 → 只核对资产，**不重复上传、不删改** → ⑧ APK 拷桌面（`老郑分享-0.<N>.apk`，用 `[System.IO.File]::Copy`）。⚠️ **历史遗留（已结束，流程里不再为它让步）**：早前另有一个并行 AI 会话会在 tag 推送后自动代建 Release，**该会话已于 2026-09-21 结束**（已核实：仓库无 GitHub Actions、无 bot，时间戳显示是 agent 行为）。**因此发版不再有任何「等待/重查/核对他人产物」的步骤，一律由本会话一次做完；历史条目里出现的「并行会话代建 Release」只是当时的记录，不代表现在还有第二条流水线。**
- **发版工具细节（本会话实测可用，照做即可）**：gh CLI 未安装 → 直接调 REST：用环境变量 **`$env:GH_TOKEN`**（40 字符）作 `Authorization: token ...` 头；创建 `POST /repos/Bug142857/ShangYin/releases`（JSON: tag_name/name/body/draft/prerelease）→ 上传 `POST https://uploads.github.com/repos/Bug142857/ShangYin/releases/<id>/assets?name=<ASCII文件名>`（`Content-Type: application/vnd.android.package-archive` + `-InFile`）；查询用 `GET /repos/Bug142857/ShangYin/releases/tags/<tag>`。⚠️ 硬教训：**资产名必须纯 ASCII**（GitHub 会自动剥离中文，实测中文名被剥成 `-0.179.apk`，统一用 `LaoZhengFenXiang-0.<N>.apk`）；PS1 脚本/命令含中文会被按 GBK 解析报语法错（命令尽量短、复杂格式化别写内联 if）；`Copy-Item` 会被沙箱拒 → 用 `[System.IO.File]::Copy`；`curl ... | ConvertFrom-Json` 偶发返空对象，别据一次空输出判失败。
- APK 输出: release 包直接从 app/build/outputs/apk/release/ 复制到桌面，文件名保持 build 产物原名 老郑分享-{versionName}.apk；每次改完都要 git push；**commit 后直接自动 push，禁止再用 AskUserQuestion 征询推送确认（2026-09-11 用户授权，2026-10-16 用户第三次强调"之后帮我自动推送"——旧规则"拦截时 AskUserQuestion 征询"已废止；安全策略若拦截 push，直接重试让用户在 IDE 权限弹窗放行即可）**；注意被拦截的组合命令（add+commit+push）会整体不执行，放行后需重新逐段执行；每次正式修改后必须 bump versionCode/versionName
- 构建 shell 是 PowerShell（不支持 && 与 bash heredoc），用 `;` 分隔命令、提交信息用 git commit -F 文件；JAVA_HOME=D:\Java\jdk-21.0.12.1+1
- 同一文件多处修改严禁并行 Edit（互相覆盖导致改动丢失，已踩坑多次）；material-icons-extended 已引入（Cloud/CloudUpload 等可用），core 集只有基础图标
- 图片缓存：Coil diskCache 150MB LRU 自动清理（cache/image_cache）+ OkHttp HTTP 缓存 20MB（cache/http），手动清理由设置页"清理缓存"按钮触发（直接删目录兜底）
- 长按下载图片：CoverImage 默认 `downloadable=false`（不挂手势）；仅独立卡片场景显式 `downloadable=true`，使用 detectTapGestures 避免手势冲突
- 设置页面必须添加 verticalScroll 修饰符以支持内容超屏时的上下滚动
- 清单条目计数：使用 WITH RECURSIVE CTE 递归统计所有层级后代清单的条目总数（影响主页根清单卡片、详情页子清单卡片、设置页分类管理）
- 云同步功能：使用坚果云 WebDAV 实现数据漫游，服务器地址预填 https://dav.jianguoyun.com/dav/，需用户输入坚果云邮箱和应用密码；恢复前自动在本机创建备份文件（auto_backup_时间.json）；云同步页面需包含坚果云注册链接和应用密码图文教程链接，点击直接跳转系统浏览器
- 编辑模式（ListDetailScreen）对条目和子清单都生效：× 删除（子清单删前弹确认，条目直接移出）+ 长按拖拽排序；子清单排序存 lists.sortIndex（DB v5，v4→5 加列迁移保留数据），排序按 sortIndex ASC, createdAt ASC
- 详情页信息显示规则：影视/图书/游戏统一合并重复日期信息，直接显示完整日期（无"上映日期"/"发行日期"标签）；图书移除"基本信息"块，出版日期合并进头部
- 横屏右侧选集面板（v2.10.4 / versionCode 74，commit a0b8578，2026-09-14 已 push）：横屏时线路+选集从"底部 LazyRow 浮层"改为右侧滑入面板（AnimatedVisibility + slideInHorizontally，CenterEnd，宽 300dp，黑底 alpha 0.72，fillMaxHeight），面板底部 padding 72dp 留出控制条 → 不再遮挡进度条；面板内容=标题+共N集+"线路"LazyRow+3列 LazyVerticalGrid 选集网格（Surface 圆角按钮，选中=主题色/onPrimary，未选中=白 alpha 0.12），跟随 controlsVisible 滑入滑出；竖屏信息面板不变。用户反馈模式：底部浮层遮进度条 → 面板化是解法
- 控制条遮罩移除（v2.11.6 / versionCode 86，commit 5203c27，2026-09-15 已 push）：v2.11.6 隐藏不生效（用户实测"没变化"）——根因=**PlayerView 的 controller 布局延迟 inflate，AndroidView update 首次回调时 findViewById 全是 null，?. 安全调用静默吞掉**（exo_settings 隐藏同理从未生效过，只是齿轮可能本来就没显示所以没暴露）。正解：所有 controller 内 View 操作（exo_controls_background/exo_settings 强制 GONE、exo_progress 挂 OnScrubListener）移到 **setControllerVisibilityListener 回调里**——回调触发时 controller 必已创建；遮罩/齿轮每次显隐都强制 GONE（幂等兜底 media3 内部重置），scrub listener 用 barBound flag 首次成功后不再绑；update 回调只留 pv.player 赋值。⚠️ 通用教训：**对 View 系控件 findViewById 拿 nullable 返回值时，"绑一次"逻辑必须确认时机在 inflate 之后，否则静默失败；用 `?.let { bound=true; ... }` 成功才置 flag**
- 片源链接测试（v2.10.5 / versionCode 75，commit 83f9f67，2026-09-14 已 push）：VodSource 加 testStatus（null/ok/dead/proxy）+testMsg+testAt（kotlinx 默认值兼容旧 JSON）；VodClient.testSource 返回带结果的 VodSource（请求 `?ac=videolist&wd=a&pg=1`，HTTP200+JSON code=1→ok"可用·共N部"，HTTP错误/非JSON/code!=1→dead，异常按 message 关键词 timeout/connect/refused/unreachable→proxy"需外网·直连超时/被拒"，其余→dead）；VodSourceScreen：点卡片测单个、顶栏 NetworkCheck"测试全部"（Semaphore(6) 并发 + joinAll，实时上屏 applyResult synchronized 防并发丢更新，结束 persist 落盘+Toast 汇总）；卡片状态行三色（ok 绿 0xFF2E7D32 / dead error红 / proxy 橙 0xFFEF6C00），汇总行"可用 n·已失效 m·需外网 k"。注意：测试结果随 vod_sources_json 持久化
- 播放起播提速 + 分类表兜底（v2.11.2 / versionCode 82，commit dd06179，2026-09-15 已 push）：① 播放慢=ExoPlayer 默认 bufferForPlaybackMs 2500ms 攒缓冲才开播 → DefaultLoadControl.setBufferDurationsMs(30000,60000,**1200**,3000) 起播减半以上（min/max 保持流畅、rebuffer 3000 防频繁卡）；② 源资源库分类缺失根因：**很多源 `?ac=videolist` 不带 class 字段（苹果CMS 标准分类表在 `?ac=list` 接口）**→ VodClient.fetchCategories（ac=list，解析 VodResp.categories），SourceBrowseScreen 首次加载 scope.async 并行补拉合并 distinctBy type_id（type_pid=0 顶级排前），VodCategory 加 type_pid；③ Kotlin 教训：`kotlinx.coroutines.async` 不是顶层函数（scope 扩展），不能全限定调用，须 import async 后 scope.async{}
- 手动分组防覆盖修复（v2.10.8 / versionCode 78，commit 5116e47，2026-09-14 已 push）：bug=编辑源改"所属目录"保存后，点卡片测试（点卡片=测试）时 testSource 的自动归组把 region 强制重置（ok/dead→cn、proxy→proxy），手动修改被顶回表现为"保存不生效"。修复：VodSource 加 `regionManual`（默认 false 兼容旧 JSON）；编辑对话框点目录 chip → regionTouched=true → 保存写 regionManual=true；testSource 中 `region = if (src.regionManual) src.region else 自动`；编辑保存时清空旧测试结果（testStatus/testMsg=null, testAt=0，地址变了旧结果不可信）；手动分组源状态行加" · 手动分组"后缀。教训：自动化逻辑（测试归组）与手动设置冲突时必须加 manual 标记
- 片源目录分组 + H1/H2 搜索（v2.10.6 / versionCode 76，commit 289deb8，2026-10-14 已 push）：① VodSource 加 `region`（cn=国内可访问默认/proxy=需外网），testSource 测试后自动归目录（proxy→外网，ok/dead→国内），编辑对话框 FilterChip 手动改，VodSourceSub/toVodSource 同步兼容 region；② 片源管理按目录分组展示（国内在前"国内可访问 · n"、外网在后"需要外网环境 · m"，SectionHeader+SourceCard 私有 composable，LazyColumn key 加 cn_/px_ 前缀防冲突）；③ 数据导出/云同步均含片源（ExportData.vodSources 默认空列表兼容旧备份，importAll 非空才替换 SP）；④ 搜索类目 chips 加 H1/H2（Row horizontalScroll 防溢出），doSearch：H2→Toast"开发中"，H1→nav

## Lessons Learned
- koa-connect wrapper caused subtle ctx.state data loss; native Koa rewrite is required instead of wrapping Express middleware

- **v0.279（P站模块完成，2026-10-21）**：
  ① 实测结论：列表/搜索/分类页同构，统一解析 `li[data-video-vkey]` 节点，内含封面、时长、标题、上传者；列表页观看数在 `.views`、搜索页在 `.videoViews`（两处都兼容）；分类现在是 `/video?c=数字` 格式，旧的 `category=slug` 会被 301 重定向；排序 `o=ht/mv/tr/mr/lg` 可用；**搜索接口不接受 `o=` 会 404**（实测发现，搜索态忽略排序档位）；播放地址：视频页 flashvars 内嵌 `mediaDefinitions` JSON → 取最高清晰度 HLS master.m3u8，实测仅 UA 即可直连（无需 Referer），ExoPlayer HLS 硬解直接吃。
  ② 实现落点：新建 `PsiteClient.kt`（网页解析客户端：列表/搜索/分类/排序/分页/缓存/重试）+ `PsiteScreens.kt`（搜索框 + 排序 chips + 分类 chips + 双列网格 + 相邻页码条/跳页 + 下拉刷新 + 会话缓存）；`AppNav.kt` 新增 `psiteHome` 路由；`HomeScreen.kt` 里世界第十入口「P站」。
  ③ 交互与 X 站一致：点卡片 → 轻遮罩「正在解析播放地址…」→ 进内置播放器（横屏、断点续播 key 用 vkey 哈希）。需外网环境访问。
  ④ 一个注意点：站点对部分 IP 段有地区封锁，若手机网络下列表空/403，换节点即可——解析失败的 Toast 会带出具体原因。
  版本：0.279/1279。

- **豆瓣登录系列踩坑（2026-10-27）**：
  ① 豆瓣登录卡白屏根因：登录成功后豆瓣停在空白中转页，而自动提取条件排除了 `accounts.douban.com/passport` 路径，导致白屏页不提取 cookie；修复为 douban.com 域即尝试提取。
  ② 登录后反复显示「已登录→已过期」根因：WebView 登录会话的 UA、bid cookie 与 App 请求不一致，被豆瓣风控判定指纹不匹配导致会话被踢；修复为两者完全统一。
  ③ 未登录就误弹「登录成功」根因：登录页残留旧的死 cookie 被自动提取；修复为进入登录页前精确清除豆瓣域的所有 WebView cookie 及本地持久化的死 cookie。
  ④ 游戏封面全黑根因：豆瓣搜索结果页轮换的旧图床域 qnmob*.doubanio.com 被整体下线，导致那几天点词条落库的条目 URL 全部成为死链；修复为图片拦截器自动将该域重写到仍存活的 img2.doubanio.com。
- **v0.291（里世界模块管理 + 吃瓜/X站/P站爱心收藏，2026-10-22）**：
  ① 模块管理：设置 → 模块管理（`ModuleManageScreen`，路由 `moduleManage`）对里世界 10 个模块（番号/本子/漫画/游戏/图书/电视/音乐/吃瓜/X站/P站）做入口显示开关；开关存 `SettingsStore` 的 StringSet SP key `disabled_inner_modules`，以 `SettingsStore.disabledInnerModules`（MutableStateFlow）驱动主页实时刷新；模块定义统一在 `ui/common/InnerModules.kt`（`INNER_MODULE_ENTRIES`：key/icon/route 一份），`HomeScreen` 里世界宫格改为按开关过滤后 `chunked(4)` 动态排布（不再写死三行）。
  ② 爱心收藏：新增 `data/InnerFavorites.kt`（`InnerFav` 模型 + 独立 SP `inner_favorites`，kotlinx JsonArray 手动映射，StateFlow 驱动；`App.onCreate` 里 `InnerFavorites.init`）。melon 只存帖子 id（详情页重拉）；xsite 存直链 mp4+mediaId+时长（重放不再请求详情）；psite 存 vkey（重放时再解析直链）。收藏页 `ui/innerfav/InnerFavoritesScreen.kt`（路由 `innerFavs/{module}` 三模块共用）：点条目继续消费（吃瓜进详情 / X、P站直接播放）、长按确认删除（项目约定）；列表卡片右上角 `FavHeartButton`（黑底小圆+红心）、详情页顶栏 `FavTopBarIcon`（吃瓜/X站有详情页，P站无详情页只有卡片）。
  ③ `playPost`（X站）/`playVideo`（P站）从 private 改公开供收藏页重放复用；三个模块首页 TopAppBar 加收藏入口（FavoriteBorder → `innerFavs/{module}`）。收藏数据为独立 SP，不进豆瓣收藏体系、不随导出备份。
  版本：0.291/1291。
