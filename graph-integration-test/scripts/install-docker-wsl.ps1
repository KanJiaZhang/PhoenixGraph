# =====================================================================
#  Install WSL2 + Docker Desktop (run in an ELEVATED PowerShell)
#
#  Usage:
#    Right-click PowerShell -> "Run as administrator", then:
#      powershell -ExecutionPolicy Bypass -File .\install-docker-wsl.ps1
#
#  After the script finishes you MUST reboot, then:
#    1. Start Docker Desktop and wait until it is "Running".
#    2. Start the single-node Aerospike:
#         cd graph-integration-test
#         docker compose up -d
#    3. Run the integration test:
#         cd ..
#         mvn -pl graph-integration-test test
# =====================================================================

$ErrorActionPreference = "Stop"

Write-Host "==> Enabling Windows features (WSL + Virtual Machine Platform) ..."
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart

Write-Host "==> Installing WSL ..."
wsl.exe --install --no-distribution

Write-Host "==> Setting WSL 2 as the default version ..."
wsl.exe --set-default-version 2

Write-Host "==> Installing Docker Desktop via winget ..."
winget install --id Docker.DockerDesktop -e --accept-source-agreements --accept-package-agreements

Write-Host ""
Write-Host "====================================================================="
Write-Host " Done. Please REBOOT the machine now, then:"
Write-Host "   1. Launch Docker Desktop and wait until it reports 'Running'."
Write-Host "   2. Start Aerospike and run the integration test:"
Write-Host "        cd graph-integration-test"
Write-Host "        docker compose up -d"
Write-Host "        cd .."
Write-Host "        mvn -pl graph-integration-test test"
Write-Host "====================================================================="
