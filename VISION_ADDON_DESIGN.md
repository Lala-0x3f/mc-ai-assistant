# Vision Addon 设计与实现说明

## 目标
为 [`McAiAssistant`](src/main/java/com/mcaiassistant/mcaiassistant/McAiAssistant.java) 增加一个 Fabric 客户端附属 mod，使服务端在玩家触发 AI 时，可以在检测到该玩家已安装附属 mod 的前提下，异步请求客户端上传当前游戏画面截图（无 HUD），并将图片一并传给 AI 模型；未安装附属 mod 时保持原有纯文本流程。

## 当前落地架构

### 服务端
- 核心管理器：[`ScreenshotManager`](src/main/java/com/mcaiassistant/mcaiassistant/ScreenshotManager.java)
- AI 接入点：[`ChatListener`](src/main/java/com/mcaiassistant/mcaiassistant/ChatListener.java)
- 多模态请求构建：[`AiApiClient`](src/main/java/com/mcaiassistant/mcaiassistant/AiApiClient.java)
- 生命周期初始化：[`McAiAssistant`](src/main/java/com/mcaiassistant/mcaiassistant/McAiAssistant.java)

### 客户端 Fabric 附属 mod
- 入口：[`McAiVisionMod`](vision-addon/src/main/java/com/mcaiassistant/visionaddon/McAiVisionMod.java)
- 截图与压缩：[`ScreenshotHandler`](vision-addon/src/main/java/com/mcaiassistant/visionaddon/ScreenshotHandler.java)
- 模组声明：[`fabric.mod.json`](vision-addon/src/main/resources/fabric.mod.json)
- 构建配置：[`build.gradle`](vision-addon/build.gradle)、[`gradle.properties`](vision-addon/gradle.properties)

## 端通信协议
已统一为 **Bukkit / Paper Plugin Messaging Channel**，不再使用 Fabric CustomPayload。

### 通道定义
- `mcai:vision/hello`
  - 方向：客户端 → 服务端
  - 用途：声明玩家客户端已安装视觉附属 mod
  - payload：UTF-8 字符串，例如 `mcai-vision:1.0.0`
- `mcai:vision/request`
  - 方向：服务端 → 客户端
  - 用途：请求当前玩家上传截图
  - payload：空
- `mcai:vision/response`
  - 方向：客户端 → 服务端
  - 用途：回传截图数据
  - payload：UTF-8 base64 JPEG 字符串

## 客户端安装识别
服务端不会盲目向所有玩家发截图请求。

识别流程由 [`ScreenshotManager`](src/main/java/com/mcaiassistant/mcaiassistant/ScreenshotManager.java) 实现：
1. 客户端在加入服务器后，立即通过 [`McAiVisionMod.onInitializeClient()`](vision-addon/src/main/java/com/mcaiassistant/visionaddon/McAiVisionMod.java:27) 发送 `mcai:vision/hello`。
2. 服务端在 [`ScreenshotManager.onPluginMessageReceived()`](src/main/java/com/mcaiassistant/mcaiassistant/ScreenshotManager.java:61) 中接收 hello。
3. 只有当 hello payload 满足 `mcai-vision:` 前缀时，才在 [`ScreenshotManager.registerModPlayer()`](src/main/java/com/mcaiassistant/mcaiassistant/ScreenshotManager.java:91) 中登记该玩家已安装 mod。
4. AI 触发时由 [`ScreenshotManager.hasModInstalled()`](src/main/java/com/mcaiassistant/mcaiassistant/ScreenshotManager.java:106) 判断是否允许发送截图请求。
5. 若未安装 mod，则 [`ScreenshotManager.requestScreenshot()`](src/main/java/com/mcaiassistant/mcaiassistant/ScreenshotManager.java:114) 直接返回 `CompletableFuture.completedFuture(null)`，保持原文本流程。

## 截图流程

