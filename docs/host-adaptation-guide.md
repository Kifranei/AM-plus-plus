# Apple Music 宿主适配手册

本手册供维护者为一个具体 Apple Music 构建建立、维护和验收 AM++ 接入。适配对象是包名、versionName 和 versionCode 组成的精确版本组合；正式版、beta 和同名的不同构建分别处理。手册覆盖当前 Activity、Fragment 两类宿主，并作为后续版本的统一流程。

各功能的运行规则、成员契约和维护入口集中在本手册；[适配清单](host-adaptation-checklist.md)集中逐项核对与人工场景，[功能保全与验收矩阵](feature-preservation.md)定义跨功能边界和结果记录方式。单次任务的排查过程、代码改动和构建日志保存在任务记录中。

## 1. 适用范围与版本登记

版本资料位于 [host-profiles](../host-applemusic/src/main/resources/host-profiles/)。支持范围由 `index.json`、精确 profile 的 `productionEnabled` 和能力标志共同确定。版本表、成员映射、已知降级与该构建的证据在档案中维护，本手册不重复登记。

“已启用”表示允许模块接入该构建，不表示全部功能已经通过当前代码的真机验收。功能还受设置、Android API 和设备形态限制；人工验收模板见 [功能矩阵](feature-preservation.md)，实际结果在对应任务中记录。

页面族按实际入口、生命周期和布局识别：`legacy-activity` 与 `fragment-content` 分别使用自己的接入。正式版、beta、相邻构建和新页面族均需独立分析，版本号大小和主版本号不能代替页面族识别。

`AppleMusicHostProfiles.find` 按版本名、版本号定位文件，并校验包名；生产资格另检查 `productionEnabled`。资料文件名使用 `<versionName>-<versionCode>.json`。同一组合只能登记一次。索引顺序还影响旧 HLE 引擎的已审核候选，适配时应保留现有条目的相对顺序。

## 2. 架构与修改位置

| 模块 | 职责 | 适配时的边界 |
|---|---|---|
| `core` | 配置、歌词与字体业务、来源、缓存、布局和会话策略 | 不引入 Android、宿主反射或 Xposed 依赖 |
| `host-api` | 宿主能力、歌曲身份、区域接口、配置读取、安装结果和可关闭订阅 | 不暴露混淆成员、反射类型或宿主 AndroidX 实现 |
| `hook-runtime` | Xposed 接入、注册作用域、通用调用缓存 | 不承载 Apple Music 版本策略或业务页面 |
| `host-applemusic` | profile、符号解析、原生歌词、设置、播放器、导航和元数据接入 | 所有 Apple Music 私有成员和页面族差异集中于此 |
| `app` | 功能装配、配置存储、设置界面、歌词服务与玻璃会话 | 通过宿主接口使用能力，不自行寻找混淆成员 |
| `glass` / `backdrop` | 材质、采样和呈现 | 不管理 Apple Music 版本资格 |
| `plugin-api` / `plugin-runtime` | 插件 SDK、导入、加载、生命周期和冲突分析 | 不随单个宿主构建改变公共协议 |

宿主装配入口为 `AppleMusicHostFactory`，按当前精确 profile 的 `family` 选择设置、双栏、蜂窝和视频处理路径。只发生混淆变化时，优先修改版本数据；调用形式、生命周期或布局语义变化时，修改对应宿主适配层。

内部接入遵循三类职责：

- **Contract**：解析并校验成员、资源及相互类型关系，持有缓存句柄。
- **Runtime / Coordinator**：注册 Hook、管理活动宿主和能力状态。
- **Session / Binding**：管理具体视图、监听器、延迟任务和属性恢复。

业务开关、缓存规则和歌词来源逻辑不应因混淆名字变化而重写。新页面族需要单独的能力工厂，不能默认归入已有路径。

## 3. 版本资料与成员契约

### 3.1 profile 的主要区域

| 区域 | 应记录的内容 |
|---|---|
| 身份与资格 | `id`、`packageName`、`versionName`、`versionCode`、`family`、`productionEnabled`、`profileRevision` |
| `capabilities` | 玻璃、蜂窝、收起拦截、播放器恢复等独立资格 |
| `indexed` | 类、方法、字段映射；支持精确契约的路径还填写 `methodContracts` / `fieldContracts` |
| `hookTargets` | HLE Hook 入口、签名约束、调用方约束及 `runtimeMemberNames` |
| `artistComposeProfile` | 可选 Compose 艺人页的完整方法、字段、构造器和艺人类型/top-songs key；声明后必须完成安装前契约解析 |
| `settings` / `chrome` | 设置入口、原生设置模型、导航和播放器资源或旧宿主契约 |
| `layoutVariants` / `cjk` | 歌词 binding、渐变和前景文本等版本数据 |
| `cjk.terminalGradient` | 可选的 CJK 末词羽化补偿精确契约：扫光/余量方法、reader、行词向量、词 ID/时间与动画标签 |
| `lyricBlur` | 两类页面族独立使用的完整歌词模糊契约：显示提交、文档/行身份、绑定、词动画、视图、触摸/滚动与 Seek |
| `lyricWrapSweep` | 可选的跨行歌词扫光精确契约：原生扫光调用、动画更新与退场渐暗回调、静态遮罩与暗色调色板、视觉行遮罩键与词时间、媒体时间轴及暂停/恢复/Seek 入口 |
| `catalogQueryRenames` | 实际目录查询 owner 与方法映射，配合完整签名和调用路径核对 |
| Fragment 专用区域 | 双栏、歌词运行时、逐字宽度、视频接力、chrome 和恢复所需契约 |
| `verification` | 离线检查需要的 DEX 描述符、字段类型、资源名和辅助接口证据 |
| `evidence` / `knownDegradations` | 来源、证据状态、运行时确认范围及已知能力缺口 |
| 匹配策略 | `ambiguityPolicy` 和历史 `legacyFirstMatchExceptions` |

JSON 描述宿主事实，不承载可执行逻辑。参数变换、回退条件、线程和生命周期策略由 Kotlin 实现。profile schema 当前为 1，应用配置 schema 当前为 15。

### 3.2 完整描述符与语义核对

| 成员 | 必须核对 |
|---|---|
| 方法 | 实际声明 owner、名字、参数顺序和完整类型、返回类型、static、抽象/bridge/synthetic 属性 |
| 字段 | 实际声明 owner、名字、完整类型、实例归属和被读写的含义 |
| 构造器 | owner、完整参数类型与顺序、创建对象的实际用途 |
| 接口调用 | 返回对象实现关系、实际声明接口、调用形式；必要时使用 `invocationOnly` |
| Hook 目标 | 可 Hook 的具体实现，及它是否位于实际活跃调用路径 |
| 资源 | 名称、类型、限定符、实际取值、包含布局、运行时父容器和参数类型 |

“类存在”“参数个数相同”和“成员能反射调用”均不足以证明正确适配。混淆类名可在新包中承担其他职责；旧方法名也可能仍存在，却接收另一种模型。应沿调用方、返回对象和后续读写核对完整链路。

继承成员记录实际声明 owner。抽象接口可作为带 `invocationOnly` 的调用契约，不能作为新增 Hook 目标。数组、primitive、可空对象和实例/静态调用不能相互替代。

### 3.3 两类解析路径

`IndexedTargetSymbolResolver` 为每个符号缓存解析结果，包括缺失和歧义。已声明的精确方法或字段契约不匹配时返回缺失；稳定名和结构回退是否允许，由该符号的策略决定。必需成员不能通过另一个相似符号补齐。

HLE 的 `AppleMusicHookProfiles` 将 JSON 转为兼容引擎的候选。Fragment 档案使用严格候选；旧 Activity 档案保留既有版本匹配和已审核回退顺序。外层生产门禁始终精确匹配版本组合。

新档案采用 `reject-ambiguous`。历史首个匹配例外只适用于原有已审核目标，不能作为新版本的默认策略。若发现两个候选，应补充类型、调用关系或契约，无法唯一确定时降级对应能力。

## 4. 每个版本的适配流程

### 4.1 确认安装包与代码基线

1. 从实际 base APK 的二进制 Manifest 确认包名、versionName、versionCode；包文件名及外层清单只作辅助。
2. 清点 base、全部配置或功能 split、各 DEX 和资源来源。不能假定 DEX 仅位于 base。
3. 记录代码基线、拟复用的页面族及对照宿主。保存可回溯的源码位置、描述符和资源证据。
4. 明确工作范围：源码分析、静态检查、构建及人工功能验收分别安排。包哈希只在明确要求时计算。
5. 保存当前代码与必要测试产物，以便对照和回退。

Java 反编译用于理解调用链，成员声明与完整签名以 DEX/smali 为准。工具生成的类型推断、命名修复以及大小写冲突后的文件名不是宿主原始类名；嵌套类、接口和泛型擦除后的实际类型按字节码记录。资源反编译还需核对限定符和实际包含关系。

### 4.2 建立完整接入清单

从工厂、能力入口和实际调用链展开，覆盖设置、歌曲身份、自定义/自动歌词、模糊、CJK、字体、名称修正、手机/平板玻璃、双栏、视频与背景、蜂窝及 DPI。

