# ▣ 私匣 PrivateDrop · Android

私域聊天传文件 **PrivateDrop** 的原生 Android 客户端（Kotlin，非 WebView 套壳）。

主项目（服务端 + 网页端 + Windows 客户端 + 完整文档）：
**👉 [yue56189/private-drop](https://github.com/yue56189/private-drop)**

## ✨ 客户端功能

- 实时聊天（SSE 推送，断线自动重连）
- 文件收发：上传/下载进度条 + 速度显示，支持暂停 / 继续 / 取消
- 长按消息：复制 / 引用
- 每日格言、Material 3 风格、自定义主题
- 适配状态栏 / 导航栏 / 键盘避让

## 📥 下载安装

到 [**Releases**](../../releases) 下载 `app-debug.apk` 安装（debug 签名，需允许"安装未知应用"）。

> ⚠️ 不同版本的 CI 构建签名密钥不同，升级时请先卸载旧版再安装。

## 🚀 使用

1. 部署主项目的服务端（纯 Python 标准库，见主项目 README）
2. 打开 App，依次填入 **服务器地址 / 访问口令 / 昵称** → 进入房间
3. 与同一房间的网页端、Windows 端互发消息、互传文件

## 🛠 构建

```bash
gradle assembleDebug
# 产物： app/build/outputs/apk/debug/app-debug.apk
```

本仓库推送后由 GitHub Actions 自动构建并发布 Release。

## 📄 协议

[MIT](LICENSE)
