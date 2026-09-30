# A2UI Chat

一个直接调用 DeepSeek 的原生 Android 聊天示例。使用 Kotlin、Jetpack Compose、Material 3 和 **AndroidX 官方 A2UI 渲染器**，把模型生成的 JSON 渲染为可填写、勾选和提交的原生卡片。

支持连续对话、完整回复、流式回复、停止与重试、新建对话、浅色/深色主题。卡片支持 Text、Row、Column、Card、List、Button、TextField、CheckBox、Divider，以及原生小游戏 **Game2048、SnakeGame、RuleGame、ScriptGame**。ScriptGame 由模型动态生成 JavaScript 规则，在隔离进程执行，界面完全由 Compose 绘制；RuleGame 继续兼容受限 JSON 规则。增加新棋盘游戏无需修改 APK。仅保留当前内存会话；旋转或 Activity 重建保留消息、表单和游戏进度，进程结束后不恢复。

## 构建与运行

需要 JDK 17 或更高版本、Android SDK Platform **37.1**、Build Tools 36.0.0、NDK **29.0.13599879**、CMake **3.22.1**。最低运行版本 Android 8.0（API 26），targetSdk 36。构建包含 arm64-v8a、armeabi-v7a、x86、x86_64；NDK/CMake 可安装在 SDK 内，也可在忽略的 `local.properties` 设置 `ndk.path`、`cmake.dir` 指向已有工具。

1. 在根目录 `.key` 中保存一行 DeepSeek API Key。不要加引号或 `KEY=` 前缀。
2. 在 `local.properties` 中设置 `sdk.dir`。本工作区已配置独立的 `.android-sdk`，未修改原有 Android SDK。
3. 执行：

   ```sh
   ./scripts/gradle-local.sh :app:assembleDebug
   ```

4. 安装 `app/build/outputs/apk/debug/app-debug.apk`，或用 Android Studio 打开根目录运行 `app`。

`scripts/gradle-local.sh` 将 Gradle 缓存和 Android 用户文件放在项目内的 `.gradle-home`、`.android-user`；调试签名也位于 `.android-user`。也可以直接使用标准 `./gradlew`，但其默认缓存位置由本机 Gradle 环境决定。

`.key` 仅注入 Debug 的 BuildConfig，缺失或为空会使调试构建报错。**调试 APK 包含可提取的密钥，仅供自用测试，不应分发。** Release 不注入密钥，不配置签名；其构建用于验证密钥隔离，不是可直接访问 DeepSeek 的发布版本。项目忽略密钥、构建产物和本机配置，不记录认证头或网络正文。

## 使用

- 首页可选择“规划今天”“安排周末”“随便聊聊”“玩 2048”“玩贪吃蛇”“玩俄罗斯方块”“玩五子棋”“玩围棋”（向下滚动可见），也可以直接输入问题。
- 底部“流式”开关默认开启。关闭后等待完整结果再显示；开启后文字逐步出现，完整 A2UI 消息到达后逐步渲染卡片。
- 卡片表单会双向绑定数据；点击卡片中的提交按钮，将事件、表单实值和当前数据模型发给模型，继续对话。
- 生成期间可以停止；失败或停止后可重新生成，替换该轮未完成结果。未完成回复不进入模型历史。
- 新对话需要确认，随后清空当前消息、输入和卡片；没有会话数据库或后台持续游戏。脚本使用按需绑定的隔离服务，后台时停止发送游戏动作。

模型默认 `deepseek-flash`，关闭思考模式，单次最多 8192 输出 token，`temperature=0.2` 以减少结构化规则输出的随机性。模型名及 API 地址集中在 `app/build.gradle.kts` 中配置。应用不包含联网搜索、订票或支付等外部工具。

### 生成与试玩小游戏

直接发送“生成一个可以玩的 2048”“来一局简单难度的贪吃蛇”“生成俄罗斯方块让我玩”或“生成一个有电脑对手的五子棋让我玩”。完整回复和流式回复都支持；卡片生成完成后才启用操作。

