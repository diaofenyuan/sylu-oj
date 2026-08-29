<#
.SYNOPSIS
    Task 1 门禁测试：前置条件清单、责任人与合规门禁。

.DESCRIPTION
    验证 scripts/compliance/evaluate-gate.ps1 的门禁判定逻辑与 docs/compliance/prerequisites.json
    的数据自洽性，并校验 Task 1 的合规文档齐备且相互一致。

    Task 1 的文档级验收（环境基线、网络入口、无堡垒机补偿、同机备份签字）由
    tests/docs/baseline.test.ps1 覆盖；本文件聚焦门禁状态机与清单自洽性。

    覆盖要点（对应 Task 1 Step 5）：
      - 任一强制项（BLOCKING）失败或尚未核查时，门禁结论必须为“禁止安装”；
      - 强烈建议项（WARN）缺失时只能生成待签名风险，未签名必须阻断；
      - 强烈建议项不得通过直接标记为 PASS 绕过签名与证据要求；
      - 状态为 PASS 的条目必须提供真实存在的证据文件；
      - 已接受风险必须能在风险登记簿中检索到，且复核日期未过期。

    测试场景由真实清单派生后写入临时目录，不修改仓库内的清单文件。
    兼容 Windows PowerShell 5.1 与 PowerShell 7。

.PARAMETER Root
    仓库根目录，默认为当前脚本所在目录的上两级。

.PARAMETER KeepTemp
    保留临时场景目录，便于排查。

.EXAMPLE
    pwsh -File tests/docs/requirements-traceability.test.ps1

.EXAMPLE
    powershell -File tests\docs\requirements-traceability.test.ps1 -KeepTemp
