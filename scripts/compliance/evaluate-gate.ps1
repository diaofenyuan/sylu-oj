<#
.SYNOPSIS
    评估 sylu-oj 单物理服务器部署的前置条件门禁。

.DESCRIPTION
    读取 docs/compliance/prerequisites.json，按设计文档 1.2 与 12.3 节的三级要求
    （强制 BLOCKING / 强烈建议 WARN / 推荐 PASS）计算安装门禁结论。

    判定顺序：
      1. 清单自身校验失败            -> BLOCKED_VALIDATION
      2. 存在未通过或未核查的强制项  -> BLOCKED_REQUIREMENT
      3. 存在缺失但未签名的强烈建议项 -> BLOCKED_UNSIGNED_RISK
      4. 全部通过且存在已接受风险    -> ALLOWED_WITH_RISK
      5. 全部通过                    -> ALLOWED

    兼容 Windows PowerShell 5.1 与 PowerShell 7。

.PARAMETER Path
    前置条件清单路径（docs/compliance/prerequisites.json）。

.PARAMETER RiskRegisterPath
    风险登记簿路径，用于校验 acceptedRiskId 是否已登记。默认取仓库 docs/risk-register.md。

.PARAMETER SkipEvidenceCheck
    跳过证据文件存在性检查。仅用于测试构造场景，生产判定不得使用。

.PARAMETER AsJson
    以单行 JSON 输出结果，供自动化测试解析。

.PARAMETER Strict
    门禁结论非“允许安装”时以退出码 1 结束，用于 CI 门禁。

.EXAMPLE
    pwsh -File scripts/compliance/evaluate-gate.ps1 -Path docs/compliance/prerequisites.json

.EXAMPLE
    pwsh -File scripts/compliance/evaluate-gate.ps1 -Path docs/compliance/prerequisites.json -AsJson

