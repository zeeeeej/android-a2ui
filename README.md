# A2UI Chat

一个直接调用 DeepSeek 的原生 Android 聊天示例。使用 Kotlin、Jetpack Compose、Material 3 和 **AndroidX 官方 A2UI 渲染器**，把模型生成的 JSON 渲染为可填写、勾选和提交的原生卡片。

支持连续对话、完整回复、流式回复、停止与重试、新建对话、浅色/深色主题。卡片支持 Text、Row、Column、Card、List、Button、TextField、CheckBox、Divider。仅保留当前内存会话；旋转或 Activity 重建保留消息和表单，进程结束后不恢复。

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

- 首页可选择“规划今天”“安排周末”“随便聊聊”，也可以直接输入问题。
- 底部“流式”开关默认开启。关闭后等待完整结果再显示；开启后文字逐步出现，完整 A2UI 消息到达后逐步渲染卡片。
- 卡片表单会双向绑定数据；点击卡片中的提交按钮，将事件、表单实值和当前数据模型发给模型，继续对话。
- 生成期间可以停止；失败或停止后可重新生成，替换该轮未完成结果。未完成回复不进入模型历史。
- 新对话需要确认，随后清空当前消息、输入和卡片；没有会话数据库或后台服务。

模型默认 `deepseek-flash`，关闭思考模式，单次最多 8192 输出 token。模型名及 API 地址集中在 `app/build.gradle.kts` 中配置。应用不包含联网搜索、订票或支付等外部工具。

## 实现结构

```text
app/src/main/java/com/xpl/a2uichat/
  chat/       聊天页面、ViewModel、当前会话与请求生命周期
  network/    DeepSeek HTTP/SSE、增量 JSON 解码、统一回复事件
  a2ui/       官方组件目录、提示词、协议处理、卡片边界校验
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
  -e class com.xpl.a2uichat.ChatInteractionTest \
  com.xpl.a2uichat.test/androidx.test.runner.AndroidJUnitRunner
```

真实 DeepSeek 验收需要显式开启，会消耗 API 额度：

```sh
adb -s 3B15AC00KT300000 shell am instrument -w -r \
  -e class com.xpl.a2uichat.LiveDeepSeekTest -e runLive true \
  com.xpl.a2uichat.test/androidx.test.runner.AndroidJUnitRunner
```

真实验收先运行完整问答、任务清单、表单提交，再运行流式文字和卡片；同时验证 Activity 重建保留表单。截图保存在真机本应用的 `files/verification` 目录，可通过 `adb pull /sdcard/Android/data/com.xpl.a2uichat/files/verification` 导出。

普通单元测试覆盖 JSON 分片/转义、空响应、协议边界、HTTP 错误、SSE 结束与取消。真机模拟模型测试覆盖数据绑定与事件回传、停止重试、失败历史隔离、新对话取消，以及无效卡片更新保护。

### 本次验收记录（2026-09-30）

- 设备：`3B15AC00KT300000`，PLG110，Android API 36。
- Debug APK、Release APK 构建成功；17 项单元测试通过。
- 5 项真机模拟交互测试和 1 项真实 DeepSeek 全流程测试通过，共 6 项。
- 真实流程覆盖完整问答、清单、表单提交、Activity 重建保留输入、流式文字、流式卡片及再次提交续聊。
- `lintDebug` 无错误；保留固定依赖版本和 targetSdk 的升级提示。
- 二进制检查确认 Debug 正确注入本地密钥，Release DEX 不含该密钥。
- 真机测试输出：`app/build/outputs/verification/device-tests.txt`；截图：`app/build/outputs/verification/verification/`。

APK 位置：`app/build/outputs/apk/debug/app-debug.apk`。该版本已安装到上述真机。

参考：[DeepSeek 对话补全](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion)、[Android Compose A2UI](https://developer.android.com/develop/ui/compose/agentic)、[AndroidX A2UI 发布说明](https://developer.android.com/jetpack/androidx/releases/a2ui-compose)。
