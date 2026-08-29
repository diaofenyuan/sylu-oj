<#
.SYNOPSIS
    Task 1 验收测试：环境核查、网络入口和上线风险登记。

.DESCRIPTION
    校验 Task 1 五个步骤的交付物齐备且内容完整，并联动 scripts/compliance/evaluate-gate.ps1
    验证门禁判定：阻断项失败时禁止生产安装，风险项必须有编号和负责人。

    覆盖要点（对应执行计划 Task 1 Step 1~Step 5）：
      Step 1 - 环境基线记录覆盖 Ubuntu 22.04、物理机、CPU/内存/磁盘、root/sudo、
               /dev/kvm、AppArmor、cgroups v2、nftables、磁盘加密和时间同步；
      Step 2 - 网络确认单覆盖 VPN、中转机、192.* 地址、入口端口、反向代理与可信 HTTPS
               证书，且未确认时阶段判定为“仅内测”；
      Step 3 - 无堡垒机补偿控制覆盖来源限制、SSH 密钥、禁止 root/密码、auditd、
               sudo 审计和工单记录；
      Step 4 - 同机备份限制、可接受数据丢失范围和项目负责人签字栏齐备；
      Step 5 - 阻断项失败时门禁为“禁止安装”，风险项具备编号与责任人。

    门禁场景由真实清单派生后写入临时目录，不修改仓库内的清单文件。
    兼容 Windows PowerShell 5.1 与 PowerShell 7。

.PARAMETER Root
    仓库根目录，默认为当前脚本所在目录的上两级。

.PARAMETER KeepTemp
    保留临时场景目录，便于排查。

.EXAMPLE
    pwsh -File tests/docs/baseline.test.ps1

.EXAMPLE
    powershell -File tests\docs\baseline.test.ps1 -KeepTemp
#>
[CmdletBinding()]
param(
    [string]$Root,

    [switch]$KeepTemp
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Test-OjEmpty {
    param($Value)
    if ($null -eq $Value) { return $true }
    return ([string]$Value).Trim().Length -eq 0
}

if (Test-OjEmpty $Root) {
    $Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
$Root = [System.IO.Path]::GetFullPath($Root)

$script:EnginePath = Join-Path $Root (Join-Path 'scripts' (Join-Path 'compliance' 'evaluate-gate.ps1'))
$script:DataPath = Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'prerequisites.json'))
$script:BaselinePath = Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'environment-baseline.md'))
$script:NetworkPath = Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'network-confirmation.md'))
$script:BastionPath = Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'no-bastion-compensation.md'))
$script:ApprovalPath = Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'approval-record.md'))
$script:RiskRegisterPath = Join-Path $Root (Join-Path 'docs' 'risk-register.md')
$script:TraceabilityPath = Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'requirements-traceability.md'))
$script:TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('oj-baseline-test-' + [guid]::NewGuid().ToString('N'))
$script:FutureReviewDate = (Get-Date).Date.AddDays(180).ToString('yyyy-MM-dd')
$script:Passed = 0
$script:Failed = 0
$script:Failures = New-Object System.Collections.ArrayList

function Assert-Oj {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Detail = ''
    )

    if ($Condition) {
        $script:Passed++
        Write-Output ('  [PASS] ' + $Name)
    } else {
        $script:Failed++
        $null = $script:Failures.Add($Name)
        Write-Output ('  [FAIL] ' + $Name)
        if (-not [string]::IsNullOrWhiteSpace($Detail)) {
            Write-Output ('         ' + $Detail)
        }
    }
}

function Read-OjText {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-Content -LiteralPath $Path -Raw -Encoding UTF8)
}

function Invoke-OjGate {
    param(
        [Parameter(Mandatory = $true)][string]$ChecklistPath,
        [switch]$SkipEvidenceCheck
    )

    # 不能使用数组展开（@arguments）传参：数组元素按位置绑定，字符串 '-AsJson'
    # 不会被重新解析为参数名，在 Windows PowerShell 5.1 下会触发 ParameterBindingException。
    # 必须显式使用命名参数；-SkipEvidenceCheck:$bool 同时兼容 5.1 与 PowerShell 7。
    $output = & $script:EnginePath -Path $ChecklistPath -AsJson -SkipEvidenceCheck:$SkipEvidenceCheck
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        throw ('门禁引擎执行失败(退出码 ' + $code + '): ' + $ChecklistPath)
    }
    $text = ($output | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) {
        throw ('门禁引擎未返回 JSON: ' + $ChecklistPath)
    }
    return ($text | ConvertFrom-Json)
}

