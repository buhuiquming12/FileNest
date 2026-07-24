# FileNest · 桌面文件整理工具（JavaFX + AI 建议）

> 一个单机、个人使用的桌面文件整理工具。核心理念只有一句：
> **AI 只负责「建议」，人是最后一道闸；所有移动都可撤销，绝不丢文件、绝不误删。**

选一个文件夹 → 单独扫描文件/文件夹占用 → 再按需生成「整理建议」或「AI 清理建议」 → 预览、人工执行或使用安全自动整理。扫描与建议互不耦合，清理建议仅预览、不自动删除。

---

## ✨ 功能特性

### 核心能力
- **手动选目录整理**：非后台常驻监控，整理时机完全可控。
- **规则分类（确定性）**：按扩展名/修改日期把文件归入 `文档 / 图片 / 视频 / 压缩包 / 代码 …` 等类别，快速、离线、永远可用。
- **全部文件清单**：扫描后单独显示当前目录顶层的全部常规文件，包括未知扩展名、无扩展名和隐藏/系统配置文件；同时显示类型、分类、大小、修改时间和安全状态。
- **两种安全自动整理**：「规则安全自动整理」只执行已知类型的确定性规则；「AI 建议自动整理」只接受置信度 ≥ 80% 且通过安全检查的 AI 动作，并要求输入确认短语。
- **精确去重（确定性）**：SHA-256 内容哈希，找出**逐字节相同**的重复文件（先按文件大小预筛，避免无谓哈希）。
- **执行前预览 + 勾选确认**：表格展示 `文件 | 建议去向 | 依据 | 来源 | 置信度`，置信度按颜色分级。
- **一键撤销**：每次整理写入操作日志，撤销 = 反向回放，把文件精确移回原位。
- **各文件夹占用排行**：使用最多 4 个有界工作线程并行扫描直接子树，按直接子文件夹展示大小、占比、文件数和子文件夹数；同一次遍历还保留最多 500 个大文件以及大型/典型嵌套目录供分析。扫描界面按已完成的一级目录显示真实进度条；`扫描`只收集事实，不调用 AI。
- **AI 清理建议（独立功能）**：基于已完成的扫描，覆盖缓存/临时目录、构建产物、开发依赖缓存、旧安装包与压缩包、日志/转储、大文件迁移和系统文件保护；展示完整绝对路径、大小、理由、置信度和来源，并可点击“打开”直接定位到对应文件夹。URL AI 与本地建议命中同一路径时保留更安全的结论，同时合并双方理由和来源；状态栏明确显示 API 返回条数或失败回退原因。该功能只提供建议，不直接删除。
- **C 盘安全清理**：预览用户/Windows 临时文件、崩溃转储和更新下载缓存，按项勾选、二次确认后清理；最近 24 小时项目默认不选。

### AI 建议功能（先独立生成并展示，不在扫描时执行）
1. **内容级智能分类** —— 识别混乱命名（`Screenshot_…`、`IMG_0001`、`微信图片…`、`发票…`、`简历…`），并对文本/无扩展名文件**嗅探首部内容**纠正误导性扩展名（例如内容像 CSV 的 `.txt` → 建议归入「表格」）。
2. **相似文件检测** —— 归一化文件名词干后聚类，识别「内容相似但不完全相同」的一组文件（如 `报告v1.docx` 与 `报告最终版.docx`），提示人工确认后归档/合并。
3. **归档结构建议** —— 「按项目」模式下，把疑似同一主题的一组文件建议归入统一的 `项目_xxx` 目录。

