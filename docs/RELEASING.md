# Windows 打包与 GitHub Release 发布

FileNest 是 JavaFX 17 应用。仓库已经提供：

- `scripts/package-windows.cmd`：在 Windows 本地生成免安装版和安装程序（内部调用 PowerShell 脚本）；
- `.github/workflows/release.yml`：推送版本标签后，由 GitHub Actions 自动构建并上传 Release。

生成物都包含裁剪后的 Java 运行时，使用者不需要另装 Java。

## 1. 本地环境

安装并加入 `PATH`：

1. **JDK 17**（必须是 JDK，且 `jpackage --version` 能正常执行）；
2. **Maven 3.9+**；
3. 仅生成 `.exe` 安装程序时需要 **WiX Toolset 3.14**。

> JDK 17 的 `jpackage` 需要 WiX 3.x，WiX 4/5 不能替代它。

检查：

```powershell
java -version
mvn -version
jpackage --version
candle.exe -?
light.exe -?
```

## 2. 打包

在项目根目录运行：

```powershell
# 同时生成免安装 ZIP 和 EXE 安装程序
.\scripts\package-windows.cmd -Type all -AppVersion 1.0.0

# 只生成免安装版（不需要 WiX）
.\scripts\package-windows.cmd -Type app-image -AppVersion 1.0.0

# 只生成安装程序
.\scripts\package-windows.cmd -Type exe -AppVersion 1.0.0
```

输出位于 `target/package/`：

- `FileNest-1.0.0.exe`：Windows 安装程序；
- `FileNest-1.0.0-windows-x64-portable.zip`：解压即用版；
- `SHA256SUMS.txt`：下载文件的 SHA-256 校验值。

安装程序默认为**当前用户安装**，会创建开始菜单项和桌面快捷方式，不要求用户预先安装 Java。以后发布新版时不要修改脚本中的 `--win-upgrade-uuid`，否则 Windows 会把新版当作另一个产品。

### 自定义图标

把 Windows `.ico` 文件放到：

```text
packaging/FileNest.ico
```

脚本检测到它后会自动用于应用和安装程序。建议 ICO 同时包含 16、32、48、64、128、256 像素图层。

## 3. 上传到 GitHub Release（推荐：自动发布）

### 第一次关联 GitHub 仓库

先在 GitHub 新建一个空仓库，然后执行（替换用户名和仓库名）：

```powershell
git remote add origin https://github.com/<用户名>/<仓库名>.git
git branch -M main
git push -u origin main
```

### 发布一个版本

确认代码已经提交，然后创建并推送格式为 `v主版本.次版本.修订号` 的标签：

```powershell
git tag -a v1.0.0 -m "FileNest 1.0.0"
git push origin v1.0.0
```

GitHub 的 **Actions** 页面会运行 `Build and publish Windows release`。成功后，仓库 **Releases** 页面会自动出现：

- EXE 安装程序；
- Portable ZIP；
- SHA-256 校验文件；
- 自动生成的变更说明。

工作流中的 `GITHUB_TOKEN` 由 GitHub 自动提供，不需要自己创建 Token。仓库若限制工作流写权限，请进入：

`Settings → Actions → General → Workflow permissions`

选择 **Read and write permissions**。

也可以在 Actions 页面手动运行工作流进行试打包。手动运行只上传到该次 workflow 的 **Artifacts**，不会创建 Release；只有推送版本标签才会发布 Release。

## 4. 手动上传（可选）

如果不使用自动工作流：

1. 本地执行打包脚本；
2. 打开 GitHub 仓库的 `Releases → Draft a new release`；
3. 新建或选择 `v1.0.0` 标签；
4. 上传 `target/package/` 中的 `.exe`、`.zip` 和 `SHA256SUMS.txt`；
5. 填写版本说明并点击 `Publish release`。

安装 GitHub CLI 后，也可以执行：

```powershell
gh auth login
gh release create v1.0.0 `
  target/package/FileNest-1.0.0.exe `
  target/package/FileNest-1.0.0-windows-x64-portable.zip `
  target/package/SHA256SUMS.txt `
  --generate-notes --title "FileNest 1.0.0"
```

## 5. 常见问题

- **提示“禁止运行脚本”**：优先运行文档中的 `.cmd` 入口；它会为本次构建使用 `ExecutionPolicy Bypass`，不修改系统策略。
- **提示找不到 `jpackage`**：当前使用的是 JRE，或 JDK 的 `bin` 没加入 PATH；改用完整 JDK 17。
- **提示找不到 `candle.exe` / `light.exe`**：安装 WiX Toolset 3.14；或者先用 `-Type app-image` 只构建免安装版。
- **Windows SmartScreen 提示未知发布者**：未签名的个人项目通常会出现，这是代码签名问题，不是打包失败。正式分发可购买代码签名证书，再对 EXE 使用 `signtool` 签名。
- **标签推错了**：修复代码后建议发布新补丁版本（如 `v1.0.1`），不要覆盖用户已经下载过的同名版本。