#>
[CmdletBinding()]
param(
    [string]$Root,

    [switch]$KeepTemp
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Test-OjBlankString {
    param($Value)
    if ($null -eq $Value) { return $true }
    return ([string]$Value).Trim().Length -eq 0
}

if (Test-OjBlankString $Root) {
    $Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
$Root = [System.IO.Path]::GetFullPath($Root)

$script:EnginePath = Join-Path $Root (Join-Path 'scripts' (Join-Path 'compliance' 'evaluate-gate.ps1'))
$script:DataPath = Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'prerequisites.json'))
$script:RiskRegisterPath = Join-Path $Root (Join-Path 'docs' 'risk-register.md')
$script:TraceabilityPath = Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'requirements-traceability.md'))
$script:ApprovalPath = Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'approval-record.md'))
$script:ConfirmationPath = Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'network-confirmation.md'))
$script:BaselinePath = Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'environment-baseline.md'))
$script:BastionPath = Join-Path $Root (Join-Path 'docs' (Join-Path 'compliance' 'no-bastion-compensation.md'))
$script:TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('oj-gate-test-' + [guid]::NewGuid().ToString('N'))
$script:FutureReviewDate = (Get-Date).Date.AddDays(180).ToString('yyyy-MM-dd')
$script:PastReviewDate = (Get-Date).Date.AddDays(-1).ToString('yyyy-MM-dd')
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

    # 注意：不能使用数组展开（@arguments）传参。数组展开的元素按位置绑定，
    # 字符串 '-AsJson' 不会被重新解析为参数名，在 Windows PowerShell 5.1 下会触发
    # ParameterBindingException（"找不到接受实际参数"-AsJson"的位置形式参数"）。
    # 因此必须使用显式命名参数；-SkipEvidenceCheck:$bool 写法同时兼容 5.1 与 PowerShell 7。
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
    $evidenceDir = Join-Path $scenarioRoot (Join-Path 'docs' (Join-Path 'evidence' 'prerequisites'))
    New-Item -ItemType Directory -Path $complianceDir -Force | Out-Null
    New-Item -ItemType Directory -Path $evidenceDir -Force | Out-Null

    # 复制风险登记簿，使场景中的 acceptedRiskId 可被检索到
    Copy-Item -LiteralPath $script:RiskRegisterPath -Destination (Join-Path $scenarioRoot (Join-Path 'docs' 'risk-register.md')) -Force

    $document = (Read-OjText -Path $script:DataPath) | ConvertFrom-Json
    foreach ($item in @($document.items)) {
        & $Mutate $item
    }

    foreach ($item in @($document.items)) {
        $status = ([string]$item.status).Trim().ToUpperInvariant()
        if ($status -ne 'PASS') { continue }
        # PASS 条目必须携带非空 evidencePath（引擎硬性校验，空值一律 EVIDENCE_MISSING）。
        # -NoEvidence 场景同样保留非空路径但不在磁盘上创建文件：配合 -SkipEvidenceCheck 的
        # 场景只关注状态机逻辑（BLOCKED_REQUIREMENT / BLOCKED_UNSIGNED_RISK），
        # 而不跳过校验的场景则由"文件不存在"产生 EVIDENCE_MISSING。
        $relative = 'docs/evidence/prerequisites/' + $item.id + '.md'
        $item.evidencePath = $relative
        if ($NoEvidence) { continue }
        $target = Join-Path $scenarioRoot ($relative -replace '/', [System.IO.Path]::DirectorySeparatorChar)
        $targetDir = Split-Path -Parent $target
        if (-not (Test-Path -LiteralPath $targetDir)) {
            New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        }
        [System.IO.File]::WriteAllText($target, ('# 测试证据 ' + $item.id + "`n`n本文件由门禁测试自动生成，仅用于验证证据存在性检查。`n"), (New-Object System.Text.UTF8Encoding($false)))
    }

    $scenarioFile = Join-Path $complianceDir 'prerequisites.json'
    [System.IO.File]::WriteAllText($scenarioFile, ($document | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
    return $scenarioFile
}

function Get-OjBaselineMutator {
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
Write-Output ' sylu-oj Task 1 门禁测试'
Write-Output (' 仓库根目录: ' + $Root)
Write-Output (' 临时目录  : ' + $script:TempRoot)
Write-Output '========================================='

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[1] 交付物齐备性'

Assert-Oj '存在前置条件清单 docs/compliance/prerequisites.json' (Test-Path -LiteralPath $script:DataPath)
Assert-Oj '存在需求追溯矩阵 docs/compliance/requirements-traceability.md' (Test-Path -LiteralPath $script:TraceabilityPath)
Assert-Oj '存在审批记录 docs/compliance/approval-record.md' (Test-Path -LiteralPath $script:ApprovalPath)
Assert-Oj '存在网络确认单 docs/compliance/network-confirmation.md' (Test-Path -LiteralPath $script:ConfirmationPath)
Assert-Oj '存在环境基线 docs/compliance/environment-baseline.md' (Test-Path -LiteralPath $script:BaselinePath)
Assert-Oj '存在无堡垒机补偿记录 docs/compliance/no-bastion-compensation.md' (Test-Path -LiteralPath $script:BastionPath)
Assert-Oj '存在风险登记簿 docs/risk-register.md' (Test-Path -LiteralPath $script:RiskRegisterPath)
Assert-Oj '存在门禁引擎 scripts/compliance/evaluate-gate.ps1' (Test-Path -LiteralPath $script:EnginePath)

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[2] 真实清单结构自洽性'

$realResult = Invoke-OjGate -ChecklistPath $script:DataPath
$realDocument = (Read-OjText -Path $script:DataPath) | ConvertFrom-Json
$realItems = @($realDocument.items)
$blockingItems = @($realItems | Where-Object { ([string]$_.level).Trim().ToUpperInvariant() -eq 'BLOCKING' })
$warnItems = @($realItems | Where-Object { ([string]$_.level).Trim().ToUpperInvariant() -eq 'WARN' })
$advisoryItems = @($realItems | Where-Object { ([string]$_.level).Trim().ToUpperInvariant() -eq 'PASS' })

Assert-Oj '清单可解析且包含条目' ($realItems.Count -gt 0) ('实际条目数: ' + $realItems.Count)
Assert-Oj '清单无校验错误' (@($realResult.ValidationErrors).Count -eq 0) (($realResult.ValidationErrors | ForEach-Object { $_.Code + ':' + $_.ItemId }) -join '; ')
Assert-Oj '强制项数量为 21' ($blockingItems.Count -eq 21) ('实际: ' + $blockingItems.Count)
Assert-Oj '强烈建议项数量为 5' ($warnItems.Count -eq 5) ('实际: ' + $warnItems.Count)
Assert-Oj '推荐项数量为 3' ($advisoryItems.Count -eq 3) ('实际: ' + $advisoryItems.Count)

$downgraded = @($realItems | Where-Object { $_.id -like 'PRQ-0*' -and ([string]$_.level).Trim().ToUpperInvariant() -ne 'BLOCKING' })
Assert-Oj '设计文档 1.2/1.4 的强制项未被降级为建议项' ($downgraded.Count -eq 0) (($downgraded | ForEach-Object { $_.id }) -join ', ')

$missingMethod = @($blockingItems | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.verificationMethod) })
Assert-Oj '每个强制项都定义了验证方法' ($missingMethod.Count -eq 0) (($missingMethod | ForEach-Object { $_.id }) -join ', ')

