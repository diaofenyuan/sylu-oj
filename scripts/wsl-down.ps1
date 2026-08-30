# Stop the whole OJ stack inside WSL Ubuntu-24.04.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\wsl-down.ps1
wsl -d Ubuntu-24.04 -- bash -lc "~/sylu-oj/scripts/wsl/stop-all.sh"
