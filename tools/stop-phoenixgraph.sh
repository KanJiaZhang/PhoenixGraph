#!/usr/bin/env bash
#
# Stop the PhoenixGraph services started by tools/start-phoenixgraph.sh.
#
#   tools/stop-phoenixgraph.sh
#
# It finds the JVMs by their command line (the graph-http / graph-web jar), so it
# also stops an instance that was started by hand.
set -uo pipefail

echo "Stopping PhoenixGraph services (graph-http / graph-web JVMs)..."

powershell -NoProfile -Command "
  \$found = 0
  Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" |
    Where-Object { \$_.CommandLine -like '*graph-http*target*graph-*' -or \$_.CommandLine -like '*graph-web*target*graph-*' } |
    ForEach-Object {
      \$found++
      Write-Host ('  killing pid ' + \$_.ProcessId)
      Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue
    }
  if (\$found -eq 0) { Write-Host '  nothing was running' }
" | tr -d '\r'

echo "done."
