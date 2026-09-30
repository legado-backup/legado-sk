# 阅读SK / legado-sk 项目总则

> 本文件是项目的长期规则来源，只保留可复用的原则、流程、环境约束和当前交付状态；一次性排障过程、界面细节、截图和临时记录不写入这里。
> 规则以 `AGENTS.md` 为准。2026-09-04 起在迁移后的电脑上工作：无 D 盘，不再使用 `D:\OneDrive\桌面\Ai\legado-sk\` 外部工作目录，配套文档策略见 §7「当前机器环境与配套文档」。`docs/` 存放设计文档与截图（`api.md`、`朗读链路.md`、`重构下载架构.md`、`ui-frida-debug.md`、`流程图/`、`预研发/` 等）。
> ⚠️ 本文件是随仓库分发的运行手册；其中 §2/§3/§7 含本机路径与设备信息，只在当前机器的检出副本上维护，不要把这些机器专属路径推送到公开仓库。

## 0. 新会话 AGENT 交接速读（凡在本仓库动手前必读）

> 目的：让**下一个新对话的 AGENT**（看不到此前任何会话，只能读工作目录文件）在动手改代码前，无歧义地弄清「项目做了什么、每个版本改了哪些、改哪里不能改错」。本小节为强制入口，按序读完再动代码。

1. **项目全貌与结构** → 读 `companion/项目文档.md`：项目定位/谱系、工程结构、核心改动方向（含「哪些是 SK 改的、哪些是上游自带」的来源辨析）、§2.1「版本索引」（一屏全貌）。
2. **改某个功能/开关/DB 前先反查它由哪个版本引入、有什么红线** → 读 `companion/发布版更新记录.md`（**版本事实的唯一权威来源**）：第 0 节「防改错速查」（全局透明度锁 0、进度同步三禁、朗读架构唯一形态=10023、DB 迁移线、R8 永不启用等）、§1 逐版净增量、第 2 节「功能→引入版本」反查表。
3. **需要作者原始文案佐证** → `companion/发布版更新原文-releasenotes.md`（GitHub Releases 逐版全文备份，可 grep）。
4. **红线与排错** → 以本文件（AGENTS.md）为准：§4 UI/工程质量、§3 构建版本产物、§6 交付基线；具体历史红线见 §4「功能红线」。
5. **机器环境/路径** → 本文件 §7「当前机器环境与配套文档」§7.1 环境快照、§7.2 companion 文档清单。
6. **动手前决定用几个子代理** → 读 §1.5「子代理编排」。大范围审计/排查**默认拆并行**，串行单代理是已知失败模式；边界（只读、写域切分、主代理收口）见该节。
7. **改朗读链路前必读** `docs/朗读链路.md`（朗读状态所有权契约，10023 架构的唯一细节来源）；**改造缓存/评论任务域前必读** `docs/重构下载架构.md`（缓存域唯一架构契约）。这两份是本仓库唯一描述内部契约的设计文档。
8. ⚠️ `companion/` 整目录在 `.gitignore` 中忽略、**不推送公开仓库**（含机器信息）；只读不随意改动，改动需与对应事实一致。若发现文档与源码事实不符，**以源码和 GitHub Releases 为准**并先核实再改文档。

## 1. 核心工作原则

每次开始编程前，先重申并遵守以下原则：

> 解决根本问题，拒绝任何兜底；有问题，直接暴露。统一维护、统一修复，避免特殊代码不断膨胀。鼓励调查，鼓励详细日志和探针，鼓励联网搜索。

具体要求：

- 先定位事实、边界和根因，再修改代码；不能用静默回退、吞异常、默认值补丁或仅覆盖症状的分支掩盖问题。
- 相同问题应收敛到共同抽象、共同入口或共同数据源。新增特殊逻辑前，先证明现有统一路径无法正确表达该需求。
- 结论必须区分“已由证据确认”和“仍属假设”。复杂问题要补足日志、探针、截图或 trace，使后续排查可以复现。
- 任何失败都必须说明原因和下一步。构建异常在解决后记录现象、根因、修复方式和是否交付；只把能长期复用的结论保留在本文件，并及时修正或删除失效规则。

## 1.5 子代理编排（默认激进并行；10038 审计实践提炼）

> 结论先行：**默认把工作拆给子代理并行跑**，主代理只做编排、交叉验证与收口。串行单代理处理大范围任务是本项目的**已知失败模式**（见下文反面案例）。

### 何时必须拆（触发条件，满足任一即拆）

- **审计/排查范围超过约 10 个文件**，或需要通读 `git diff` / 全库 grep 才能定性。
- **任务含 ≥3 个彼此独立的检查项**（如"审 P1~P8 八项方案"）。
- **需要"审查方"与"被审方"分离**——即结论需要被独立证伪时。
- **单项预计耗时长**（如逐文件比对、大量外部资料检索）。
- **同一批改动需要多角度验证**（静态审查 / 编译 / 运行时回归可并行准备）。

### 拆法（经验值）

- **按"独立结论单元"切，不按文件数平摊**：切分后每个子代理应能**独立给出可用结论**，不依赖其它子代理的输出。例：按缺陷项分组（第 1 批 P1~P7 / 第 2 批 P3~P4 / 第 3 批 T3~T6），而非"你读前 50 个文件"。
- **3~5 个并行是舒适区**；超过 8 个后汇总成本超过收益。
- **允许子代理再派子代理**（本会话第 3 批探索代理自行派了 2 层共 3 个后代，效果良好）。给它的提示词里写明"你可在需要时自行拆分"。
- **对抗性任务必须显式要求证伪**：提示词要写"默认假设方案有错，去源码找反证"、"找不到反证就明说未找到并列出验证过的证据"，否则子代理倾向"配合确认"。
- **给子代理的提示词必须自包含**：它看不到本对话。要带上背景、已知排除项（避免重复报误报）、边界约束、输出格式、以及"必须给出：位置/证据/影响/建议/风险等级/改动量"。

### 边界（硬约束，不可让渡）

- **写权限必须显式划界**：审查类子代理只能**只读**，唯一可写是**自己名下的报告文件**；须在提示词里点名"严禁修改 `app/src/` 下任何文件"。
- **多代理不得同时写同一文件**：并行任务按文件/目录切分写域，避免互相覆盖。若无法切分，改为串行。
- **主代理负责收口**：子代理结论**一律不盲信**，尤其"现状描述是否准确"与"是否有更优方案"两项必须亲自到源码复核。本会话中主代理据此**纠正了子代理 3 处误判**，同时被审查代理**纠正了自己 2 处实质错误**——双向纠错才是目的。
- **不改代码的探查阶段禁止改任何源码**；需要"方案 → 审查 → 再动手"闸门时，主代理在审查通过前不得编辑源码。
- **别在运行中给子代理追加需求**：见下文反面案例。

### 反面案例（10038 实测，必读）

**单代理串行 + 中途追加需求 = 失控**：一个审查代理被要求审 7 项方案，**54 分钟零产出、未派任何子代理**；期间主代理向其追加了两轮补充材料（新增 4 项 + 新证据）。追加内容作为 steering 消息插入其正在执行的步骤，极可能触发**反复重新规划已完成的工作**。
**纠正后**：按范围切成 3 个独立审查代理，**约 4 分钟产出 3 份共 105KB 报告**，且因相互独立而抓到主代理方案的 1 项实质性错误。

**由此得出**：
1. 大任务先拆再派，不要派一个"全能代理"。
2. **派单要一次说清**；确需补充时，等它返回本轮结果后**重新派单**，而不是往运行中的代理插消息。
3. 判断"卡住"vs"慢"的判据：**长时间（如 >20 分钟）零产出文件、且未派任何子代理** → 按卡住处理，中断并重切任务，不要继续等。

### 与既有流程的关系

- 本节**不改变** §5 的提交纪律（每个独立修改一个提交）与 §2/§3 的验证闭环；子代理只分担**调查、审查、方案编制**，**最终改代码与提交仍由主代理串行完成**，以保证提交边界清晰可回退。
- 子代理产出的中间报告写入已忽略的 `test-records/`（见 §7.2），不提交、不推送。

## 2. 设备与测试边界

### 真机设备

| 设备 | 型号 | 序列号 | 备注 |
|---|---|---|---|
| 手机 | 小米 Redmi `23049RAD8C`（`marble`） | `72a2b362` | 2026-09-29 核实：Android 13 / HyperOS（MIUI `V140`）/ arm64-v8a |
| 平板 | 联想 TB-9707F | `HA1KAPWG` | Android 14 |

- ⚠️ **两台真机都只装了共存版 `io.legado.app.sk2`，均未装正式版 `io.legado.app.c`**（2026-09-29 核实）。故真机覆盖安装一律用 **`_（共存版）.apk`**；装正式版会变成"多出一个空 App"而不是升级。
- ⚠️ **华为 MAR-AL00（`9HQDU19903003356`）已不在用**（2026-09-29 核实，`adb devices` 中不存在）。若日后重新接入，需先确认型号与序列号再补回本表。
- ⚠️ **HyperOS/MIUI 手机上 `adb install` 会被 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` 拒绝**，这是「USB 安装」开发者开关（设置 → 更多设置 → 开发者选项 → USB 安装），**必须用户在手机上手动打开**。已验证 adb 侧无法绕过：`verifier_verify_adb_installs 0`、`package_verifier_user_consent -1`、`appops set … REQUEST_INSTALL_PACKAGES allow` 三者均无效（`pm install-create` 能建会话但 commit 被 MIUI 否决）。遇到该报错**直接请用户开开关**，不要反复重试。
- 所有 `adb` 命令必须显式带 `-s <serial>`；执行前确认目标设备，禁止裸 `adb`。
- 真机安装统一 `adb -s <serial> install -r <_（共存版）.apk>`（同 debug 签名，覆盖升级保数据）。**校验升级成功的判据是 `firstInstallTime` 不变**（`dumpsys package <pkg>`；该值变化即"重装"而非"升级"）。
- 换签名迁移数据走 run-as tar 打包流程：导出必须用 Python subprocess 二进制流（git bash `>` 重定向会 CRLF 污染 tar）；`pm uninstall -k` 不可行（数据绑定签名）。备份与导出在仓库根的 `backup\`（被 .gitignore 忽略）。
- 真机问题优先依据用户描述、代码和用户提供的日志排查。
- ⚠️ 若某台真机 `run-as` 报 `package not debuggable`，那是**正确行为**（release 包），不是故障。

### 雷电模拟器

- APK 安装、运行和调试只使用雷电模拟器（LDPlayer）。**迁移后本机（2026-09-04）使用的 LDPlayer 在 C 盘**，启动程序路径为 `C:\download\down\cloud-down\雷电模拟器14纯净绿色版+狐狸+LSP+微霸\LDPlayer14\dnplayer.exe`（同目录含 `ldconsole.exe`、`adb.exe`；实例 `leidian0`，instanceIndex=0）。未启动时可尝试启动；失败则请用户手动打开。旧的 `F:\down\...\LDPlayer14` 与本机 `F:\leidian\LDPlayer14` 均不再使用。
- android-dev 工具链统一目标在 `tools\android-dev\target.json`，已改指上述 C 盘 LDPlayer；其 ADB 走环路 `127.0.0.1:5555` + ldconsole 启动序列号校验（见 §4 分层调试），禁止以该环路之外的裸 serial 操作。常规手动 `adb` 序列号仍用 `emulator-5554`，每条命令都必须显式带 `-s`；执行前确认目标确为模拟器，不确定时停止。
- 分辨率 1440x2560，模拟器内建议配置 WebDAV。每条 `adb` 命令都必须显式带序列号，例如 `-s emulator-5554`。执行前确认目标确为模拟器；不确定时停止，禁止裸 `adb`。
- 真实小说优先用于阅读功能验证。`C:\Users\skxingyu\Documents\leidian14\Pictures` 与模拟器 Pictures 目录互通，可作为导入素材。
- ⚠️ 模拟器覆盖安装前，先 `adb -s <serial> shell dumpsys package io.legado.app.c` 读已装 versionCode，只允许 ≥ 已装版本的覆盖（当前基线见 §6）。

### 验证闭环

- 每次代码改动都按以下闭环执行：正式编译（`assembleAppRelease`）APK -> 安装到已确认的雷电模拟器 -> 复现并回归验证；真机最终验证由用户手动完成。
- AI 侧回归只在雷电模拟器执行；真机安装仅在用户明确指示下进行。

- 崩溃或行为异常时，先收集日志和复现证据，定位根因后修复，再重新正式编译和回归；不能报告未经验证的修复。
- UI 改动必须覆盖受影响的交互、显示、主题/状态切换和关闭重开等生命周期，而不是只确认一张静态截图。

## 3. 构建、版本与产物

### 不可变交付约束

- 代码改动只能用正式版（`app` flavor + `release` buildType，包名 `io.legado.app.c`）验证与交付，产物在 `app\build\outputs\apk\app\release`。禁止以中间 Gradle 任务、debug APK 或改名旧包充当验证/交付物。
- 覆盖安装前必须显式传入 `VERSION_CODE` 和 `VERSION_NAME`。新 `VERSION_CODE` 必须比最近一次交付大；`VERSION_NAME` 必须按 GMT+8 编译时刻单调递增，格式为 `3.26.MMddHH`。
- 正式版 = `app` flavor + `release` buildType（`assembleAppRelease`，包名 `io.legado.app.c`）。`versionName` 需直接传完整值（含 `c` 后缀，如 `3.26.090812c`），无自动加后缀机制；`versionCode` 遵循 SK 独立递增约定（当前基线见 §6）。
- 编译前先从模拟器已安装包确认版本；模拟器不可用时使用第 6 节的最近交付基线。确认新版本后，只删除 `app\build\outputs\apk\app\release` 中对应的旧 APK，绝不删除宽泛目录或源码。
- 编译前通读本节（「本机环境与正式命令」+「长命令和构建失败」+「产物验证」）——这是编译/排错的**唯一出处**，不存在单独的排错手册。

### 本机环境与正式命令（迁移后 2026-09-04 核对）

- 代码/构建唯一目录（无 D 盘，不再分编译树）：`C:\code\ai-code\legado-sk`（git 仓库，remote = `skxingyu/legado-sk`，main 分支，gh auth 直连推送）。
- JDK 17：`C:\Users\skxingyu\AndroidDev\jdk-17.0.2`
- Android SDK：`C:\Users\skxingyu\AndroidDev\android-sdk`（platforms `android-34`/`android-36`；build-tools `34.0.0`/`36.0.0`）
- Gradle：用项目 wrapper `gradlew.bat`（distributionUrl = gradle-8.14.4-bin，首次自动下载到 `C:\Users\skxingyu\.gradle\wrapper\dists`）；本机另有 `C:\Users\skxingyu\AndroidDev\gradle-9.7.1` 备用，勿覆盖 wrapper 约定
- Gradle user home：不显式设置 → 默认 `C:\Users\skxingyu\.gradle`
- 系统 adb：`C:\Users\skxingyu\AndroidDev\android-sdk\platform-tools\adb.exe`
- Gradle wrapper: `8.14.4`; AGP `8.13.2`; compileSdk `36`; 依赖/平台已按此装齐
- 交付 APK（`-Pabi=arm64-v8a`）产物在 `app\build\outputs\apk\app\release`

```powershell
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$env:JAVA_HOME = 'C:\Users\skxingyu\AndroidDev\jdk-17.0.2'
$env:ANDROID_HOME = 'C:\Users\skxingyu\AndroidDev\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
# GRADLE_USER_HOME 不设置，走默认 C:\Users\skxingyu\.gradle
$env:Path = @(
  "$env:JAVA_HOME\bin",
  "$env:ANDROID_HOME\cmdline-tools\latest\bin",
  "$env:ANDROID_HOME\platform-tools",
  "$env:ANDROID_HOME\build-tools\36.0.0"
) + ($env:Path -split ';') -join ';'

