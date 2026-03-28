# WXFileManager - AI Agents 配置

本项目是一个 Android 文件管理器应用，支持本地文件管理和 SMB 网络共享文件浏览。

## 项目概述

- **包名**: `top.weixiaoweb.wxfilemanager`
- **最低 SDK**: Android 5.0 (API 21)
- **目标 SDK**: Android 16
- **主要功能**:
  - 本地文件浏览与管理
  - SMB 局域网共享文件浏览
  - 图片查看器（支持手势缩放）
  - 视频播放器（基于 ExoPlayer/Media3）
  - 文件搜索功能

## 技术栈

- **语言**: Kotlin
- **UI 框架**: Android View + Material Design
- **导航**: Navigation Component
- **图片加载**: Glide
- **视频播放**: Media3 ExoPlayer
- **SMB 协议**: smbj 库
- **异步处理**: Kotlin Coroutines

## 代码规范

### 命名约定

- **类名**: 大驼峰命名法 (PascalCase)，如 `SmbManager`, `VideoPlayerActivity`
- **方法名**: 小驼峰命名法 (camelCase)，如 `openFile`, `checkAndReconnect`
- **常量**: 全大写下划线分隔，如 `CONNECTION_TIMEOUT`
- **资源文件**: 小写下划线分隔，如 `activity_main.xml`

### 文件组织

```
app/src/main/java/top/weixiaoweb/wxfilemanager/
├── adapter/          # RecyclerView 适配器
├── model/            # 数据模型
├── ui/               # UI 层（Fragment、Activity）
│   ├── local/        # 本地文件模块
│   ├── smb/          # SMB 网络共享模块
│   └── viewer/       # 文件查看器
├── utils/            # 工具类
└── viewmodel/        # ViewModel 层
```

## SMB 连接管理

### 连接生命周期

SMB 连接由 `SmbManager` 单例管理，具有以下特点：

1. **线程安全**: 使用 `synchronized` 锁保护所有连接操作
2. **自动重连**: 连接断开时自动尝试重连（最多 3 次）
3. **超时检测**: 60 秒无操作后验证连接有效性

### 关键方法

- `isConnected()`: 检查连接状态
- `checkAndReconnect()`: 检查并重连
- `openFile(path)`: 打开 SMB 文件
- `disconnect()`: 断开连接

## 常见问题处理

### SMB 连接断开

当应用从后台切回前台时，SMB 连接可能已被服务器断开。处理流程：

1. `VideoPlayerActivity.onResume()` 或 `SmbFragment.onResume()` 调用 `checkAndReconnect()`
2. `SmbDataSource.openFileWithRetry()` 在打开文件前检查连接
3. 连接无效时自动重连，最多重试 3 次

### 文件打开失败

如果文件打开失败，检查：

1. SMB 连接是否有效
2. 文件路径是否正确
3. 是否有读取权限

## 构建命令

```bash
# 构建 Debug 版本
./gradlew assembleDebug

# 构建 Release 版本
./gradlew assembleRelease

# 运行单元测试
./gradlew test

# 运行 Lint 检查
./gradlew lint
```

## 相关链接

- [SMBJ 库文档](https://github.com/hierynomus/smbj)
- [Media3 ExoPlayer 文档](https://developer.android.com/media/media3)
- [Glide 文档](https://github.com/bumptech/glide)
