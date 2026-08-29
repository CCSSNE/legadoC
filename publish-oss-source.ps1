<#
publish-oss-source.ps1 — 开源源码发布：把本仓库（own）HEAD 中"已提交"的源码导出为公开仓库快照，单提交同步推送。

用法:
  .\publish-oss-source.ps1 -PublicRepo 'D:\AI\audio\legadoC-oss' [-RemoteName origin] [-Branch main] [-DryRun]

规则（与 AGENTS.md §3「双构建路线 / 开源源码发布」一致）:
  - 严禁把 own 分支直接 push 到公开仓库：历史提交里含 app/src/app 专有内容与 .so，push 分支 = 泄露全部历史。
  - 本脚本只导出 HEAD 的已跟踪文件（git archive，天然排除构建产物与本地未跟踪垃圾），
    删除排除清单中的私有路径后镜像进公开仓库，公开仓库历史因此永不含专有内容。
  - 排除清单改动必须与 AGENTS.md 同节同步。

排除清单（相对仓库根，目录）:
  app/src/app    专有插件源码（百度TTS引擎/com.baidu SDK/jniLibs so/flavor manifest/插件引导实现）；
                 发布树会注入 src/oss 的空壳 AppPlugins（同 FQCN no-op），保证公开树全部变体可编译
  AGENTS.md      私有项目规则（含本机路径与交付基线）
  docs           私有文档与截图
  tools          私有调试工具链（模拟器路径/Frida 探针等）
#>
param(
  [Parameter(Mandatory = $true)][string]$PublicRepo,
  [string]$RemoteName = 'origin',
  [string]$Branch = 'main',
  [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$own = $PSScriptRoot

$excludeDirs = @('app/src/app', 'AGENTS.md', 'docs', 'tools') # 目录；AGENTS.md 按文件处理

if (-not (Test-Path (Join-Path $PublicRepo '.git'))) {
  throw "公开仓库不存在或不是 git 仓库：$PublicRepo（先 git init 或 clone）"
}

$ownSha = git -C $own rev-parse --short HEAD
if ($LASTEXITCODE -ne 0) { throw '无法读取 own HEAD' }

$dirty = git -C $own status --porcelain
if ($dirty) { Write-Output "[警告] own 有未提交改动，本次发布仅包含已提交内容（HEAD=$ownSha）" }

# 1) 导出 HEAD 已跟踪文件到临时目录
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("legado-oss-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
  git -C $own archive --format=tar HEAD | tar -x -C $tmp
  if ($LASTEXITCODE -ne 0) { throw 'git archive 导出失败' }

  foreach ($item in $excludeDirs) {
    $p = Join-Path $tmp ($item -replace '/', '\')
    if (Test-Path $p) { Remove-Item -Recurse -Force $p }
  }

  # 2) 注入 app flavor 空壳 AppPlugins（与 src/oss 同 FQCN 的 no-op）：公开树所有变体均可编译
  $stubSrc = Join-Path $tmp 'app\src\oss\java\io\legado\app\plugin\AppPlugins.kt'
  $stubDstDir = Join-Path $tmp 'app\src\app\java\io\legado\app\plugin'
  if (-not (Test-Path $stubSrc)) { throw '导出树中缺少 src/oss 空壳 AppPlugins，无法注入' }
  New-Item -ItemType Directory -Path $stubDstDir -Force | Out-Null
  Copy-Item $stubSrc (Join-Path $stubDstDir 'AppPlugins.kt') -Force

  # 3) 镜像进公开仓库：先清空除 .git 外的全部旧内容，保证"删除"也能同步
  Push-Location $PublicRepo
  try {
    if (git rev-parse --verify --quiet ("refs/heads/" + $Branch)) {
      git checkout $Branch
    } elseif (git rev-parse --verify --quiet ("refs/remotes/" + $RemoteName + "/" + $Branch)) {
      git checkout -b $Branch ("-t" + $RemoteName + "/" + $Branch)
    } else {
      git checkout -b $Branch
    }
    if ($LASTEXITCODE -ne 0) { throw "切换分支 $Branch 失败" }

    Get-ChildItem -Force | Where-Object { $_.Name -ne '.git' } | Remove-Item -Recurse -Force
    Copy-Item -Path (Join-Path $tmp '*') -Destination . -Recurse -Force
    git add -A
    if ($LASTEXITCODE -ne 0) { throw '公开仓库暂存失败' }

    if ($DryRun) {
      Write-Output '--- DryRun 变更预览（未提交） ---'
      git status -s | Select-Object -First 50
      return
    }

    $staged = (git diff --cached --name-only | Measure-Object -Line).Lines
    if ($staged -eq 0) { Write-Output '公开仓库无变更，跳过提交与推送'; return }

    git commit -m ("同步开源版源码（own @ " + $ownSha + "）")
    if ($LASTEXITCODE -ne 0) { throw '公开仓库提交失败' }
    git push -u $RemoteName $Branch
    if ($LASTEXITCODE -ne 0) { throw '公开仓库推送失败' }
    Write-Output '--- 公开仓库最近提交 ---'
    git log --oneline -3
  } finally { Pop-Location }
} finally {
  if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
}