function New-OjScenario {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Mutate,
        [switch]$NoEvidence
    )

    $scenarioRoot = Join-Path $script:TempRoot $Name
    $complianceDir = Join-Path $scenarioRoot (Join-Path 'docs' 'compliance')
    New-Item -ItemType Directory -Path $complianceDir -Force | Out-Null

    # 复制风险登记簿，使场景中的 acceptedRiskId 可被检索到
    Copy-Item -LiteralPath $script:RiskRegisterPath -Destination (Join-Path $scenarioRoot (Join-Path 'docs' 'risk-register.md')) -Force

    $document = (Read-OjText -Path $script:DataPath) | ConvertFrom-Json
    foreach ($item in @($document.items)) {
        & $Mutate $item
    }

    $evidenceDir = Join-Path $scenarioRoot (Join-Path 'docs' (Join-Path 'evidence' 'prerequisites'))
    foreach ($item in @($document.items)) {
        $status = ([string]$item.status).Trim().ToUpperInvariant()
        if ($status -ne 'PASS') { continue }
        $relative = 'docs/evidence/prerequisites/' + $item.id + '.md'
        $item.evidencePath = $relative
        if ($NoEvidence) { continue }
        New-Item -ItemType Directory -Path $evidenceDir -Force | Out-Null
        $target = Join-Path $scenarioRoot ($relative -replace '/', [System.IO.Path]::DirectorySeparatorChar)
        [System.IO.File]::WriteAllText($target, ('# 测试证据 ' + $item.id + "`n`n由 baseline 验收测试自动生成。`n"), (New-Object System.Text.UTF8Encoding($false)))
    }

    $scenarioFile = Join-Path $complianceDir 'prerequisites.json'
    [System.IO.File]::WriteAllText($scenarioFile, ($document | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
    return $scenarioFile
}

function Get-OjAllPassMutator {
    return {
        param($item)
        $level = ([string]$item.level).Trim().ToUpperInvariant()
        if ($level -eq 'BLOCKING') {
            $item.status = 'PASS'
        } elseif ($level -eq 'WARN') {
            $item.status = 'WARN_ACCEPTED'
            $item.approvalRef = 'AP-TEST-01'
            $item.approver = '测试审批人'
            $item.reviewDate = $script:FutureReviewDate
        } else {
            $item.status = 'PASS'
        }
    }
}

Write-Output '========================================='
Write-Output ' sylu-oj Task 1 环境核查与上线风险登记验收'
Write-Output (' 仓库根目录: ' + $Root)
Write-Output (' 临时目录  : ' + $script:TempRoot)
Write-Output '========================================='

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[1] Task 1 交付物齐备性'

Assert-Oj '存在环境基线 docs/compliance/environment-baseline.md' (Test-Path -LiteralPath $script:BaselinePath)
Assert-Oj '存在网络确认单 docs/compliance/network-confirmation.md' (Test-Path -LiteralPath $script:NetworkPath)
Assert-Oj '存在无堡垒机补偿记录 docs/compliance/no-bastion-compensation.md' (Test-Path -LiteralPath $script:BastionPath)
Assert-Oj '存在审批记录 docs/compliance/approval-record.md' (Test-Path -LiteralPath $script:ApprovalPath)
Assert-Oj '存在风险登记簿 docs/risk-register.md' (Test-Path -LiteralPath $script:RiskRegisterPath)
Assert-Oj '存在前置条件清单 docs/compliance/prerequisites.json' (Test-Path -LiteralPath $script:DataPath)
Assert-Oj '存在门禁引擎 scripts/compliance/evaluate-gate.ps1' (Test-Path -LiteralPath $script:EnginePath)
Assert-Oj '已移除与当前基线冲突的旧确认单 idp-network-backup-confirmation.md' (-not (Test-Path -LiteralPath (Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'idp-network-backup-confirmation.md')))))

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[2] Step 1 环境基线必查项'