Set-Location 'C:\code\ai-code\legado-sk'   # 编译必须在仓库根执行
$versionCode = <new-version-code>
$versionName = '3.26.<MMddHH>c'            # 完整版本名（含 c 后缀）
.\gradlew.bat ':app:assembleAppRelease' "-Pabi=arm64-v8a" "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" --console=plain --warning-mode=summary
# 共存版（与正式版 / 阅读C 同时安装）：与正式版同一次编译一并产出，除任务名外参数完全相同
.\gradlew.bat ':app:assembleAppSk2' "-Pabi=arm64-v8a" "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" --console=plain --warning-mode=summary
```

#### 共存版 `sk2`（2026-09-21 起恢复，与正式版并行交付）

> 历史：10004–10006 曾以 `sk2` 共存版随正式版双发，10007 起停发并写明「不再提供 sk2」。**2026-09-21 作者决定恢复**，本日起每次正式编译都**必须同时**产出共存版，文档口径已同步更正。

- 变体 = `app` flavor + `sk2` buildType（**buildType，不是新 flavor**），包名 **`io.legado.app.sk2`**，应用名同「阅读SK」；与正式版 `io.legado.app.c`（`app`+`release`）、阅读C 三者可同时安装。
- 产物在 `app\build\outputs\apk\app\sk2`；⚠️ 产物目录就是 `app\sk2` 本身（是 buildType 名，与是否继承 debug 无关）。
- ⚠️ **`sk2` 必须 `initWith release`，禁止 `initWith debug`（2026-09-24 修正，勿改回）**：继承 debug 会让共存版带上 `android:debuggable=true` 且 dex 不合并/不优化 —— 实测 **23 个 dex vs 正式版 8 个**，APK 大 ~8 MB（44.5 MB vs 36.2 MB）。共存版与正式版的**唯一**差异只能是包名，行为与体积都必须对齐。同时必须显式覆盖继承自 `release` 的两项：`applicationIdSuffix '.sk2'`（否则变成 `.c.sk2` 之类）与 `versionNameSuffix ''`。
  - ⚠️ 同类陷阱对**任何**新 buildType 成立：先想清 `initWith` 的基底是否与交付语义一致，再显式覆盖它会带偏的每一项（applicationIdSuffix / versionNameSuffix / minify / shrinkResources / matchingFallbacks）。
  - ⚠️ 已存在的 sk2 产物（10073 之前，44.5 MB 那批）**是带 debuggable 的包**，如需发布共存量请用 `initWith release` 重编。
- Gradle 输出名同正式版（`legado_sk_<versionName>_<versionCode>.apk`，无 `_arm64-v8a` 后缀），**收进 `release/` 时按约定补后缀并加 `_sk2`**。
- ⚠️ **共存版与正式版共用同一 `VERSION_CODE` / `VERSION_NAME`，不另加版本后缀**（`sk2` 刻意**没有** `versionNameSuffix`）。两条产物的版本事实必须逐字一致，便于回溯「同一个 10058」。
- ⚠️ **两者同用 SDK debug 签名**：改的是包名不是签名，因此**不能互相覆盖安装**（这正是共存的前提）；也**不能覆盖安装阅读C**（签名不同，需走 §2 的数据迁移流程）。
- ⚠️ **共存版是独立应用、独立数据**：包名不同 → `filesDir` / `getExternalFilesDir(null)`（缓存、书籍、主题、pref、DB）全部隔离，**不共享书架与进度**。它是"并行再装一份"，不是"共用数据"；要同步数据走应用内备份导出/导入（WebDAV 或本地 zip）。私有目录之外的**用户可见路径共用**（如 `/sdcard/Download/yuedu` 导出目录、WebDAV 目录名），互导时注意覆盖。
- ⚠️ **无需为共存改任何源码**：隔离由 Android 平台按 `applicationId` 保证（不同包名 → 不同 uid / 数据沙箱 / `FileProvider` authority；manifest 中 `authorities` 全部是 `${applicationId}` 占位，`AppConst.authority` 取 `BuildConfig.APPLICATION_ID`）。共存版与正式版除包名外**无任何行为差异**（10059 起内置书源与其授权守卫已整体移除，见 §6）。
- 共存版**不参与更新检查**（不含它自己的自动更新），但**发布时必须随正式版一同上传为 Release 资产**（2026-09-21 作者指示修正：此前写的「不发布 Release 资产」已作废，10065 起两个 APK 同时发布）。
  - ⚠️ 发布说明里要**写清两者区别**：正式版可覆盖升级、与阅读C 签名不同不能互覆；共存版可与之三者并存、**独立数据不共享**、**不能覆盖安装正式版**。

编译成功后必须把新 APK 收进仓库根已忽略的交付目录：
1. 覆盖 `C:\code\ai-code\legado-sk\release\legado-sk-arm64-v8a.apk`（「当前交付 APK」，固定名；`/release` 已被 .gitignore 忽略）。
2. 按版本命名同存于 `C:\code\ai-code\legado-sk\release\`：`legado_sk_<versionName>c_<versionCode>_arm64-v8a.apk`。
3. 共存版同存于 `C:\code\ai-code\legado-sk\release\`：`legado_sk_<versionName>c_<versionCode>_arm64-v8a_（共存版）.apk`（本地文件名带「（共存版）」，让用户一眼分清哪个能共存）。
   - ⚠️ **GitHub Release 资产名不支持非 ASCII 字符**：上传 `…_（共存版）.apk` 会被静默截断成 `…_arm64-v8a_.apk`（实测；用 API `PATCH` 改名为中文会得到 `default.apk`）。**Release 资产名一律用 ASCII 后缀 `_sk2-coexist`**，并在发布说明里写明它就是共存版。

### 长命令和构建失败

- 任何可能超过 30 秒的命令必须实时监控。每 30 秒以内检查进程是否存活、CPU 是否增长、日志/产物是否更新；停滞时终止并报告，不能无限等待。
- 后台编译须保存 stdout、stderr 和退出码。`cmd /c` 的内联重定向不可靠时，改用 `.bat` 文件启动，不得把空日志误判为正常编译。
- 先阅读实际错误中的文件、行号和异常，再选择修复。不得把源码错误猜成内存问题后盲目重跑。
- 仅在证据指向缓存锁定、守护进程或原生内存问题时，先停止 Gradle，清理残留 Gradle/Kotlin/Java 进程，再用正式 `assembleAppRelease` 进行最小必要的冷编译诊断，例如 `--no-daemon --max-workers=1 -Dkotlin.incremental=false -Dksp.incremental=false -Dkotlin.compiler.execution.strategy=in-process`。目录清理仅限受影响模块的 `build` 目录。
- 构建无论成功或失败，执行 `.\gradlew.bat --stop` 并按 PID 清理残留构建进程，避免占用内存。
- 2026-08-15：`HeaderlessDialogChrome` 首次正式编译在 `AccentTextView(context)` 失败，因为该控件构造器强制要求 `AttributeSet?`；读取 Kotlin 报错后改为 `AccentTextView(context, null)`，同版本重编译成功。失败包未产出、未交付。动态创建项目自定义 View 时必须先核对构造器签名，不能假定存在单参构造器。
- 2026-08-15：首次启动 10608 构建时，把批处理和退出码写入拼在 `cmd /c` 参数中，Windows 报“文件名、目录名或卷标语法不正确”，没有 Gradle 进程、构建日志或 APK。改为由 `.bat` 自己记录退出码，再以 `Start-Process` 直接启动，构建正常。后台构建的重定向/引号错误必须以“未启动”处理，不能等待或误判为 Gradle 卡死。
- 2026-09-05（10029 编译两次失败复盘）：在 DSH 沙箱 `workspace-write` 会话里启动 `gradlew.bat`，wrapper 阶段即报 `gradle-8.14.4-bin.zip.lck (拒绝访问)` 退出。判别要点：① 报错在 `GradleWrapperMain`/`ExclusiveFileAccessManager` 而非 Gradle 任务 → 不是项目代码或内存问题，不要跑冷编译诊断；② 删锁文件、杀光残留 java 进程后**仍**报同一处拒绝访问，且系统无 java 进程持锁 → 说明不是锁被占用，而是进程根本没有写 `C:\Users\skxingyu\.gradle`（wrapper 锁/缓存/daemon，仓库外用户级目录）的沙箱授权。处理：用 `sandbox_permissions` 放开权限**原样重试同一条编译命令**（pwsh `danger-full-access`，justification 说明 Gradle 必须写 `.gradle`），一次成功。规则：**在本机跑 Gradle 构建（含 `gradlew --stop`）必须默认带放开权限执行**；`workspace-write` 下 Gradle 必失败，不要浪费轮次删锁/杀进程重试。另注意：残留 daemon 清理仍有价值（本次 10028 遗留 4.5GB+3.6GB 两个 java 进程），但它是例行卫生，不是该报错的根因。

### 产物验证

```powershell
$apk = 'C:\code\ai-code\legado-sk\app\build\outputs\apk\app\release\legado_sk_<version>_<code>.apk'
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt.exe" dump badging $apk
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs $apk
```

交付前确认：包名 `io.legado.app.c`、版本号递增、中文名 `阅读SK`、`arm64-v8a`、产物来自 `assembleAppRelease`，且 `apksigner` 退出码为 0。部分 `META-INF` 条目未受签名保护的提示可接受。

## 4. 工程质量规则

- 无头弹窗的统一策略只负责移除 `Toolbar` 并把菜单动作迁到标准底部操作区；不得以保留空白 Toolbar 伪装“无头”。移除 Toolbar 前必须核对布局测量：原来依赖 Toolbar 固定高度的 `0dp` / weight 内容区，要改成显式的“内容区 + 底部操作区”结构，否则 `wrap_content` Dialog 会塌缩。
- 无头迁移器向 `ConstraintLayout` 加入底部操作区时，所有原先 `bottomToBottom=parent` 的内容必须统一改为约束到 footer 顶部；禁止仅增加 parent padding 伪造预留空间，否则滚动内容会与按钮重叠。`dialog_content_edit` 于 2026-08-15 以此规则完成回归。
- 标准 `AlertDialog` 的标题不能直接追加到 `contentPanel`：该面板是叠放容器，会与选择列表重叠。统一表面路径应将标题和原内容重组为垂直内容列后再隐藏 `topPanel`，使标题成为同一玻璃面上的正文首行，而非独立顶栏。使用 `setCustomView` 时内容位于 `customPanel`；标题迁移后只能保持 `customPanel` 或 `contentPanel` 之一作为中段，禁止额外启用另一个面板挤占 `buttonPanel` 的测量空间。缺少相应面板属于结构错误，应直接暴露，不能悄悄丢弃标题或遮住首项。

### UI 内核与浮层规范

本项目的 UI 内核不是一套普通页面和另一套弹窗页面，而是四层单向组合。所有新 UI 必须先在此树中归类；业务页面只能使用下层能力，不能反向改写或复制下层逻辑。

```text
主题语义层
ThemeStore / ThemeUtils / UiCorner
    └─ UI、阅读、Dialog 三组颜色、透明度、圆角和描边语义
        │