### 安全特性（个人工具最关键的部分）
| 风险 | 对策 |
|---|---|
| AI 建议出错导致误移动 | 所有 AI 来源动作**在类型层面强制** `requiresConfirm=true`，UI 默认不勾选 |
| 自动整理范围过大 | 仅处理所选目录顶层源文件，目标必须留在所选目录内，单批最多 500 项 |
| 隐藏/系统/符号链接被误动 | 只显示不自动整理；执行器再次检查符号链接、系统关键目录、`.filenest` 与路径越界 |
| AI 自动整理判断错误 | 仅允许置信度 ≥ 80% 的 AI 动作，且必须手动输入「确认AI整理」 |
| 移动后想反悔 | `OperationLog` 记录每次「源→目标」，撤销即反向回放 |
| 目标已存在同名文件 | 冲突只「跳过」或「自动重命名（`name (1).ext`）」，**绝不覆盖** |
| AI 服务超时/不可用 | 远程请求按最多 60 个文件分批串行发送，遇到 429/502/503/504 最多尝试 3 次；本地建议始终先生成，远程异常不会清空已有建议；状态栏显示 API 失败原因和本地回退状态 |
| AI 清理建议判断错误 | 建议页只读，不提供直接删除按钮；同路径冲突采用更保守的结论，并同时显示本地与 URL AI 的来源及风险理由 |
| C 盘清理误删 | 只允许删除本次安全白名单扫描返回的子项，不跟随符号链接、不删除白名单根目录，并要求人工勾选和二次确认 |

---

## 🧱 技术栈

| 项 | 选型 |
|---|---|
| 语言 / JDK | Java 17 (LTS) |
| UI | JavaFX 17.0.13（纯代码构建，无 FXML） |
| 持久化 | Jackson 2.17.x（操作日志存为 JSON） |
| 测试 | JUnit 5.10.x |
| 构建 | Maven 3.9+ |

---

## 🚀 快速开始

### 环境要求
- JDK 17（本仓库在 `D:\JDK` 的 JDK 17.0.8 上验证通过）
- Maven 3.9+
- 首次构建需联网下载 JavaFX / Jackson / JUnit 依赖

### 运行 GUI
```bash
mvn javafx:run
```

> **从 IDE（IntelliJ 等）运行**：请运行入口类 **`com.filenest.Launcher`**，**不要**直接运行 `App`。
> 直接运行 `App`（它 `extends Application`）会报「缺少 JavaFX 运行时组件」——这是 JDK 启动器针对
> module path 的检查。`Launcher` 不继承 `Application`，可绕过该检查并从 classpath 正常加载 JavaFX。
> （IDE 方式下会出现一行无害警告 `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'`，可忽略。）

### 运行测试
```bash
mvn test
```

### 仅编译
```bash
mvn -DskipTests compile
```

> 提示：若 `JAVA_HOME` 未指向 JDK 17，可临时设置，例如（Git Bash）：
> ```bash
> export JAVA_HOME='D:\JDK'
> ```

---

## 📁 项目结构

```
src/main/java/com/filenest
├── Launcher.java                    # 程序入口（推荐从 IDE 运行此类；不继承 Application）
├── App.java                         # JavaFX Application（由 Launcher / javafx:run 调起）
├── app/
│   └── OrganizeService.java         # 应用层门面：编排 扫描→分类→去重→AI→合并→执行→撤销
├── core/
│   ├── scanner/FileScanner.java     # 扫描目录（仅顶层常规文件，跳过隐藏/子目录）
│   ├── classifier/
│   │   ├── ClassifierStrategy.java  # 分类策略接口（OCP 扩展点）
│   │   ├── ExtensionClassifier.java # 按扩展名/日期分类（确定性、永远可用）
│   │   └── CategoryRules.java       # 扩展名 → 类别 的静态映射
│   ├── duplicate/DuplicateDetector.java  # SHA-256 精确去重
│   ├── advisor/
│   │   ├── AiAdvisor.java           # AI 建议接口（DIP 依赖倒置的核心缝隙）
│   │   ├── NoOpAiAdvisor.java       # 降级实现（无建议）
│   │   ├── HeuristicAiAdvisor.java  # 默认：本地启发式 AI（离线、无需 Key）
│   │   ├── TimeoutAiAdvisor.java    # 装饰器：超时/异常自动降级
│   │   └── LlmAiAdvisor.java        # OpenAI 兼容 URL API 调用、JSON 响应解析与路径校验
│   ├── executor/
│   │   ├── FileExecutor.java        # 唯一真正移动文件的组件；不覆盖、写日志
│   │   └── ConflictPolicy.java      # 冲突策略：SKIP / RENAME
│   ├── storage/                     # 文件夹大小统计与 C 盘安全清理
│   └── log/
│       ├── OperationLog.java        # 操作日志接口（撤销的事实来源）
│       └── JsonOperationLog.java    # JSON 文件实现（~/.filenest/operations.json）
├── model/                           # 全系统共享的数据契约（record）
- 整理扫描**只读取所选目录的顶层文件**，不递归移动已有子目录内容。未知、隐藏和系统配置文件会显示在清单中，但默认不参与自动整理。
│   ├── OrganizePlan.java            # 一次整理的完整计划
│   ├── FileMeta / OrganizeContext / OperationRecord / OperationBatch
│   ├── OrganizeResult / DuplicateGroup / ActionType
└── ui/
    ├── MainView.java                # 主窗口：目录大小、AI API 配置、整理计划
    ├── CleanupDialog.java           # C 盘清理预览、选择和确认窗口
    └── ActionRow.java               # 表格行视图模型（含勾选状态）

src/test/java/com/filenest          # 18 个测试类，48 个用例
```

