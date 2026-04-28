# WXFileManager - AI Agent 参考

Android 文件管理器，支持本地文件浏览与 SMB 局域网共享。

## 项目信息

- **包名**: `top.weixiaoweb.wxfilemanager`
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
| 视频播放 | Media3 ExoPlayer + libVLC (Hi10P/特殊格式) |
| SMB 协议 | smbj + dcerpc |
| 异步 | Kotlin Coroutines + Lifecycle |

## AI Agent 工作原则

### 1. 编码前思考

**不要假设。不要隐藏困惑。呈现权衡。**

- **明确说明假设** — 如果不确定，询问而不是猜测
- **呈现多种解释** — 当存在歧义时，不要默默选择
- **适时提出异议** — 如果存在更简单的方法，说出来
- **困惑时停下来** — 指出不清楚的地方并要求澄清

### 2. 简洁优先

**用最少的代码解决问题。不要过度推测。**

- 不要添加要求之外的功能
- 不要为一次性代码创建抽象
- 不要添加未要求的"灵活性"或"可配置性"
- 不要为不可能发生的场景做错误处理
- 如果 200 行代码可以写成 50 行，重写它

**检验标准：** 资深工程师会觉得这过于复杂吗？如果是，简化。

## 代码规范

- **类名**: PascalCase (`SmbManager`, `VideoPlayerActivity`)
- **方法/变量**: camelCase (`openFile`, `checkAndReconnect`)
- **常量**: UPPER_SNAKE_CASE (`CONNECTION_TIMEOUT`)
- **资源文件**: 小写下划线分隔 (`activity_main.xml`)

## 文件组织

```
app/src/main/java/top/weixiaoweb/wxfilemanager/
├── adapter/          # RecyclerView 适配器
├── model/            # 数据模型
├── ui/               # Activity / Fragment
│   ├── local/        # 本地文件模块
│   ├── smb/          # SMB 网络共享模块
│   └── viewer/       # 文件查看器（图片/视频）
├── utils/            # 工具类
└── viewmodel/        # ViewModel
```

## SMB 连接管理

由 `SmbManager` 单例管理：

- **线程安全**: `synchronized` 保护所有连接操作
- **自动重连**: 断开时自动重连，最多 3 次
- **超时检测**: 60 秒无操作后验证连接有效性

**关键方法**: `isConnected()`, `checkAndReconnect()`, `openFile(path)`, `disconnect()`

**后台恢复处理**: `VideoPlayerActivity.onResume()` / `SmbFragment.onResume()` → `checkAndReconnect()` → `SmbDataSource.openFileWithRetry()`

## 构建命令

```bash
./gradlew assembleDebug      # Debug 构建
./gradlew assembleRelease    # Release 构建
./gradlew test               # 单元测试
./gradlew lint               # Lint 检查
```