$baselineText = Read-OjText -Path $script:BaselinePath
$baselineChecks = @(
    @{ Name = 'Ubuntu 22.04'; Pattern = 'Ubuntu 22.04' },
    @{ Name = '物理机判定'; Pattern = 'systemd-detect-virt' },
    @{ Name = 'CPU'; Pattern = 'lscpu' },
    @{ Name = '内存'; Pattern = 'free -g' },
    @{ Name = '磁盘'; Pattern = 'lsblk' },
    @{ Name = 'root 远程登录'; Pattern = 'permitrootlogin' },
    @{ Name = 'sudo'; Pattern = 'sudo' },
    @{ Name = '/dev/kvm'; Pattern = '/dev/kvm' },
    @{ Name = 'AppArmor'; Pattern = 'AppArmor' },
    @{ Name = 'cgroups v2'; Pattern = 'cgroup2fs' },
    @{ Name = 'nftables'; Pattern = 'nftables' },
    @{ Name = '磁盘加密'; Pattern = 'LUKS' },
    @{ Name = '时间同步'; Pattern = 'chronyc' }
)
foreach ($check in $baselineChecks) {
    Assert-Oj ('环境基线覆盖 ' + $check.Name) ($baselineText -match [regex]::Escape($check.Pattern))
}
Assert-Oj '环境基线声明 Windows 开发环境不构成生产证据' ($baselineText -match 'Windows 开发环境不构成生产证据')
Assert-Oj '环境基线提供可执行的采集脚本' ($baselineText -match 'collect-environment-baseline')

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[3] Step 2 网络入口与 HTTPS 证书'

$networkText = Read-OjText -Path $script:NetworkPath
$networkChecks = @(
    @{ Name = '校园网来源'; Pattern = '校园网 CIDR' },
    @{ Name = 'VPN 地址池'; Pattern = 'VPN 地址池' },
    @{ Name = '中转机'; Pattern = '中转机' },
    @{ Name = '服务器 192.* 地址'; Pattern = '192.\*' },
    @{ Name = '入口端口'; Pattern = '入口端口' },
    @{ Name = '反向代理'; Pattern = '反向代理' },
    @{ Name = '端口转发'; Pattern = '端口转发' },
    @{ Name = 'HTTPS 证书'; Pattern = 'HTTPS 证书' },
    @{ Name = '禁止自签名'; Pattern = '禁止 HTTP-01' },
    @{ Name = '仅内测阶段判定'; Pattern = '仅内测' }
)
foreach ($check in $networkChecks) {
    Assert-Oj ('网络确认单覆盖 ' + $check.Name) ($networkText -match $check.Pattern)
}
Assert-Oj '网络确认单声明未确认字段不得臆测填写' ($networkText -match '不得臆测')
Assert-Oj '网络确认单未取得确认时判定为仅内测' (($networkText -match '是否可开放用户入口\s*\|\s*否') -and ($networkText -match '是否可承载正式考试\s*\|\s*否'))
Assert-Oj '网络确认单覆盖教务网页登录适配器字段组' ($networkText -match '教务网页登录适配器')

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[4] Step 3 无堡垒机补偿控制'

$bastionText = Read-OjText -Path $script:BastionPath
$bastionChecks = @(
    @{ Name = '来源限制'; Pattern = '管理来源限制' },
    @{ Name = 'SSH 密钥认证'; Pattern = 'PasswordAuthentication no' },
    @{ Name = '禁止 root 登录'; Pattern = 'PermitRootLogin no' },
    @{ Name = 'auditd 审计'; Pattern = 'auditd' },
    @{ Name = 'sudo 审计'; Pattern = 'sudo' },
    @{ Name = '工单记录'; Pattern = '工单' },
    @{ Name = '会话录制缺失声明'; Pattern = '会话录制' }
)
foreach ($check in $bastionChecks) {
    Assert-Oj ('补偿控制覆盖 ' + $check.Name) ($bastionText -match [regex]::Escape($check.Pattern))
}
Assert-Oj '补偿控制声明本系统没有堡垒机且不得把中转机描述为堡垒机' (($bastionText -match '没有堡垒机') -and ($bastionText -match '不得.{0,60}堡垒机'))
Assert-Oj '补偿控制逐项指定责任人' ($bastionText -match '运维负责人')

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[5] Step 4 同机备份限制与数据丢失接受'