表面描述与渲染层
SurfaceStyle / SurfaceStyles / SurfaceDrawable
    └─ 同一裁剪路径绘制模糊底图、tint、描边和几何
        │
表面生命周期层
SurfaceBackdrop
    └─ 稳定几何、PixelCopy、局部模糊、代际丢弃和位图回收
        │
宿主适配与内容层
BaseDialogFragment / BasePrefDialogFragment / BaseBottomSheetDialogFragment
AndroidAlertBuilder / SurfacePopupMenu / 阅读页显式浮层
    └─ Feature 的业务内容、操作和布局
```

#### 首先分类，不得按“看起来像”处理

| 类型 | 统一入口 | 表面规则 |
|---|---|---|
| 普通 Activity / Fragment 页面与页内控件 | `ThemeStore`、`UiCorner`、现有主题 View/样式 | 只使用 UI 组样式；不是模糊浮层，禁止为整页安装 `SurfaceBackdrop`。 |
| 普通模态 Dialog | `BaseDialogFragment` | 声明真实可见表面（优先 `vw_bg`），由基类安装 Dialog 表面。 |
| Preference Dialog | `BasePrefDialogFragment` 或现有 preference adapter | 走同一 Dialog 表面与无头 Alert 规则。 |
| 底部 Sheet / 阅读设置 Sheet | `BaseBottomSheetDialogFragment`；阅读页使用 `BaseReaderSheet*` | 仅上角几何；阅读色彩只能来自 `ReaderSheetStyle`。 |
| 简单确认、选择、输入框 | `alert` / `selector` / `AndroidAlertBuilder` | 由 `applyAlertSurface()` 处理 AppCompat 面板和无头标题。 |
| 右上角更多、列表行更多等 PopupWindow 菜单 | `SurfacePopupMenu` 或 `View.showPopupMenu` | 应用拥有唯一可见外壳，显示前完成其局部表面准备。 |
| 阅读页 Activity 内的主菜单、搜索菜单、文本操作浮层 | 调用方声明的专用背景层 | 这是同窗口浮层，不是 Dialog；只能刷新明确命名的目标表面。 |

Activity 页面标题和正文标题不是“弹窗头”，不得为追求无头规则而删除。无头规则只适用于广义浮层的独立顶栏：Dialog、Alert、Sheet、PopupWindow 和阅读页浮层都不得新增 `Toolbar` / `TitleBar` 顶栏；操作应放在内容内的标准底部操作区。标题有业务语义时只能作为正文首行，不能恢复独立 chrome。

#### 只有一个表面内核

- `SurfaceStyle` 只描述视觉：tint、圆角、描边、模糊半径；它不得知道窗口类型、布局树或业务状态。
- `SurfaceBackdrop` 是唯一可做 PixelCopy、模糊、稳定几何等待、显示代际和位图回收的地方。`SurfaceDrawable` 是唯一把底图、tint、描边绘入同一裁剪路径的地方。
- 每个浮层必须显式声明一个真实、唯一的可见表面。不能扫描控件树猜目标，不能把内容按钮、列表或宿主 decor 当作表面，也不能缓存宿主整页后按猜测坐标裁剪。
- UI、阅读、Dialog 的颜色和透明度只能经 `UiCorner` / `SurfaceStyles` / `ReaderSheetStyle` 取得；Feature 不得重算 alpha、圆角、描边、模糊半径或写另一套玻璃颜色公式。
- `updateStyle()` 只更新同一目标的样式，不得中断该目标在途取图；关闭、换目标、重新显示和尺寸变化才创建新代际。Feature 不得自行管理另一套 generation 或 Bitmap 生命周期。

#### 新代码的强制入口

- 新的自定义模态框只能继承相应 `Base*DialogFragment`。新的简单 Alert 只能走 `alert` / `selector` / `AndroidAlertBuilder`；新的菜单只能走 `SurfacePopupMenu` 或其扩展入口。
- 新的阅读页浮层必须先声明“宿主 Window、唯一背景层、显示前准备点、关闭点、尺寸变化点”，然后复用 `SurfaceBackdrop`。这些条件无法表达时，先扩展内核/宿主适配器并完成全路径验证，禁止在 Feature 内新建 `xxxBlur`、`xxxGlass`、`xxxPopup` 或私有表面助手。
- 需要跨两个以上 Feature 或两种以上宿主复用的视觉/交互模式，提升到 `lib/theme`、`lib/theme/surface`、`lib/dialogs` 或 `ui/widget` 的现有内核旁；只属于一个 Feature 的业务内容留在 Feature 内，但仍使用核心表面和样式。
- 现存直接 `Dialog`、`PopupWindow` 或第三方窗口类属于迁移存量，不是新代码模板。修改它们时优先接入上述入口；确有宿主限制时，先记录限制和适配方案，不能复制一份私有实现。

#### 绝对禁止

- 禁止给宿主 Activity `decorView` 做全局 `RenderEffect`；禁止 `FLAG_BLUR_BEHIND`、`setBackgroundBlurRadius`、`DIM_BEHIND` 或任何系统整窗变暗来替代局部表面。
- 禁止反射 PopupWindow 私有字段、共享可变背景 Drawable、叠加“矩形 Bitmap + 另一层圆角颜色”背景，或以透明/纯色/全屏模糊作为取图失败的 Feature 级兜底。
- 禁止在新 Dialog 布局中新增 `Toolbar` / `TitleBar`，禁止新建特定页面的 alpha、blur、corner、surface-color 常量或 `when (页面名)` 特例。
- 禁止为绕过本规范添加新的 suppress、静默 catch、默认回退目标或吞掉表面安装错误。内核无法表达的需求必须直接暴露并先修内核。

#### UI 变更验收清单

- [ ] 已明确它是普通 UI、Dialog、Preference、Sheet、Alert、PopupWindow 还是阅读页同窗口浮层，并使用了表中唯一入口。
- [ ] 浮层已明确真实背景层；目标 attach、连续两帧几何稳定后才取图，首次可见前背景已安装。
- [ ] 没有全局模糊、系统 DIM、私有反射、Feature 自建表面算法、独立 Bitmap 生命周期或页面专属兜底。
- [ ] Dialog/Alert/Popup 没有独立头栏；需要的操作在标准底部区，关闭、重开、主题变化和尺寸变化都不会让旧回调覆盖新表面。
- [ ] 已在雷电模拟器回归：截图检查范围/圆角/透明度，uiautomator2 检查层级和可点击性；普通证据不足才按分层调试规则同时采集 Perfetto、Winscope 与 Frida。

### 功能红线（历史踩坑，违反即回归）

- **R8/混淆永久禁用**：legado 是重反射应用（书源引擎 / JS 桥 / 动态类加载），开启 `minifyEnabled` / `shrinkResources` 会破坏反射链并误删系统过渡动画，实测运行时卡顿（10009 已回退）。瘦身只允许资源层：图片重编码但保持文件名不变、删除零引用资源、`resConfigs "zh"` 语言裁剪。
- **阅读进度同步三禁**（移植上游后逐项核对防回归）：
  1. `BookProgress.compareWith` 禁止时间戳优先，只比较 `durChapterIndex` → `durChapterPos`；
  2. `AppWebDav.getProgressFileName` 保持 `书名_作者.json` 双参无 mediaType 后缀；
  3. `ReadBookActivity` / `ReadMangaActivity.onPause` 自动同步禁止加 `BuildConfig.DEBUG` 限制。
- **听书翻页竞态守卫（10023 起为新架构）**：朗读跟随体系采用上游「两原语 + 纯函数跟随规则 + 派生脱节」（10017/10018 的 `pageTurnAnimating` / `TTS_PROGRESS` 存储式守卫已被 `shouldFollowAloudAdvance` 单调性规则整体替代，`readAloudPageDetached`/地板闩已删除）。防拽页由「显示页==朗读出发页且位置前进才跟随」单一规则保证，翻页由 UI 侧观察者单点执行，引擎只发布位置绝不直写 `durChapterPos`。移植上游时不得回退到旧的存储式 detach / 跟随地板方案，不得让引擎重新直写显示进度。
- **原版共享偏好 key**：`BookCover.kt` 的 `legadoCoverRuleConfig` 是原版遗留 key，不能改名。
- **品牌与更新**：不做交流群（QQ 入口全删）；更新检查与仓库链接全部指向 `skxingyu/legado-sk`（`UpdateManager.GITHUB_API`、关于页 README 直连 `raw.githubusercontent.com/skxingyu/legado-sk/main/README.md`）；「更新设置」只存在于关于页，无启动自动检查。
- **内置书源与其授权守卫：10059 起永久移除，不要恢复**（2026-09-21 作者指示）。不得再引入 `defaultData/bookSources.json`、`DefaultData.builtinBookSources` / `seedBuiltinBookSourcesOnce()`、`LocalConfig.builtinBookSourceSeeded`、`JsExtensions.matchApp()` / `getAppName()` / `getAppPackageName()`、`AppInfo.packageName` / `appName`。回归锁 `BuiltinSourceRemovedTest`（已双向证伪）会在恢复时失败。⚠️ 通用接口 `getAppVersionName()` / `getAppVersionCode()` / `getAppVariant()` 与平台 API `appCtx.packageName` **不受影响，勿顺手删**。存量装机已播种的那条书源**保留不动**（无法区分系统播种与用户自建，主动删会误删用户数据）。
- **内置主题预设的可见性（10060 起播种）**：主题管理页列的是 `themePackages/{day,night}/` 下的**目录**（`loadLocal()` 的 `listFiles()`），**不读 `ThemeConfig.configList`**。10054 的 `MD3·墨墟`/`MD3·琴女` 预设原本只作为 `configList` 的**资产来源**存在、从未物化成目录，故 10060 之前**没有任何入口能选到**；10060 起由 `ThemePackageManager.seedBuiltinPresetsOnce()` 在**首次进入主题管理页**时落成普通主题包（可应用/可编辑/可删除）。⚠️ 三个不可改回的点：① 判据必须是 `LocalConfig.builtinThemePresetSeeded` **一次性标记**，改成「目录是否存在」会让用户删掉的预设复活；② 落包前必须 `resolvePresetBackgrounds` 解掉 `@asset:` 前缀，否则背景静默丢失；③ 播种时机是**进主题页**而非 App 启动。⚠️ **不要相信「`themeConfig.json` 存在会遮蔽主题预设」**——该遮蔽只影响读 `configList` 的资产合并，与主题管理页无关（唯一消费者 `ThemeListDialog` 是死代码）；2026-09-21 曾据此误判「共存版开过主题页导致预设消失」，**已证伪**。
- **内置主题预设改名后必须统一「已物化」判据（10063 确立；10062 因此翻车）**：落包入口有**两个**——`seedBuiltinPresetsOnce` 与 `ensureLocalAppliedTheme`——而主题管理页**纯目录扫描、不去重**。预设改名后旧名目录仍留在存量设备上，**只堵一个入口就会同主题并列两条**（10062 只堵了播种：`ensureLocalAppliedTheme` 用默认兜底名「白」查不到旧名目录「黑猫慢生活」→ 落出 `day/白` 空壳，日/夜各 4 条）。
  - ⚠️ **判据必须唯一**：`ThemePackageManager.findMaterializedPreset(isNightTheme, name)`——**连同旧名一起查，命中即返回该条目**；**任何新增落包入口都必须复用它**，不允许各自内联「只查新名」的目录检查。回归锁 `everyMaterializationEntryPointSharesLegacyAwarePredicate` 会失败。
  - ⚠️ **日后再改预设名时，必须把旧名补进 `presetLegacyDirNames`**，否则重复条目重现。
  - **作者选择方案 A「不动存量」**：旧名目录存在即跳过，**既不新建也不改名**；存量设备继续显示旧名，只有全新安装才显示新名。
  - ⚠️ **`白`/`黑` 预设 `backgroundImgPath` 本来就是 `None`（纯色预设），`bg=None` 不是空壳判据**；判别空壳要看 `primaryColor` 是否为预设真值（`#ffecebe9`/`#ff333333`），带背景的是 `MD3·墨墟`/`MD3·琴女`（`background.jpg`）。