---

## 🏗️ 架构设计

```
┌─────────────────────────────────────────────┐
│                 UI 层 (JavaFX)                │  只依赖 OrganizeService 门面
│   目录选择 / 计划表格 / 勾选确认 / 执行 / 撤销    │  不含任何文件操作逻辑
└───────────────────┬───────────────────────────┘
                    │
┌───────────────────▼───────────────────────────┐
│           应用服务层 OrganizeService            │  编排整个流程
│  扫描 → 规则分类 → 精确去重 → AI建议 → 合并/排序   │
└──┬───────────┬───────────┬───────────┬────────┘
   │           │           │           │
┌──▼───┐   ┌──▼─────┐  ┌──▼──────┐ ┌──▼─────────┐
│Scanner│  │Classifier│ │AiAdvisor│ │FileExecutor│
└───────┘   └──────────┘ └────┬────┘ └─────┬──────┘
   规则分类 与 AI 建议是「并列」信息源 ┘        │
   AI 挂了，规则计划照常出（容错隔离）    ┌─────▼──────┐
                                     │OperationLog │→ 撤销
                                     └─────────────┘
```

**关键设计决策**：`Classifier`（规则）与 `AiAdvisor`（AI）是**并列而非串联**的两个独立信息源，由 `OrganizeService` 融合决策——即使 AI 离线/超时/出错，规则分类仍独立工作，系统不会因 AI 故障而整体瘫痪。

### 设计原则落地

| 原则 | 体现 |
|---|---|
| 单一职责 (SRP) | 扫描/分类/去重/AI/执行/日志 各自独立，互不依赖实现细节 |
| 开闭原则 (OCP) | `ClassifierStrategy`、`AiAdvisor` 均为接口，新增能力不改核心流程 |
| 依赖倒置 (DIP) | 应用层只依赖 `AiAdvisor` 接口，本地启发式 / 大模型 / 降级实现可插拔 |
| 关注点分离 | UI 只做展示与确认，只认识 `OrganizePlan` 数据结构 |
| 最小知识 | GUI 不知道文件如何被分类，只消费计划 |
| 幂等可恢复 | 撤销 = 反向回放日志，而非「猜」原位置 |

---

## 📜 核心数据契约：`FileAction`

整个系统的「普通话」——UI 展示它、执行器消费它、日志记录它，各层无需知道彼此实现：

```java
public record FileAction(
    Path sourcePath,          // 源位置
    Path targetPath,          // 目标位置（SKIP 时等于源）
    ActionType type,          // MOVE / RENAME / SKIP
    String reason,            // 依据，如 "扩展名规则: .jpg → 图片"
    double confidence,        // 规则=1.0，AI=(0,1)
    boolean requiresConfirm,  // 低置信度/危险操作强制确认
    Source source             // RULE / AI
) { ... }
```

