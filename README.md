# 写在前面
本人只是一个无能的高中生，近期沉迷vibe coding，想要一个小而美的移动端听歌软件，看到yesplaymusic开源但是只有电脑端于是让AI以他为参考，生成手机应用，同时融入了自己对UI设计的喜好和理解。
本项目由deepseek 4.1 flash和Qwen 3.8 flash共同制作和编译，希望大家喜欢，也欢迎批评和指正
### 与yesplaymusic不同之处
- 本项目基于Kotlin实现，不依赖 WebView，
- 本项目使用Material Design 3主题与动效，同时融入我自己的好恶，个人认为UI还算优雅
- 目前适配了Android 5.0及以上的移动设备

接下来看一下AI写的README.md吧

# YesPlayMusic Mobile

一个基于 Kotlin + Material Design 3 的原生 Android 网易云音乐客户端。

项目以 YesPlayMusic 的产品形态为参考，使用原生 Android 重新实现，不依赖 WebView。整体定位是**轻量、低依赖、兼容低版本设备**：`minSdk 21`，核心网络、图片加载、音频缓存、歌词渲染均为vibe coding实现。

> 本项目仅用于学习与个人使用，与网易云音乐官方无关。接口可能随官方调整而失效，请勿用于商业用途。

---

## 功能特性

### 音乐发现
- 首页个性化推荐歌单
- 歌单广场分页加载
- 每日推荐歌曲
- 搜索单曲、歌单、专辑、艺人
- 搜索范围筛选：全站 / 我喜欢的音乐 / 收藏的歌单 / 收藏的专辑
- 歌单详情、专辑详情、艺人主页
- 艺人关注 / 取消关注
- 歌单、专辑收藏 / 取消收藏

### 播放
- 后台播放服务 + 前台通知
- 顺序播放 / 单曲循环 / 随机播放
- 上一首、下一首、进度拖动
- 锁屏与通知栏媒体控制（API 21+）
- 耳机拔出自动暂停
- 音频焦点管理
- 播放失败自动跳过，连续失败保护
- 音频磁盘缓存，按音质分键，LRU 淘汰

### 歌词
- LRC 解析，支持一行多时间标签
- 中文翻译 / 日语罗马音转平假名注音（二选一显示）
- 当前行高亮、自动滚动
- 歌词字号设置
- 横屏时封面、歌词、控制同屏显示

### 账号
- 网易云 App 扫码登录
- 登录态、Cookie、用户资料持久化
- 红心歌曲同步

### 外观与交互
- Material Design 3 主题与动效
- 跟随系统 / 浅色 / 深色模式
- 竖屏 Navigation Bar，横屏 Navigation Rail
- 横屏歌曲列表独立专辑列
- 迷你播放器：竖屏整行悬浮，横屏按内容收窄并靠右
- 横屏长按歌名区域进入艺人选择，长按专辑列进入专辑
- 播放页歌名/歌手名过长时可左右滑动查看，不再省略号截断

### 设置
- 外观模式
- 音质：标准 128Kbps / 高 320Kbps / 无损 FLAC（切换后从下一首歌曲生效）
- 歌词字号
- 歌曲缓存上限（仅统计音频缓存，不含图片缓存）

---

## 技术栈

| 类型 | 方案 |
|---|---|
| 语言 | Kotlin |
| 构建 | Gradle Kotlin DSL + Android Gradle Plugin 9.4.1 |
| 最低版本 | Android 5.0（API 21） |
| 目标版本 | Android 14（API 34） |
| 编译版本 | Android 16（API 36） |
| UI | XML 布局 + Material Components 1.12 |
| 主题 | Material Design 3（DayNight） |
| 网络 | `HttpURLConnection` + `org.json` |
| 播放 | `MediaPlayer` + `MediaSession` + `AudioManager` |
| 图片 | 自研 `ImageLoader`（内存 LruCache + 磁盘缓存） |
| 音频缓存 | 自研 `AudioCache`（磁盘 LRU） |
| 持久化 | `SharedPreferences` |
| 二维码 | ZXing Core |

主要依赖：

```kotlin
androidx.core:core:1.13.1
androidx.appcompat:appcompat:1.6.1
com.google.android.material:material:1.12.0
androidx.constraintlayout:constraintlayout:2.1.4
androidx.recyclerview:recyclerview:1.3.2
com.google.zxing:core:3.5.3
```

依赖版本为兼容 `minSdk 21` 特意选择，未使用 OkHttp、Retrofit、Glide、Coil、Room、Coroutines 或依赖注入框架。

---

## 项目结构