- **阅读排版预设按数组下标寻址**：`DefaultData.readConfigs` 由 `ReadBookConfig.getConfig(index)` 直接取用，`readStyleSelect` 是 **Int**。**只允许在数组末尾追加**，改名安全但要同步 `BuiltinPresetAssetTest.READ_PRESET_HEAD`；插入/重排会让存量用户当前排版整体位移。唯一比较预设**名字**的地方是 `ReadBookConfig.kt` 的 `isOldFormatConfigList`（旧格式迁移），改名经实跑验证不影响其布尔结果（下标 0 先命中 `||` 短路）。
- **语言裁剪边界**：`resConfigs "zh"` **会裁掉同语言 region 变体**（`zh-rHK`/`zh-rTW` 与繁体、其他语言一样被裁，只保留精确 `zh`）。产物实测 `locales: '--_--' 'zh'`、`unzip` 中 HK/TW 计数为 0，故 `values-zh-rHK|rTW` 是**不进 APK 的死资源**（已于 10038 删除），不存在"HK/TW 回退到简体或英文"的情形。详见 §6 的语言裁剪边界注。
- **备份打包清单的两类路径（10044 确立、10045 补强，改动前必读）**：给 `ZipUtils.zipFile` 的路径分两类——「本次流程自己创建/校验的」可直接传，「依赖用户配置才存在的」必须先 `exists()` 过滤。⚠️ 但**过滤时点**同样关键：由本次流程**稍后**才创建的目录（如 `covers`，`prepareCustomCoverBackup()` 才建）**不能放进 `backgroundAssetDirNames` 交给存在性判定**，否则会被提前跳过 → 数据静默漏备份（10045 修）。正确做法：先让创建者建好目录并补齐内容，**再按"目录里实际有什么"判定**（`coverDirShouldBeZipped(File)` 判 `listFiles()` 非空）。⚠️ **判据必须锚定"最终要被打包的那个对象的状态"，不能锚定"本次流程做了什么动作"**——`prepareCustomCoverBackup()` 对**已在该目录内**的封面会跳过拷贝，用"本次拷了几个"判定会把最常见的场景误判为空（10045 首版实现即犯此错，被实机回归抓到）。
- **⚠️ 已知继承缺陷（2026-09-15 审查登记，作者决定不修，后续审查勿重复上报）**：以下两项是**上游自带**缺陷（上游 `v3.26.091403` 仍未修），**刻意与上游保持一致**以降低同步成本：
  1. **`exportWebDav(uri,…)` 三处调用点未接异常**（`ExportBookService.kt` 的 `exportPdf`/`exportEpub`/`save2Drive` 裸调用）。10043 把该重载改为抛异常契约，同文件 TXT-ZIP 与 `uploadExportToWebDav` 已接住，这三处没有 → 异常冒泡到导出循环 `catch (e: Throwable)` → 本地文件其实已写好却被计入 `failedExports`（**不崩溃**）。⚠️ 修它需给 `exportPdf`/`exportEpub`（返回 `Unit`）改签名并调整调用点消费链，**非"3 行"改动**。
  2. **恢复回滚不含数据库**：`RestoreJournal.buildSnapshotTargets` 不登记 `legado.db`，而 `Restore.kt` 的 `restoreDbData`（SK 10037 引入）事务真实提交 → DB 段之后的步骤失败或进程被杀时，`rollbackNow()` 只还原配置文件、**DB 保持备份内容**（prefs 与 DB 错位）。⚠️ **两条看似显然的修法均已被证伪，勿照做**：① **把 DB 段挪到最后会破坏 `repairLocalCoverPaths`**（其读 `bookDao.all` 回写，必须在 `restoreBackgroundAssets` 之后、且在 DB 恢复后），会让新恢复的书**从未被修复封面路径**且无报错；② **把 DB 纳入快照不可行**：`appDb` 是顶层 `val … by lazy`，全库无 close/reopen 入口。若日后要修，可行方向是把 `RestoreJournal.begin` 下移到 `restoreDbData` **之后**（不动步骤顺序），但须先核查其状态机与 `App.kt` 的 `recoverIfNeeded` 假设。