.EXAMPLE
    pwsh -File scripts/compliance/evaluate-gate.ps1 -Path docs/compliance/prerequisites.json -Strict
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [string]$RiskRegisterPath,

    [switch]$SkipEvidenceCheck,

    [switch]$AsJson,

    [switch]$Strict
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Get-OjPropertyValue {
    param(
        [object]$InputObject,
        [string]$Name
    )

    if ($null -eq $InputObject) { return $null }
    $property = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Test-OjBlank {
    param($Value)

    if ($null -eq $Value) { return $true }
    if ($Value -is [string]) { return ($Value.Trim().Length -eq 0) }
    return $false
}

function Get-OjComplianceGate {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [string]$RiskRegisterPath,

        [switch]$SkipEvidenceCheck
    )

    $levels = @('BLOCKING', 'WARN', 'PASS')
    $statusValues = @('UNVERIFIED', 'PASS', 'FAIL', 'WARN_OPEN', 'WARN_ACCEPTED', 'NOT_APPLICABLE')
    $blockingStatus = @('UNVERIFIED', 'PASS', 'FAIL')
    $warnStatus = @('UNVERIFIED', 'PASS', 'WARN_OPEN', 'WARN_ACCEPTED')
    $passStatus = @('UNVERIFIED', 'PASS', 'NOT_APPLICABLE')
    $requiredFields = @('id', 'category', 'title', 'requirement', 'level', 'designRef', 'verificationMethod', 'owner', 'status')

    $errors = New-Object System.Collections.ArrayList
    $blockingFailures = New-Object System.Collections.ArrayList
    $pendingWarnings = New-Object System.Collections.ArrayList
    $acceptedRisks = New-Object System.Collections.ArrayList
    $advisoryItems = New-Object System.Collections.ArrayList
    $seenIds = @{}

    $addError = {
        param([string]$Code, [string]$ItemId, [string]$Message)
        $null = $errors.Add([pscustomobject]@{
            Code    = $Code
            ItemId  = $ItemId
            Message = $Message
        })
    }

    $fullPath = (Resolve-Path -LiteralPath $Path).ProviderPath
    $raw = Get-Content -LiteralPath $fullPath -Raw -Encoding UTF8
    if (Test-OjBlank $raw) { throw "清单内容为空: $fullPath" }

    $document = $raw | ConvertFrom-Json
    $items = @(Get-OjPropertyValue $document 'items')
    if ($items.Count -eq 0) {
        & $addError 'NO_ITEMS' '' '清单未包含任何条目'
    }

    # 仓库根目录：清单位于 <root>/docs/compliance/prerequisites.json
    $fileDirectory = Split-Path -Parent $fullPath
    $root = Split-Path -Parent (Split-Path -Parent $fileDirectory)

    # 风险登记簿：校验 acceptedRiskId 是否已登记
    $registerPath = $RiskRegisterPath
    if (Test-OjBlank $registerPath) {
        $registerPath = Join-Path $root (Join-Path 'docs' 'risk-register.md')
    } else {
        $registerPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $registerPath))
    }

    $registeredRiskIds = @()
    if (Test-Path -LiteralPath $registerPath) {
        $registerText = Get-Content -LiteralPath $registerPath -Raw -Encoding UTF8
        foreach ($match in [regex]::Matches($registerText, 'RISK-\d{4}-\d{2}')) {
            if ($registeredRiskIds -notcontains $match.Value) {
                $registeredRiskIds += $match.Value
            }
        }
    }

    $today = (Get-Date).Date

    foreach ($item in $items) {
        $rawId = [string](Get-OjPropertyValue $item 'id')
        if (Test-OjBlank $rawId) {
            & $addError 'ITEM_ID_MISSING' '' '条目缺少 id 字段'
            continue
        }

        $id = $rawId.Trim()
        if ($seenIds.ContainsKey($id)) {
            & $addError 'DUPLICATE_ID' $id '条目编号重复'
        } else {
            $seenIds[$id] = $true
        }

        foreach ($field in $requiredFields) {
            if (Test-OjBlank (Get-OjPropertyValue $item $field)) {
                & $addError 'FIELD_MISSING' $id "缺少必填字段: $field"
            }
        }

        $title = [string](Get-OjPropertyValue $item 'title')
        $owner = [string](Get-OjPropertyValue $item 'owner')
        $level = ([string](Get-OjPropertyValue $item 'level')).Trim().ToUpperInvariant()
        $status = ([string](Get-OjPropertyValue $item 'status')).Trim().ToUpperInvariant()

        if ($levels -notcontains $level) {
            & $addError 'LEVEL_INVALID' $id "level 取值非法: '$level'（允许 BLOCKING / WARN / PASS）"
            continue
        }
        if ($statusValues -notcontains $status) {
            & $addError 'STATUS_INVALID' $id "status 取值非法: '$status'"
            continue
        }

        if ($level -eq 'BLOCKING' -and $blockingStatus -notcontains $status) {
            & $addError 'STATUS_LEVEL_MISMATCH' $id "强制项不接受状态 $status（强制项只能为 UNVERIFIED / PASS / FAIL）"
        }
        if ($level -eq 'WARN' -and $warnStatus -notcontains $status) {
            & $addError 'STATUS_LEVEL_MISMATCH' $id "强烈建议项不接受状态 $status（只能为 UNVERIFIED / PASS / WARN_OPEN / WARN_ACCEPTED）"
        }
        if ($level -eq 'PASS' -and $passStatus -notcontains $status) {
            & $addError 'STATUS_LEVEL_MISMATCH' $id "推荐项不接受状态 $status（只能为 UNVERIFIED / PASS / NOT_APPLICABLE）"
        }

        # 状态为 PASS 必须提供真实存在的证据文件
        if ($status -eq 'PASS') {
            $evidencePath = [string](Get-OjPropertyValue $item 'evidencePath')
            if (Test-OjBlank $evidencePath) {
                & $addError 'EVIDENCE_MISSING' $id '状态为 PASS 但未填写 evidencePath'
            } elseif (-not $SkipEvidenceCheck) {
                $candidate = $evidencePath.Trim()
                if (-not [System.IO.Path]::IsPathRooted($candidate)) {
                    $candidate = Join-Path $root $candidate
                }
                if (-not (Test-Path -LiteralPath $candidate)) {
                    & $addError 'EVIDENCE_MISSING' $id "证据文件不存在: $evidencePath"
                }
            }
        }

        # 已接受风险必须完成登记、审批与复核日期
        $acceptedRiskId = $null
        if ($status -eq 'WARN_ACCEPTED') {
            $acceptedRiskId = ([string](Get-OjPropertyValue $item 'acceptedRiskId')).Trim()
            $approvalRef = ([string](Get-OjPropertyValue $item 'approvalRef')).Trim()
            $approver = ([string](Get-OjPropertyValue $item 'approver')).Trim()
            $reviewDate = ([string](Get-OjPropertyValue $item 'reviewDate')).Trim()

            if (Test-OjBlank $acceptedRiskId) {
                & $addError 'RISK_ID_MISSING' $id 'WARN_ACCEPTED 必须填写 acceptedRiskId'
            } elseif ($registeredRiskIds.Count -gt 0 -and ($registeredRiskIds -notcontains $acceptedRiskId)) {
                & $addError 'RISK_NOT_REGISTERED' $id "风险编号未在风险登记簿中登记: $acceptedRiskId"
            }
            if (Test-OjBlank $approvalRef) {
                & $addError 'APPROVAL_REF_MISSING' $id 'WARN_ACCEPTED 必须填写 approvalRef（审批记录编号）'
            }
            if (Test-OjBlank $approver) {
                & $addError 'APPROVER_MISSING' $id 'WARN_ACCEPTED 必须填写审批人'
            }
            if (Test-OjBlank $reviewDate) {
                & $addError 'REVIEW_DATE_MISSING' $id 'WARN_ACCEPTED 必须填写复核日期'
            } elseif ($reviewDate -notmatch '^\d{4}-\d{2}-\d{2}$') {
                & $addError 'REVIEW_DATE_INVALID' $id "复核日期格式应为 YYYY-MM-DD: $reviewDate"
            } else {
                try {
                    $parsed = [datetime]::ParseExact($reviewDate, 'yyyy-MM-dd', [System.Globalization.CultureInfo]::InvariantCulture)
                    if ($parsed.Date -lt $today) {
                        & $addError 'REVIEW_OVERDUE' $id "复核日期已过期: $reviewDate"
                    }
                } catch {
                    & $addError 'REVIEW_DATE_INVALID' $id "复核日期不可解析: $reviewDate"
                }
            }
        }

        # 门禁归类
        if ($level -eq 'BLOCKING') {
            if ($status -ne 'PASS') {
                $reason = '强制项尚未核查或无证据'
                if ($status -eq 'FAIL') { $reason = '强制项核查不通过' }
                $null = $blockingFailures.Add([pscustomobject]@{
                    Id     = $id
                    Title  = $title
                    Status = $status
                    Owner  = $owner
                    Reason = $reason
                })
            }
        } elseif ($level -eq 'WARN') {
            if ($status -eq 'WARN_OPEN' -or $status -eq 'UNVERIFIED') {
                $reason = '强烈建议项缺失，风险已登记但尚未取得审批人签名'
                if ($status -eq 'UNVERIFIED') { $reason = '强烈建议项尚未核查，无法判定是否满足' }
                $null = $pendingWarnings.Add([pscustomobject]@{
                    Id     = $id
                    Title  = $title
                    Status = $status
                    Owner  = $owner
                    Reason = $reason
                })
            } elseif ($status -eq 'WARN_ACCEPTED') {
                $null = $acceptedRisks.Add([pscustomobject]@{
                    Id         = $id
                    Title      = $title
                    RiskId     = $acceptedRiskId
                    Approver   = ([string](Get-OjPropertyValue $item 'approver')).Trim()
                    ApprovalRef = ([string](Get-OjPropertyValue $item 'approvalRef')).Trim()
                    ReviewDate = ([string](Get-OjPropertyValue $item 'reviewDate')).Trim()
                })
            }
        } else {
            if ($status -eq 'PASS' -or $status -eq 'NOT_APPLICABLE') {
                $null = $advisoryItems.Add([pscustomobject]@{
                    Id     = $id
                    Title  = $title
                    Status = $status
                })
            }
        }
    }

    $decision = '禁止安装'
    $code = 'BLOCKED_VALIDATION'
    if ($errors.Count -eq 0) {
        if ($blockingFailures.Count -gt 0) {
            $code = 'BLOCKED_REQUIREMENT'
        } elseif ($pendingWarnings.Count -gt 0) {
            $code = 'BLOCKED_UNSIGNED_RISK'
        } elseif ($acceptedRisks.Count -gt 0) {
            $decision = '允许安装'
            $code = 'ALLOWED_WITH_RISK'
        } else {
            $decision = '允许安装'
            $code = 'ALLOWED'
        }
    }

    return [pscustomobject]@{
        Source            = $fullPath
        RiskRegister      = $registerPath
        EvaluatedAt       = (Get-Date).ToString('o')
        Decision          = $decision
        DecisionCode      = $code
        BlockingFailures  = $blockingFailures.ToArray()
        PendingWarnings   = $pendingWarnings.ToArray()
        AcceptedRisks     = $acceptedRisks.ToArray()
        AdvisoryItems     = $advisoryItems.ToArray()
        ValidationErrors  = $errors.ToArray()
        Counts            = [pscustomobject]@{
            Total          = $items.Count
            Blocking       = @($items | Where-Object { ([string]$_.level).Trim().ToUpperInvariant() -eq 'BLOCKING' }).Count
            BlockingPass   = @($items | Where-Object { (([string]$_.level).Trim().ToUpperInvariant() -eq 'BLOCKING') -and (([string]$_.status).Trim().ToUpperInvariant() -eq 'PASS') }).Count
            Warn           = @($items | Where-Object { ([string]$_.level).Trim().ToUpperInvariant() -eq 'WARN' }).Count
            WarnOpen       = @($items | Where-Object { (([string]$_.level).Trim().ToUpperInvariant() -eq 'WARN') -and (([string]$_.status).Trim().ToUpperInvariant() -eq 'WARN_OPEN') }).Count
            WarnAccepted   = @($items | Where-Object { (([string]$_.level).Trim().ToUpperInvariant() -eq 'WARN') -and (([string]$_.status).Trim().ToUpperInvariant() -eq 'WARN_ACCEPTED') }).Count
            Advisory       = @($items | Where-Object { ([string]$_.level).Trim().ToUpperInvariant() -eq 'PASS' }).Count
        }
        RegisteredRiskIds = $registeredRiskIds
        PolicyNotice      = '已接受的强烈建议项风险不得被引用为合并安全域、取消隔离或降低控制强度的依据（设计文档 1.2、12.3）。'
    }
}

