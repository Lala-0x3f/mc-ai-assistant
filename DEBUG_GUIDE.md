# MC AI Assistant 调试指南

## 🔍 增强的调试功能

我已经为您的插件添加了详细的调试功能，帮助排查 API 调用失败的问题。

### 📋 新增的调试信息

#### 1. **请求前调试信息**
当启用调试模式时，插件会记录：
- 请求类型（普通请求/搜索请求）
- 请求 URL 和方法
- 所有请求头（敏感信息会被隐藏）
- 请求体内容（长内容会被截取）

#### 2. **响应调试信息**
- 响应状态码和消息
- 响应内容长度
- 完整响应内容（短内容）或预览（长内容）

#### 3. **错误详细信息**
当 API 调用失败时，会显示：
- 详细的错误响应内容
- 解析后的 JSON 错误信息
- API 错误消息、类型和代码
- 完整的请求信息（调试模式下）
- 异常堆栈跟踪

## 🛠️ 如何启用调试模式

### 1. 修改配置文件
编辑 `plugins/McAiAssistant/config.yml`：
```yaml
features:
  debug_mode: true  # 启用调试模式
```

### 2. 重启插件或服务器
```
/reload
```

## 📊 调试日志示例

### 成功请求的日志
```
[INFO]: === API 请求调试信息 ===
[INFO]: 请求类型: 搜索请求
[INFO]: 请求 URL: https://api.openai.com/v1/chat/completions
[INFO]: 请求方法: POST
[INFO]: 请求头信息:
[INFO]:   Authorization: Bearer ***your-key
[INFO]:   Content-Type: application/json
[INFO]:   User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36...
[INFO]: 请求体预览: {"model":"gpt-4o-search-preview","max_tokens":1000...
[INFO]: === 开始发送请求 ===
[INFO]: API 响应成功: 200 OK
[INFO]: 响应内容长度: 1234 字符
[INFO]: 搜索请求处理完成，响应长度: 1234
```

### 失败请求的日志
```
[ERROR]: 搜索 API 调用失败: API 请求失败: 400 Bad Request
请求类型: 搜索请求
错误响应内容: {"error":{"message":"Invalid model specified","type":"invalid_request_error","code":"model_not_found"}}
API 错误消息: Invalid model specified
错误类型: invalid_request_error
错误代码: model_not_found
请求 URL: https://api.openai.com/v1/chat/completions
请求方法: POST
请求头信息:
  Authorization: Bearer ***your-key
  Content-Type: application/json
  User-Agent: Mozilla/5.0...
```

## 🔧 常见问题排查

### 1. **400 Bad Request 错误**
通常表示请求参数有问题：
- 检查模型名称是否正确
- 确认 API 密钥是否有效
- 验证请求体格式是否正确

### 2. **401 Unauthorized 错误**
API 密钥问题：
- 检查 API 密钥是否正确
- 确认密钥是否有相应权限
- 验证密钥是否已过期

### 3. **403 Forbidden 错误**
权限或配额问题：
- 检查 API 配额是否用完
- 确认是否有使用特定模型的权限
- 验证地区限制

### 4. **404 Not Found 错误**
端点或模型不存在：
- 检查 API URL 是否正确
- 确认模型名称是否存在
- 验证 API 版本是否匹配

### 5. **429 Too Many Requests 错误**
请求频率过高：
- 降低请求频率
- 检查速率限制配置
- 等待一段时间后重试

### 6. **500/502/503 服务器错误**
API 服务端问题：
- 稍后重试
- 检查 API 服务状态
- 联系 API 提供商

## 📝 收集调试信息的步骤

### 1. 启用调试模式
```yaml
features:
  debug_mode: true
```

### 2. 重现问题
在游戏中发送触发 AI 的消息：
```
@search 怎么做中式建筑
```

### 3. 收集日志
从服务器控制台复制相关日志，包括：
- `=== API 请求调试信息 ===` 开始的完整请求信息
- 错误消息和详细信息
- 异常堆栈跟踪（如果有）

### 4. 分析问题
根据错误代码和消息确定问题类型：
- 配置问题（API 密钥、URL、模型名）
- 权限问题（密钥权限、配额）
- 网络问题（连接超时、DNS）
- API 服务问题（服务器错误）

## 🎯 针对您当前问题的排查

根据您的错误 `API 请求失败: 400`，建议检查：

1. **模型名称**: 确认 `gpt-4o-search-preview` 是否为有效模型名
2. **API 密钥权限**: 确认密钥是否有使用该模型的权限
3. **请求格式**: 检查生成的请求体是否符合 API 规范

启用调试模式后，您将看到完整的请求和响应信息，这将帮助精确定位问题所在。

## 💡 优化建议

1. **生产环境**: 问题解决后记得关闭调试模式
2. **日志管理**: 定期清理调试日志避免占用过多空间
3. **敏感信息**: 调试日志会隐藏 API 密钥的大部分内容，但仍需注意保护
4. **性能影响**: 调试模式会增加日志输出，可能轻微影响性能