$missingOwner = @($realItems | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.owner) })
Assert-Oj '每个条目都指定了责任人' ($missingOwner.Count -eq 0) (($missingOwner | ForEach-Object { $_.id }) -join ', ')

$registerText = Read-OjText -Path $script:RiskRegisterPath
$unregisteredWarn = @($warnItems | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.acceptedRiskId) -or ($registerText -notmatch ([string]$_.acceptedRiskId)) })
Assert-Oj '每个强烈建议项的风险编号已在风险登记簿中登记' ($unregisteredWarn.Count -eq 0) (($unregisteredWarn | ForEach-Object { $_.id + '->' + $_.acceptedRiskId }) -join ', ')

if ($realResult.Decision -eq '允许安装') {
    Assert-Oj '真实清单判定为允许安装时，强制项必须全部通过' ($realResult.Counts.Blocking -eq $realResult.Counts.BlockingPass)
    Assert-Oj '真实清单判定为允许安装时，不得存在待签名风险' (@($realResult.PendingWarnings).Count -eq 0)
} else {
    $blockedReasons = @($realResult.BlockingFailures).Count + @($realResult.PendingWarnings).Count + @($realResult.ValidationErrors).Count
    Assert-Oj '真实清单判定为禁止安装时具有明确的阻断原因' ($blockedReasons -gt 0)
    Write-Output ('         当前结论: ' + $realResult.Decision + ' [' + $realResult.DecisionCode + ']，待核查强制项 ' + @($realResult.BlockingFailures).Count + ' 项，待签名风险 ' + @($realResult.PendingWarnings).Count + ' 项')
}

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[3] 门禁逻辑：放行路径'

$baselineMutator = Get-OjBaselineMutator

$baselinePath = New-OjScenario -Name 'baseline-all-pass' -Mutate $baselineMutator
$baseline = Invoke-OjGate -ChecklistPath $baselinePath
Assert-Oj '全部强制项通过且强烈建议项已签名 -> 允许安装' ($baseline.Decision -eq '允许安装') ($baseline.Decision + ' / ' + $baseline.DecisionCode)
Assert-Oj '放行场景判定码为 ALLOWED_WITH_RISK' ($baseline.DecisionCode -eq 'ALLOWED_WITH_RISK') $baseline.DecisionCode
Assert-Oj '放行场景无待签名风险' (@($baseline.PendingWarnings).Count -eq 0)
Assert-Oj '放行场景已接受风险数等于强烈建议项总数' (@($baseline.AcceptedRisks).Count -eq $warnItems.Count) ('实际: ' + @($baseline.AcceptedRisks).Count)

$satisfiedPath = New-OjScenario -Name 'baseline-warn-satisfied' -Mutate {
    param($item)
    $item.status = 'PASS'
}
$satisfied = Invoke-OjGate -ChecklistPath $satisfiedPath
Assert-Oj '强烈建议项条件满足且附证据 -> 允许安装且无风险' (($satisfied.Decision -eq '允许安装') -and ($satisfied.DecisionCode -eq 'ALLOWED')) ($satisfied.Decision + ' / ' + $satisfied.DecisionCode)

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[4] 门禁逻辑：强制项必须阻断'