- **2048**：4×4 棋盘，滑动或点击方向键合并，达到 2048 后可继续挑战；支持重新开始。
- **贪吃蛇**：20×20 棋盘，点击“开始”，滑动或方向键转向；简单/普通/困难分别为 300/200/120ms 一步，每吃一颗食物得 10 分。
- **规则游戏**：模型生成棋盘大小、事件、形状、计分和胜负规则；客户端统一解释执行。俄罗斯方块默认 10×20，使用生成的左右/旋转/下落按钮；五子棋默认 15×15，点击交叉点落子，本地电脑自动应手。生成内容可能有误，不保证完整复刻专业游戏规则。
- 点击“全屏”展开，点击“收起”或返回键回到聊天卡片，进度保持一致。2048/贪吃蛇支持棋盘内滑动；RuleGame 使用点击和按钮，具体控件由规则决定。
- 贪吃蛇和规则游戏离屏、切换全屏、进入后台或 AI 回复期间会暂停，需要手动继续；同时最多一局需要计时或电脑操作的游戏运行。
- 每步操作和计时都在本地执行，**不会调用模型**。点击“让 AI 点评”发送游戏类型、成绩、状态等摘要；点击“让 AI 修复规则”会发送当前规则与错误。不会自动发送整张棋盘。
- 标题更新保留进度；贪吃蛇难度变更下一局生效。RuleGame / ScriptGame 的新规则先暂存，点击“应用新规则并重开”才替换当前规则和进度。新建对话、删除或替换游戏组件会清理对应进度，没有排行榜或持久化存档。

2048 和贪吃蛇仍使用已有的专用组件，其他网格/棋盘玩法优先使用 ScriptGame。可以继续测试不预置的新变体，例如：

- “生成 8×8 棋盘的四子棋，四连获胜，有电脑对手。”
- “生成 6×12 的下落方块游戏，只用三格直条与三格 L 形，每消一行 75 分。”

RuleGame 不是任意代码执行器：没有 WebView，不生成或执行 JavaScript、HTML、Kotlin，不开放网络、文件或系统 API；暂不支持 3D、物理模拟、联网多人等超出规则语言的玩法。

规则会经过字段、引用、类型和启动操作校验，包括玩家与电脑使用同一落子规则的探测。规则校验失败，或含 RuleGame 的回复 JSON 格式错误时，客户端最多额外调用三次模型修复（含首次生成最多四次请求）；每次反馈最新候选与校验错误，并显示修复进度 1/3、2/3、3/3，通过即停止。达到上限仍失败则显示错误，不无限重试。网络失败、用户取消不触发自动修复。运行时错误会暂停并保留上一步状态，可点击“让 AI 修复规则”；这会发起一轮新对话，带上当前规则和错误，修正后仍需明确应用并重开。启动校验是有限探测，不能证明所有后续操作都正确。

游戏组件配置示例（位于 A2UI `updateComponents.components` 中）：

```json
{"id":"root","component":"SnakeGame","gameId":"root","title":"轻松贪吃蛇","difficulty":"easy"}
```

`gameId` 必须与组件 `id` 相同；`title` 可选，1～80 字。`Game2048` 使用相同结构但省略 `difficulty`；贪吃蛇默认 `normal`，也接受 `easy`、`hard`。游戏是叶子组件，不能包含子组件，也不能在同一组件树中重复引用。成绩点评通过现有 A2UI 事件链回传 `game_result`。

### RuleGame 规则接口

组件格式为 `{"id":"root","component":"RuleGame","gameId":"root","title":"游戏名","spec":{...}}`。`spec.version=1`，其余字段包括 `width/height`、`players`、`initial`、`handlers`，可选 `controls/tickMs/style/palette/overlay/bot`。

