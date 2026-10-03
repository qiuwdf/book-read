# 秋の小说 (book-read)

纯本地 TXT 小说阅读器，Android 4.0+。**零网络权限**——不上传任何数据，不联网，不内嵌广告。

> 读什么、存哪里，全部由你掌控。把 txt 文件放进手机，打开 app 就能看。

<!-- TODO: 建议在这里放两张截图（书架 + 阅读页），例如：
<p align="center">
  <img src="screenshots/bookshelf.png" width="270" />
  <img src="screenshots/reader.png" width="270" />
</p>
-->

## 特性

- **纯离线**：manifest 里没有 `INTERNET` 权限，物理上不可能联网，不内嵌广告
- **老设备 / 低配硬件友好**：最低支持 Android 4.0（minSdk 14），点读笔、儿童手表这类低配置设备也能安装运行；适配小屏 / 瘦长屏
- **大书库不卡**：实测 8000+ 本 / 20GB 书库，流式扫描 + 分行容错缓存，1GB 内存的老机型也能跑
- **操作逻辑与番茄小说 App 一致**：用惯番茄的用户零学习成本上手
- **秒开秒续读**：章节索引缓存 + 阅读进度持久化，替换 txt 文件后进度也不丢
- **封面支持**：txt 同目录放 `<book_id>.png` 即自动显示封面（3:4），无图时按书名生成配色封面
- **翻页引擎**：拖拽/点击翻页、动画中再次操作即时结算不吞页
- **音量键翻页**、电量显示、锁定竖屏

## 下载安装

**快速下载**：[Releases 页面](../../releases) → 下载最新的 `readbook-v*.apk` 安装即可。

- 安装后首次使用：给「所有文件访问」权限（按路径扫描本地目录需要）
- 小说 txt 自行准备，本仓库**不分发任何小说内容**

## 小说文件格式

应用按约定解析 txt，示例如下：

```
书名：冒烟测试书
作者：秋晚的枫
book_id=999001
状态：完结
评分：4.5
简介：一句话简介……

====

【第一卷：卷名】

第1章 章节标题

　　段落以两个全角空格缩进，段与段之间空一行。

第2章 章节标题

　　……
```

- 头部元数据（`书名/作者/book_id/简介` 等，可选）与正文用 `====` 分隔线隔开
- 章节行：`第X章 标题`；卷标题：`【第X卷：卷名】`
- **封面**：与 txt 同目录放 `book_id.png`（book_id 即头部 `book_id=` 的值），如 `999001.png`
- 编码支持 UTF-8 / GBK 等（自动探测，只读文件头 32KB，万册书库扫描不读全文）

### 兼容番茄小说下载器

本应用兼容 [Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader) 下载导出的小说 txt，
无需任何转换，导出后放进存储目录即可直接阅读——书名、作者、简介、章节、卷、封面（`book_id.png`）全部自动识别。

```text
番茄小说 --下载--> Tomato-Novel-Downloader --导出 txt--> 手机存储目录 --扫描--> 秋の小说
```

## 从源码构建

```
JDK 21 + Android Gradle Plugin 8.4.0 + Gradle 8.7 + compileSdk 34 + minSdk 14
```

```bash
# local.properties 里配好 sdk.dir 后：
./gradlew clean assembleDebug          # 调试包
./gradlew clean assembleRelease        # 发布包（需要 keystore.properties 配签名，
                                       #  没有则自动回落 debug 签名）
./gradlew testDebugUnitTest            # 47 条单元测试（Robolectric）
```

签名配置：工程根目录放 `keystore.properties`（`storeFile=` / `storePassword=` / `keyAlias=` / `keyPassword=`），
密钥文件放 `keystore/` 下。**这两个路径不入库**，用你自己的密钥即可。

## 工程结构

```
app/src/main/java/com/qiuwdf/readbook/
├── core/       # 书架扫描、缓存（流式 JSON Lines）、设置
├── parser/     # 编码探测 + txt 元数据/章节解析
├── reader/     # 阅读页 + 翻页引擎（ReaderView）
├── ui/         # 书架 / 详情 / 阅读Activity
├── util/       # 封面加载（LRU）、目录选择、格式化
└── widget/     # 封面控件（3:4 比例，进度条叠加）
```

## 开发说明

- **技术栈**：Java 8 + 原生 XML 布局，无 Kotlin、无跨端框架、依赖极轻（release APK 约 1.2MB）
- **开发方式**：本项目全程 AI 辅助开发，由 AI 与作者协作完成
- **质量保障**：47 条 Robolectric 单元测试 + R8 混淆发布，真机覆盖 Android 5.1 老机型

## 版权与免责

- 本应用只做**本地文件阅读**，不提供、不分发、不下载任何小说内容
- 用户须自行对放入的小说文件负责

## 许可证

[GPL-3.0](LICENSE) © 秋晚的枫

任何人可以自由使用、修改、分发本项目，但衍生作品必须同样以 GPL-3.0 开源。