$allFailPath = New-OjScenario -Name 'blocking-all-fail' -NoEvidence -Mutate {
    param($item)
    $level = ([string]$item.level).Trim().ToUpperInvariant()
    if ($level -eq 'BLOCKING') {
        $item.status = 'FAIL'
    } elseif ($level -eq 'WARN') {
        $item.status = 'WARN_ACCEPTED'
        $item.approvalRef = 'AP-TEST-01'
        $item.approver = '测试审批人'
        $item.reviewDate = $script:FutureReviewDate
    } else {
        $item.status = 'PASS'
    }
}
$allFail = Invoke-OjGate -ChecklistPath $allFailPath -SkipEvidenceCheck
Assert-Oj '全部强制项失败 -> 禁止安装' ($allFail.Decision -eq '禁止安装') ($allFail.Decision + ' / ' + $allFail.DecisionCode)
Assert-Oj '全部强制项失败时判定码为 BLOCKED_REQUIREMENT' ($allFail.DecisionCode -eq 'BLOCKED_REQUIREMENT') $allFail.DecisionCode
Assert-Oj '全部强制项失败时阻断项数量等于强制项总数' (@($allFail.BlockingFailures).Count -eq $blockingItems.Count) ('实际: ' + @($allFail.BlockingFailures).Count)

$blockingFailCount = 0
$blockingUnverifiedCount = 0
foreach ($item in $blockingItems) {
    $targetId = [string]$item.id

    $failScenario = New-OjScenario -Name ('blocking-fail-' + $targetId) -NoEvidence -Mutate {
        param($it)
        & $baselineMutator $it
        if ($it.id -eq $targetId) { $it.status = 'FAIL' }
    }
    $failResult = Invoke-OjGate -ChecklistPath $failScenario -SkipEvidenceCheck
    $containsTarget = @(@($failResult.BlockingFailures) | Where-Object { $_.Id -eq $targetId }).Count -eq 1
    if (($failResult.Decision -eq '禁止安装') -and $containsTarget) { $blockingFailCount++ }

    $unverifiedScenario = New-OjScenario -Name ('blocking-unverified-' + $targetId) -NoEvidence -Mutate {
        param($it)
        & $baselineMutator $it
        if ($it.id -eq $targetId) { $it.status = 'UNVERIFIED' }
    }
    $unverifiedResult = Invoke-OjGate -ChecklistPath $unverifiedScenario -SkipEvidenceCheck
    $containsUnverified = @(@($unverifiedResult.BlockingFailures) | Where-Object { $_.Id -eq $targetId }).Count -eq 1
    if (($unverifiedResult.Decision -eq '禁止安装') -and $containsUnverified) { $blockingUnverifiedCount++ }
}
Assert-Oj '任一强制项置为 FAIL 均导致禁止安装并计入阻断项' ($blockingFailCount -eq $blockingItems.Count) ('通过 ' + $blockingFailCount + '/' + $blockingItems.Count)
Assert-Oj '任一强制项未核查（UNVERIFIED）均导致禁止安装' ($blockingUnverifiedCount -eq $blockingItems.Count) ('通过 ' + $blockingUnverifiedCount + '/' + $blockingItems.Count)

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[5] 门禁逻辑：强烈建议项不得绕过签名'

