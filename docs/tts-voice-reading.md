# 语音阅读（TTS）功能调研

基线：v1.0.32（versionCode 33）；分支 `feature/tts-reading`；调研日期 2026-10-10。
硬性约束：**必须在 Android 5.1（API 22）及以上可用**；**尽量减小体积**。

---

## 0. 一句话结论

走**系统 TTS 引擎**（`android.speech.tts.TextToSpeech`，API 1 起可用）：APK 体积增量约 **3~6KB**
（纯 Java 两个类 + 1 个矢量图标 + 几条字符串），**不新增任何依赖、不需要任何权限**。
代价是**依赖设备已装语音引擎** —— 而实测这台目标真机**当前一个引擎都没有**，所以
「引擎检测 + 引导安装」不是可选项，而是必需功能。内置离线引擎（几十 MB）与「尽量减小体积」
直接冲突，否决。

---

## 1. 真机实测（Q7 / Android 5.1 / API 22，2026-10-10）

| 检查项 | 命令 | 实测结果 |
|---|---|---|
| 系统版本 | `getprop ro.build.version.sdk` | `22`（Android 5.1） |
| 默认合成引擎设置 | `settings get secure tts_default_synth` | `com.macrohard.tts` |
| 默认语言设置 | `settings get secure tts_default_locale` | `me.ag2s.tts:zh_CN` |
| 上述两个包是否真的装着 | `pm list packages` | **两个都不在设备上** → 设置是失效残留 |
| 谁注册了 TTS 服务 | `dumpsys package \| grep -c android.intent.action.TTS_SERVICE` | **`0`** |
| 系统自带引擎（PicoTTS 等） | `pm list packages -s \| grep -i -e svox -e pico -e tts` | 无（只有 `com.mediatek.voiceextension`、`com.baidu.voicesearch`，都不是 TTS 引擎） |
| 第三方包（17 个） | `pm list packages -3` | 无任何语音引擎 |

**结论：这台设备上没有任何可用的 TTS 引擎。** 直接开读的表现是**静音**：
`TextToSpeech` 初始化回调 `ERROR`，或 `setLanguage(zh_CN)` 返回 `LANG_MISSING_DATA`。
因此必须实现：

- 初始化失败 / `getEngines()` 为空 → 给出明确提示 + 「安装语音引擎」引导
  （`Engine.ACTION_INSTALL_TTS_DATA`，或跳应用商店/官网）。
- 语种不可用（返回值 < `LANG_AVAILABLE`）→ 提示缺中文数据，引导在系统
  「设置 → 语言和输入法 → 文字转语音输出」里下载。

> 换句话说：这个功能在**装了引擎的机器上开箱可用**，在某些干净 ROM 上需要用户先装一个
> 5~10MB 的引擎 APK（讯飞语音引擎 / 百度语音引擎 / Google TTS / MultiTTS 等）。这是选型
> 的固有权衡，必须在应用内讲清楚，而不是让用户对着静音发呆。

---

## 2. 三条路线的体积代价

| 路线 | 体积增量 | 中文可用性 | 结论 |
|---|---|---|---|
| **A. 系统 TTS 引擎**（`TextToSpeech`） | **≈0**（只用框架类，零依赖） | 取决于所装引擎，常见中文引擎都支持 | ✅ **采用** |
| B. 内置离线神经网络引擎（sherpa-onnx / piper 中文） | **+20~60MB**（模型 + 各 ABI 的 native so） | 开箱可用、纯离线 | ❌ 与 1.24MB 的包体不在一个量级，且 MT6582 跑不动 |
| C. 内置云端 SDK（讯飞 / 百度） | +3~10MB | 需联网 | ❌ 与本应用「零网络权限」定位冲突 |

---

## 3. API 可用性（实测，不是推测）

来源：Robolectric 依赖的 `android-all-instrumented-5.1.1_r9`，`javap android.speech.tts.TextToSpeech`。

| 能力 | 5.1 可用 | 说明 |
|---|---|---|
| `speak(String,int,HashMap)` | ✅ | API 1 的重载，兼容面最广（21 起标记废弃，但 5.1 上工作正常） |
| `speak(CharSequence,int,Bundle,String)` | ✅ | API 21 起 |
| `setOnUtteranceProgressListener` + `onStart/onDone/onError` | ✅ | API 15 起 —— **自动翻页就靠 `onDone`** |
| `getEngines()` / `getDefaultEngine()` / `setEngineByPackageName()` | ✅ | 引擎检测与优选 |
| `setSpeechRate()` / `setPitch()` / `setLanguage()` / `getLanguage()` / `isSpeaking()` / `stop()` / `shutdown()` | ✅ | |
| `Engine.ACTION_INSTALL_TTS_DATA` / `ACTION_CHECK_TTS_DATA` | ✅ | 引导安装引擎数据 |
| `onRangeStart`（逐字回调） | ❌ **API 26 才有** | → 5.1 上**做不到逐字高亮**，进度只能按「句 / 页」维度 |
| `onStop(String,boolean)` | ❌ API 23 才有 | 停止语义要自己维护 |

对方针的影响：**逐字高亮跟读不做**；代码里凡涉及 API 23+ 的分支都必须条件判断（现有代码
已有这种习惯）。

---

## 4. 功能设计（分三阶段，阶段 1 是本次要做的）

### 阶段 1 · 最小可用（只在前台阅读页出声）

- 阅读页底部面板加一个「朗读」按钮（`btn_tts`）：点一下**从当前页开始读**，再点停止；
  按钮文案/状态跟随播放状态（朗读 ↔ 停止）。