- **⚠️ 脆弱点（登记）**：`ThemeConfig.kt` 有 `putPrefInt(PreferKey.uiLayoutAlpha, …)` **绕过 `AppConfig.uiLayoutAlpha` 的 setter 直写 pref**。当前无害的唯一原因是 `getPrefInt(PreferKey.uiLayoutAlpha)` 全库 **0 个读取点**；**若日后新增对该 pref 原始值的读取，即成为透明度锁的真实逃逸路径**。

### 设置默认值

每个设置的界面默认值与实际读取默认值必须一致：

- 界面默认值在 `app\src\main\res\xml\pref_config_*.xml` 的 `android:defaultValue`。
- 实际默认值在 `AppConfig.kt` 及各调用点的 `getPrefBoolean`、`getPrefInt`、`getPrefString`。`getPrefBoolean(key)` 不带默认参数时默认是 `false`。
- 修改任意设置默认值时，全库搜索该 key 的所有读取点，逐一核对类型和值；界面显示与实际行为不一致属于缺陷，不能接受“默认分支行为等价”作为理由。
- 背景图这类文件型默认值不能写成某台设备的绝对路径。必须把素材随 APK 提供，并由统一主题初始化在 `applyDayNightInit()` 前复制到应用私有目录，再为尚未配置的日间/夜间 key 写入该稳定路径。`backgroundImage` / `backgroundImageNight` 缺失表示从未配置；空字符串表示用户明确移除背景，后续启动不得覆盖。
- `uiLayoutAlpha` 的值表示“全局界面透明度”：`0` 为不透明、`100` 为全透明。数值到物理表面 alpha 的换算只能在 `UiCorner.uiLayoutSurfaceAlpha()` 中发生；普通 UI、底栏玻璃外壳和液态玻璃内容均复用该入口，业务页面不得再自行反向计算。

### 异步 UI 与局部模糊

- 只处理真实浮层表面或明确声明的背景层，禁止扫描控件树猜测目标；找不到可靠目标时应暴露问题，不能扩大为宿主 Activity 全屏模糊或纯色兜底。
- 几何、着色、描边与模糊底图必须由同一表面模型和同一裁剪路径管理。每个浮层实例使用独立背景副本，不能混用可变 Drawable 或叠加互相冲突的形状背景。
- 取图必须在目标和宿主 attach、且几何连续两帧稳定后进行。`PixelCopy` 源矩形必须使用源 Window 坐标并严格相交裁剪；不能用强制最小 1 像素矩形掩盖坐标错误。
- 首次可见前完成背景安装。关闭、换目标、重新显示和尺寸变化要使旧回调失效并释放旧位图；样式更新只更新样式，不应取消同一目标仍有效的取图，回调安装时使用最新样式。
- 禁止 `RenderEffect` 作用于宿主 `decorView`，以及 `setBackgroundBlurRadius` / `FLAG_BLUR_BEHIND` 等整窗模糊路径。若要改变浮层外壳几何，先分离外壳、背景层、内容层并完成模拟器全路径验证。

### 分层调试

- 常规问题先用模拟器 ADB、`uiautomator2`、截图和 logcat。
- 只有常规证据不足，且明确怀疑时序、线程、Window/Surface 合成或运行时调用链时，才升级到 Perfetto、Winscope 或 Frida。
- 高级证据必须围绕同一次复现采集：记录开始时间、操作、结束时间；将 UI 层级、时间线、Window/Surface 与调用证据对齐，明确观察结果、排除项、根因和结构性修复。
- 工具入口为 `tools\android-dev`，输出写入已忽略的 `test-records\android-dev`，不得提交 trace、截图、临时二进制或虚拟环境。雷电 Android 14 不支持的 WindowManager 时间序列 tracing 必须如实标为快照降级模式。
- Frida 仅能连接 `127.0.0.1:5555`，默认只读；方法跟踪须限定包、类、方法和最长 30 秒，不修改参数、字段或返回值。脚本错误、`Java is not defined` 或初始化缺失均为失败；结束后卸载脚本并移除模拟器临时 server。

理想环境操作：
uiautomator2 / ADB
        │
        ▼
──────── AI ────────
 │       │        │
 │       │        └── Perfetto
 │       │            看时间/线程/帧
 │       │
 │       └────────── Winscope trace
 │                    看 Window/Surface
 │
 └────────────────── Frida / AI Debug Probe
                      看真实运行时对象和调用链

## 5. 发布与版本控制

- 发布前重新执行第 3 节的 APK 验证（aapt badging + apksigner verify）。
- ⚠️ 迁移后：下述第 3、4 条里的「migrate 仓库 / 只读上游 CCSSNE / 指定代理端口 31180/31181 与 github.com 代理 10808」是旧机的单向推送环境，本机不复存在。本机检出即 `skxingyu/legado-sk` 目标仓库本身（git init + remote 后直接推 main），推送前先用 `git remote -v` 与代理环境实测确认通道，不要照搬旧机代理参数。
- 推送代码到 `skxingyu/legado-sk` 的 main：若走 gh CLI 直连可先 `gh api user` 确认可用；git 直连不通时用 gh token + 显式 URL（`$token = gh auth token`；目标仓库若 shallow，先 `git fetch --unshallow`）。具体直连命令写入 `companion\项目文档.md`（缺失时按实况重建）。
- 用 gh CLI 分步发布，避免大文件上传中断：先 `gh release create "<tag>" --title "..." --notes-file "<CHANGELOG路径>"`（pre/Beta 版加 `--prerelease`），再 `gh release upload "<tag>" "<APK路径>"`；上传大文件前如走代理受阻，按实测 `unset HTTPS_PROXY HTTP_PROXY; export GODEBUG=http2client=0` 处理。
- tag 格式 `v3.26.<MMddHH>-<versionCode>`（如 `v3.26.082220-10018`）；发布后用 GitHub MCP `get_release_by_tag` 或网页复核 tag、目标提交、资产大小、中文排版与 Latest/prerelease 状态。
- **发布类型默认 Pre-release**：除非作者明说「发布正式版/Latest」，一律以 `--prerelease` 发布为预览版（不顶替当前 Latest）；正式/转正需作者另行指示才发布非 Pre。此前 10030/10033 等即按此惯例发布 Pre。

### Git

- 提交前检查 `git status`、`git diff`、`git log`。只暂存本次需要的文件，不提交 APK、构建日志、trace 或临时文件。
- 提交信息简洁且准确，遵循现有仓库风格。
- 每个独立修改在完成代码审查、且准备开始正式 APK 编译前，必须先创建一个只包含该已确认修改的 Git 提交；正式编译、安装和回归通过后，再提交版本基线与验证记录。发生回归时只允许从这些明确提交边界回退，禁止猜测性撤销工作区文件。