每项都记录“直接 Hook 入口”和“回调继续使用的附属成员”。附属成员包括 binding、holder、getter、listener、列表、Fragment 事务、浏览器接口和模型字段。没有变化的契约也应有当前安装包依据。

### 4.3 建立档案并实现接入

1. 建立精确 tuple JSON，开发期保持 `productionEnabled=false`。
2. 按实际页面族填写通用区域和该族必需区域；从相邻构建取模板时逐项重新核对。
3. 将新依赖同时加入 profile、代码维护的必需角色清单和适配清单。
4. 调整 Contract、Runtime、Session 中发生变化的调用或生命周期；同一能力不同时维护一份固定旧版本映射和一份新映射。
5. 缓存成员与资源，处理失败和恢复。缺失目标应明确报告能力状态。
6. 对照全部布局限定符和生成 binding，确认原生重绑定、尺寸动画和异步回调不会覆盖模块布局规则。

### 4.4 检查、登记与交付

静态核对和源码检查用于发现成员与资料缺口；APK 构建用于确认可以生成交付物；人工验收用于确认具体功能和设备场景。三者分别记录。

完成接入后登记到 `index.json`。为明确请求的用户测试启用精确 tuple 时，保留尚未完成的运行时验收状态。相邻构建和未登记版本仍不获得资格。

交付至少包括精确 profile、必要宿主接入、当前通用文档、可安装 APK、能力缺口和按版本填写的验收结果。发布、推送和 PR 依任务授权单独处理。

## 5. Activity 页面族

`legacy-activity` 的主要入口为 `AppleMusicDualPaneTarget`、`DualPaneShell` / `ConstraintLayoutPane`、`LegacyChromeHostBinding` 和旧设置桥。

| 接入域 | 每个构建应核对 |
|---|---|
| Activity 与播放器 | PlayerActivity 根视图、BottomSheet、控制器初始化/创建/选择 pane、原生枚举和状态访问器 |
| 双栏资源 | 主播放器布局、左右 host、ConstraintLayout 字段、歌词 sheet、导航高度与收起边界 |
| 歌词同步 | Fragment binding、RecyclerView、渐变、度量、行词回调及监听清理 |
| 旧 chrome | 导航 holder、导航切页、mini、展开进度、拖动与采样区域 |
| 设置 | PreferenceFragment、PreferenceScreen、设置行 key/标题/摘要、生命周期及 Activity 结果桥 |
| 视频规则 | 既有 Editorial URL 处理范围、预览及 Music Video 的原生行为 |
| 名称修正 | HLE 模型与 binding、Epoxy、资料库、主页等全部显示表面 |

旧宿主的 AndroidX 参数类可能经过重新打包和字段混淆。应依据实际字段类型和原生使用位置确认映射，不能直接采用另一构建的字段名，也不能用模块自带 AndroidX 对象替换宿主对象。

旧路径的布局安装、原生状态选择及已审核候选回退属于兼容语义。调整通用接入时，需对已登记构建分别列出回归项。已有冻结夹具位于 `host-applemusic/src/test/resources/baseline/`；普通适配不能以更新夹具消除行为差异。

玻璃、蜂窝和 `staticCollapsedIntercept` 是独立资格，按当前 profile 判断，不能扩展到其他构建。操作表、主页封面等子面的已知降级单独记录，不能因其他表面成功而省略。

## 6. Fragment 页面族

`fragment-content` 的播放器、页面导航与 mini 生命周期由 Fragment 接入；不能套用旧 Activity 的 holder、设置或事务模型。

### 6.1 必需契约

| 区域 | 代码入口 | 完整性要求 |
|---|---|---|
| `fragmentDualPane` | `FragmentDualPaneContract` | 主播放器状态、Fragment/Manager/事务、pane LiveData、封面 callback、BottomSheet、歌词可用性及 binding 完成接入 |
| `fragmentLyricsRuntime` | `FragmentLyricsRuntimeContract` / `BetaLyricsPaneRuntime` | 生命周期、binding/附属容器、RecyclerView、度量、行监听注册与清理 |
| `fragmentKaraokeWidth` | `FragmentKaraokeWidthContract` | adapter、缓存宽度、holder、item、binding 与 flexbox |
| `fragmentTabletVideoHandoff` | `FragmentTabletVideoContract` / `FragmentTabletVideoHandoff` | 元数据 listener、视频尺寸/原生 resize 入口、静态基准/目标尺寸、TextureView、浏览器接口、模式和动画字段 |
| `fragmentDualPane.songLayout` | 歌曲栏 Contract / Runtime / Session | 明确需要覆盖布局时的参数、Guideline、Barrier、binding 与 padding setter |
| `fragmentChrome.members` | `FragmentChromeContract` / `FragmentChromeInventory` | 导航、菜单、LiveData、进度、生命周期、BottomSheet 和 mini 启动 |
| `indexed` 与 `playerRecovery` | `FragmentPlayerRecoveryContract` | 封面、尺寸基准、背景、进入/共享动画及销毁恢复的精确契约 |
| `settings` | Fragment 原生设置工厂及模型契约 | ViewModel、原生行模型、构造器、callback、Compose 入口及结果归属 |

`FragmentDualPanePreflight` 按 `FragmentDualPaneInventory` 解析双栏及附属组，再校验成员间的类型关系。JSON 少写必需角色会导致预检失败。chrome 和播放器恢复分别完成自己的必需契约解析，不能把双栏预检成功当作其他能力完整。

`songLayoutPolicy` 显式选择 `native` 或 `1606-column`。后者是现有列布局策略的标识，所有成员仍从当前版本 profile 读取。`native` 不携带布局覆盖；`1606-column` 必须提供 `songLayout`。新增布局策略需要代码和契约共同实现。

`layoutReview` 的六个字段必须有当前构建证据：`artwork`、`metadata`、`controls`、`progress`、`binding`、`restoration`。填写证据说明已经做过对照，不表示真机通过。

### 6.2 Fragment 生命周期与容器

`FragmentTabletDualPaneCoordinator` 管理控制器，`FragmentTabletDualPaneSession` 管理单个播放器视图。Fragment view 重建、配置变化和 controller 销毁分别处理。保存状态期间的事务应延后；恢复中的歌词 host 保持可测量，不能在事务提交前折成无效宽度。

右栏运行时 ID 和 Fragment 恢复 tag 属于持久恢复约定。其历史命名不影响作用，不能随版本号改名。SONG / QUEUE 使用原生状态和选择方法，独立歌词放在右栏。

`PlayerSurfacePort` 及其 Fragment 适配提供导航、mini 和播放器区域。模块视图只挂入允许的普通容器或 sibling；受限的 FragmentContainerView 不接受任意模块 View。

### 6.3 导航、mini 与设置

手机使用原生底导航与 mini 边界；平板使用顶部导航和独立底部 mini。平板 Fragment 玻璃与双栏开关独立。导航模型、三个 getter、选择 callback、重复点击、搜索及抽屉逐项核对，不能只找一个签名相似的方法。

手机玻璃的收回目标和终态布局通过宿主端口协调。`fragmentChrome.members` 必须登记原生启动平滑落位的 `settleSheet(View, int, boolean)` 和重新计算折叠坐标的 `refreshCollapsedOffset()`，并核对当前构建的目标状态参数、BottomSheet 状态、折叠坐标和 peek setter 语义。原生开始向折叠状态落位前，先同步模块占位高度并刷新折叠坐标，再让原生动画读取目标；相同 peek 值也须能刷新过期坐标。模块写入不能被记成新的原生 peek 基线。

收回期间可能因 inset 或布局变化再次改变折叠坐标。宿主适配须记录原生本次落位的 owner、sheet、目标状态和目标坐标；只有仍在该次收回的 settling 且目标坐标确实变化时，才通过原生平滑落位入口更新剩余动画终点。保持正常拖动及展开、隐藏路径，不直接改写 View 的 top/translation，不在目标未变时重复启动动画；原生开始其他目标或进入拖动/稳定状态、会话销毁时清理记录。动画结束后的 `FragmentPhoneChromePort.reconcileCollapsedSheet` 位置检查仅作兜底：原生已折叠但实际位置仍偏离有效目标时，合并布局请求并在完成前阻止该帧绘制。该检查覆盖后续 inset 和布局变化，不能仅依赖 peek 数值变化或进度归零。

原生面板位置校准须先于 mini 折叠锚点采样。材质岛的待处理尺寸和边距应在同一轮绘制前完成测量与布局，不能依赖多个 pre-draw listener 的注册顺序。适配新版本时沿用宿主端口边界，状态及坐标成员从当前 profile 预先解析；旧 Activity 与平板布局分别维护。

mini 启动恢复需同时满足原生登录判断及当前播放对象存在。显示方法可能为实例调用，也可能是静态的 `(owner, boolean)`；按当前描述符及调用标识处理。重试和延迟回调确认 owner / view 仍有效，销毁后停止。

Fragment 设置使用原生 ViewModel 和设置行模型，保留分类、行去重、点击与文件选择结果归属。构造器中的 artwork 类型、callback 和 Unit 不应借用其他构建的类型。

## 7. 各功能的适配要点