- 状态只包含整数、布尔、短字符串和数组；表达式支持白名单算术、判断、棋盘读取、有限遍历、图形变换、连线和满行检测。
- 通过 `init/tap/tick` 或按钮事件更新状态；每次操作原子提交，非法操作或执行错误不会留下部分修改。
- 棋盘最大 20×20，规则最大 64 KiB、4096 节点、12 层表达式，每事件最多 20000 计算操作；状态也受大小和深度限制。
- 规则编译和事件串行运行在后台线程。回合制电脑使用规则中的评分表达式进行有预算的两层搜索，筛选最多 12 个首层候选，单次最多 500ms，不会逐步请求 DeepSeek；它是休闲对手，不是专业棋力引擎。

精确语言说明与模型提示见 [RulePrompt.kt](app/src/main/java/com/xpl/a2uichat/games/rules/RulePrompt.kt)，完整连线棋和下落方块规则数据见 [RuleExamples.kt](app/src/main/java/com/xpl/a2uichat/games/rules/RuleExamples.kt)。样例仅用于提示和测试，运行时不按游戏名称选择模板。

### ScriptGame 动态 JavaScript 接口

可以直接说“创建一个围棋我玩玩”“生成黑白棋和电脑玩”“生成首击安全的扫雷”或“改成 7 路围棋、白贴 5.5 目”。默认围棋提示要求 9 路、黑先、白贴 7.5、提子、禁自杀和全局同形禁着；双方停一手后进入死子标记阶段，再确认面积计分或恢复对局。这些规则由模型生成，不是按游戏名称切换内置引擎，生成结果仍可能有规则错误。

组件为 `ScriptGame`，`spec` 包含 `version:1`、`source`、`config` 和可选 `botPlayer`、`tickMs`、`tests`。`source` 是返回函数对象的 JavaScript **表达式**（不是 ES module），例如 `(() => { …; return {init,legalActions,step,view,evaluate}; })()`。

- `init(config, seed)` 返回 JSON 状态；`legalActions(state)` 返回含 `type` 的动作对象数组；`step(state, action)` 返回 `{accepted, state}`。
- `view(state)` 返回原生棋盘描述：尺寸、格子颜色索引、可选文字和标记、状态、回合、阶段及按钮。阶段支持 `playing/scoring/finished`，点击是 `{type:"tap",x,y}`；停一手、确认、恢复等也是普通动作。
- 有电脑时提供 `evaluate(state, player)`，同一合法动作接口用于玩家和电脑。电脑最多两层、12 个候选、500ms 预算，超时优先采用已算出的合法动作；不是专业棋力。
- 每次操作新建虚拟机，所有持久状态必须放在 JSON 中。没有 Date/Math.random，随机玩法必须用显式 seed 和 state 内 PRNG。状态只在主进程验证成功后提交；取消或崩溃不会覆盖旧棋局。
- 新游戏收齐并通过隔离启动探测后才可交互。格式、编译或启动校验失败最多自动修复三次（含首次生成最多四次请求），通过即停止；网络失败和取消不自动修复。更新现有游戏先暂存，需点击“应用新规则并重开”。运行时错误可继续重试或“让 AI 修复规则”，修复会发送当前规则源码及错误。
- 点击“开始 / 继续”后游玩；离屏、后台、切换全屏、聊天生成时暂停。计分阶段不会自动触发电脑，可使用脚本声明的死子标记和确认按钮。

安全边界：官方 QuickJS **2026-06-04** 固定源码随 APK 编译，MIT 许可证和上游哈希见 [UPSTREAM.md](app/src/main/cpp/quickjs/UPSTREAM.md)。JNI 只在 `exported=false`、`isolatedProcess=true` 的服务中加载；通过异步 AIDL 传递有大小限制的 UTF-8 JSON，不传聊天记录、密钥、Context 或 Android 对象。不注册 IO/Java 桥，不编译 quickjs-libc，不加载模块、远程 DEX/SO 或 QuickJS 字节码，也没有 WebView。隔离不是对未来引擎漏洞的绝对保证，需要持续跟进上游安全修复；Debug APK 本身仍含可提取的测试密钥，不能分发。

