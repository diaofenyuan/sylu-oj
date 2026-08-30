# Start the dev judge agent inside WSL Ubuntu-24.04 (host-dev mode).
# host-dev executes code directly on the WSL host: NO container isolation.
# Dev/local testing only - never use in production or exams.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\start-dev-agent.ps1
# Prereqs: backend running on localhost:8080; gcc/g++/python3 installed in WSL.
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repo 'var\oj-dev-agent.env'
$bin = Join-Path $repo 'var\oj-agent-linux'

if (-not (Test-Path $envFile)) { throw "missing $envFile (needs OJ_AGENT_ID / OJ_AGENT_SECRET)" }

if (-not (Test-Path $bin)) {
    Write-Host 'cross-compiling linux/amd64 agent...'
    Push-Location (Join-Path $repo 'judge\agent')
    $env:GOOS = 'linux'; $env:GOARCH = 'amd64'
    go build -o $bin ./src
    Remove-Item Env:GOOS, Env:GOARCH
    Pop-Location
}

$vars = @{}
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([A-Z_]+)=(.*)\s*$') { $vars[$Matches[1]] = $Matches[2] }
}
$id = $vars['OJ_AGENT_ID']; $secret = $vars['OJ_AGENT_SECRET']
$url = $vars['OJ_GATEWAY_URL']; $policy = $vars['OJ_POLICY_FILE']
$sandbox = $vars['OJ_SANDBOX_PREFERRED']
$linuxBin = ('/mnt/' + $bin.Substring(0, 1).ToLower() + $bin.Substring(2)) -replace '\\', '/'

# Copy to the WSL-native filesystem: executing ELF binaries directly from /mnt/*
# (9p/drvfs) can segfault. /tmp copy is required.
wsl -d Ubuntu-24.04 -- bash -c "cp $linuxBin /tmp/oj-agent && chmod +x /tmp/oj-agent && setsid nohup env OJ_GATEWAY_URL=$url OJ_AGENT_ID=$id OJ_AGENT_SECRET=$secret OJ_POLICY_FILE=$policy OJ_SANDBOX_PREFERRED=$sandbox /tmp/oj-agent > /tmp/oj-agent.log 2>&1 < /dev/null & sleep 2; tail -5 /tmp/oj-agent.log"