$approvalText = Read-OjText -Path $script:ApprovalPath
Assert-Oj '审批记录包含同机备份限制签字表' ($approvalText -match '同机备份限制与可接受数据丢失范围')
Assert-Oj '签字表要求量化可接受的数据丢失范围' ($approvalText -match '可接受的数据丢失范围')
Assert-Oj '签字表指定项目负责人为签署人' ($approvalText -match '项目负责人签名')
Assert-Oj '签字表要求审批人会签' ($approvalText -match '会签')
Assert-Oj '签字表把异地备份列为后续改进项' ($approvalText -match '后续改进项')
Assert-Oj '签字表声明不能防御整机损坏场景' ($approvalText -match '不能防御的场景')

$registerText = Read-OjText -Path $script:RiskRegisterPath
Assert-Oj '风险登记簿登记同机备份残余风险' ($registerText -match 'RISK-2026-07')
Assert-Oj '风险登记簿登记异地备份落点缺失风险' ($registerText -match 'RISK-2026-05')

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[6] 风险项编号与责任人'

$document = (Read-OjText -Path $script:DataPath) | ConvertFrom-Json
$items = @($document.items)
$warnItems = @($items | Where-Object { ([string]$_.level).Trim().ToUpperInvariant() -eq 'WARN' })
$blockingItems = @($items | Where-Object { ([string]$_.level).Trim().ToUpperInvariant() -eq 'BLOCKING' })

Assert-Oj '清单包含强制项' ($blockingItems.Count -gt 0) ('实际: ' + $blockingItems.Count)
Assert-Oj '清单包含强烈建议项' ($warnItems.Count -gt 0) ('实际: ' + $warnItems.Count)

$missingRiskId = @($warnItems | Where-Object { Test-OjEmpty $_.acceptedRiskId })
Assert-Oj '每条强烈建议项都有风险编号' ($missingRiskId.Count -eq 0) (($missingRiskId | ForEach-Object { $_.id }) -join ', ')

$unregistered = @($warnItems | Where-Object { -not (Test-OjEmpty $_.acceptedRiskId) -and ($registerText -notmatch ([regex]::Escape([string]$_.acceptedRiskId))) })
Assert-Oj '每条强烈建议项的风险编号已在风险登记簿中登记' ($unregistered.Count -eq 0) (($unregistered | ForEach-Object { $_.id + '->' + $_.acceptedRiskId }) -join ', ')

$missingOwner = @($items | Where-Object { Test-OjEmpty $_.owner })
Assert-Oj '每个条目都指定了责任人' ($missingOwner.Count -eq 0) (($missingOwner | ForEach-Object { $_.id }) -join ', ')

$missingApproval = @($warnItems | Where-Object {
    $riskId = [string]$_.acceptedRiskId
    if (Test-OjEmpty $riskId) { return $false }
    return ($approvalText -notmatch ([regex]::Escape($riskId)))
})
Assert-Oj '每条强烈建议项风险在审批记录中有对应条目' ($missingApproval.Count -eq 0) (($missingApproval | ForEach-Object { $_.id }) -join ', ')

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[7] 门禁判定：阻断项必须禁止安装'

$realGate = Invoke-OjGate -ChecklistPath $script:DataPath
Write-Output ('         真实清单门禁: ' + $realGate.Decision + ' [' + $realGate.DecisionCode + ']，待核查强制项 ' + @($realGate.BlockingFailures).Count + ' 项，待签名风险 ' + @($realGate.PendingWarnings).Count + ' 项')

if ($realGate.Decision -eq '允许安装') {
    Assert-Oj '真实清单放行时强制项必须全部通过' ($realGate.Counts.Blocking -eq $realGate.Counts.BlockingPass)
    Assert-Oj '真实清单放行时不得存在待签名风险' (@($realGate.PendingWarnings).Count -eq 0)
} else {
    $reasonCount = @($realGate.BlockingFailures).Count + @($realGate.PendingWarnings).Count + @($realGate.ValidationErrors).Count
    Assert-Oj '真实清单禁止安装时具有明确的阻断原因' ($reasonCount -gt 0)
}

$allPassMutator = Get-OjAllPassMutator