限制：源码 64 KiB、状态 128 KiB、回复 256 KiB、堆 32 MiB、栈 512 KiB、棋盘最大 20×20、按钮最多 8 个。普通操作 200ms、电脑 500ms、启动测试 2 秒；引擎中断与服务独立看门狗共同限制执行，主进程处理 Binder 死亡并保留快照。启动探测不是形式化验证，无法证明任意 AI 生成规则始终正确。

精确协议见 [ScriptPrompt.kt](app/src/main/java/com/xpl/a2uichat/games/script/ScriptPrompt.kt)。测试脚本位于 `androidTest/assets/script`；另有 [ScriptExamples.kt](app/src/main/java/com/xpl/a2uichat/games/script/ScriptExamples.kt) 中经过测试的围棋教学源码随提示提供给模型，以减少提子、全局同形和整块死子标记错误。它不会由运行时按游戏名称直接加载；实际游玩的规则仍来自模型回复。数据自测支持 `expect.absentActions` 检查禁手。

## 实现结构

```text
app/src/main/java/com/xpl/a2uichat/
  chat/       聊天页面、ViewModel、当前会话与请求生命周期
  network/    DeepSeek HTTP/SSE、增量 JSON 解码、统一回复事件
  a2ui/       官方组件目录、提示词、协议处理、卡片边界校验
  games/      纯 Kotlin 游戏规则、会话级状态、自定义 A2UI 组件和 Compose 游戏界面
    rules/    通用规则校验器、解释器、本地电脑、后台会话和 RuleGame 原生渲染
    script/   动态游戏协议、隔离服务、异步 Binder、快照会话与 Compose 棋盘
  ui/         Material 3 浅色/深色主题
```

应用对 DeepSeek 的 `POST /chat/completions` 请求启用 JSON 输出，模型回复结构为：

```json
{
  "text": "给用户的自然语言回复",
  "a2uiMessages": []
}
```

数组中的每项是标准 A2UI 消息，协议 envelope 使用 `version: "v0.9"`，也接受官方解析器支持的 `v0.9.1`。自有目录 ID 为 `https://xpl.dev/a2ui/chat/v1/catalog.json`，它是协议标识，不需要联网获取。模型可见的 schema 由实际注册组件生成。

渲染使用 `androidx.a2ui.compose` 和 `androidx.compose.material3:material3-a2ui:1.0.0-alpha01`。其依赖固定 Compose `1.13.0-alpha03`、Material 3 `1.5.0-alpha28`；这是 Alpha API，升级时需要重新验证协议兼容性。

同一 `AgentClient` 接口提供完整回复和流式回复，输出 `Text`、`UiMessage`、`Complete` 事件。增量解码器处理字符串转义、中文、Unicode 代理对及任意网络分片；只把完整 JSON 消息交给官方 A2UI 解析器。EOF 再校验整个回复；连接中断或长度截断不会被当作成功。

界面消息限制为每轮 3 个新 surface、每个 80 个组件、12 层树深度；禁止循环引用及未注册函数。列表限制高度，以支持嵌套在聊天滚动列表中。旧卡片更新先在独立的官方消息处理器中校验，校验通过再应用，避免错误输出破坏已填写表单。生成中的卡片禁用交互。

## 验证

本地测试与静态检查：

```sh
./scripts/gradle-local.sh :app:testDebugUnitTest :app:lintDebug
./scripts/gradle-local.sh :app:assembleRelease
ruby scripts/verify-key-isolation.rb
```

指定真机安装与交互测试（不调用真实模型）：

```sh
./scripts/gradle-local.sh :app:assembleDebug :app:assembleDebugAndroidTest
adb -s 3B15AC00KT300000 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 3B15AC00KT300000 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 3B15AC00KT300000 shell am instrument -w -r \
  -e class com.xpl.a2uichat.ChatInteractionTest,com.xpl.a2uichat.GameInteractionTest,com.xpl.a2uichat.GameViewportTest,com.xpl.a2uichat.RuleGameInteractionTest,com.xpl.a2uichat.ScriptSandboxTest,com.xpl.a2uichat.ScriptGameInteractionTest \
  com.xpl.a2uichat.test/androidx.test.runner.AndroidJUnitRunner
```