- **文本切分**：把当前页文本按「。！？；…\n」切成句子 —— 独立纯 Java 类，可单测。
- **入队**：`QUEUE_ADD` 逐句入队，每句一个 utteranceId（形如 `p<页号>_s<句号>`），
  便于 `onDone` 时判断进度。
- **自动翻页**：本页句子全部 `onDone` → 调 `mReader.next()`；跨章沿用现有机制
  （`turn()` 会自动 `queueTurn()`，等 `setNeighbor()` 到达后 `drainQueuedTurns()`）；
  页真正变了以后由 `onPageChanged` 回调继续喂下一页文本。
- **手动翻页 / 切章 / 退出阅读页** → 立即 `stop()` 并清空队列，避免"读着上一页"。
- **语速**：设置面板加「语速」选项（0.8 / 1.0 / 1.2），`setSpeechRate` 即时生效并存 Prefs。

### 阶段 2 · 后台播放（工作量最大，建议阶段 1 验过再定）

- 前台 Service + 通知栏控制（上一句 / 暂停 / 下一句 / 停止），`AudioManager` 音频焦点，来电自动暂停。
- 兼容分支：API 26+ 需要 NotificationChannel；API 34 需要 `foregroundServiceType` +
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限（5.1 上这些分支都不进）。
- 会明显增加清单与代码复杂度，因此与阶段 1 分开评估。

### 阶段 3 · 体验细节

定时停止（10/20/30 分钟）、按章连读、耳机按键控制、朗读时是否强制亮屏。

---

## 5. 接入点（具体到文件）

| 文件 | 改动 |
|---|---|
| `reader/ReaderView.java` | 新增 `public String pageText()`：把 `mCurrent.pages.get(mPage)` 的行拼起来并去掉 `\u3000\u3000` 缩进（当前没有对外暴露页文本的接口） |
| `reader/SpeechSegmenter.java` | **新增**：纯逻辑分句（可 `javac` 单编测试，与现有 `tools/parsertest` 同思路） |
| `reader/TtsSpeaker.java` | **新增**：封装 `TextToSpeech` —— init / 引擎检测 / 滑动窗口入队 / 停止 / 语速 / 回调 |
| `ui/ReaderActivity.java` | 按钮接线 + 生命周期（`onPause` 停读、`onDestroy` `shutdown()`）+ 在 `onPageChanged` 里续读 |
| `core/Prefs.java` | 加 `reader_tts_rate`（可选 `reader_tts_engine`） |
| `res/layout/activity_reader.xml` | 底部面板加一个按钮（复用现有按钮样式与配色） |
| `res/values/strings.xml` | 约 6 条（朗读 / 停止 / 语速 / 未安装引擎提示 / 去安装） |
| `res/drawable/` | 1 个纯矢量图标（<1KB） |
| `AndroidManifest.xml` | **阶段 1 不动**（不需要任何权限） |

---

## 6. 风险与对策

1. **没引擎 / 没中文数据**（本机现状）→ 检测 + 引导安装；`setLanguage` 结果低于
   `LANG_AVAILABLE` 时给出「缺中文语音数据」的提示。
2. **初始化慢**：`TextToSpeech` 构造是异步的，部分引擎首次要拉起服务（1~3 秒）→
   把关播动作记在待办里，`init` 成功后再 flush，**不要在主线程等**。
3. **一次灌太多句**：部分引擎的队列有长度限制 → 只预排 2~3 句，`onDone` 再补，滑动窗口。
4. **回调线程不一致**：不同引擎的 `onDone` 回调线程不同 → 统一 `post` 回主线程再操作 UI / 翻页。
5. **与手动翻页冲突**：翻页后要么按新页重新起读、要么停止，不能两处同时喂文本。
6. **功耗**：MT6582 上 TTS 常驻耗电明显 → 只在播放期间持有音频焦点，退出即 `stop()`。
7. **与「音量键翻页」打架**：朗读中把音量键交还给音量控制；同时指定
   `KEY_PARAM_STREAM` 为音乐流，避免默认走通话音量。

---

## 7. 测试方案（无需真机的部分已核实可做）

- **纯逻辑**：`SpeechSegmenter` 分句 —— 中英混排、省略号、引号内句号、超长句截断。
- **Robolectric**：`ShadowTextToSpeech` 在 4.13 中存在，可用方法已用 `javap` 核实：
  `getLastSpokenText()`、`getUtteranceProgressListener()`、`isShutdown()`、`isStopped()`、
  `getQueueMode()`、`getCurrentLanguage()`、`simulateSynthesizeToFileResult(int)`。
  可写：① 引擎不可用时给提示且不崩溃；② 触发 `onDone` 后自动翻页（手动唤起 shadow 的 listener）；
  ③ 停止后不再入队；④ 语速改动被透传。
- **真机（Q7 / 5.1）**：只能验出音、音质、跨章衔接、停止响应 —— 前提是先装一个中文引擎。

---

## 8. 明确不做

- 不做逐字高亮跟读（5.1 没有 `onRangeStart`）。
- 不内置任何语音模型 / 引擎（体积与性能双重否决）。
- 不引入第三方 TTS SDK，不申请网络权限。

---

## 9. 下一步

确认阶段 1 的范围后，在本分支实现：新增 2 个类 + `ReaderView` 1 个方法 + 底部面板 1 个按钮
+ 约 6 条字符串 + 1 个矢量图标；目标仍是 **58 条现有用例不回归 + 新增 TTS 用例全绿 + lint 0 error**。