if (-not (Test-Path -LiteralPath $Path)) {
    Write-Error "找不到前置条件清单: $Path"
    exit 2
}

try {
    $result = Get-OjComplianceGate -Path $Path -RiskRegisterPath $RiskRegisterPath -SkipEvidenceCheck:$SkipEvidenceCheck
} catch {
    Write-Error ("门禁评估失败: " + $_.Exception.Message)
    exit 2
}

if ($AsJson) {
    $result | ConvertTo-Json -Depth 6 -Compress
} else {
    Write-Output '========================================='
    Write-Output ' sylu-oj 部署前置条件门禁'
    Write-Output '========================================='
    Write-Output ("结论        : " + $result.Decision + " [" + $result.DecisionCode + "]")
    Write-Output ("清单        : " + $result.Source)
    Write-Output ("风险登记簿  : " + $result.RiskRegister)
    Write-Output ("评估时间    : " + $result.EvaluatedAt)
    Write-Output ("条目统计    : 总计 " + $result.Counts.Total +
        " / 强制 " + $result.Counts.Blocking + "（通过 " + $result.Counts.BlockingPass + "）" +
        " / 强烈建议 " + $result.Counts.Warn + "（待签名 " + $result.Counts.WarnOpen + "，已接受 " + $result.Counts.WarnAccepted + "）" +
        " / 推荐 " + $result.Counts.Advisory)
    Write-Output ''

    if (@($result.ValidationErrors).Count -gt 0) {
        Write-Output '【校验错误】'
        foreach ($e in @($result.ValidationErrors)) {
            Write-Output ("  [" + $e.Code + "] " + $e.ItemId + " - " + $e.Message)
        }
        Write-Output ''
    }
    if (@($result.BlockingFailures).Count -gt 0) {
        Write-Output '【未通过的强制项】'
        foreach ($f in @($result.BlockingFailures)) {
            Write-Output ("  " + $f.Id + " [" + $f.Status + "] " + $f.Title + "（责任人: " + $f.Owner + "） - " + $f.Reason)
        }
        Write-Output ''
    }
    if (@($result.PendingWarnings).Count -gt 0) {
        Write-Output '【待签名的强烈建议项风险】'
        foreach ($w in @($result.PendingWarnings)) {
            Write-Output ("  " + $w.Id + " [" + $w.Status + "] " + $w.Title + "（责任人: " + $w.Owner + "） - " + $w.Reason)
        }
        Write-Output ''
    }
    if (@($result.AcceptedRisks).Count -gt 0) {
        Write-Output '【已签名接受的风险】'
        foreach ($r in @($result.AcceptedRisks)) {
            Write-Output ("  " + $r.Id + " -> " + $r.RiskId + " | 审批 " + $r.ApprovalRef + " | 审批人 " + $r.Approver + " | 复核日期 " + $r.ReviewDate)
        }
        Write-Output ''
    }
    Write-Output ("提示        : " + $result.PolicyNotice)
}

if ($Strict -and $result.Decision -ne '允许安装') {
    exit 1
}

exit 0