真实 DeepSeek 验收需要显式开启，会消耗 API 额度：

```sh
adb -s 3B15AC00KT300000 shell am instrument -w -r \
  -e class com.xpl.a2uichat.LiveDeepSeekTest -e runLive true \
  com.xpl.a2uichat.test/androidx.test.runner.AndroidJUnitRunner

# ScriptGame：真实生成围棋、黑白棋、扫雷、7路围棋变体
adb -s 3B15AC00KT300000 shell am instrument -w -r \
  -e class com.xpl.a2uichat.ScriptLiveTest -e runScriptLive true \
  com.xpl.a2uichat.test/androidx.test.runner.AndroidJUnitRunner
```

真实验收覆盖完整问答、任务清单、表单提交、流式文字和卡片，以及非流式 2048、流式贪吃蛇和成绩点评；新增 RuleGame 五子棋、俄罗斯方块及两种变体，验证本地操作、全屏和 Activity 重建保留进度。截图保存在真机本应用的 `files/verification` 目录，可通过 `adb pull /sdcard/Android/data/com.xpl.a2uichat/files/verification` 导出。

没有真机时，可单独验证真实模型输出的规则是否可编译和执行（同样消耗 API 额度，不验证 Android 界面）：

```sh
RUN_LIVE_RULES=true ./scripts/gradle-local.sh :app:testDebugUnitTest \
  --tests com.xpl.a2uichat.games.RuleLiveGenerationTest --rerun-tasks
```

覆盖 15×15 五子棋、10×20 俄罗斯方块、8×8 四子棋和 6×12 三格下落方块，交替使用流式/完整回复；生成规则保存在 `app/build/outputs/verification/rules-live/`。普通测试默认跳过真实 API 调用。官方 A2UI 解析器依赖 Android `JsonReader`，完整修复事务和渲染测试位于 `androidTest`，不能以普通 JVM 规则测试替代。

普通单元测试覆盖 JSON 分片/转义、空响应、协议边界、HTTP 错误、SSE 结束与取消，以及游戏规则、随机生成、时钟暂停和游戏配置边界。真机模拟模型测试覆盖数据绑定与事件回传、停止重试、失败历史隔离、新对话取消、无效卡片更新保护、游戏本地操作、全屏状态共享、后台/离屏暂停和成绩点评。

### 既有 2048 / 贪吃蛇版本验收记录（2026-09-30）

- 设备：`3B15AC00KT300000`，PLG110，Android API 36。
- Debug APK、Release APK 构建成功；33 项单元测试通过。
- 10 项真机模拟交互测试和 2 项真实 DeepSeek 全流程测试通过，共 12 项。
- 真实流程覆盖完整问答、清单、表单提交、Activity 重建保留输入、流式文字、流式卡片及再次提交续聊；新增非流式 2048、流式贪吃蛇、成绩点评、横竖屏全屏布局和重建保留游戏进度。
- `lintDebug` 无错误；保留 10 项已有的依赖、targetSdk 和备份配置提示。
- 二进制检查确认 Debug 正确注入本地密钥，Release DEX 不含该密钥。
- 本次完整真机测试输出：`app/build/outputs/verification/game-device-tests.txt`；小游戏截图：同目录下 `08-game-2048-fullscreen.png`、`08b-game-2048-landscape.png`、`09-game-snake-fullscreen.png`、`10-game-snake-card.png`。

以上记录对应新增 RuleGame 之前的版本，该旧版已安装到上述真机。

### RuleGame 本次验证（2026-09-30）

