<p align="center">
  <img src="assets/banner.svg" width="720" alt="QQ AI 嘴替" />
</p>

<h1 align="center">QQ AI 嘴替</h1>

<p align="center">
  <b>不会回？不知道怎么回？想换语气？让 AI 替你嘴。</b>
</p>

<p align="center">
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/License-GPLv3-blue.svg" /></a>
  <a href="https://github.com/Winkmoon/QQ-AI-Reply-Assistant/releases"><img alt="Release" src="https://img.shields.io/github/v/release/Winkmoon/QQ-AI-Reply-Assistant" /></a>
  <img alt="LSPosed" src="https://img.shields.io/badge/LSPosed-module-brightgreen" />
  <img alt="Xposed" src="https://img.shields.io/badge/Xposed-API%2082-green" />
  <img alt="Android" src="https://img.shields.io/badge/Android-8%2B-blue" />
  <img alt="API" src="https://img.shields.io/badge/API-OpenAI%20Compatible-orange" />
</p>

一个 LSPosed/Xposed 模块，在 QQ 聊天输入框上方提供可拖动的 AI 入口：
不会说话、不知道该回什么、想换语气（礼貌/雌小鬼/zako/猫娘/阴阳怪气等）都能直接用 AI 生成回复，一键发送或填入输入框。

## 功能

- 在 QQ 群聊/会话页显示可拖动的圆形 AI 按钮
- 点击后输入想说的话 / 不知道怎么回的内容，调用 OpenAI 兼容 API 生成多条回复
- 可自定义提示词实现不同语气/人设：礼貌、雌小鬼、zako、猫娘、冷冰冰等
- 结果可：
  - **发送**：直接通过 QQ 发送按钮发出
  - **编辑**：以系统粘贴方式填入 QQ 输入框，由用户自行修改
  - **取消**：放弃
- 首次进入聊天页显示“查找 hook 点”进度弹窗：
  - 自动查找 QQ 版本
  - 查找输入框 `com.tencent.mobileqq:id/input`
  - 查找发送按钮 `com.tencent.mobileqq:id/send_btn`
  - 同版本只弹一次，缓存到 QQ 数据目录
- 无 Key 时可在 QQ 内直接填写 API Key，写入 QQ 自己的目录

## 配置

### 方式一：模块主页

打开“QQ AI 嘴替”填写：

- API Key
- API Base URL（任意 OpenAI 兼容服务，按服务商填写，如 `https://api.openai.com/v1`）
- 模型（按服务商填写，如 `gpt-4o-mini`）
- 提示词
- 返回条数

### 方式二：QQ 内配置

如果 QQ 读不到配置，在 QQ 里点 AI → 输入 → AI 生成，会弹出 **QQ 内配置 AI** 对话框，填一次后保存到 QQ 自己目录。

## 构建

不需要 Gradle，使用 Android SDK 命令行工具：

```bash
bash build.sh
```

产物：`build/QQAiAssist.apk`

要求：

- JDK 8+
- Android SDK（`android-34` platform、build-tools）
- `d8/aapt2/zipalign/apksigner/keytool/zip`

首次构建会自动生成 `keystore.jks`（自签名）。

### GitHub Actions 使用固定签名

在仓库 Secrets 中配置以下变量后，Actions 会用固定签名而不是每次生成新签名：

- `KEYSTORE_BASE64`：keystore 的 Base64 内容
- `KEYSTORE_PASSWORD`：store password
- `KEYSTORE_ALIAS`：别名
- `KEY_PASSWORD`：key password

未配置时工作流仍可构建，但每次会用新自签名 keystore。

## 安装

1. 安装 `QQAiAssist.apk`
2. 在 LSPosed 管理器启用模块，作用域勾选 `com.tencent.mobileqq`
3. 重启/软重启框架
4. 打开 QQ 进入聊天页即可看到 AI 按钮

## 项目结构

```text
app/src/main/java/com/qqaiassist/
  MainActivity.java     # 模块设置主页
  QQAiAssist.java       # Xposed 主逻辑：入口、润色、发送/编辑
app/src/main/assets/xposed_init
app/src/main/res/values/arrays.xml
build.sh
enable_lsposed.py       # 可选：手动写 LSPosed DB 的脚本（仅调试）
```

## AI 参与声明

本模块在开发过程中有人工智能参与，包括但不限于：

- 代码编写与修改
- 问题排查与调试
- 逆向分析与 Hook 点定位辅助
- 文档整理与翻译

项目中部分代码可能由 AI 生成或辅助完成，已尽力进行人工审查与测试，但无法保证完全无缺陷。使用前请自行阅读代码、评估风险，并对你的账号与设备负责。

## 免责声明

- 仅供学习与技术研究使用
- 使用第三方 AI API 需要自行承担费用
- 请遵守腾讯 QQ 用户协议及当地法律法规
- 因使用本模块产生的账号风险/封禁等后果自负

## License

[GPL-3.0](LICENSE)

- 可学习/修改/再分发
- 衍生作品必须使用 GPL-3.0 开源共享（禁止闭源）
- 允许商用，但商用衍生品也必须开源
