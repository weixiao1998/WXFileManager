# SMB 连接管理

由 `SmbManager` 单例管理，位于 `utils/SmbManager.kt`。

## 特性

- **线程安全**: `synchronized` 保护所有连接操作
- **自动重连**: 断开时自动重连，最多 3 次
- **超时检测**: 60 秒无操作后验证连接有效性
- **目录缓存**: 30 秒缓存期，减少重复网络请求

## 关键方法

| 方法 | 作用 |
|------|------|
| `connect(host, user, pass, share)` | 建立 SMB 连接 |
| `isConnected()` | 检查连接是否有效（含超时检测） |
| `checkAndReconnect()` | 断开时自动重连 |
| `listFiles(path)` | 列出目录内容（带缓存） |
| `openFile(path)` | 打开文件用于读取 |
| `findSubtitles(videoPath)` | 查找同名字幕文件 |
| `disconnect()` | 断开连接并清理资源 |

## 后台恢复处理

`VlcVideoPlayerActivity.onResume()` / `SmbFragment.onResume()` → `checkAndReconnect()` → VLC 内置 SMB 协议直接播放