- 51 项普通单元测试通过，包含胜负、消行、变体、电脑合法落子、防止越界/递归/资源耗尽、错误回滚、规则更新暂存及错误 JSON 识别；另有 1 项真实 API 测试默认跳过。
- 真实 DeepSeek 四种规则生成及本地执行验证通过，包含五连/四连获胜、三格形状和每行 75 分验证。模型仍可能生成错误规则，不保证每次生成都成功。
- Debug、Release 和 Android 测试 APK 构建通过，`lintDebug` 无错误。
- 已在指定设备 `3B15AC00KT300000` 安装并验证。17 项模拟交互测试和 3 项真实 DeepSeek 全流程测试通过，共 20 项；覆盖自动修复上限、取消、历史隔离、旧游戏保护、运行时手动修复、全屏/后台暂停及重建保留进度，且回归了聊天、表单、2048 和贪吃蛇。
- 真机记录：`app/build/outputs/verification/rule-all-device-tests.txt`（真实流程及全量回归；其中表单用例曾遇到 UI 根节点暂时缺失），补充界面就绪等待后重新运行模拟测试，结果见 `rule-fake-device-tests.txt`。四种规则游戏截图：同目录 `rule-screenshots/11-rule-{0,1,2,3}-fullscreen.png`。
- 本机验证期间 Google Maven 的 Java TLS 下载发生错误；使用已有 `androidx.core:core:1.19.0` 缓存和临时本地 Maven 初始化脚本完成构建，未更改项目的依赖版本或仓库配置。若本机仍遇同一问题，可执行 `./scripts/gradle-local.sh --offline -I /Users/xiangpengle/Documents/tmp/a2ui-local-core.init.gradle :app:assembleDebug`；其他环境正常联网构建即可。

新版 APK：`app/build/outputs/apk/debug/app-debug.apk`。Debug 内含本地测试密钥，仅供自用。

### ScriptGame 验证（2026-09-30）

- Debug、Release、Android 测试 APK 构建成功，QuickJS 四种 ABI 均编译成功；55 项 JVM 测试通过，另 1 项真实 API JVM 测试默认跳过。`lintDebug` 无错误，保留原有 10 项依赖、targetSdk、备份配置提示；Release DEX 不包含本地测试密钥。
- 指定真机 `3B15AC00KT300000` 的 8 项隔离运行测试通过，覆盖不同 UID / 无网络权限、进程终止后重连、死循环、递归、内存耗尽、取消恢复、动态围棋/黑白棋/扫雷及变体、提子/自杀/全局同形/死子标记和计分。
- 分组复跑 22 项原生 UI / 事务测试全部通过，覆盖 ScriptGame 自动修复、运行时超时回滚、规则更新暂存、电脑落子、全屏/后台暂停，同时回归聊天表单、2048、贪吃蛇和 RuleGame。与隔离测试合计 30 项非联网真机测试。
- 4 项真实 DeepSeek 测试通过，分别生成 9 路围棋、7 路白贴 5.5 变体、8×8 黑白棋、6×6 扫雷并本地游玩。真实生成的围棋还检查提子、打劫禁手、双停、标记死子、恢复对局、确认计分及贴目。
- 验收期间发现并修复了漏按钮、空计分动作、自测禁手处理和自动修复误更新已回滚卡片的问题。部分模型曾漏写围棋同形/计分规则，因此提示增加了已测试的算法参考和禁手自测。有限探测仍不能保证所有生成规则正确；当前失败最多自动修复三次，运行中可手动修复。
- 整批混跑曾出现 Compose Alpha 测试框架的页面就绪和 `SnapshotStateObserver` 线程错误，保留失败记录 `app/build/outputs/verification/script-acceptance-device-tests.txt`，分组复跑用于确认结果。
- 最终记录位于 `app/build/outputs/verification/`：隔离测试 `script-sandbox-verified.txt`、UI 回归 `script-ui-verified.txt`、真实生成 `script-live-verified.txt`；四种游戏截图及生成规则为 `script-verified-artifacts/script-live-{0,1,2,3}.{png,json}`。QuickJS 许可证同时保存在源码目录和 APK 的 `assets/licenses/QuickJS-NOTICES.txt`。

参考：[DeepSeek 对话补全](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion)、[Android Compose A2UI](https://developer.android.com/develop/ui/compose/agentic)、[AndroidX A2UI 发布说明](https://developer.android.com/jetpack/androidx/releases/a2ui-compose)。
