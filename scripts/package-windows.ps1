[CmdletBinding()]
param(
    [ValidateSet("app-image", "exe", "all")]
    [string]$Type = "all",

    # jpackage 的 Windows 版本号必须为纯数字版本；例如 1.2.3。
    # 留空时从 pom.xml 读取，并把 1.0-SNAPSHOT 规范化为 1.0.0。
    [string]$AppVersion = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $projectRoot

function Assert-Command([string]$Name, [string]$InstallHint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "找不到 $Name。$InstallHint"
    }
}

function Invoke-Native([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Command 执行失败，退出码：$LASTEXITCODE"
    }
}

function Enable-Wix3 {
    if ((Get-Command candle.exe -ErrorAction SilentlyContinue) -and
        (Get-Command light.exe -ErrorAction SilentlyContinue)) {
        return
    }

    $programFilesX86 = [Environment]::GetFolderPath("ProgramFilesX86")
    if ($programFilesX86) {
        $wixBins = Get-ChildItem -Path $programFilesX86 -Directory -Filter "WiX Toolset v3*" -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName "bin" } |
            Where-Object { (Test-Path (Join-Path $_ "candle.exe")) -and (Test-Path (Join-Path $_ "light.exe")) }
        $wixBin = $wixBins | Select-Object -Last 1
        if ($wixBin) {
            $env:Path = "$wixBin;$env:Path"
        }
    }

    if (-not ((Get-Command candle.exe -ErrorAction SilentlyContinue) -and
               (Get-Command light.exe -ErrorAction SilentlyContinue))) {
        throw "生成安装程序需要 WiX Toolset 3.x（JDK 17 的 jpackage 不支持 WiX 4/5）。请先安装 WiX 3.14，再重新运行。只生成免安装版可使用：-Type app-image"
    }
}

Assert-Command "mvn" "请安装 Maven 3.9+ 并加入 PATH。"
Assert-Command "jpackage" "请安装完整的 JDK 17（不能只有 JRE）并把其 bin 目录加入 PATH。"

[xml]$pom = Get-Content -LiteralPath (Join-Path $projectRoot "pom.xml") -Raw
$projectVersion = [string]$pom.project.version
if ([string]::IsNullOrWhiteSpace($projectVersion)) {
    throw "无法从 pom.xml 读取版本号。"
}
$projectVersion = $projectVersion.Trim()
$requestedVersion = if ([string]::IsNullOrWhiteSpace($AppVersion)) { $projectVersion } else { $AppVersion.Trim().TrimStart("v") }

if ($requestedVersion -notmatch '^(\d+)\.(\d+)(?:\.(\d+))?') {
    throw "版本号 '$requestedVersion' 无法转换为 jpackage 版本号，请使用类似 1.2.3 的版本。"
}
$patch = if ($Matches[3]) { $Matches[3] } else { "0" }
$packageVersion = "$($Matches[1]).$($Matches[2]).$patch"

Write-Host "==> 构建 FileNest $packageVersion" -ForegroundColor Cyan
Invoke-Native "mvn" @("clean", "package")

$inputDir = Join-Path $projectRoot "target\package-input"
Invoke-Native "mvn" @(
    "-q",
    "org.apache.maven.plugins:maven-dependency-plugin:3.7.1:copy-dependencies",
    "-DincludeScope=runtime",
    "-DoutputDirectory=$inputDir"
)

$mainJar = Get-ChildItem -Path (Join-Path $projectRoot "target") -File -Filter "FileNest-*.jar" |
    Where-Object { $_.Name -notmatch '(-sources|-javadoc|-tests)\.jar$' } |
    Select-Object -First 1
if (-not $mainJar) {
    throw "没有在 target 中找到 FileNest 主 JAR。"
}
Copy-Item -LiteralPath $mainJar.FullName -Destination (Join-Path $inputDir $mainJar.Name) -Force

$outputDir = Join-Path $projectRoot "target\package"
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

$commonArgs = @(
    "--name", "FileNest",
    "--input", $inputDir,
    "--main-jar", $mainJar.Name,
    "--main-class", "com.filenest.Launcher",
    "--app-version", $packageVersion,
    "--vendor", "FileNest",
    "--description", "本地桌面文件整理与空间分析工具",
    "--dest", $outputDir,
    "--add-modules", "java.base,java.desktop,java.logging,java.net.http,java.prefs,java.sql,java.xml,jdk.crypto.ec,jdk.localedata,jdk.unsupported",
    "--java-options", "-Dfile.encoding=UTF-8"
)

$iconPath = Join-Path $projectRoot "packaging\FileNest.ico"
if (Test-Path -LiteralPath $iconPath) {
    $commonArgs += @("--icon", $iconPath)
}

if ($Type -in @("app-image", "all")) {
    Write-Host "==> 生成免安装版（自带 Java 运行时）" -ForegroundColor Cyan
    Invoke-Native "jpackage" ($commonArgs + @("--type", "app-image"))

    $appImageDir = Join-Path $outputDir "FileNest"
    $portableZip = Join-Path $outputDir "FileNest-$packageVersion-windows-x64-portable.zip"
    Compress-Archive -LiteralPath $appImageDir -DestinationPath $portableZip -CompressionLevel Optimal -Force
}

if ($Type -in @("exe", "all")) {
    Enable-Wix3
    Write-Host "==> 生成 Windows 安装程序" -ForegroundColor Cyan
    $installerArgs = @(
        "--type", "exe",
        "--win-menu",
        "--win-menu-group", "FileNest",
        "--win-shortcut",
        "--win-dir-chooser",
        "--win-per-user-install",
        "--win-upgrade-uuid", "BEF12F82-0B99-4C5A-911D-80FEADBF8D25"
    )
    Invoke-Native "jpackage" ($commonArgs + $installerArgs)
}

$releaseFiles = Get-ChildItem -Path $outputDir -File | Where-Object { $_.Extension -in @(".exe", ".zip") }
if ($releaseFiles) {
    $checksumLines = foreach ($file in $releaseFiles) {
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($file.Name)"
    }
    Set-Content -LiteralPath (Join-Path $outputDir "SHA256SUMS.txt") -Value $checksumLines -Encoding ASCII
}

Write-Host "`n构建完成：$outputDir" -ForegroundColor Green
Get-ChildItem -Path $outputDir | Select-Object Name, Length, LastWriteTime