## 6. 当前交付基线

> **本节只保留「最近交付 + 一句历史指针」**，不再累积逐版流水。
> 逐版净增量、每个开关/红线由哪版引入 → 查 `companion\发布版更新记录.md`（**版本事实唯一权威**）；
> 一屏版本全貌 → 查 `companion\项目文档.md` §2.1「版本索引」；原始发布文案 → `companion\发布版更新原文-releasenotes.md` 或 GitHub Releases。
> ⚠️ **每次交付只在下面追加一条，并把最老的挤出去**（保持 ≤3 条）。历史版本**不要往这里写**——本文件是每次会话都要整份读入的运行手册，累积流水会挤占注意力。

> ⚠️ **默认分支 = `main`（2026-09-23 作者指示，长期规则）**
>
> **所有代码改动、编译、验证、产物收集一律以 `main` 为基底**，不需要每次向作者确认。其他分支（含 `feat/cleanup-cloud-backup`、`fix/review-r2`、`main-v2` 等）**默认一律不管**，除非作者在本次对话中**明确点名**某个分支。
>
> - **`feat/cleanup-cloud-backup`（10066–10072：按备份清理支持云端 + 备份/恢复共用锁）是试验分支**，作者尚未决定是否合并（仍有一些小问题）。**默认忽略它**：不要检查它、不要合并它、不要基于它编译、不要把它记为「当前交付」。
> - ⚠️ **它的 versionCode 区间（10066–10072）不作为递增基线**，因为该分支未合并回 main。**main 下一次 versionCode 从 `10076` 递增**。
> - 若作者明确点名该分支并要求在其上工作，那是**本次对话的一次性例外**，做完仍回到「默认 main」。

---

- ✅ **10076（`3.26.093001c`）——main 当前交付，待发布（2026-09-30）**（修复「加入书籍」不触发自动备份 + 补齐 3 个漏点 + 2 项加固）：
  - 分支 **main**（提交 `33ab2fb2` 主修 / `3e17ab5f` 加固）。**无 DB 迁移**（版本仍 117）。
  - **根因**：**`Book.save()` 不是加架的唯一收口**。全项目有**两个**写库收口，10075 只接了其一：
    | 收口 | 本质 | 10075 是否接 |
    |---|---|---|
    | `Book.save()`（`data/entities/Book.kt`） | 按 `bookUrl` 覆盖写 | ✅ 接（只覆盖少部分路径） |
    | **`BookUpsert.savePlain()` + `merge()`** | **按身份收敛写入**（10054 引入） | ❌ **没接** |
  - ⚠️ `BookUpsert` **直调 `bookDao.insert/update/delete`，从不调 `Book.save()`**（新书入库在 `savePlain` 的 insert），而**主流「加入书架」全走它**：搜索页 `HomepageViewModel.onAddToShelf`、详情页 `BookInfoViewModel.addToBookshelf`。
    - **实机现象**：删书能备份、加书不能。**删除侧正常纯属巧合**（删除收口恰好是被接上的那两个方法）。
    - 时序自洽：`BookUpsert` 于 `9655ee5e`(10054) 引入，**早于**触发点接线 `2cb89035`(10075)。
    - ⚠️ **作者原猜测「因为加书不在书架页面所以没判断到」不成立**——触发点从未接在 UI 入口上。
  - **修复**：`savePlain()` 与 `merge()` 的**事务之后**各接一次触发。
    - ⚠️ 必须接在 `savePlain` **函数末尾**而非 insert 分支：该函数有 insert / update / 删 stray 三个出口，**stray 删除在分支之外**。
    - ⚠️ 两处都必须在**事务之后**：事务内失败会回滚，提前触发会备份出一个并未发生的新书架。
  - **补齐 3 个独立漏点**（审核穷举全库 `bookDao` 写点后查出）：`VideoPlayerViewModel.removeFromBookshelf`（视频页「移出书架」→ 改走 `Book.delete()`）、`CacheManageViewModel.restoreCacheToBookshelf`（缓存恢复到书架）、`ImportOldData.importOldBookshelf`（导入旧版数据）。
    - 经核实**按设计不该触发、故未接**：本地 TXT/EPUB 导入（`BookType.local`）、`deleteNotShelfBook`（`notShelf`）、`AiBookshelfTool`（**本版无加书入口**）。三者都被 `ShelfIdentity` 排除。
  - **加固一（结构性隔离）**：`Restore.overwriteShelfIfNeeded` 原本调**会触发**的 `book.delete()`，只靠 `Restore.isRestoring` 一个**运行时布尔**挡住 ⇒ 隔离其实是隐式约定，一旦有人改动其作用域，恢复就会在解压中途触发备份，**把正在读取的备份目录删掉**。新增 `Book.deleteWithoutShelfBackup()`，恢复侧显式调用；`isRestoring` 保留作第二道防线。
    - ⚠️ **10075 的注释是错的**：`Book.kt` 与守卫测试都声称「恢复流程不经过 save/delete」，**只提了 `save()`、漏了 `delete()`**，与 `Restore.kt:466` 直接矛盾。已修正。
  - **加固二**：拿锁后复查 `lastShelfKeys`。`pendingShelfChangeJob` 是普通 var（无 volatile/无锁），并发时两个 job 都可能进 `withLock` ⇒ 先到的记完账，后到的**重复备份一次**。
  - **回归锁 9→12 项**：`AutoBackupOnShelfChangeGuardTest`。
    - ⚠️ **旧断言对本缺陷完全不可见**（测试盲区）：它只检查 `Book.kt` 里**有没有**那行字符串，**压根不看 `BookUpsert.kt`** ⇒ 加书全漏触发而测试全绿。现断言落在各**函数体**上（`shelfWriteFunnelsAllTriggerBackup`），并把 `triggerIsOnDomainMethodsNotDao` 从「整个文件含字符串」收紧到「`delete()` 函数体含字符串」（否则 save 有、delete 没有也算绿）。
    - **四项双向证伪**（各自注入 → 对应测试失败 → 全部还原）：摘 savePlain 触发 / 摘 merge 触发 / 恢复侧改回会触发的删除 / 摘拿锁复查。
    - ⚠️ 其中「拿锁复查」的断言**首版是无效断言**（写「块内出现过 `lastShelfKeys`」，**记账行也命中**，实测摘掉复查块后**仍全绿**）。已改为锚定复查语句本身（比较 freshly-read 的 `verified`，且 `return` 早于 `backup`）。**这类"断言了但没真断言"必须靠注入证伪才能发现。**
  - **验证**：全量单测 **242 项 / 10 失败**（10 项＝既有已知失败 `CacheTaskStoreTest` ×9 + `ReadBookConfigTest.sanitize_clampsUnsafeLineSpacing`，**无新增失败**；新增 4 项全绿）。
  - **三份独立只读审核交叉验证**（AGENTS §1.5 实践；报告在 gitignored `test-records/autobackup-add-not-triggering/`）：证伪代理确认根因成立并**纠正主代理 3 处过度断言**；穷举代理列出完整漏触发清单；方案代理**否决了另 3 个方案**（含「App 层观察 `bookDao`」——会让**每次翻页存进度**都跑全表查询 + 集合比对，是真正的高频风险）。
  - **补可观测性缺口**（`88ab2bba`）：`autoBackupOnShelfChangeIfNeeded` 的「身份键集合未变化 ⇒ return」原本**完全静默**，导致实机上「加了书却没备份」**无法从日志区分**三种情形（① 触发点没接上＝缺陷；② 集合确实没变＝设计幂等；③ 正在去抖/上传）。排查时只能靠读源码推断。现补日志，**四个「决定不备份」出口全部可查**：开关关闭 / 恢复中 / 未配任何目标 / 集合未变。
    - 回归锁 `everySkipBranchLogsItsReason`（12→**13** 项），已双向证伪（摘掉该日志 → FAILED）。
    - 实机诊断命令：`adb -s <serial> logcat -d | findstr "书架变动自动备份"`。
  - **实机验证（两台，`io.legado.app.sk2` 10075 → 10076 覆盖安装保数据）**：
    - 平板 `HA1KAPWG`：`firstInstallTime` 保持 `2026-09-21 13:08:56`、`ceDataInode` 保持 `3702639`；pid 存活、`logcat -b crash` 0 条。**作者实测「搜索页加书架」已正常备份**（10075 时不备份）。
    - 手机 `72a2b362`：`firstInstallTime` 保持 `2026-09-21 16:12:52`、`ceDataInode` 保持 `820249`；`logcat -b crash` 0 条。
    - ⚠️ **手机首装被 `INSTALL_FAILED_USER_RESTRICTED` 拒绝**（HyperOS「USB 安装」开关），请作者手动开启后成功。**再次印证：adb 侧绕不过去，直接请作者开开关，不要反复重试。**
  - **⚠️ 排查教训（第二轮实机反馈）**：作者报「搜索页加书架仍不备份」，但那条路径（搜索网格 → 详情页 → 加书架 → `BookUpsert`）**代码上已被本版覆盖**；真因是**手机当时仍是 10075**。
    - **教训：实机反馈先核实「被测设备装的哪个 versionCode」，再读代码。** 本次差点为一条其实已修好的路径去改代码。
    - 另一条要记住的正常行为：若该书在进详情页时**已落库**（点「开始阅读」/「目录」会先落库），再点「加入书架」时书架集合**没变** ⇒ 按幂等设计**不该备份**（现已有日志可区分，见上）。
  - **产物**（重编后）：正式版 36,178,830 字节（sha256 `7a30b44d…`）＋ 共存版 36,178,814 字节（sha256 `6994d46c…`）；均 `10076` / `3.26.093012c` / 阅读SK / arm64-v8a / locales `'zh'` / 8 dex / 无 `debuggable` / apksigner exit 0（`79fef578…`）。
  - **未发布 Release。下一次交付 versionCode 从 `10077` 递增。**