```text
LiteMusicAndroid/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/litemusic/
│       │   ├── App.kt                 应用入口与主题初始化
│       │   ├── Api.kt                 业务接口门面
│       │   ├── ApiClient.kt           网络传输、多通道与 Cookie 会话
│       │   ├── Eapi.kt                 eapi 加密
│       │   ├── Weapi.kt                weapi 加密
│       │   ├── Prefs.kt               登录态与设置持久化
│       │   ├── Models.kt              Song / Playlist / LyricLine / Parser
│       │   ├── AudioCache.kt          音频磁盘缓存
│       │   ├── ImageLoader.kt         图片内存 + 磁盘缓存
│       │   ├── LrcParser.kt           LRC 歌词解析
│       │   ├── Kana.kt                罗马音转平假名
│       │   ├── player/
│       │   │   ├── PlayerService.kt   播放服务、队列、通知、媒体会话
│       │   │   └── PlayerCompat21.kt  API 21+ 专属实现隔离
│       │   └── ui/
│       │       ├── MainActivity.kt
│       │       ├── HomeFragment.kt
│       │       ├── SearchFragment.kt
│       │       ├── MineFragment.kt
│       │       ├── PlayerActivity.kt
│       │       ├── PlaylistActivity.kt
│       │       ├── ArtistActivity.kt
│       │       ├── LoginActivity.kt
│       │       ├── SettingsActivity.kt
│       │       ├── SongAdapter.kt
│       │       ├── LikedStore.kt
│       │       ├── MiniPlayerController.kt
│       │       ├── MiniProgressRing.kt
│       │       └── CenteredRippleLayout.kt
│       └── res/
│           ├── layout/                竖屏布局
│           ├── layout-land/           横屏布局
│           ├── values/                M3 浅色主题
│           ├── values-night/          M3 深色主题
│           ├── drawable/
│           └── anim/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

---

## 架构说明

项目采用轻量分层结构，没有引入额外架构框架：

```text
UI 层
  Activity / Fragment / RecyclerView Adapter / 自定义 View
        │
        ├── 业务接口 ──── Api.kt ──── ApiClient.kt ──── Eapi / Weapi / linuxapi
        │                                      │
        │                                      └── Parser ──── Song / Playlist
        │
        └── 播放控制 ──── PlayerService ──── MediaPlayer / MediaSession
                              │
                              ├── AudioCache
                              └── ImageLoader
持久化
  Prefs（SharedPreferences）
```

### 网络通道

| 通道 | 用途 |
|---|---|
| eapi | 常规业务接口、登录、搜索、收藏 |
| weapi | 会话恢复及部分接口回退 |
| linuxapi | 关注艺人等敏感写操作 |

`ApiClient` 统一处理加密、Cookie、登录态、风控错误和会话恢复。

### 播放数据流

```text
用户点击歌曲
  → PlayerService.play()
  → 建立队列与当前索引
  → 命中 AudioCache 则本地播放
  → 否则 Api.songUrl() 获取地址
  → AudioCache.fetch() 下载到磁盘
  → MediaPlayer 播放
  → Listener 回调 UI / 更新通知与 MediaSession
```

### 缓存策略

- 音频：`cacheDir/audio`，文件名为 `歌曲ID-音质.aud`，按体积上限 LRU 淘汰；单曲体积大于上限时保留当前播放文件，缓存可临时超出上限一首歌
- 图片内存：`LruCache`，约为最大堆的 1/8
- 图片磁盘：`cacheDir/images`，键为 URL 的 MD5，上限 96MB
- 歌词：内存 LRU，最多 50 首

---

## 构建

### 环境要求

- Android Studio
- JDK 17 或更高版本
- Android SDK 36
- Gradle 9.6.0（当前仓库未包含 Gradle Wrapper）

### 配置 SDK

在 `LiteMusicAndroid/local.properties` 中配置：

```properties
sdk.dir=C\:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
```

### 构建 Debug APK

使用 Android Studio 打开 `LiteMusicAndroid` 目录，或使用本地 Gradle：

```bash
gradle :app:assembleDebug
```

输出文件：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 版本信息

- `versionName`: 3.2.0
- `versionCode`: 42
- `applicationId`: `com.yesplaymusic.mobile`
- 源码 namespace: `com.litemusic`

---

## 使用说明

1. 安装并打开应用。
2. 在“我的”页点击登录。
3. 使用网易云音乐 App 扫描二维码登录。
4. 登录后可同步红心歌曲、收藏歌单、收藏专辑和关注艺人。

未登录状态下仍可浏览推荐内容与搜索，但 VIP / 付费歌曲可能无法播放。

---

## 已知限制

- 项目依赖网易云音乐非公开接口，接口变更或风控可能导致部分功能失效。
- VIP、付费或下架歌曲可能无法播放。
- 项目未包含单元测试与仪器测试。
- 仓库不提交 APK 与构建产物，APK 建议通过 GitHub Releases 分发。
- `minSdk 21` 限制了部分依赖版本与系统媒体 API 的使用，相关兼容逻辑集中在 `PlayerCompat21`。

---

## 免责声明

本项目为个人学习项目，与网易云音乐官方无任何关联。项目不提供音乐内容，所有内容版权归原权利方所有。请遵守相关服务条款与版权规定。