foreach ($item in $warnItems) {
    $targetId = [string]$item.id

    $openScenario = New-OjScenario -Name ('warn-open-' + $targetId) -NoEvidence -Mutate {
        param($it)
        & $baselineMutator $it
        if ($it.id -eq $targetId) { $it.status = 'WARN_OPEN' }
    }
    $openResult = Invoke-OjGate -ChecklistPath $openScenario -SkipEvidenceCheck
    $openOk = ($openResult.Decision -eq '禁止安装') -and
        ($openResult.DecisionCode -eq 'BLOCKED_UNSIGNED_RISK') -and
        (@(@($openResult.PendingWarnings) | Where-Object { $_.Id -eq $targetId }).Count -eq 1) -and
        (@(@($openResult.AcceptedRisks) | Where-Object { $_.Id -eq $targetId }).Count -eq 0)
    Assert-Oj ('强烈建议项 ' + $targetId + ' 未签名 -> 禁止安装且计入待签名风险') $openOk ($openResult.Decision + ' / ' + $openResult.DecisionCode)

    $downgradeScenario = New-OjScenario -Name ('warn-downgrade-' + $targetId) -NoEvidence -Mutate {
        param($it)
        & $baselineMutator $it
        if ($it.id -eq $targetId) { $it.status = 'PASS' }
    }
    $downgradeResult = Invoke-OjGate -ChecklistPath $downgradeScenario
    $evidenceError = @(@($downgradeResult.ValidationErrors) | Where-Object { $_.ItemId -eq $targetId -and $_.Code -eq 'EVIDENCE_MISSING' }).Count -ge 1
    Assert-Oj ('强烈建议项 ' + $targetId + ' 不得无证据直接标记为 PASS') (($downgradeResult.Decision -eq '禁止安装') -and $evidenceError) ($downgradeResult.Decision + ' / ' + $downgradeResult.DecisionCode)

    $noApproverScenario = New-OjScenario -Name ('warn-no-approver-' + $targetId) -NoEvidence -Mutate {
        param($it)
        & $baselineMutator $it
        if ($it.id -eq $targetId) { $it.approver = '' }
    }
    $noApproverResult = Invoke-OjGate -ChecklistPath $noApproverScenario -SkipEvidenceCheck
    $approverError = @(@($noApproverResult.ValidationErrors) | Where-Object { $_.ItemId -eq $targetId -and $_.Code -eq 'APPROVER_MISSING' }).Count -ge 1
    Assert-Oj ('强烈建议项 ' + $targetId + ' 已接受但缺审批人 -> 校验失败') (($noApproverResult.Decision -eq '禁止安装') -and $approverError) $noApproverResult.DecisionCode

    $unknownRiskScenario = New-OjScenario -Name ('warn-unknown-risk-' + $targetId) -NoEvidence -Mutate {
        param($it)
        & $baselineMutator $it
        if ($it.id -eq $targetId) { $it.acceptedRiskId = 'RISK-9999-99' }
    }
    $unknownRiskResult = Invoke-OjGate -ChecklistPath $unknownRiskScenario -SkipEvidenceCheck
    $riskError = @(@($unknownRiskResult.ValidationErrors) | Where-Object { $_.ItemId -eq $targetId -and $_.Code -eq 'RISK_NOT_REGISTERED' }).Count -ge 1
    Assert-Oj ('强烈建议项 ' + $targetId + ' 使用未登记风险编号 -> 校验失败') (($unknownRiskResult.Decision -eq '禁止安装') -and $riskError) $unknownRiskResult.DecisionCode

    $overdueScenario = New-OjScenario -Name ('warn-overdue-' + $targetId) -NoEvidence -Mutate {
        param($it)
        & $baselineMutator $it
        if ($it.id -eq $targetId) { $it.reviewDate = $script:PastReviewDate }
    }
    $overdueResult = Invoke-OjGate -ChecklistPath $overdueScenario -SkipEvidenceCheck
    $overdueError = @(@($overdueResult.ValidationErrors) | Where-Object { $_.ItemId -eq $targetId -and $_.Code -eq 'REVIEW_OVERDUE' }).Count -ge 1
    Assert-Oj ('强烈建议项 ' + $targetId + ' 复核日期过期 -> 校验失败') (($overdueResult.Decision -eq '禁止安装') -and $overdueError) $overdueResult.DecisionCode
}

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[6] 门禁逻辑：证据存在性'

$noEvidencePath = New-OjScenario -Name 'evidence-missing' -NoEvidence -Mutate $baselineMutator
$noEvidence = Invoke-OjGate -ChecklistPath $noEvidencePath
$evidenceErrors = @(@($noEvidence.ValidationErrors) | Where-Object { $_.Code -eq 'EVIDENCE_MISSING' }).Count
Assert-Oj '状态为 PASS 但证据文件缺失 -> 校验失败并阻断' (($noEvidence.Decision -eq '禁止安装') -and ($evidenceErrors -gt 0)) ($noEvidence.Decision + ' / 证据错误数 ' + $evidenceErrors)

$removedEvidenceScenario = New-OjScenario -Name 'evidence-removed' -Mutate $baselineMutator
$removedRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $removedEvidenceScenario))
$removedTarget = Join-Path $removedRoot (Join-Path 'docs' (Join-Path 'evidence' (Join-Path 'prerequisites' 'PRQ-001.md')))
Remove-Item -LiteralPath $removedTarget -Force
$removedResult = Invoke-OjGate -ChecklistPath $removedEvidenceScenario
$removedError = @(@($removedResult.ValidationErrors) | Where-Object { $_.ItemId -eq 'PRQ-001' -and $_.Code -eq 'EVIDENCE_MISSING' }).Count -ge 1
Assert-Oj '删除已归档证据文件后 -> 校验失败并阻断' (($removedResult.Decision -eq '禁止安装') -and $removedError) $removedResult.DecisionCode

