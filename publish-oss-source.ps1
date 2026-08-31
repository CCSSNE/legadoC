<#
publish-oss-source.ps1 — 开源发布：把本地 own 完整历史剥离专有路径后，作为公开仓库 origin/own 的清洗镜像强推。

机制（与 AGENTS.md §3「双构建路线 / 开源源码发布」一致）:
  - 本地 own = 完整私有历史，是专有代码唯一副本；origin/own（公开仓库 CCSSNE/legadoC）= 清洗镜像。
  - 本脚本在临时克隆上用 git filter-repo --invert-paths 剥离剥离清单（确定性改写：旧提交哈希稳定，
    后续同步通常为快进；纯专有提交会因变空被剪除），全历史校验为零后，在镜像末尾注入一个
    确定性时间戳的"空壳插件引导"提交（保证公开树 app/oss 两个 flavor 都可编译），
    最后从主仓库 --force 推 refs/heads/own。
  - 严禁绕过本脚本直接 `git push origin own`：本地与远程历史不同，非快进会被拒（防泄露保护），
    强推则会把专有历史重新公开。
  - 剥离清单改动必须与 AGENTS.md 同节同步。
  - 推送成功后自动把本地完整私有历史推送到私有备份仓 private（CCSSNE/legadoC-private，快进）。

剥离清单（相对仓库根）:
  app/src/app                                                  专有插件源码（百度TTS引擎/so/flavor manifest/引导实现）
  app/src/main/java/com/baidu                                  百度SDK JNI契约层（迁移前旧路径）
  app/src/main/java/io/legado/app/help/bdtts                   百度TTS引擎层（迁移前旧路径）
  app/src/main/jniLibs                                         百度引擎动态库（迁移前旧路径）
  app/src/main/java/io/legado/app/service/BdReadAloudService.kt        （迁移前旧路径）
  app/src/main/java/io/legado/app/ui/book/read/config/BdEngineManageActivity.kt （迁移前旧路径）

用法:
  .\publish-oss-source.ps1 [-DryRun]     # DryRun 只做克隆/剥离/校验/预览，不推送
#>
param(
  [switch]$DryRun
)
$ErrorActionPreference = 'Continue'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$own = $PSScriptRoot
$remoteName = 'origin'
$branch = 'own'
$privateRemote = 'private'
$backupForkUrl = 'https://github.com/legado-backup/legado-c.git'

$stripPaths = @(
  'app/src/app',
  'app/src/main/java/com/baidu',
  'app/src/main/java/io/legado/app/help/bdtts',
  'app/src/main/jniLibs',
  'app/src/main/java/io/legado/app/service/BdReadAloudService.kt',
  'app/src/main/java/io/legado/app/ui/book/read/config/BdEngineManageActivity.kt'
)

git filter-repo --version *> $null
if ($LASTEXITCODE -ne 0) { throw 'git filter-repo 不可用：先 pip install git-filter-repo' }

$localSha = git -C $own rev-parse $branch
if ($LASTEXITCODE -ne 0) { throw "本地分支 $branch 不存在" }
Write-Output "本地 own（完整私有历史）: $localSha，提交数 $((git -C $own rev-list --count HEAD))"

# 1) 临时克隆（filter-repo 要求新克隆）
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("legado-clean-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
try {
  git clone --quiet $own $tmp
  if ($LASTEXITCODE -ne 0) { throw '临时克隆失败' }

  Push-Location $tmp
  try {
    # 2) 剥离专有路径（确定性改写，纯专有提交变空被剪除）
    $frArgs = @('--force', '--invert-paths')
    foreach ($p in $stripPaths) { $frArgs += @('--path', $p) }
    git filter-repo $frArgs
    if ($LASTEXITCODE -ne 0) { throw 'filter-repo 剥离失败' }

    # 3) 全历史校验：剥离清单必须为零命中
    foreach ($p in $stripPaths) {
      $hits = git log --all --oneline -- $p
      if ($hits) { throw "剥离校验失败：$p 在历史中仍有提交" }
    }

    # 4) 注入空壳插件引导（复制 src/oss 的 no-op AppPlugins，固定时间戳保证哈希确定）
    $stubSrc = Join-Path $tmp 'app/src/oss/java/io/legado/app/plugin/AppPlugins.kt'
    $stubDir = Join-Path $tmp 'app/src/app/java/io/legado/app/plugin'
    if (-not (Test-Path $stubSrc)) { throw '清洗树中缺少 src/oss 空壳 AppPlugins，无法注入' }
    New-Item -ItemType Directory -Path $stubDir -Force | Out-Null
    Copy-Item $stubSrc (Join-Path $stubDir 'AppPlugins.kt') -Force
    git add -A
    $env:GIT_AUTHOR_DATE = '2026-08-30T00:00:00+0000'
    $env:GIT_COMMITTER_DATE = '2026-08-30T00:00:00+0000'
    git commit -m '开源发布：注入空壳插件引导（发布脚本自动维护，勿手改）'
    if ($LASTEXITCODE -ne 0) { throw '空壳引导提交失败' }
    Remove-Item Env:GIT_AUTHOR_DATE -ErrorAction SilentlyContinue
    Remove-Item Env:GIT_COMMITTER_DATE -ErrorAction SilentlyContinue

    $cleanSha = git rev-parse HEAD
    $cleanCount = git rev-list --count HEAD
    Write-Output "清洗镜像: $cleanSha，提交数 $cleanCount（纯专有提交已剪除，其余历史原样保留）"

    if ($DryRun) {
      Write-Output '--- DryRun：清洗镜像最近8条 ---'
      git log --oneline -8
      Write-Output '--- DryRun：HEAD 顶层内容 ---'
      git ls-tree --name-only HEAD
      return
    }

    # 5) 从临时克隆推送（清洗对象只在临时克隆；远端URL取自主仓库，凭据走全局凭据管理器）
    $originUrl = git -C $own remote get-url $remoteName
    git remote add $remoteName $originUrl
    if ($LASTEXITCODE -ne 0) { throw '临时克隆添加远端失败' }
    git push --force $remoteName ("{0}:refs/heads/{1}" -f $cleanSha, $branch) 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '清洗镜像推送失败' }
    git -C $own fetch $remoteName --quiet 2>&1 | Out-Null
    Write-Output "已强推公开镜像：origin/$branch = $cleanSha"
    git -C $own log "refs/remotes/$remoteName/$branch" --oneline -5 2>&1 | Out-Null

    # 6) 私有备份仓同步（完整私有历史含专有代码；正常为快进）
    git -C $own remote get-url $privateRemote *> $null
    if ($LASTEXITCODE -ne 0) {
      Write-Output "[警告] 未配置私有备份 remote '$privateRemote'，跳过备份推送"
    } else {
      git -C $own push $privateRemote ("{0}:refs/heads/{1}" -f $localSha, $branch) 2>&1 | Out-Null
      if ($LASTEXITCODE -ne 0) { throw '私有备份推送失败' }
      Write-Output "已同步私有备份：private/own = $localSha（完整历史）"
    }

    # 7) 公开备份fork同步（legado-backup/legado-c，与公开镜像同一条清洗历史）
    git push --force $backupForkUrl ("{0}:refs/heads/{1}" -f $cleanSha, $branch) 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '公开备份fork推送失败' }
    Write-Output "已同步公开备份fork：legado-backup/legado-c own = $cleanSha"
  } finally { Pop-Location }
} finally {
  if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
}