$allPassPath = New-OjScenario -Name 'all-pass' -Mutate $allPassMutator
$allPass = Invoke-OjGate -ChecklistPath $allPassPath
Assert-Oj '全部强制项通过且风险已签名 -> 允许安装' ($allPass.Decision -eq '允许安装') ($allPass.Decision + ' / ' + $allPass.DecisionCode)
Assert-Oj '放行场景存在已接受风险' (@($allPass.AcceptedRisks).Count -eq $warnItems.Count) ('实际: ' + @($allPass.AcceptedRisks).Count)

$blockingBlocked = 0
foreach ($item in $blockingItems) {
    $targetId = [string]$item.id
    $scenario = New-OjScenario -Name ('blocking-fail-' + $targetId) -NoEvidence -Mutate {
        param($it)
        & $allPassMutator $it
        if ($it.id -eq $targetId) { $it.status = 'FAIL' }
    }
    $result = Invoke-OjGate -ChecklistPath $scenario -SkipEvidenceCheck
    $hit = @(@($result.BlockingFailures) | Where-Object { $_.Id -eq $targetId }).Count -eq 1
    if (($result.Decision -eq '禁止安装') -and ($result.DecisionCode -eq 'BLOCKED_REQUIREMENT') -and $hit) {
        $blockingBlocked++
    }
}
Assert-Oj '任一强制项失败均导致禁止安装' ($blockingBlocked -eq $blockingItems.Count) ('通过 ' + $blockingBlocked + '/' + $blockingItems.Count)

$unsignedBlocked = 0
foreach ($item in $warnItems) {
    $targetId = [string]$item.id
    $scenario = New-OjScenario -Name ('warn-open-' + $targetId) -NoEvidence -Mutate {
        param($it)
        & $allPassMutator $it
        if ($it.id -eq $targetId) { $it.status = 'WARN_OPEN' }
    }
    $result = Invoke-OjGate -ChecklistPath $scenario -SkipEvidenceCheck
    $hit = @(@($result.PendingWarnings) | Where-Object { $_.Id -eq $targetId }).Count -eq 1
    if (($result.Decision -eq '禁止安装') -and ($result.DecisionCode -eq 'BLOCKED_UNSIGNED_RISK') -and $hit) {
        $unsignedBlocked++
    }
}
Assert-Oj '任一强烈建议项未签名均导致禁止安装' ($unsignedBlocked -eq $warnItems.Count) ('通过 ' + $unsignedBlocked + '/' + $warnItems.Count)

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[8] 交叉一致性'

Assert-Oj '存在需求追溯矩阵 docs/compliance/requirements-traceability.md' (Test-Path -LiteralPath $script:TraceabilityPath)
$traceabilityText = Read-OjText -Path $script:TraceabilityPath
$categories = @($items | ForEach-Object { [string]$_.category } | Sort-Object -Unique)
$missingCategory = @($categories | Where-Object { $traceabilityText -notmatch [regex]::Escape($_) })
Assert-Oj '追溯矩阵覆盖清单中的全部条目分类' ($missingCategory.Count -eq 0) (($missingCategory) -join ', ')

Assert-Oj '追溯矩阵记录当前门禁结论' ($traceabilityText -match '禁止安装')
Assert-Oj '环境基线与网络确认单相互引用' (($baselineText -match 'network-confirmation') -and ($networkText -match 'environment-baseline'))
Assert-Oj '网络确认单与补偿控制相互引用' (($networkText -match 'no-bastion-compensation') -and ($bastionText -match 'network-confirmation'))

# ------------------------------------------------------------------
Write-Output ''
Write-Output '========================================='
Write-Output (' 通过: ' + $script:Passed + '   失败: ' + $script:Failed)
if ($script:Failed -gt 0) {
    Write-Output ' 失败项:'
    foreach ($name in $script:Failures) { Write-Output ('   - ' + $name) }
}
Write-Output (' 真实清单当前门禁: ' + $realGate.Decision + ' [' + $realGate.DecisionCode + ']')
Write-Output '========================================='

if (-not $KeepTemp) {
    Remove-Item -LiteralPath $script:TempRoot -Recurse -Force -ErrorAction SilentlyContinue
} else {
    Write-Output (' 临时目录已保留: ' + $script:TempRoot)
}

if ($script:Failed -gt 0) { exit 1 }
exit 0
