# A2UI Chat

一个直接调用 DeepSeek 的原生 Android 聊天示例。使用 Kotlin、Jetpack Compose、Material 3 和 **AndroidX 官方 A2UI 渲染器**，把模型生成的 JSON 渲染为可填写、勾选和提交的原生卡片。

支持连续对话、完整回复、流式回复、停止与重试、新建对话、浅色/深色主题。卡片支持 Text、Row、Column、Card、List、Button、TextField、CheckBox、Divider，以及原生小游戏 **Game2048、SnakeGame**。仅保留当前内存会话；旋转或 Activity 重建保留消息、表单和游戏进度，进程结束后不恢复。

## 构建与运行

需要 JDK 17 或更高版本、Android SDK Platform **37.1**、Build Tools 36.0.0。最低运行版本 Android 8.0（API 26），targetSdk 36。

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

- 首页可选择“规划今天”“安排周末”“随便聊聊”“玩 2048”“玩贪吃蛇”（向下滚动可见），也可以直接输入问题。
- 底部“流式”开关默认开启。关闭后等待完整结果再显示；开启后文字逐步出现，完整 A2UI 消息到达后逐步渲染卡片。
- 卡片表单会双向绑定数据；点击卡片中的提交按钮，将事件、表单实值和当前数据模型发给模型，继续对话。
- 生成期间可以停止；失败或停止后可重新生成，替换该轮未完成结果。未完成回复不进入模型历史。
- 新对话需要确认，随后清空当前消息、输入和卡片；没有会话数据库或后台服务。

模型默认 `deepseek-flash`，关闭思考模式，单次最多 8192 输出 token。模型名及 API 地址集中在 `app/build.gradle.kts` 中配置。应用不包含联网搜索、订票或支付等外部工具。

### 生成与试玩小游戏

直接发送“生成一个可以玩的 2048”或“来一局简单难度的贪吃蛇”。完整回复和流式回复都支持；卡片生成完成后才启用操作。

- **2048**：4×4 棋盘，滑动或点击方向键合并，达到 2048 后可继续挑战；支持重新开始。
- **贪吃蛇**：20×20 棋盘，点击“开始”，滑动或方向键转向；简单/普通/困难分别为 300/200/120ms 一步，每吃一颗食物得 10 分。
- 点击“全屏”展开，点击“收起”或返回键回到聊天卡片，进度保持一致。棋盘内滑动控制游戏，棋盘外滑动聊天。
- 贪吃蛇离屏、切换全屏、进入后台或 AI 回复期间会暂停，需要手动继续；同时最多一局贪吃蛇运行。
- 每步操作和计时都在本地执行，**不会调用模型**。只有点击“让 AI 点评”才发送游戏类型、成绩、状态等摘要；不会自动发送整张棋盘。
- 标题更新保留进度；难度变更下一局生效。新建对话、删除或替换游戏组件会清理对应进度，没有排行榜或持久化存档。

模型选择预置的原生组件和配置，不生成或执行 JavaScript、HTML 或任意代码。目前仅支持上述两款游戏。

游戏组件配置示例（位于 A2UI `updateComponents.components` 中）：

```json
{"id":"root","component":"SnakeGame","gameId":"root","title":"轻松贪吃蛇","difficulty":"easy"}
```

`gameId` 必须与组件 `id` 相同；`title` 可选，1～80 字。`Game2048` 使用相同结构但省略 `difficulty`；贪吃蛇默认 `normal`，也接受 `easy`、`hard`。游戏是叶子组件，不能包含子组件，也不能在同一组件树中重复引用。成绩点评通过现有 A2UI 事件链回传 `game_result`。

## 实现结构

```text
app/src/main/java/com/xpl/a2uichat/
  chat/       聊天页面、ViewModel、当前会话与请求生命周期
  network/    DeepSeek HTTP/SSE、增量 JSON 解码、统一回复事件
  a2ui/       官方组件目录、提示词、协议处理、卡片边界校验
  games/      纯 Kotlin 游戏规则、会话级状态、自定义 A2UI 组件和 Compose 游戏界面
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
  -e class com.xpl.a2uichat.ChatInteractionTest,com.xpl.a2uichat.GameInteractionTest,com.xpl.a2uichat.GameViewportTest \
  com.xpl.a2uichat.test/androidx.test.runner.AndroidJUnitRunner
```

真实 DeepSeek 验收需要显式开启，会消耗 API 额度：

```sh
adb -s 3B15AC00KT300000 shell am instrument -w -r \
  -e class com.xpl.a2uichat.LiveDeepSeekTest -e runLive true \
  com.xpl.a2uichat.test/androidx.test.runner.AndroidJUnitRunner
```

真实验收覆盖完整问答、任务清单、表单提交、流式文字和卡片，以及非流式 2048、流式贪吃蛇和成绩点评；同时验证 Activity 重建保留表单和游戏进度。截图保存在真机本应用的 `files/verification` 目录，可通过 `adb pull /sdcard/Android/data/com.xpl.a2uichat/files/verification` 导出。

普通单元测试覆盖 JSON 分片/转义、空响应、协议边界、HTTP 错误、SSE 结束与取消，以及游戏规则、随机生成、时钟暂停和游戏配置边界。真机模拟模型测试覆盖数据绑定与事件回传、停止重试、失败历史隔离、新对话取消、无效卡片更新保护、游戏本地操作、全屏状态共享、后台/离屏暂停和成绩点评。

### 本次验收记录（2026-09-30）

- 设备：`3B15AC00KT300000`，PLG110，Android API 36。
- Debug APK、Release APK 构建成功；33 项单元测试通过。
- 10 项真机模拟交互测试和 2 项真实 DeepSeek 全流程测试通过，共 12 项。
- 真实流程覆盖完整问答、清单、表单提交、Activity 重建保留输入、流式文字、流式卡片及再次提交续聊；新增非流式 2048、流式贪吃蛇、成绩点评、横竖屏全屏布局和重建保留游戏进度。
- `lintDebug` 无错误；保留 10 项已有的依赖、targetSdk 和备份配置提示。
- 二进制检查确认 Debug 正确注入本地密钥，Release DEX 不含该密钥。
- 本次完整真机测试输出：`app/build/outputs/verification/game-device-tests.txt`；小游戏截图：同目录下 `08-game-2048-fullscreen.png`、`08b-game-2048-landscape.png`、`09-game-snake-fullscreen.png`、`10-game-snake-card.png`。

APK 位置：`app/build/outputs/apk/debug/app-debug.apk`。该版本已安装到上述真机。

参考：[DeepSeek 对话补全](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion)、[Android Compose A2UI](https://developer.android.com/develop/ui/compose/agentic)、[AndroidX A2UI 发布说明](https://developer.android.com/jetpack/androidx/releases/a2ui-compose)。