# ------------------------------------------------------------------
Write-Output ''
Write-Output '[7] 文档交叉一致性'

$traceabilityText = Read-OjText -Path $script:TraceabilityPath
$categories = @($realItems | ForEach-Object { [string]$_.category } | Sort-Object -Unique)
$missingCategory = @($categories | Where-Object { $traceabilityText -notmatch [regex]::Escape($_) })
Assert-Oj '需求追溯矩阵覆盖清单中的全部条目分类' ($missingCategory.Count -eq 0) (($missingCategory) -join ', ')

$approvalText = Read-OjText -Path $script:ApprovalPath
$approvalMissing = @($warnItems | ForEach-Object {
    $riskId = [string]$_.acceptedRiskId
    if ($approvalText -match [regex]::Escape($riskId)) { $null } else { $riskId }
} | Where-Object { $null -ne $_ })
Assert-Oj '审批记录覆盖全部强烈建议项风险编号' (@($approvalMissing).Count -eq 0) ((@($approvalMissing)) -join ', ')

$confirmationText = Read-OjText -Path $script:ConfirmationPath
Assert-Oj '网络确认单包含网络路径、HTTPS 入口、证书、教务适配器四组字段' (($confirmationText -match '表 A') -and ($confirmationText -match '表 B') -and ($confirmationText -match '表 C') -and ($confirmationText -match '表 D'))
Assert-Oj '网络确认单声明未确认字段不得臆测填写' ($confirmationText -match '不得臆测')
Assert-Oj '网络确认单覆盖 VPN 到中转机再到 192.* 的入口链路' (($confirmationText -match 'VPN') -and ($confirmationText -match '中转机') -and ($confirmationText -match '192\.\*'))
Assert-Oj '网络确认单未确认阶段判定为仅内测' ($confirmationText -match '仅内测')

$bastionText = Read-OjText -Path $script:BastionPath
Assert-Oj '补偿控制覆盖来源限制、SSH 密钥、禁止 root/密码、auditd、sudo 与工单' (($bastionText -match '管理来源限制') -and ($bastionText -match 'PasswordAuthentication no') -and ($bastionText -match 'PermitRootLogin no') -and ($bastionText -match 'auditd') -and ($bastionText -match 'sudo') -and ($bastionText -match '工单'))

$baselineText = Read-OjText -Path $script:BaselinePath
Assert-Oj '环境基线覆盖 Ubuntu、/dev/kvm、AppArmor、cgroups v2、nftables、LUKS 与时间同步' (($baselineText -match 'Ubuntu 22.04') -and ($baselineText -match '/dev/kvm') -and ($baselineText -match 'AppArmor') -and ($baselineText -match 'cgroup2fs') -and ($baselineText -match 'nftables') -and ($baselineText -match 'LUKS') -and ($baselineText -match 'chronyc'))

$registerMissing = @('RISK-2026-01', 'RISK-2026-02', 'RISK-2026-03', 'RISK-2026-04', 'RISK-2026-05' | Where-Object { $registerText -notmatch [regex]::Escape($_) })
Assert-Oj '风险登记簿包含五条强烈建议项风险' (@($registerMissing).Count -eq 0) ((@($registerMissing)) -join ', ')

# ------------------------------------------------------------------
Write-Output ''
Write-Output '========================================='
Write-Output (' 通过: ' + $script:Passed + '   失败: ' + $script:Failed)
if ($script:Failed -gt 0) {
    Write-Output ' 失败项:'
    foreach ($name in $script:Failures) { Write-Output ('   - ' + $name) }
}
Write-Output (' 真实清单当前门禁: ' + $realResult.Decision + ' [' + $realResult.DecisionCode + ']')
Write-Output '========================================='

if (-not $KeepTemp) {
    Remove-Item -LiteralPath $script:TempRoot -Recurse -Force -ErrorAction SilentlyContinue
} else {
    Write-Output (' 临时目录已保留: ' + $script:TempRoot)
}

if ($script:Failed -gt 0) { exit 1 }
exit 0