维护规则集中在本节：[歌词模糊](#lyrics-blur)、[CJK 辉光与末词扫光](#cjk-animation)、[跨行歌词扫光](#lyric-wrap-sweep)。成员核对与完整人工场景集中在 [适配清单](host-adaptation-checklist.md)。

<a id="lyrics-blur"></a>

### 7.1 歌词模糊适配与维护

`AppleMusicLyricBlurContract` 对两类页面族均解析独立 `lyricBlur` 区域，不依赖双栏开关或双栏契约解析成功。以下规则用于已登记构建维护和新版本适配；精确版本、成员与能力资格仍以当前 profile 为准。

#### 7.1.1 显示行为

| 项目 | 规则 |
|---|---|
| 高亮歌词 | 正常跟随时焦点半径和边缘权重目标均为 0；实际效果可以在 300ms 内逐渐清晰 |
| 过渡 | 清晰与模糊两个方向均为 300ms 线性插值；同目标事件不重启动画，目标改变时从当前值接续 |
| 已唱歌词 | 从距离 0 起，基础半径为 `0、13、17、22px`，更远保持 22px |
| 待唱歌词 | 从距离 0 起，基础半径为 `0、8、13、17、22px`，更远保持 22px |
| 用户偏移 | 使用现有配置键、单位和范围，高亮目标 0 不受偏移影响 |
| 无三点前奏 | 有首句时间与播放位置或宿主前奏状态的证据时，文档首句清晰，后续按原曲线模糊 |
| 三点延迟显示 | 三点实际绑定、显示且有尺寸后，平滑切到间奏距离；首句未高亮时基础目标为 8px，已高亮仍为 0 |
| 间奏 | 锚点为间奏后的第一条歌词序号；最近的已唱、待唱歌词分别按距离 1；三点自身不施加模块模糊 |
| Credits | 单独分类，继承文档末尾歌词的焦点强度 |
| 歌词结束 | 保留末句期间最后有效的焦点和模糊分布，不因高亮集合变空让整页清晰；文档变化与 Seek 清除旧焦点 |
| 用户浏览 | 触摸或用户拖动后平滑解除焦点模糊；长按与用户惯性滚动继续保持浏览模式 |
| 恢复跟随 | 手指离开且列表空闲 1 秒后，按最新显示状态平滑恢复；再次触摸或滚动撤销计时 |
| 程序滚动 | 宿主自动跟随不会主动进入浏览模式 |
| 平板边缘 | 上下 10% 区域、最大 5px；浏览模式保留边缘效果 |

前奏、间奏和高亮保护分开保存。前奏确认在文档变化和 Seek 时清除，在宿主实际显示行/词或进入其他间奏时更新；不能仅因空高亮集合就推断为前奏或间奏，也不能把第一条可见行伪装成高亮。

#### 7.1.2 分层与入口

| 层 | 职责与维护入口 |
|---|---|
| `core` | [LyricBlurState](../core/src/main/kotlin/dev/amenhancer/module/hook/LyricBlurState.kt)：事件、代际、归并、保护集合、焦点与交互；[BidirectionalBlurPolicy](../core/src/main/kotlin/dev/amenhancer/module/hook/BidirectionalBlurPolicy.kt)：原半径曲线、偏移、量化与插值 |
| `host-api` | [LyricBlurHostPort](../host-api/src/main/java/dev/amenhancer/module/hook/LyricBlurHostPort.kt)：文档、显示快照与行身份接口；不暴露宿主私有对象 |
| `host-applemusic` 契约 | [AppleMusicLyricBlurContract](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurContract.kt)：解析 profile、必需角色及类型关系；[HostPort](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurHostPort.kt)：读取宿主已经应用的显示状态 |
| `host-applemusic` 会话 | [Runtime](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurRuntime.kt)：Hook 与活动视图；[LyricBlurViewSession](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurViewSession.kt)：每个 root 的绑定、恢复任务和帧驱动 |
| `host-applemusic` 渲染 | [Renderer](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurViewRenderer.kt)：逻辑行动画与边缘几何；[EffectOwner](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurEffectOwner.kt)：效果写入与恢复 |
| `hook-runtime` | [HookRegistrationScope](../hook-runtime/src/main/java/dev/amenhancer/module/hook/HookRegistrationScope.kt)：PREPARING、激活与关闭 |
| 装配 | [AppleMusicHostFactory](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt) 创建唯一 [能力目标](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicBidirectionalLyricBlurTarget.kt)；`app` 保留设置、门禁与能力状态入口 |

Credits 与三点语义标记由 [LyricCreditsRowResourceHook](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricCreditsRowResourceHook.kt) 接入。字体、翻译、CJK 和双栏通过现有架构协作，模糊不依赖双栏开关或双栏安装成功。

仅混淆名变化、语义一致时，主要更新精确 profile。宿主提交时序、模型或生命周期变化时，修改相应 Contract、HostPort 或 Runtime；共享状态与渲染规则继续复用。新页面族必须重新分析，不能仅凭主版本号选旧接入。

#### 7.1.3 宿主契约与显示读取

必需角色由代码维护。新增依赖时同步更新 `AppleMusicLyricBlurContract`、当前 profile 和 `scripts/verify-host-profile.py` 的独立清单；JSON 少写一个角色不能绕过安装预检。

每个构建独立核对以下链路：

- Fragment 生命周期、binding、精确 RecyclerView、子项监听、触摸和滚动状态。
- 逐行与逐字 adapter 的提交、快照、位置映射、类型、稳定 ID、文档安装、绑定及回收。
- 文档 token/reader、native 行和词 ID、首句时间与当前媒体浏览器播放位置。
- 主词、背景词、发音与背景发音四路模型及实际文字动画、开始/结束/取消释放；纯装饰动画不延长保护。
- Native、VM 刷新及媒体控制器各个实际 Seek 入口。

方法记录实际声明 owner、参数、返回类型、static 和调用方式，字段记录 owner、完整类型及对象归属。沿实际 `extends` / `implements` 关系确认继承或接口成员；使用 DEX 的擦除签名，不把反编译工具推断的泛型、类型或修复名称当作契约。

| 身份或角色 | 用途 |
|---|---|
| `nativeLineId` | 文档内原生歌词身份，用于显示保护与文档映射 |
| `lyricOrdinal` | 文档内歌词序号，用于距离计算 |
| `adapterPosition` | 当前列表位置，经 adapter 映射后才能对应歌词 |
| `nativeLineId/nativeWordId` getter | 原生对象 ID 角色，与 adapter getter 分开登记 |
| `lineId/wordId` getter | adapter 稳定 ID 角色，用于核对当前 holder 与绑定身份 |
| `nativeLineBegin/nativeLineEnd/mediaBrowser/mediaPosition` | 首句时间、文档最大结束时间与当前播放位置；前奏和歌词结束所需证据 |
| `wordAnimationTag` | 当前构建的标记接口 getter；识别主动画中的纯位移子项 |

在 adapter 实际提交方法完成后重新读取其显示集合，包括宿主因输入是旧集合子集而跳过更新的情况。宿主等待 ItemAnimator、再向 Handler 提交期间，继续使用已应用的显示状态。Native 和 VM 事件可以触发刷新，不能直接把新 raw 集合替换为显示集合，也不能因 Native Hook 安装成功永久屏蔽其他来源。

词保护读取四路模型的主文字动画，递归排除 `KARAOKE_WORD_LIFT_TAG` 位移子项；`p` 中的辉光、缩放和回落不作为高亮证据。只观察相关文字子动画的开始、结束与取消，及时触发快照刷新；不等待整个装饰动画组结束，不使用固定 grace 或修改宿主动画。模型归属复用弱身份映射，避免绑定字段改变 `hashCode` 后丢失结束通知。保护集合与焦点独立：即使焦点进入间奏，仍在显示的高亮/词行保持目标 0。

Native vector、pointer 及包装对象在 Hook 当次同步解码，只向主线程提交复制出的数值。`Complete` 空集合、`Pending`、`Partial` 和 `Failed` 分别处理；不完整读取不能解释为空高亮。没有可信显示状态时平滑解除焦点模糊，等待下一次有效事件恢复。`NO_POSITION`、未知类型和身份不一致的行不参与距离计算。

#### 7.1.4 文档、视图与交互生命周期

每个歌词 root 有独立会话，全屏和右栏可同时存在。事件按适用的视图、文档、播放与交互代际校验；所有状态在主线程串行归并，一帧使用同一份状态与行快照。

文档安装、清空、adapter 替换及自定义 TTML 重装更新文档代际，即使宿主包装对象引用未变化。Seek 更新播放代际，清理旧词流和过渡；集合缩小不能作为 Seek 证据。

末句进入有效高亮后记录当前焦点；后续真实高亮继续更新它，显示集合正常清空后转为独立 `Ending` 焦点，保留原曲线与清晰锚点的边缘保护。暂停打开或重建时，可用完整文档的最大 `getEnd()` 与当前播放位置确认结束，再使用末句焦点。读取失败不当作正常结束，文档变化和 Seek 清除旧记录；手动浏览与 1 秒恢复规则继续生效。

动画按逻辑行保存。同一歌词再次出现可衔接原进度；View 换绑时撤销旧租约，按新行状态初始化，不继承旧半径。首次取得 adapter、文档、binding 或行布局就安排首帧，包括暂停时打开歌词；初始化等待仅在后续宿主事件重读，不增加固定延迟轮询。

观察原生触摸与滚动方法，不替换 `OnTouchListener`，不消费事件。浏览期间高亮更新只更新状态；恢复任务携带会话和交互代际，再次触摸、继续滚动、换歌或销毁均使旧任务失效。

关闭视图取消帧回调、恢复任务和监听，释放仍拥有的效果。旧会话回调不得操作新视图。

#### 7.1.5 渲染、所有权与安装

焦点半径和边缘保护权重分别按 300ms 动画计算：

```text
最终半径 = 量化并限制到 0–32(
    max(动画中的焦点半径, 当前边缘半径 × 动画中的边缘权重)
)
```

边缘使用经过行 View 变换、位移与列表滚动的矩形，视口扣除 padding。几何更新不重启焦点动画。每个会话保留一个帧驱动，合并脏状态与动画推进；动画结束且状态、几何不变时停止请求连续帧。`DECAL` 效果最多缓存 32 个，量化半径及宿主效果版本不变时不重复写入。

存在模块模糊时，由渲染会话持有列表裁剪租约：关闭 RecyclerView 的逐子项边界裁剪，让模糊自然扩散，避免短小的 Credits 行出现矩形硬边；列表自身仍按当前视口与宿主显式裁剪范围的交集裁剪，原有 padding 和外层渐隐保留。尺寸或宿主裁剪范围变化时更新，不改变行高、间距、字体、透明度或原半径曲线；半径全部归零、行释放、列表替换或会话关闭时归还仍拥有的裁剪属性。实现只使用 SDK 属性，由各精确版本共用，不增加混淆成员契约。

Android 12 起在歌词视图创建前观察 SDK `View` 构造和 `setRenderEffect` 写入，记录最新已知宿主效果；模块写入带重入标记并与宿主效果合成。释放只撤销仍由模块拥有的最后写入，恢复已知的最新宿主效果。初始效果未知的既有 View 不强行接管，不使用效果 getter、隐藏 API 或 `alpha = 1f` 清理。

安装顺序固定为：精确版本资格与 Android 门禁 → 解析全部必需契约 → PREPARING scope 注册 Hook 和清理 → 全部成功后 activate → 返回能力状态。必需解析或注册失败关闭整组，报告降级并保留宿主原生行为。

#### 7.1.6 诊断

沿用 `AMPP-LYRIC-PROBE` 入口，DEBUG 启用后才输出详细事件。每会话最多 128 条，ID/行观察最多 32 项，重复摘要与失败原因合并限量。记录构建、来源、各代际、行映射、保护集合、焦点、模式、目标/实际焦点半径及动画时间，不记录歌词文本。

逐项核对及人工场景见 [清单：歌词模糊](host-adaptation-checklist.md#lyrics-blur-checks)。

<a id="cjk-animation"></a>

### 7.2 CJK 辉光与末词扫光适配

#### 7.2.1 功能范围与开关

现有 `cjkKaraokeAnimationEnabled` 同时控制 CJK 辉光和末词扫光补偿，不增加配置键或独立开关。修改设置后按原安装方式重启宿主生效；关闭时不安装这两部分 hook。

辉光沿用单个、未合并 CJK 词的原生触发门槛、前景绑定、回落和结束清理。末词补偿依据原生词序列识别尾词，不额外要求该尾词触发长尾辉光。

末词补偿只调整逐字内部渐变终点及其配套的延迟补亮动画，不修改原生逐字时间、词分组、整行切换、滚动或位移。本实现不包含词动画防中断、重新绑定保护或换行遮罩修正；跨行遮罩修复是独立能力，见 [7.3 跨行歌词扫光](#lyric-wrap-sweep)，不受本开关、本契约或本注册作用域控制。

#### 7.2.2 代码与安装链

| 层 | 入口 | 责任 |
|---|---|---|
| `core` | [CjkTerminalGradientPolicy.kt](../core/src/main/kotlin/dev/amenhancer/module/hook/CjkTerminalGradientPolicy.kt) | 羽化终点余量规则，不依赖 Android 或宿主成员 |
| `host-applemusic` | [AppleMusicCjkTerminalGradientTarget.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCjkTerminalGradientTarget.kt) | `TerminalGradientContract`、末词识别、原生扫光 hook 和失败释放 |
| `host-applemusic` | [AppleMusicCjkKaraokeAnimationTarget.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCjkKaraokeAnimationTarget.kt) | 辉光安装成功后安装末词补偿，合并能力状态 |
| `host-applemusic` | [AppleMusicHostFactory.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt) | 将当前精确 profile 和宿主 ClassLoader 注入补偿能力 |
| `app` | [CjkKaraokeAnimationFeature.kt](../app/src/main/java/dev/amenhancer/module/hook/CjkKaraokeAnimationFeature.kt) | 读取现有开关，决定是否安装整个 CJK 能力 |

末词契约在安装前通过 `FragmentProfileMembers` 解析完整成员清单。两个新 hook 使用同一 `HookRegistrationScope`，全部注册完成后才激活；失败关闭 scope，部分注册的回调保持失活。

#### 7.2.3 末词补偿规则

渐变的完全高亮边缘落后于扫光前缘一个羽化宽度。终点刚好停在字形右端时，羽化尾部尚未通过末字，末字会留下未完全高亮的区域。

对于合法的 CJK 尾词扫光，终点余量改为 `max(original, feather)`。仅接受有限数值及 `0 < feather <= 1`；原生余量已经更大时保留它，无效几何保持原生结果。

末词选择读取当前 reader 对应行的完整原文和原生发音向量，分别选出合法 `end` 最大的词 ID；相同 `end` 取向量中后出现的词。向量大小必须在 `2..4096`，词时间必须满足 `begin >= 0` 且 `end > begin`。两路 ID 独立读取，结果按 reader 弱引用与行 ID 缓存。

补偿只作用于当前 `d0` 调用中的对应 CJK 词，保留第八个参数（零基索引 7）为 `true` 的原生分支和 Latin 文本。`c0` 的词对象必须与当前扫光词一致；线程作用域在嵌套调用和异常退出后恢复。

有效几何补偿发生后，从 `d0` 的动画返回列表移除标签为 `KARAOKE_SPLIT_ON_CHARS_LANGUAGES_RUSH_GRADIENT_TAG` 的延迟动画，避免已完成的遮罩被随后补亮覆盖。其他动画及原生逐字时长保留；未发生有效补偿时保留原动画列表。

#### 7.2.4 精确版本契约

目前仅以下档案声明 `cjk.terminalGradient`：

| 宿主 | profile | 契约 |
|---|---|---|
| `7.0.0-beta / 1606` | [7.0.0-beta-1606.json](../host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1606.json) | `contractVersion = 1` |
| `7.0.0-beta / 1609` | [7.0.0-beta-1609.json](../host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1609.json) | `contractVersion = 1` |
| `7.0.0-beta / 1611` | [7.0.0-beta-1611.json](../host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1611.json) | `contractVersion = 1` |

缺少本契约的已登记版本保留原有辉光，安装信息明确报告末词补偿不支持。已声明契约但成员解析或注册失败时，CJK 能力报告降级，已安装的辉光继续运行。未知版本不借用这两个档案。

以下简写仅用于阅读：`A` 为 `com.apple.android.music.player.A`，`Word` 为 `com.apple.android.music.player.viewmodel.PlayerLyricsViewModel$e`，`Flex` 为 `com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout`，`Mask` 为 `Flex$a`。新版本必须核对完整 owner、参数、返回值和静态属性。

| 角色 | 1606 / 1609 成员或关系 |
|---|---|
| `build` | 实例 `A.d0(Integer, Mask[], Word, List, Flex, boolean, boolean, boolean, int): ArrayList` |
| `margin` | 静态 `A.c0(float, Flex, Mask, Word, ViewDataBinding, boolean): float` |
| `reader` | 实例字段 `A.p: com.apple.android.music.ttml.j` |
| `rowId` / `wordId` / `text` | `Word.a: int` / `Word.b: int` / `Word.c: String` |
| `readerLine` / `lineGet` | reader 的 `a(int)` 返回 `LyricsLine$LyricsLinePtr`，其 `get()` 返回原生行 |
| `lineWords` / `linePronunciationWords` | 原生行的 `getWords()` / `getPronunciationWords()` 返回 `LyricsWordVector` |
| `vectorSize` / `vectorGet` / `wordGet` | `size(): long`、`get(long)` 返回词指针、指针 `get()` 返回原生词 |
| `wordBegin` / `wordEnd` / `nativeWordId` | `LyricsTiming.getBegin()/getEnd(): int`、原生词 `getWordId(): int` |
| `animationTag` | `N7.c.getTag(): Object`；接口方法以 `invocationOnly` 解析 |

`TerminalGradientContract` 固定要求上述 13 个方法角色和 4 个字段角色，JSON 漏写任何角色均不能通过安装前解析。辉光使用的 `cjk.foregroundTextField` 和 `bindingOwners` 继续独立核对，不由末词契约代替。

#### 7.2.5 后续版本适配

1. 核对精确宿主身份和原生扫光调用链，确认 `build`、`margin` 仍负责相同的几何计算；不能只凭名字或参数数量复制档案。
2. 更新 `cjk.terminalGradient` 的全部描述符，确认 reader 按行取词、词 ID 和词时间的含义，以及原文/发音的独立 ID。
3. 核对第八个参数的分支语义、羽化宽度单位、延迟动画标签和标签接口。若参数语义变化，连同 Kotlin 分支规则一起适配。
4. 确认文档替换、词序列或发音更新时 reader 的替换方式。若新宿主复用同一 reader 并改写词数据，需要增加相应缓存失效入口。
5. 核对完整必需角色清单与安装状态；使用离线接口工具时确认它显式覆盖本契约，其他区域的检查结果不能代替本项结果。
6. 由使用者手动验收，并按实际宿主和场景记录结果。仅编译成功或静态成员匹配不等于真机动画通过。

末词羽化规则、原生尾词选择和延迟补亮处理源自 Tcrrry-Lyrics-Plugin 1.0.6 的 `NativeTerminalGradientFix.kt`（GPL-3.0）。AM++ 增加精确成员契约、原子注册及现有 CJK 开关联动，详见 [第三方来源说明](../THIRD_PARTY_NOTICES.md)。

逐项核对及人工场景见 [清单：CJK 辉光与末词扫光](host-adaptation-checklist.md#cjk-animation-checks)。

<a id="lyric-wrap-sweep"></a>

### 7.3 跨行歌词扫光适配（独立能力）

宿主原生逐字高亮按 TTML 词处理；词跨多个视觉行时几何按行计算，后续行容易落后或残留暗边。本能力在保留宿主原生 `ValueAnimator`/`AnimatorSet` 与歌词身份的前提下，只修正跨行词的遮罩几何与进度。

它是独立能力：使用自己的能力集合、契约区域、注册作用域与安装状态，不读取 `cjkKaraokeAnimationEnabled`，不依赖 `cjk.terminalGradient` 的安装结果。两者会接入同一个原生 `d0`，但契约和作用域独立：单行尾词保留 7.2 补偿；跨行接管成功后由本节更新各行遮罩及片段羽化，原生单行更新被抑制，避免叠加写入。能力键为 `ModuleConstants.FEATURE_LYRIC_WRAP_SWEEP`（`lyric_wrap_sweep`），由 `LyricWrapSweepFeature` 通过能力接口安装，没有独立设置项。

模块启用后，仅当当前精确 profile 在顶层声明 `lyricWrapSweep` 且 `contractVersion = 1` 时默认安装，无需手动开启；未声明的已登记版本明确报告不支持并保留原生渲染。当前声明本契约的版本为 `7.0.0-beta / 1609` 与 `7.0.0-beta / 1611`。

#### 7.3.1 代码与安装链

| 层 | 入口 | 责任 |
|---|---|---|
| `core` | [LyricWrapSweepPolicy.kt](../core/src/main/kotlin/dev/amenhancer/module/hook/LyricWrapSweepPolicy.kt) | 跨行片段计划、局部进度、边缘终点、词进度、渐变位置与跨 span 交接边界；不依赖 Android 或宿主成员 |
| `host-api` | [TargetAdaptation.kt](../host-api/src/main/java/dev/amenhancer/module/hook/TargetAdaptation.kt) | `LyricWrapSweepTarget` 能力接口与默认不支持实现 |
| `host-applemusic` 契约 | [LyricWrapSweepContract.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricWrapSweepContract.kt) | 解析 `lyricWrapSweep` 成员、校验类型关系、读取媒体浏览器位置并识别延迟补亮动画 |
| `host-applemusic` 几何 | [LyricWrapSweepGeometry.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricWrapSweepGeometry.kt) | 视觉行识别、跨行判定、弱引用几何校验与多遮罩写入 |
| `host-applemusic` 目标 | [AppleMusicLyricWrapSweepTarget.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricWrapSweepTarget.kt) | Hook 注册、接管与退役、静态帧缓存、布局失效与失败释放 |
| `host-applemusic` 装配 | [AppleMusicHostFactory.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt) | 注入当前精确 profile 与宿主 ClassLoader |
| `app` | [LyricWrapSweepFeature.kt](../app/src/main/java/dev/amenhancer/module/hook/LyricWrapSweepFeature.kt) | 通过能力接口安装并上报能力状态 |
| `hook-runtime` | [HookRegistrationScope](../hook-runtime/src/main/java/dev/amenhancer/module/hook/HookRegistrationScope.kt) | PREPARING、激活与关闭 |

契约在安装前由 `FragmentProfileMembers` 解析完整必需角色；全部注册成功后才 activate。必需解析或注册失败时关闭 scope、报告降级，并保持宿主原生渲染。

#### 7.3.2 接管规则与时间轴

模块不新建动画器或全局计时器。仅在动画器已注册、其更新监听器的遮罩身份与注册来源遮罩一致、且 RTL 方向与注册方向一致时，才抑制原生单行写入并改为多遮罩写入；其余情况保持原生回调。同一 flexbox 与同一 bank 只保留最新注册的接管：注册新词时，先前同 flexbox 同 bank 的接管立即退役。

进度使用同一毫秒时间轴：词起止来自宿主词对象的 `begin`/`duration`，播放位置来自原生媒体浏览器 `getCurrentPosition`。初始与每帧进度都读取 `(position - begin) / duration` 的限幅值；同一接管内只前进，原生完成 tick 完成整词，向后 Seek 后由新接管重新计算较低进度。仅在几何已就绪、词已开始且尚未结束（`position >= begin` 且初始进度 < 1）时，把动画时长最多缩至实际剩余段；延迟布局不先改时长。不把 JNI 事件 duration 或原生 listener 的行内横坐标误当整词进度。暂停与恢复作用于该词原生 `AnimatorSet`，保留其子动画归属。

进度按实际视觉行字形宽度累计：每个视觉行按其在 flexbox 中的归一化左右边界构成片段，局部进度与边缘终点由累计宽度决定，完全高亮的边缘在本行完成时到达片段末端。多行结束时随行尾字形宽度的羽化已包含在该终点计算内，不需要额外补亮动画。

#### 7.3.3 遮罩、静态恢复与生命周期

| 时机 | 处理 |
|---|---|
| 宿主 `build` 之后 | 仅当词实际跨多个视觉行时注册接管；保留原生扫光及延迟单行补亮动画的调度。主扫光每次先释放当前接管标记，成功绘制后才重新持有；`rushUpdate` 仅在仍持有且上次绘制的几何仍有效时抑制单行补亮写入，失败时保留原生回调 |
| 跨行词完成绘制后 / `maskUpdate` 写入 | 只有跨行几何实际完成绘制且颜色为方向一致的四 stop 渐变时，才为其遮罩记录已亮边界。后继原生扫光、补亮与静态写入只在原几何身份仍有效时约束两个 stop：LTR 不后退，RTL 不前移；写入成功后才更新记录。颜色、`mask.i`、原生动画时间与调度保留 |
| 宿主 `staticMask`（`W`）之前 | 词为空、未开始、媒体位置不可用或前缀调色板为原生暗色时，先清除对应 flexbox/bank 的交接保护，再让原版绘制；不能让上一段亮色边界覆盖未来行或暗色状态 |
| 宿主 `staticMask`（`W`）之后 | 先清除同 flexbox 同 bank 的旧静态帧；词为空、未开始、前缀调色板为原生暗色或形状不支持时保留原生结果。其余已开始的跨行词保存静态帧；同 flexbox 同 bank 有活动接管时，按该接管的最新进度、颜色与羽化重绘；无活动接管时按当前播放位置重建，羽化按当前宽度重算 |
| 宿主 `fadeUpdate`（`A$o.onAnimationUpdate`）之前 | 通过弱引用记录的遮罩与 flexbox/bank 对应关系核对当前遮罩身份，清除该 bank 静态帧和交接保护并退役旧扫光与补亮写入；原版渐暗回调继续执行。过期遮罩不撤销新绑定的接管 |
| 宿主 `rebind`（`i0`）前后 | 前置核对 holder 四路词 Map 中的对象身份，退役已不属于当前行的接管/静态帧/交接保护并使几何失效；后置重绘保留的静态帧 |
| 布局变化 | 对应 flexbox 的布局变化清除交接保护，使缓存几何失效并按当前状态重绘 |
| `bind` / `recycle` 之前 | 退役绑定根所属的接管与静态帧，清除其交接保护 |
| `document` 之前 | 退役该 adapter 的接管与静态帧，清除其交接保护 |
| `clearMasks` 之前 | 退役该 flexbox 的接管并清除其静态帧及交接保护 |
| `seek` 之前 | 退役该媒体浏览器的接管与静态帧，清除交接保护，取消该词原生 `wordAnimator` 释放 `word.o` 去重门禁，使新位置可重新建立动画 |
| `pause` / `pauseRequested` / `resume` 之后 | 同步所属 adapter 的原生词动画集暂停或恢复，发音子动画缺少独立 owner 时使用其原生扫光动画 |
| 作用域关闭 | 清空接管、静态帧、交接保护与遮罩归属，移除布局监听 |

退役后的回调不得写入新绑定的行或替换后的文档；被退役的动画器不再产生原生单行写入。

`W` 同时服务于已唱词恢复和未来歌词行的初始化，不能把该方法被调用当作词已开始的依据。原生初始化可用该行最后一个词及暗色前缀绘制未唱状态；模块仅在媒体位置不早于词 `begin` 时重建跨行静态帧。未到开始时间时保留原生暗色，不能把限幅后的零进度映射到 span 起点并点亮其前缀。缓存帧在每次重绘前重新检查开始时间；未来词或无效绑定替换旧状态时，应清掉对应 bank 的旧帧，避免重绑定与布局回调再次覆盖原生结果。

词已开始或已结束也不代表该行应保持高亮。静态恢复必须读取 `W` 的前缀调色板（第 4 个参数），并按原版内容比较规则识别前景/背景暗色数组；原生暗色状态不保存亮色静态帧。退场渐暗绕过 `W` 直接写遮罩，必须由精确 `fadeUpdate` 入口撤销缓存及旧动画写入权限，避免后续布局或重绑定恢复亮色。入场渐亮的首帧也可能使用暗色前缀，不能仅凭该前缀退役活动扫光；退场以专用回调为准，亮暗曲线继续由宿主控制。

跨行词最后片段结束后，下一 span 可能只占一个视觉行并回到原生扫光。跨行片段把完全亮色 stop 推至自身终点，但原生 `mask.i` 仍保存限于该片段的前沿；原生后继按前沿减羽化宽度绘制，可能使前一段尾部重新进入渐变。交接保护在已完成的跨行遮罩上记住两个实际绘制 stop，并以几何快照校验宽度、文本/Layout 和遮罩身份，只让后续兼容方向的四 stop 渐变继续前进。后继仍使用原版动画；不通过抬高 `mask.i` 消耗短 span 的扫光余量，也不全局关闭羽化。纯暗色写入释放该遮罩保护，其他不兼容颜色/位置形状保持原生。

#### 7.3.4 几何语义与已知边界

遮罩的键是视觉 Flexbox 行的首个 child 索引（精确档案的 `maskFirst`，即 `Flex$a.c`），`maskLast`（`Flex$a.d`）界定该行 child 范围；`topMasks`/`bottomMasks`（`Flex` 的 `T`/`U` 字段）按该键索引。它不是 TTML `lineId`、歌词序号或 adapter position。绑定到同一遮罩行的多个视觉片段按字形宽度并集累计。

同一 flexbox 内，`TOP` / `BOTTOM` 是原文与发音两个垂直遮罩 bank（不是前景与背景）：`staticMask` 的位置枚举决定使用哪个 bank，`build` 一侧由来源遮罩落在哪个 bank 判定。前景与背景是两个独立 flexbox（当前版本为原生 `p9.Y` 与 `p9.X`），二者不共享同一组 bank。模块只接管该词所在的 flexbox 与其 bank，另一 bank 及另一 flexbox 保持宿主原生。

以下情况不接管多行扫光并保留宿主原生动画（fail-open）：单行词、片段数少于 2、无法构造单行文本片段（文本宽度、行数或布局无效）、宽度或边界无效、绑定与遮罩行对应不唯一。单行后继只有共享已完成跨行词的有效遮罩时才受交接边界保护；其他单行写入透传。混合方向复合形状（同一视觉行在文本顺序中被重复访问）无法用单一遮罩表示，同样保持原生。几何在宽度、布局或绑定身份变化后失效并重绘。

跨行计划生成的两个羽化 stop 与写回 `mask.i` 的边缘值均钳制在该片段边界内，完成当前词不会提前点亮下一词。交接层只保留已经实际写入的后继边界，防止旧静态刷新拉回已经前进的扫光；不凭歌词长度预测未来边缘。文本内容与原生词文本、TextView 内容及 Layout 身份均参与复用校验。一个 TextView 自身包含多行的复合形状当前保留原生，不能作为本能力已修复的场景。

#### 7.3.5 精确版本契约

目前仅以下档案声明顶层 `lyricWrapSweep`：

| 宿主 | profile | 契约 |
|---|---|---|
| `7.0.0-beta / 1609` | [7.0.0-beta-1609.json](../host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1609.json) | `contractVersion = 1` |
| `7.0.0-beta / 1611` | [7.0.0-beta-1611.json](../host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1611.json) | `contractVersion = 1` |

缺少本契约的已登记版本报告不支持并保留原生渲染；已声明契约但成员解析或注册失败时报告降级，同样保留原生行为。未知版本不借用 1609 档案。

以下简写仅用于阅读：`A` 为 `com.apple.android.music.player.A`，`Word` 为 `com.apple.android.music.player.viewmodel.PlayerLyricsViewModel$e`，`Flex` 为 `com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout`，`Mask` 为 `Flex$a`，`Anim` 为 `com.apple.android.music.player.t`，`Holder` 为 `com.apple.android.music.player.f1$b`，`Callback` 为 `com.apple.android.music.player.fragment.N`，`Browser` 为 `J4.B`。新版本必须核对完整 owner、参数、返回值和静态属性。

| 方法角色 | 1609 成员 |
|---|---|
| `build` | 实例 `A.d0(Integer, Mask[], Word, List, Flex, boolean, boolean, boolean, int): ArrayList` |
| `update` | 实例 `Anim.onAnimationUpdate(ValueAnimator): void` |
| `rushUpdate` | 实例 `com.apple.android.music.player.u.onAnimationUpdate(ValueAnimator): void` |
| `fadeUpdate` | 实例 `A$o.onAnimationUpdate(ValueAnimator): void` |
| `staticMask` | 实例 `A.W(Word, List, Flex, int[], int[], boolean, Flex$b): void` |
| `rebind` | 实例 `A.i0(A$a, int): void` |
| `bind` | 实例 `A.k(RecyclerView$D, int): void` |
| `recycle` | 实例 `A.r(RecyclerView$D): void` |
| `document` | 实例 `A.R(SongInfo$SongInfoPtr): void` |
| `clearMasks` | 实例 `Flex.v(): void` |
| `textView` | 静态 `A.v0(androidx.databinding.ViewDataBinding): CustomTextView` |
| `maskUpdate` | 实例 `Mask.b(int[], float[], Float): void` |
| `animationTag` | `N7.c.getTag(): Object`；以 `invocationOnly` 解析 |
| `mediaBrowser` | 实例 `ia.a$c.getMediaBrowser(): J4.w` |
| `mediaPosition` | 实例 `Browser.getCurrentPosition(): long` |
| `seek` | 实例 `Browser.seekTo(int, long): void` |
| `pause` / `pauseRequested` / `resume` | 实例 `A.I(): void` / `A.J(): void` / `A.K(): void` |

| 字段角色 | 1609 成员 |
|---|---|
| `topMasks` / `bottomMasks` | `Flex.T` / `Flex.U`：`SparseArray` |
| `maskFirst` / `maskLast` | `Mask.c` / `Mask.d`：`int` |
| `wordBegin` / `wordDuration` | `Word.d` / `Word.e`：`int` |
| `wordText` / `wordAnimator` | `Word.c`：`String` / `Word.o`：`Animator` |
| `words` / `backgroundWords` / `pronunciationWords` / `pronunciationBackgroundWords` | `A$a.G` / `H` / `I` / `J`：`ArrayMap` |
| `bindingRoot` | `androidx.databinding.ViewDataBinding.d`：`View` |
| `holderBinding` | `Holder.u`：`ViewDataBinding` |
| `adapterCallback` / `callbackFragment` | `A.y`：`fragment.N` / `Callback.a`：`PlayerLyricsViewFragment` |
| `featherPixels` | `A.b0`：`float` |
| `listenerMask` / `listenerColors` / `listenerFeather` / `listenerRtl` | `Anim.f`：`Flex$a` / `Anim.g`：`int[]` / `Anim.e`：`float` / `Anim.a`：`boolean` |
| `foregroundInactiveColors` / `backgroundInactiveColors` | `A.P` / `A.Y`：`int[]`；原版前景/背景暗色调色板 |
| `fadeMask` | `A$o.f`：`Flex$a`；原版退场渐暗写入的遮罩 |

除角色本身外，安装前还校验以下类型关系，缺少任一项即视为契约不成立：

1. `build`、`rebind`、`adapterCallback` 同一 owner；`build` 的第 3 个参数类型与 `wordBegin`/`wordDuration` 的 owner 一致。
2. `clearMasks` 声明在 `ViewGroup` 子类上；`topMasks`/`bottomMasks` 与 `clearMasks` 同 owner。
3. `maskUpdate`、`maskFirst`、`maskLast` 同 owner（`Mask`），`maskUpdate` 的参数严格为 `int[]`、`float[]` 和可空装箱 `Float`；第二个参数是 shader stop，第三个参数更新原版 `mask.i`。
4. `listenerMask` 的 owner 与 `update` 相同，且其类型等于 `maskUpdate` 的 owner。
5. `update` 的唯一参数为 `ValueAnimator`；`bindingRoot` 的类型是 `View` 子类。
6. `callbackFragment` 的 owner 等于 `adapterCallback` 的类型；`mediaBrowser` 的 owner 可由 `callbackFragment` 类型赋值；`mediaPosition` 的 owner 可由 `mediaBrowser` 的返回类型赋值。
7. `rushUpdate` 的唯一参数为 `ValueAnimator`；词文本、词动画与词时间字段同 owner；四路词 Map 声明于 `rebind` 的 holder 参数类型，字段为 Map 子类。
8. `fadeUpdate` 的唯一参数为 `ValueAnimator`；`fadeMask` 与该回调同 owner，字段类型等于 `maskUpdate` 的 owner；两路暗色调色板与 `build` 同 owner，字段类型为 `int[]`。

`LyricWrapSweepContract` 固定要求上述 19 个方法角色和 24 个字段角色，另要求 `contractVersion = 1` 且档案 `productionEnabled`；JSON 漏写任何角色均不能通过安装前解析。`scripts/verify-host-profile.py` 与 `scripts/verify-profile-data.py` 的必需角色清单应与本契约同步维护。

#### 7.3.6 后续版本适配

1. 核对精确宿主身份与原生扫光调用链，确认 `build`、`update`、`staticMask` 仍负责相同的跨行几何和动画更新，不能只凭名字或参数数量复制档案。
2. 更新 `lyricWrapSweep` 的全部描述符，确认视觉行遮罩键仍是首个 child 索引、`maskLast` 仍是该行范围，以及原文/发音两个 bank 的字段。
3. 核对词对象的时间字段单位与 `begin`/`duration` 语义，确认媒体浏览器的播放位置与词时间处于同一毫秒时间轴；不一致时连同进度与时长规则一起适配。
4. 核对延迟补亮动画的标签接口、取值与精确更新回调，确认抑制写入只针对当前仍有效持有遮罩的接管；主扫光失败或上次绘制几何失效时须透传原生补亮。标签语义变化时同步修改 Kotlin 判断。单行尾词仍由 7.2 补偿处理，跨行词使用本节片段羽化，不能叠加两套遮罩写入。
5. 核对 `staticMask` 前缀参数与前景/背景暗色调色板的比较规则，以及退场渐暗绕过静态入口时的实际更新回调和遮罩字段；区分入场暗色首帧与退场状态，保留宿主亮暗曲线。
6. 核对后继 span 的主扫光、补亮和静态恢复是否均经 `maskUpdate` 写入；确认四个 stop 为 `0, s1, s2, 1`，LTR 颜色为高 alpha / 高 alpha / 低 alpha / 低 alpha，RTL 顺序相反，以及 `mask.i` 的原生前沿语义。若形状或语义改变，连同交接保护一起适配，不能直接扩大原生进度。
7. 核对换绑、回收、文档替换、清空遮罩、布局变化、暗色写入和 Seek 的实际入口，确认退役与静态帧/交接保护失效仍然覆盖；新增入口需要同步代码与档案。
8. 由使用者手动验收，并按实际宿主和场景记录结果。仅静态成员匹配或构建成功不等于真机通过。

逐项核对及人工场景见 [清单：跨行歌词扫光](host-adaptation-checklist.md#lyric-wrap-sweep-checks)。各版本的实际验收结果应记录于对应PR或独立验收记录，手册和清单保持通用维护规则与验收标准。

### 7.4 其他功能与共同规则

| 功能 | 必须保持的业务与接入规则 |
|---|---|
| 当前歌曲身份 | 元数据转换器、当前 item 和歌曲 ID 对应实际播放对象；异步结果再次核对当前歌曲 |
| 自定义歌词 | 原生 TTML 解析、有效指针检查、普通/无官方歌词歌曲、多 ID、禁用优先级、异步重应用和重复注入抑制 |
| 自动补全 | 既有触发条件、来源顺序、手动/禁用优先级、代次控制、重试和缓存策略；切歌后旧结果不能注入 |
| 字体 | TTF/OTF、后台加载、重试、行词一致、视图重建和原字体恢复；区分 Hook 安装与字体就绪 |
| 名称修正 | 原地区/cn/jp 模式、原名与标识证据、目录查询、离线及持久缓存、显示优先级；各表面单独安装与报告 |
| 玻璃 | Android API、精确资格、导航/mini 区域、采样、触摸、展开过渡和属性恢复；材质准备完成后接管原生呈现 |
| 蜂窝入口 | 精确能力资格、原生登录/类别逻辑、availability 接入和默认开关；不使无资格构建自动支持 |
| DPI 与数据 | 仅宿主范围、冷启动和配置重建、失败失活；配置键、文件 ID、目录、SQLite、缓存 namespace 和 ZIP 格式保全 |
| 视频与背景 | 页面族自己的原生视频策略、尺寸/比例、暂停缩放、表面切换、shader/背景清理和后台恢复 |

名称修正至少覆盖播放器、队列、历史、操作表、最近搜索、资料库、专辑、歌单、艺术家和主页。除目标方法外，还需核对模型绑定、Epoxy holder、LiveData、辅助 getter 和实际封面加载路径。一个表面成功不能代表整项能力成功。

目录查询需核对实际接口、请求结构和后续返回路径；Compose/LiveData 需核对当前类型别名及真实参数类型。名称相同或反射调用成功不足以确认它们仍服务于同一表面。

<a id="artist-compose-profile"></a>

### Compose 艺人页名称修正

1607 与 1609 的艺人页由 `profiles2.GenericProfileViewModel` 发布不可变 Success 状态。本接入沿用 HLE 的 `AppleArtistSurfaceHost`、entity/alias、歌曲地区证据、元数据应用和刷新队列；宿主私有对象留在 `host-applemusic`。没有新增艺人查询通道，也不根据艺人名称推断语言或替换 logo。

`ARTIST_SURFACE_CLASSES` 的 `compose_profile_view_model` 和 `compose_profile_success_state` 是表面声明；完整调用信息集中在 `artistComposeProfile`。两个角色或本区域中任意一项已声明，就尝试安装独立的 `hle.artist-compose-profile` 组。缺少另一角色、契约或实际成员时报告降级；完全未声明的旧档案不安装该表面。角色缓存只有在当前档案的全部声明解析成功后才完成。

| 必需契约角色 | 应核对的内容 |
|---|---|
| 方法 `setUiState` | 实际声明 owner、状态参数、void 返回及实例调用；Success 可赋给该参数，不猜测其直接父类 |
| 方法 `headerData` / `shelves` | 以 MediaEntity 为参数；返回类型分别与 Success 的 header/shelves 字段一致 |
| 方法 `entityType` / `entityTitle` / `entityViews` | 实际 MediaEntity 的 String/String/Map getter 完整签名 |
| 方法 `viewEntities` | 当前 views map 值对象的实例 getter，返回 MediaEntity[]；1609 的泛型签名确认值为 `internals.Relationship` |
| 字段 `id` / `title` / `header` / `shelves` / `entity` / `revision` | 六个不同的 Success 实例字段，完整类型分别与构造器和 builder 一致 |
| 构造器 `success` | 当前 Success owner 和完整参数顺序；契约 v1 为 id、title、header、shelves、entity、long revision |
| `artistType` / `topSongsView` | 当前包中的艺人类型与热门歌曲 key；现有档案为 artists / top-songs |

`ArtistComposeProfileContract` 复用现有成员解析器，在注册前解析并校验全部句柄、静态/实例、返回值、字段及类型关系。`REQUIRED` 和 `scripts/verify-host-profile.py` 的独立清单由代码维护；JSON 漏写角色不能绕过预检。`verification` 同步登记附属 getter。views 路径按已声明的值类型调用，不猜测 `getData` 回退。

`ArtistComposeProfileSessions` 管理弱 owner、当前 Success、艺人/歌曲引用和 pending 刷新。每次原生 Success 替换都会关闭旧会话并取消其排队任务；loading/error、非艺人或缺少 ID/属性同样清理。帧队列以会话身份区分工作，执行前确认会话仍属于活动安装组。作用域关闭只清理该组的引用、状态与待执行任务，已从队列取出的旧任务也会因会话失活而跳过。

alias 到达或恢复原名时，按当前实体重建 title/header/shelves 并发布 Success；v1 保留原生 ID 和 revision。后续版本若调整状态布局、构造参数顺序、相等性、发布线程或生命周期，需要重新分析对应 Contract/Runtime/Session，不能只改混淆名。仅名称或类型别名变化时优先修改精确档案。适配覆盖检查从 `index.json` 遍历，不写死版本文件列表。

1607 与 1609 均保留 `runtimeVerified=false`。本次新增 views/getter 契约的包内依据来自本地 1609 DEX；1607 使用同名 MediaAPI 和其原有 `profiles.X` 头部映射，新增 getter 仍需对照 1607 安装包。人工场景见[清单中的 Compose 艺人页检查](host-adaptation-checklist.md#artist-compose-profile-checks)，结果按具体版本单独填写。

旧 Activity Editorial 处理和 Fragment 原生视频策略分别保留。停用的全局目录语言功能及未启用的播放模式 UI 不因新增档案自动获得启用。

## 8. 平板布局与视频尺寸

### 8.1 资格与基础布局

双栏使用既有门禁：精确版本、开关、横屏、宽度至少 600dp，以及原生 `useNavigationDrawer`。同时核对 DPI 覆盖和多窗口对有效宽度的影响。

当前共享呈现规则如下：

| 项目 | 布局要求 |
|---|---|
| 分区 | 名义分隔位置为播放器宽度的 45%，歌词视口位于右侧 55% 分区 |
| 左侧留白 | 标题/进度内容到屏幕左边缘 50dp |
| 右侧留白 | 标题/进度内容右端到歌词正文起点 50dp |
| 歌词留白 | 歌词自己的 margin、padding 和右侧边界沿用原生 |
| 左栏对齐 | 标题、进度、音质标识、播放按钮和封面使用一致的水平中心 |
| Fragment 控制区 | SONG / QUEUE 控制区为原生容器高度的 25% |
| 音量 | Fragment 的整个控件含两端图标与进度内容等宽，轨道内部每侧保留40dp图标空间 |
| Fragment footer | 被替代的原生动作保留布局但不可见且不接收输入，模块音量及右下代理使用当前有效位置 |
| Fragment 代理 | 歌词/队列代理沿用原生 drawable、selector、tint及动作，44dp点击区域、8dp间隔、底部12dp |
| 播放反馈 | 原生图标尺寸保持，Fragment水波纹为图标最大半径外加8dp、白色20%不透明度 |
| 原生动作 | SONG / QUEUE、播放、搜索、抽屉和队列操作保留原生语义 |

50dp 的右侧测量终点是歌词正文，不是名义分隔线。对当前原生布局，歌词起点为分隔线加 48dp（host 16dp、行 margin 20dp、文字 padding 12dp）；原生播放器内部左右各 32dp，外层各 18dp。左 host 宽度为 `0.45 × 播放器宽度 + 48dp - 2 × 18dp`。这是可见内容间距，不能据此声称两个实际 View 宽度严格为 45% / 55%。

计算时先将 dp 按当前宿主密度换算为像素，再计算容器和整像素边界；窗口宽度来自当前播放器区域。

新宿主需重新核对这些内部尺寸及资源限定符，再由共享布局策略计算容器。普通/逐字、静态、翻译、背景、间奏和 credits 行应分别检查；字体、高亮比例和字形边界会影响可见像素。

Fragment 媒体组件位于 `hook/tabletmedia/`。旧 Activity 路径共用列宽和封面策略，并保留自己的原生控制组件；不能把 Fragment 的附加音量、代理按钮或动画接入直接视为 Activity 页面族功能。

Fragment 音量行中心比原生 footer 中心高6dp，与播放/进度区域保留4dp净间距；短窗口优先调整行位置或压缩触摸空白，24dp图标保持。原生被替代动作使用 INVISIBLE 保留约束，并关闭点击、取消待处理输入及按压反馈；原本 GONE 的装饰保持 GONE。关闭会话恢复仍由模块拥有的属性。歌词渐变视口不额外预留底部代理高度。

### 8.2 完整布局和数据绑定

布局核对必须覆盖所有有效的 `layout`、`layout-land`、宽度限定符、dimen/integer/bool，以及递归 include。资源名称相同也可能取到不同值。

特别检查：

- 封面 Guideline、ratio、上下锚点、实际 inset 和元数据 Barrier。
- 标题/副标题/合作信息/更多按钮的边界及是否依赖封面宽度。
- 进度和直播进度的容器 padding、音质标识、播放按钮及 footer 约束。
- generated binding 是否在换歌、播放状态变化或异步加载后重写 padding、尺寸和可见性。
- adapter 首次绑定是否早于右栏测量，以及逐字有效宽度是否从正确视口初始化。

反射与资源在安装或视图绑定阶段解析。Fragment 的原生 binding 完成接入只处理当前播放器子树，在测量前准备控制区和 footer。尺寸修改后应完成新布局再绘制；普通重新布局期间，已经放置的模块控件保留有效状态。

### 8.3 封面、视频与恢复

静态封面基准为实际进度内容宽度与上方可用高度的较小值，并在该区域居中。尺寸由当前测量重新计算，封面可因高度限制小于进度宽度。原生暂停 scale、sheet scale/Y、队列和共享过渡分别保留。

视频需要分别核对外层 `artwork_container`、内层 card、静态图、TextureView、尺寸基准和目标尺寸。静态转视频、视频转静态、视频转视频、静态转静态必须独立验收。

当前 Fragment 双栏由视图会话维护普通/视频共用的外框和静态尺寸基准。进入视频模式不释放双栏外框；原生 image/TextureView 继续负责视频比例和渐变。`FragmentTabletVideoHandoff` 在原生尺寸计算和 resize 前通过 Coordinator 准备当前会话的基准，视频目标按其比例适配该外框，静态目标使用同一基准。`staticBaseline`、`targetSize` 和 `resize` 均为当前精确 profile 的必需角色。

`FragmentPlayerArtworkRecovery` 优先读取当前双栏会话的目标尺寸；视图尚未完成重测时，也不使用旧的测量值覆盖新基准。外框修改请求布局，并在布局完成后绘制。会话退出、视图更换和销毁时按属性所有权恢复；进入/共享过渡、sheet 进度及非双栏状态仍使用各自的原生边界。

原生视频尺寸回调可能经过临时零尺寸，再进入有效尺寸；生成视频宽高比的函数还可能读取缓存的静态基准。每个新构建应追踪这条完整路径，检查外框、基准和目标是否一致。原生内部比例/渐变动画和模块外框变化分别判断；动画期间的写入、让权和恢复也要明确到具体 View 及生效时机。

播放器恢复、双栏尺寸和视频接力可能处理不同层级的 View。应列出每个写入者，确认不会交替恢复和覆盖同一参数。视频表面接力必须确认当前 owner、视图和播放状态；仅凭旧纹理仍可见不能认定它属于当前歌曲。

关闭歌词后的居中、右栏可用性与封面 sheet 补偿只适用于对应 Fragment 会话。原生 callback 执行前释放上一帧补偿，执行后合成当前帧；不累计 X，不覆盖原生 scale/Y，SONG / QUEUE 切换保留手动关闭状态。

## 9. 安装、降级与资源所有权

安装顺序为精确版本识别、配置迁移/绑定、DPI、资源回调、Application 后功能、设置入口。资源基础设施失败应停止依赖它的安装；单个能力目标缺失由该能力报告。

新增 Hook 使用 `HookRegistrationScope`：注册阶段为 preparing，必需项成功后 activate，失败或退出 close。关闭后的回调保持原生透传；不依赖框架一定提供可用的 unhook。

HLE 使用 `HleMetadataInstallation` 和 `AppleHookRegistrar.stageInstallation` 按表面分组。可选子组单独降级，父组失效关闭其子组；后续加入的 getter 继承所属组。已捕获的内层安装错误仍须上报，不能仅记日志后把整项能力报告为成功。

`HostViewSessionController` 以视图身份和配置 revision 管理会话，先关闭旧会话再发布新会话。`OwnedHostProperty` 仅恢复模块仍拥有的最后写入，原生后续写入保留。每个 listener、observer、animation listener 和延迟任务明确所属会话及清理时机。

成员调用缓存按 owner、完整签名及类加载器身份组织；旧路径明确采用历史兼容调用时保留原候选语义。slide、pre-draw、歌词高亮和逐字动画不扫描 DEX、不发现新反射成员。网络、文件读取和 TTML 处理沿用后台业务路径。

## 10. 检查、验收与回退

### 10.1 证据阶段

| 阶段 | 能证明什么 | 不能替代什么 |
|---|---|---|
| 安装包/源码分析 | 版本身份、成员描述符、调用关系、资源与布局决定 | 当前设备的实际触发和显示 |
| 静态工具 | 工具覆盖范围内的数据/契约一致性 | 所有辅助路径、资源取值和运行表现 |
| APK 构建 | 可生成本次代码的安装包 | Hook 实际安装、视觉和功能正确性 |
| 能力状态 | 当前进程中安装、降级、未支持或失败的结果 | 全部场景和旧版本回归 |
| 人工验收 | 指定版本、设备、设置和操作下的结果 | 未执行场景及其他版本 |

`productionEnabled` 与 `runtimeVerified` 分别维护。局部场景确认不能把整个档案改为运行时验证完成；没有对应字段或证据也不能推断为通过。

### 10.2 工具与执行范围

现有辅助入口包括 `scripts/verify-profile-data.py`、`scripts/verify-architecture.py` 和 `scripts/verify-host-profile.py`。按证据缺口和当次任务范围选择，工具说明见 [scripts 文档](../scripts/README.md)。

宿主静态脚本读取二进制 base Manifest，并收集包内 split 的 DEX 与布局名；显式版本参数必须与真实 Manifest 一致。开发期未启用的 profile 可通过 `--profile` 提供。静态匹配结果应同时记录脚本覆盖不到的语义依赖。

项目默认由使用者执行功能验收，不主动操作设备或运行功能脚本、全量测试、哈希验证。既有测试夹具供获授权的检查和 CI 使用；仅文档修改不需要构建 APK。构建交付物时选择任务需要的 Gradle 构建目标，记录产物与实际结果。

### 10.3 完成条件与回退

一个构建的适配记录应能回答：

1. 精确身份、页面族和能力资格是否明确。
2. 所有功能和附属契约是否有证据，缺口是否可报告。
3. 资源、binding、视频与恢复是否已按实际路径核对。
4. 配置、数据和插件接口是否保持兼容。
5. 构建、人工场景与旧版本回归分别完成到哪一步。
6. 出现问题时能否停用该 tuple 或能力，并恢复原生行为。

回退通过停用新版本登记及撤销对应接入实现完成。保持配置 schema、键、文件 ID、数据库/缓存 namespace 和 ZIP 格式时，无需反向数据迁移；涉及数据格式变化的工作必须另行定义迁移和回退，不能作为普通混淆适配附带处理。
