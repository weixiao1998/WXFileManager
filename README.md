# WXFileManager

一个功能强大的 Android 文件管理器应用，支持本地文件管理和 SMB 网络共享文件浏览。

## 📱 功能特性

- **本地文件管理** - 浏览、管理和操作设备上的文件
- **SMB 网络共享** - 访问局域网内的 SMB 共享文件夹
- **图片查看器** - 支持手势缩放的图片预览功能
- **视频播放器** - 基于 ExoPlayer/Media3 的视频播放
- **文件搜索** - 快速查找所需文件

## 🛠️ 技术栈

| 组件 | 技术 |
|------|------|
| **开发语言** | Kotlin |
| **最低 SDK** | Android 5.0 (API 21) |
| **目标 SDK** | Android 16 |
| **UI 框架** | Android View + Material Design |
| **导航** | Navigation Component |
| **图片加载** | Glide |
| **视频播放** | Media3 ExoPlayer |
| **SMB 协议** | smbj |
| **异步处理** | Kotlin Coroutines |

## 📦 项目结构

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

## 🚀 快速开始

### 环境要求

- Android Studio Arctic Fox 或更高版本
- JDK 11 或更高版本
- Android SDK API 21+

### 构建项目

```bash
# 克隆项目
git clone <repository-url>
cd WXFileManager

# 构建 Debug 版本
./gradlew assembleDebug

# 构建 Release 版本
./gradlew assembleRelease

# 运行单元测试
./gradlew test

# 运行 Lint 检查
./gradlew lint
```

### 安装应用

构建完成后，Debug APK 文件位于：
```
app/build/outputs/apk/debug/app-debug.apk
```

## 📖 使用说明

### 本地文件管理

1. 打开应用后默认进入本地文件浏览界面
2. 点击文件或文件夹可进行查看或进入
3. 长按文件可选择更多操作（复制、移动、删除等）

### SMB 网络共享

1. 切换到 SMB 标签页
2. 添加新的 SMB 连接（服务器地址、用户名、密码）
3. 连接成功后即可浏览共享文件
4. 支持视频文件的在线播放

### 图片查看器

- 支持双击缩放
- 支持捏合手势缩放
- 支持滑动关闭

### 视频播放器

- 基于 Media3 ExoPlayer
- 支持常见的视频格式
- 自动检测 SMB 连接状态并自动重连

## 🔧 配置说明

### SMB 连接管理

SMB 连接由 `SmbManager` 单例管理，具有以下特性：

- **线程安全** - 使用 synchronized 锁保护所有连接操作
- **自动重连** - 连接断开时自动尝试重连（最多 3 次）
- **超时检测** - 60 秒无操作后验证连接有效性

### 权限配置

在 `AndroidManifest.xml` 中已配置以下权限：

```xml
<!-- 存储权限 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

<!-- 网络权限 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## 🐛 常见问题

### SMB 连接断开

**问题**：应用从后台切回前台时，SMB 连接可能已断开

**解决方案**：
1. 应用会自动检测连接状态
2. `VideoPlayerActivity.onResume()` 会调用 `checkAndReconnect()`
3. 连接无效时自动重连，最多重试 3 次

### 文件打开失败

**可能原因**：
1. SMB 连接已断开
2. 文件路径不正确
3. 没有读取权限

**解决方案**：
1. 检查网络连接
2. 验证文件路径
3. 确保已授予存储权限

### 视频无法播放

**可能原因**：
1. 视频格式不支持
2. 网络速度过慢
3. SMB 连接断开

**解决方案**：
1. 检查视频格式（支持 MP4、MKV、AVI 等常见格式）
2. 确保网络连接稳定
3. 重新连接 SMB 服务器

## 📄 代码规范

### 命名约定

- **类名**: 大驼峰命名法 (PascalCase)，如 `SmbManager`, `VideoPlayerActivity`
- **方法名**: 小驼峰命名法 (camelCase)，如 `openFile`, `checkAndReconnect`
- **常量**: 全大写下划线分隔，如 `CONNECTION_TIMEOUT`
- **资源文件**: 小写下划线分隔，如 `activity_main.xml`

## 🔗 相关资源

- [SMBJ 库文档](https://github.com/hierynomus/smbj)
- [Media3 ExoPlayer 文档](https://developer.android.com/media/media3)
- [Glide 文档](https://github.com/bumptech/glide)
- [Kotlin 协程文档](https://kotlinlang.org/docs/coroutines-overview.html)
- [Material Design 指南](https://material.io/design)

## 📝 开发计划

- [ ] 支持更多网络协议（FTP、WebDAV）
- [ ] 添加文件压缩/解压功能
- [ ] 支持云存储服务（Google Drive、OneDrive）
- [ ] 优化大文件夹加载性能
- [ ] 添加深色主题支持


## 👨‍💻 贡献

欢迎提交 Issue 和 Pull Request！
