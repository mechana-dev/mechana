# Copyright (c) 2026 Mark Vita
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Target = (Join-Path $env:ProgramData 'Mechana\runtime\java-25')
)

$ErrorActionPreference = 'Stop'
if (-not $JdkHome) { throw 'Set JAVA_HOME or pass -JdkHome with a JDK 25 installation.' }
$jlink = Join-Path $JdkHome 'bin\jlink.exe'
$jmods = Join-Path $JdkHome 'jmods'
if (-not (Test-Path -PathType Leaf $jlink)) { throw "jlink.exe was not found at $jlink" }
if (-not (Test-Path -PathType Container $jmods)) { throw "JDK modules were not found at $jmods" }
if (Test-Path $Target) { throw "Refusing to replace existing runtime: $Target" }

$parent = Split-Path -Parent $Target
New-Item -ItemType Directory -Force $parent | Out-Null
& $jlink --module-path $jmods --add-modules ALL-MODULE-PATH --strip-debug --no-header-files --no-man-pages `
    --compress=zip-6 --output $Target
if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit code $LASTEXITCODE" }

& (Join-Path $Target 'bin\java.exe') -version
if ($LASTEXITCODE -ne 0) { throw 'The private Mechana Java runtime did not start.' }
Write-Output "Installed private Mechana Java runtime at $Target"
