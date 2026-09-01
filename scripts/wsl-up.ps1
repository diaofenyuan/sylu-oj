# Start the whole OJ stack inside WSL Ubuntu-22.04.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\wsl-up.ps1
wsl -d Ubuntu-22.04 -- bash -lc "~/sylu-oj/scripts/wsl/start-all.sh"