- ✅ **10075（`3.26.092916c`）——历史交付，✅ 已发布 Pre-release `v3.26.092916-10075`（2026-09-29）**（10074+10075 合并交付：新增「恢复时按备份覆盖书架」+「书架变动时自动备份」）：
  - 分支 **main**（提交 `64665ebd` / `120bcc16` / `9257bc3c` / `2cb89035`）。**无 DB 迁移**（版本仍 117）。
  - ⚠️ **10074 从未单独发布 Release**（其自动备份实际不可用），已并入本版。**两个新开关默认都是关**（作者确认）：`autoBackupOnShelfChange`、`overwriteShelfOnRestore`。
  - **改动一：恢复时按备份覆盖书架**（`64665ebd`）。开启后恢复会删掉「本机比备份多」的**在线书**。
    - 判据抽 `help/book/ShelfIdentity.kt`：**搬** `ShelfCleanupRules.keyOf` 的 **trim** 口径（**不是重写**）。
    - ⚠️ **判据必须 trim**：备份与本机书名/作者可能只在首尾空白上不同，不归一会把备份里确实存在的书误判为「本机多余」→ **删掉有阅读历史的那条**。`BookMergeRules.identityKeyOf` **刻意不 trim**，两者不可互相替代、不得合并。
    - ⚠️ **三道守卫缺一即可能删光书架**：① `bookshelf.json` 不存在 ⇒ 拒绝删除（它与 `covers` 绑在同一「书架」可勾选项，取消勾选会让该文件不存在 ⇒ 备份在线书集合为空 ⇒ 全部−空 = 删光）；② 解析失败 ⇒ 拒绝删除（**禁止 `orEmpty()` 降级**）；③ 两侧集合都过滤离线书。
    - ⚠️ **本机集合必须在 merge 之前取快照**（`localKeysBeforeMerge`）。⚠️ **删书放在恢复最末尾、DB 事务之外**（`RestoreJournal` 快照不含 `legado.db`）。删除前落清单 `filesDir/deleted-books-<ts>.json`。
    - **回归锁**：`ShelfIdentityTest`（11 项）、`RestoreOverwriteGuardTest`（7 项），已双向证伪。
  - **改动二：书架变动时自动备份**（`120bcc16`）。⚠️ **本版此前实际不可用，已由下述修复改好。**
  - **改动三：移除「按备份清理本机书籍」入口**（`9257bc3c`）。判据语义未丢（已迁入 `ShelfIdentity`）。
  - **修复（`2cb89035`）**：作者实机反馈 10074 的开关打开后增删书籍均无备份、云端看不到。查到**两个独立根因**，均已修。
  - **根因一（致命）**：把「本地备份目录为空」误判成「没配置备份」而直接 `return`。
    - ⚠️ `AppConfig.backupPath` **只是本地/SAF 目录**，与 WebDAV **完全无关**；WebDAV 由 `backup()` 内部的 `AppWebDav.backUpWebDav()` 独立完成。
    - ⚠️ **手动备份**在同一值为空时的行为是**弹目录选择器**（`BackupConfigFragment.backup()`），**不是跳过** —— 这才是正确语义。自动备份无 UI 可弹，故为空时应**只跳过本地落盘、仍走 WebDAV**。
    - 后果：**只配 WebDAV 的用户（最常见）自动备份永远静默不执行**。
    - 现判据：本地与 WebDAV **都没配**才跳过；**字段命名是 `localPath`**（不是 `backupPath`），改这里别再退回「路径空即 return」。
  - **根因二**：触发点只接了 `addBookByUrl` 一条路径，**所有删除路径都没接**。
    - 接在**领域方法**收口，不再逐条接 UI 入口（必然漏）：`Book.save()`（覆盖全部加架路径）、`Book.delete()` + `BookShortcutHelp.delete()`（覆盖全部删除路径）。
    - ⚠️ **这两个接缝天然排除恢复流程**：`Restore` 全程走 `bookDao.insert/update` 原始调用、**不经过** `save()`/`delete()`，故无需额外排除条件。
    - ⚠️ `BookshelfViewModel.notifyShelfMaybeChanged()` **已删除**（旧设计的临时触发入口）。
  - **新增**：备份成功后 **0.5 秒一闪提示**（`toastOnUiBrief`，复用主题化 Toast）。
    - ⚠️ **`Toast.setDuration` 只认 `LENGTH_SHORT`(0) / `LENGTH_LONG`(1)，传毫秒无效**（系统按未知常量落回 SHORT≈2s，且受无障碍设置影响）。故 `toastOnUiBrief` 是「按 SHORT 显示 + 到点主动 `cancel()`」实现。
  - **回归锁**：`AutoBackupOnShelfChangeGuardTest`（7→**9** 项）。**四项已双向证伪**：本地路径空即 return → `doesNotTreatMissingLocalPathAsUnconfigured` 失败；摘掉提示 → `showsBriefToastAfterSuccessfulAutoBackup` 失败；摘掉删除漏斗触发 → `triggerIsOnDomainMethodsNotDao` 失败；把提示移到备份之前 → `showsBriefToastAfterSuccessfulAutoBackup` 失败。
    - ⚠️ 写这类断言时**观测窗口要精确**：初版把「WebDAV 判据之前不许有 return」的范围开得过大，把方法开头合法的 `Restore.isRestoring` 守卫也算进去 → **代码正确却报错**。现只观测「决定哪里有处可写」那一小段。
  - **验证**：全量单测 **238 项 / 10 失败**（10 项＝既有已知失败 `CacheTaskStoreTest` ×9 + `ReadBookConfigTest.sanitize_clampsUnsafeLineSpacing`，**无新增失败**）。
  - **实机（两台，`io.legado.app.sk2` 10074 → 10075 覆盖安装保数据）**：
    - 手机 `72a2b362`：`firstInstallTime` 保持 `2026-09-21 16:12:52`、`ceDataInode` `820249` 未变；pid 存活；`logcat -b crash` 0 条。
    - 平板 `HA1KAPWG`：`firstInstallTime` 保持 `2026-09-21 13:08:56`；pid 存活；`logcat -b crash` 0 条。
    - ⚠️ **界面/功能验证由作者完成**（作者要求：AI 只装实机、不做模拟器测试）。**未做任何改动数据的操作**。
  - **产物**（同版本号、同签名、仅包名不同）：`release/legado_sk_3.26.092916c_10075_arm64-v8a.apk`（36,178,675 字节，`io.legado.app.c`，sha256 `A5B7B6D9…`）＋ `release/legado_sk_3.26.092916c_10075_arm64-v8a_（共存版）.apk`（36,178,718 字节，`io.legado.app.sk2`，sha256 `C3A40022…`）。
    - aapt 均 `10075` / `3.26.092916c` / 阅读SK / arm64-v8a / locales `'zh'`；`debuggable` 无输出；`classes*.dex` 均 **8** 个；apksigner exit 0（证书 SHA-256 `79fef578…`）。`release/legado-sk-arm64-v8a.apk`（固定名）已更新为 10075 正式版。
  - **下一次交付 versionCode 从 `10076` 递增。**
  - ⚠️ **验证共存版 APK 时注意**：`apksigner.bat` 传含 `（共存版）` 的路径会因批处理按 OEM 代码页解析参数而报「找不到文件」（**不是签名问题**）。绕法：先复制成 ASCII 名再验（`java -jar apksigner.jar` 直调同样会中招）。

- ✅ **10074（`3.26.092915c`）已构建并安装到平板实机（2026-09-29）——历史交付（新增「自动备份」+「恢复按备份覆盖」）**：
  - 分支 **main**（提交 `64665ebd` / `120bcc16` / `9257bc3c`）。**无 DB 迁移**。
  - ⚠️ **两个新开关默认都是关**（作者确认）：`autoBackupOnShelfChange`、`overwriteShelfOnRestore`。
  - **改动一：恢复时按备份覆盖书架**（`64665ebd`）。开启后恢复会删掉「本机比备份多」的**在线书**。
    - 判据抽 `help/book/ShelfIdentity.kt`：**搬** `ShelfCleanupRules.keyOf` 的 **trim** 口径（**不是重写**）。
    - ⚠️ **判据必须 trim**：备份与本机书名/作者可能只在首尾空白上不同，不归一会把备份里确实存在的书误判为「本机多余」→ **删掉有阅读历史的那条**。`BookMergeRules.identityKeyOf` **刻意不 trim**，两者不可互相替代、不得合并。
    - ⚠️ **三道守卫缺一即可能删光书架**：① `bookshelf.json` 不存在 ⇒ 拒绝删除（它与 `covers` 绑在同一「书架」可勾选项，取消勾选会让该文件不存在 ⇒ 备份在线书集合为空 ⇒ 全部−空 = 删光）；② 解析失败 ⇒ 拒绝删除（**禁止 `orEmpty()` 降级**）；③ 两侧集合都过滤离线书。
    - ⚠️ **本机集合必须在 merge 之前取快照**（`localKeysBeforeMerge`）。
    - ⚠️ **删书放在恢复最末尾、DB 事务之外**（`RestoreJournal` 快照不含 `legado.db`）。
    - 删除前落清单 `filesDir/deleted-books-<ts>.json`（可手工找回）。
    - **回归锁**：`ShelfIdentityTest`（11）+ `RestoreOverwriteGuardTest`（7），已双向证伪。
  - **改动二：书架变动时自动备份**（`120bcc16`）。**⚠️ 本版此功能实际不可用，已在 10075 修复 —— 改这里前务必先读上面 10075 条。**
  - **改动三：移除「按备份清理本机书籍」入口**（`9257bc3c`）。判据语义未丢（已迁入 `ShelfIdentity`）。
  - **产物**：`release/…_10074_arm64-v8a.apk`（36,172,863 字节，sha256 `EDA85BEC…`）＋ `…_（共存版）.apk`（36,172,644 字节，sha256 `BC68F827…`）。**未发布 Release。**

