# WXFileManager - AI Agent 参考

Android 文件管理器，支持本地文件浏览与 SMB 局域网共享。

## 项目信息

- **包名**: `dev.weixiao.wxfilemanager`
- **minSdk**: 24 (Android 7.0)
- **targetSdk**: 34 (Android 14)
- **compileSdk**: 35
- **语言**: Kotlin (JVM 17)
- **构建**: Gradle + Kotlin DSL (`./gradlew`)

## 技术栈

| 模块 | 依赖 |
|------|------|
| UI | Android View + Material Design + ViewBinding |
| 导航 | Navigation Component |
| 图片加载 | Glide |
| 视频播放 | libVLC |
| SMB 协议 | smbj + dcerpc |
| 异步 | Kotlin Coroutines + Lifecycle |

## AI Agent 工作原则

**不要假设。不要隐藏困惑。呈现权衡。**

- 明确说明假设 — 不确定时询问
- 呈现多种解释 — 存在歧义时列出选项
- 适时提出异议 — 有更简单的方法时说出来
- 困惑时停下来 — 指出不清楚的地方

**简洁优先。** 用最少的代码解决问题。不要过度推测。

## 代码规范

- **类名**: PascalCase
- **方法/变量**: camelCase
- **常量**: UPPER_SNAKE_CASE
- **资源文件**: 小写下划线分隔

## 文件组织

```
app/src/main/java/dev/weixiao/wxfilemanager/
├── adapter/          # RecyclerView 适配器
├── model/            # 数据模型
├── ui/               # Activity / Fragment
│   ├── local/        # 本地文件模块
│   ├── smb/          # SMB 网络共享模块
│   └── viewer/       # 文件查看器（图片/视频）
├── utils/            # 工具类
└── viewmodel/        # ViewModel
```

## 模块索引

- **SMB 连接管理**: 详见 [docs/SMB_CONNECTION.md](docs/SMB_CONNECTION.md)

## 构建命令

```bash
./gradlew assembleDebug      # Debug 构建
./gradlew assembleRelease    # Release 构建
./gradlew test               # 单元测试
./gradlew lint               # Lint 检查
```