### 服务端侧
1. 玩家聊天触发 AI。
2. [`ChatListener`](src/main/java/com/mcaiassistant/mcaiassistant/ChatListener.java) 先检查是否安装附属 mod。
3. 若已安装，则调用 [`ScreenshotManager.requestScreenshot()`](src/main/java/com/mcaiassistant/mcaiassistant/ScreenshotManager.java:114)。
4. 服务端通过 `mcai:vision/request` 异步请求截图，并等待 `CompletableFuture<String>`。
5. 超时 5 秒自动回退为 `null`。
6. 若收到图片，则传入 [`AiApiClient`](src/main/java/com/mcaiassistant/mcaiassistant/AiApiClient.java) 的多模态请求体；否则走无图分支。

### 客户端侧
1. [`McAiVisionMod`](vision-addon/src/main/java/com/mcaiassistant/visionaddon/McAiVisionMod.java) 收到 `mcai:vision/request`。
2. 在客户端主线程调用 [`ScreenshotHandler.captureAndSend()`](vision-addon/src/main/java/com/mcaiassistant/visionaddon/ScreenshotHandler.java:42)。
3. [`ScreenshotHandler`](vision-addon/src/main/java/com/mcaiassistant/visionaddon/ScreenshotHandler.java) 会：
   - 临时设置 `client.options.hudHidden = true`
   - 读取当前 framebuffer 像素
   - 在 `finally` 中恢复 HUD 状态
   - 将像素异步压缩为 JPEG
   - base64 编码后通过 `mcai:vision/response` 回传

## 无 HUD 实现说明
当前无 HUD 方案位于 [`ScreenshotHandler.captureAndSend()`](vision-addon/src/main/java/com/mcaiassistant/visionaddon/ScreenshotHandler.java:42) 及 [`ScreenshotHandler.captureFramebufferWithoutHud()`](vision-addon/src/main/java/com/mcaiassistant/visionaddon/ScreenshotHandler.java:57)：
- 截图前临时隐藏 HUD；
- 读取游戏 framebuffer；
- 无论成功失败都在 `finally` 中恢复 HUD。

该方案的优点：
- 简单直接；
- 不影响服务端；
- 与异步 JPEG 压缩配合较好。

注意：由于 Minecraft 渲染与当前帧时序存在关系，极端情况下仍可能受单帧状态影响；但当前实现已是满足“无 HUD 截图”要求的可落地版本。

## 异步与性能
- 服务端等待截图使用 [`CompletableFuture`](src/main/java/com/mcaiassistant/mcaiassistant/ScreenshotManager.java:30)；
- 超时自动回退，避免 AI 请求卡死；
- 客户端只在主线程读取 framebuffer，JPEG 压缩与 base64 编码放到异步线程执行；
- 服务端对回传图片长度做了上限校验，避免异常大包影响性能。

## 多模态 AI 请求
[`AiApiClient`](src/main/java/com/mcaiassistant/mcaiassistant/AiApiClient.java) 已支持在 user message 中构建 OpenAI 兼容的文本 + 图片内容：
- 文本部分：`type=text`
- 图片部分：`type=image_url`
- 图片 URL：`data:image/jpeg;base64,...`

这样当截图存在时，AI 可以直接“看到”玩家视角内容。

## 当前兼容性
- 服务端：Paper 插件主工程可通过 `mvn -q -DskipTests compile`
- 客户端附属 mod：目标版本已调整为 Fabric / Minecraft `1.21.10`
- 未安装客户端 mod：保持原先 AI 流程不变

## 后续可继续增强的点
- 在玩家退出服务器时主动调用 [`ScreenshotManager.unregisterPlayer()`](src/main/java/com/mcaiassistant/mcaiassistant/ScreenshotManager.java:98) 做状态清理
- 将同样的截图逻辑扩展到 [`RedisChatCompatibility`](src/main/java/com/mcaiassistant/mcaiassistant/RedisChatCompatibility.java)
- 为客户端附属 mod 增加截图质量、宽度、超时等可配置项
- 增加服务端对 Fabric 子工程的单独构建验证脚本