- ✅ **10073（`3.26.092401c`）——历史交付（新增「默认备份内容」）；✅ 已发布 Pre-release `v3.26.092401-10073`（2026-09-24）**：
  - 分支 **main**（提交 `fe182e31`）。**无 DB 迁移**。两包均已上传 Release 资产（共存版资产名用 ASCII `_sk2-coexist`）。
  - **`BackupTargetConfig`**（`filesDir/backupTarget.json`）持久化备份范围，一次设定长期生效。
    - ⚠️ **未保存过的项必须默认勾选**（`selections[key] ?: true`）：升级后无该文件，**默认方向反了会让第一次备份变成空包**而用户以为备份成功。
    - ⚠️ 项目 `key` 一旦发布即存量设备持久化键，**只能新增、不能改名或复用**。
  - **`BackupItems`**（storage 层）把 18 个分组清单从 UI 层提升上来，**备份与恢复共用同一份**。
    - ⚠️ **不得在两侧各留一份**：漂移会导致「恢复把不该恢复的内容恢复了且无任何报错」。
    - ⚠️ `BackupItems.navigationBarDirName` 必须是**字面量**（引用 `rootDir` 会读 `appCtx`，使整份清单无法在 JVM 单测求值）；由 `Backup.navigationBarDirNameStaysInSync()` 比对。
  - **备份入口不再弹框**（`selectBackupTargets`）：全选 → `targets = null`（旧「全部打包」语义，新增目标自动包含）；部分勾选 → 下发集合；**一个都没勾 → 拒绝 + toast**（`R.string.backup_select_none`），**绝不塌缩成"全打包"或"空包"**。
  - ⚠️ **恢复侧行为一字未改**：仍逐次询问、仍只服从「恢复忽略列表」。**本项只管备份范围，两套语义互不干扰**。
  - **回归锁**：`BackupTargetConfigTest`（7 项，源码级静态断言），已双向证伪。
  - **产物**：正式版 36,184,360 字节（sha256 `ec20ad38…`）＋ 共存版 36,184,231 字节（sha256 `d76f2eab…`），均按 `initWith release` 重编。
    - ⚠️ **10073 首版共存包（44,542,383 字节）已废弃**：那是 `initWith debug` 产物，带 `debuggable=true` 且 23 个未合并 dex。

---

> **更早版本（10004–10072）**：本文件不再记录。查 `companion\发布版更新记录.md` §1 逐版净增量，或 `companion\项目文档.md` §2.1 版本索引，或 git log / GitHub Releases。

> ⚠️ **语言裁剪边界（2026-09-10 修正，不随版本过期）**：`resConfigs "zh"` **会裁掉同语言 region 变体**。产物实测 `locales: '--_--' 'zh'`，`unzip -l` 中 `zh-rHK|zh-rTW` 计数为 **0**。故 `values-zh-rHK` / `values-zh-rTW` 是**不进 APK 的死资源**（已于 10038 删除）。**「缺失 HK/TW 字符串会回退到简体/英文」的说法不成立**——该 locale 整体不存在。

> ⚠️ **重植上游后必须核查并删除 `.github/dependabot.yml`（2026-09-10）**：SK 版不使用 Dependabot。因本仓库是独立仓库而非 fork，不继承上游配置，但**每次重植都会把该文件重新带入**（已删过两次：`e080aa96`、`fe4d57a8`）。重植后执行 `git cat-file -e HEAD:.github/dependabot.yml` 确认不存在；若被带入则删除并单独提交。
> 判断依据可复用：① `modules/web` **不参与 APK 构建**（`settings.gradle` 仅 `include ':app'` / `':modules:book'` / `':modules:rhino'`）；② `gradle/libs.versions.toml` 的 kotlin/ksp/AGP/wrapper 属**已验证的构建工具链组合**，跨大版本升级会破坏 `assembleAppRelease`，不得自动合入。

> 重植方法论修正（10037 教训）：核对「SK 定制是否全部保留」必须以 `git diff <旧基底> <旧SK main>` 的**全量内容比对**为准（新增行 + 删除行双向核查），不能只依赖按功能分簇的素材清单——10036 即因分簇清单不全而漏植约十项。

每次交付后当场更新本节（追加新条 + 挤掉最老的）。历史发布信息从 Git、GitHub Release 或 `companion\发布版更新记录.md` 查询，不在本文件累积。

## 7. 当前机器环境与配套文档（2026-09-04 迁移后）

> 迁移后的电脑为全新环境：无 D 盘，原先 `D:\code\...`、`D:\OneDrive\桌面\Ai\legado-sk\`、`F:\leidian\LDPlayer14`、`F:\down\雷电模拟器14...` 均已失效或弃用。本节统一登记本机事实；若某条与实际不符，先核实再改，勿让 AGENTS 出现悬空路径。

### 7.1 环境快照（已逐项核对）

| 项 | 值 |
|---|---|
| 代码/构建唯一目录 | `C:\code\ai-code\legado-sk`（git 仓库，remote = `skxingyu/legado-sk`，main 分支） |
| JDK 17 | `C:\Users\skxingyu\AndroidDev\jdk-17.0.2` |
| Android SDK | `C:\Users\skxingyu\AndroidDev\android-sdk`（platforms 34/36；build-tools 34.0.0/36.0.0；cmdline-tools latest） |
| Gradle | 项目 wrapper `gradlew.bat`（gradle-8.14.4，dist 下载到 `C:\Users\skxingyu\.gradle\wrapper\dists`）；备用 `C:\Users\skxingyu\AndroidDev\gradle-9.7.1` |
| Gradle user home | 默认 `C:\Users\skxingyu\.gradle`（不显式设置） |
| 系统 adb | `C:\Users\skxingyu\AndroidDev\android-sdk\platform-tools\adb.exe` |
| 雷电模拟器（C 盘） | `C:\download\down\cloud-down\雷电模拟器14纯净绿色版+狐狸+LSP+微霸\LDPlayer14`（实例 `leidian0`=index 0） |
| sdkmanager | `cmdline-tools\latest\bin\sdkmanager.bat`（已装 android-36 / build-tools 36.0.0） |

shell 选择：默认 Git Bash（POSIX）；原生 Windows 工具 / `.ps1` / 需 PowerShell 场景改用 Pwsh。

### 7.2 配套工作文档（从外部工作目录收敛进仓库）

原散落在 `D:\OneDrive\桌面\Ai\legado-sk\` 的配套文档，迁移后统一收进 **`C:\code\ai-code\legado-sk\companion\`**（含 backup、release 也在 gitignored 位置）。该目录已在 `.gitignore` 忽略，含机器信息，绝不推送公开仓库。

**现有 companion 工作文档**（新会话先读 §0；此处为目录概览，避免歧义）：
- `companion\项目文档.md` —— 项目概览 + **§2.1 版本索引**（一屏全貌 + 版本定性；逐版细节在下一份）。
- `companion\发布版更新记录.md` —— **版本事实的唯一权威来源**：§0 防改错速查 / §1 逐版净增量 / §2 功能→版本反查表。
- `companion\发布版更新原文-releasenotes.md` —— GitHub Releases 逐版作者原文全文（备份至 10037，可 grep）。
- `companion\基底重植方法论与10036记录.md` —— **基底重植方法论**（新枝重植怎么做、通用移植原则）+ **⭐ 重植完整性核查方法**（§2，下次重植必跑）+ 10036 那次重植的定性。⚠️ **不是待执行方案**，其中的逐项移植清单已删除（行号已过期）。

**已确认不存在、且不再计划创建的配套文档**（历史上有引用，2026-09-29 清理时核对：这些文件从未存在过，**不要按旧引用去找**）：
- `companion\编译注意事项与排错手册.md` —— 内容已由本文件 §3「本机环境与正式命令」+「长命令和构建失败」覆盖，**不另建**。
- `companion\代码审查报告-第 N 轮.md` —— 各轮审查结论已并入 `发布版更新记录.md` §0 与本文件 §4「功能红线」，**不另建**。

**确实缺失、需要时可重建的本机产物**（不属于文档，丢了随时可再生成）：
- `release\legado_sk_3.26.090212c_10026_arm64-v8a.apk` —— 旧产物，当前检出没有；需要时从 GitHub Release 重新下载。
- `backup\` —— 真机数据备份，本机尚无。

**已归档（2026-09-10 文档精简，源码见 `test-records/doc-cleanup/archived/`）**：`新上游基底重做清单.md`（自述使命完成，独有信息已迁入 `项目文档.md` §3 与 `发布版更新记录.md`）、`移植方案-上游三项增量.md` / `移植方案-换源弹出卡片.md`（结论已并入 `发布版更新记录.md` 10031/10029 节）、`docs/ui-design-spec.md`（与 §4 重复的陈旧分叉，且唯一差异的"工作模式分级"经作者裁决已废止）。

**用途约定**：AGENTS.md 本身随公开仓库分发（其中 §2/§3/§7 为机器专属运行信息）；凡机器/本机信息应只写进 AGENTS 检出副本与 `companion\`，不要新增进公开可读的 README/docs。本机 `~/.dsh/AGENTS.md` 为本机级总则，与仓库 AGENTS.md 并行。

**子代理中间产物**（见 §1.5）：探查报告、审查结论、运行时的 UI dump / 日志一律写入 `test-records\<任务名>\`（同样被 `.gitignore` 忽略），**不提交、不推送**；交付完成后可清理临时 dump，只保留方案与审查结论备查。

### 7.3 仓库检出状态与首启清单

- 当前目录已是完整 git 仓库（2026-09-05 `git init` 并对齐 `origin/main` 历史，gh auth 直连推送成功）；提交时勿把机器路径/`companion\`、`release\`、`build_logs\` 误提交。
- 首次正式编译前：确认 SDK36/build-tools 36 已装（已装）、`android-36` platform 存在；首次 `gradlew.bat` 会自动下载 gradle-8.14.4（联网）。
- android-dev 高级调试工具链依赖 `.android-dev-venv\`（uiautomator2/frida/adbutils）与 `tools\android-dev\bin\frida-server-17.17.0-...`，本机未就绪；仅当需要 Perfetto/Winscope/Frida 分层调试时再重建，不影响常规编译/模拟器回归。
