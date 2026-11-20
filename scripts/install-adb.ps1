<#
.SYNOPSIS
	Install one APK for the device's current Android user only, never all users.

.DESCRIPTION
	Serial from -Serial, else adb get-serialno (exactly one device required); user id
	from adb shell am get-current-user (single non-negative decimal). Runs:
		adb -s <serial> install --user <id> -r [-t] <absolute apk>
	ASCII-only: Windows PowerShell 5.1 reads BOM-less scripts as ANSI.

.EXAMPLE
	.\scripts\install-adb.ps1 D:\builds\SuzuBot-v1.1.4.apk -AllowTestApk
#>
[CmdletBinding()]
param(
	[Parameter(Mandatory = $true, Position = 0)]
	[string]$ApkPath,
	[string]$AdbPath = 'adb',
	[string]$Serial,
	[switch]$AllowTestApk
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
	Write-Host "ERROR: $Message" -ForegroundColor Red
	exit 1
}

# ADB may write server startup notices to stderr. Read only stdout as the query value.
function Invoke-Adb([string[]]$Arguments) {
	$ErrorActionPreference = 'Continue'
	$output = @(& $adb @Arguments | ForEach-Object { $_.Trim() } | Where-Object { $_ })
	[pscustomobject]@{ Code = $LASTEXITCODE; Lines = $output }
}

if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { Fail "APK not found: $ApkPath" }
$apkFull = (Resolve-Path -LiteralPath $ApkPath).Path

$adbCommand = Get-Command -Name $AdbPath -CommandType Application -ErrorAction SilentlyContinue |
	Select-Object -First 1
if (-not $adbCommand) { Fail "adb not found: $AdbPath (pass -AdbPath or add adb to PATH)" }
$adb = $adbCommand.Source

# Joining the lines rejects multi-device output too: several serials cannot match ^\S+$.
if ([string]::IsNullOrWhiteSpace($Serial)) {
	$probe = Invoke-Adb @('get-serialno')
	$serialText = ($probe.Lines -join ' ').Trim()
	if ($probe.Code -ne 0 -or $serialText -eq 'unknown' -or $serialText -notmatch '^\S+$') {
		Fail "need exactly one device; adb get-serialno exited $($probe.Code): $serialText"
	}
	$Serial = $serialText
}

$currentUser = Invoke-Adb @('-s', $Serial, 'shell', 'am', 'get-current-user')
$userId = ($currentUser.Lines -join ' ').Trim()
if ($currentUser.Code -ne 0 -or $userId -notmatch '^[0-9]+$') {
	Fail "cannot resolve current user of $Serial; adb exited $($currentUser.Code): $userId"
}

$installArgs = @('-s', $Serial, 'install', '--user', $userId, '-r')
if ($AllowTestApk) { $installArgs += '-t' }
$installArgs += $apkFull
Write-Host "adb $($installArgs -join ' ')"
# adb keeps its own stdout/stderr so failures stay diagnosable; exit code passes through.
& $adb @installArgs
exit $LASTEXITCODE