> **安全不变量写进了类型系统**：`FileAction` 的构造器强制「凡 `source==AI` 则 `requiresConfirm==true`」，任何 AI 来源的动作都不可能被漏标为「无需确认」。

---

## 🔌 AI 可插拔设计

应用层只依赖 `AiAdvisor` 接口。默认装配（见 `OrganizeService.createDefault()`）：

```
TimeoutAiAdvisor( HeuristicAiAdvisor,  ← 默认：本地启发式，离线可跑、无需 Key
                  NoOpAiAdvisor,        ← 降级：AI 不可用时无建议
                  超时 5 秒 )
```

### 接入 URL AI API
主窗口只需填写 **AI API 域名（或服务根地址）、API Key（可选）和模型名**，例如 `https://api.example.com`。客户端会自动将聊天请求补全为 `/v1/chat/completions`，将“获取模型”请求补全为 `/v1/models`；仍兼容完整端点、自定义网关前缀和 Ollama `/api/chat`、`/api/tags`。点击“应用 AI API”后，整理建议和清理建议分别调用模型；扫描本身不会调用 AI。

也可使用环境变量预填客户端：
- `FILENEST_LLM_ENDPOINT` —— API 域名或服务根地址，例如 `https://example.com`（自动补全 `/v1/chat/completions`）
- `FILENEST_LLM_API_KEY` —— API Key；本地无鉴权 API 可留空
- `FILENEST_LLM_MODEL` —— 请求中的模型名

模型必须返回 `suggestions` JSON 数组。客户端会校验文件名、目标相对路径和置信度；AI 建议始终默认不勾选且必须人工确认。整理请求最多取 180 个文件，按每批 60 个串行请求，单次 60 秒超时，整体保护时间为 195 秒。整理和清理请求遇到 429/502/503/504 时会退避重试，最多尝试 3 次。本地启发式建议会保留；清理建议会压缩输入清单并使用 180 秒请求超时。将地址留空并应用即可切回本地 AI。

---

## 💾 数据与文件

- **操作日志**：`~/.filenest/operations.json`（撤销依据；纯文本、可读、可移植）。
- **重复文件**：被建议移入待整理目录下的 `_重复文件/`（默认需确认，不自动执行）。
- 整理扫描**只读取所选目录的顶层文件**，不递归移动已有子目录内容。未知、隐藏和系统配置文件会显示在清单中，但默认不参与自动整理。

---

## ⚠️ 注意事项 / 已知边界（刻意不做，避免过度设计）

- 不引入数据库/服务端：本地 JSON 记日志已足够。
- 不做实时监控（`WatchService`）：手动触发更可控。
- 不让 AI 直接访问文件系统：AI 只生成 `FileAction` 建议，真正执行必须经过客户端安全策略和用户确认。
- GUI 需在有桌面环境的机器上通过 `mvn javafx:run` 启动。

---

## 📦 Windows 打包与发布

项目已提供本地 `jpackage` 打包脚本和 GitHub Actions 自动发布流程，可生成自带 Java 运行时的 **EXE 安装程序**与 **Portable ZIP**：

```powershell
.\scripts\package-windows.cmd -Type all -AppVersion 1.0.0
```

完整的环境安装、打包、GitHub Release 和常见问题说明见：**[Windows 打包与 GitHub Release 发布指南](docs/RELEASING.md)**。

---

## 🧪 测试

54 个 JUnit 5 用例，覆盖全类型文件扫描、并行空间统计与进度回调、完整路径展示与文件夹定位、分类与精确去重、自动整理安全门、路径越界/符号链接阻断、冲突不覆盖、撤销回放、AI 超时降级与大清单分批、API 清理建议来源合并、端点自动补全、URL 模型获取和日志持久化。

```bash
mvn test
# Tests run: 54, Failures: 0, Errors: 0
```
