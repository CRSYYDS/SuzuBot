<#
.SYNOPSIS
	Builds the Armbat-based ARM64 runner host from a pinned, separate source tree.

.DESCRIPTION
	The runner is a third-party container (dima-xd/armbat, Apache-2.0) plus a
	small local patch, so nothing here is part of the SuzuBot Gradle build: the
	script takes an already-checked-out armbat tree, verifies it is the pinned
	revision with its three submodules at the pinned commits, verifies the
	Android SDK pieces the manifest asks for, applies runner.patch (once), and
	runs :app:assembleDebug.

	The script is read-only with respect to the tree apart from the patch:

	- it never runs git clean, git checkout, git reset or git stash;
	- it never overwrites a file that the patch does not own;
	- a second run recognises an already-applied patch with
	  "git apply --reverse --check" and does not apply it again;
	- it changes no git configuration (every -c below is per-invocation) and
	  touches no device, adb, or system state.

	This file is intentionally ASCII-only: Windows PowerShell 5.1 decodes
	BOM-less scripts with the ANSI code page, and non-ASCII text corrupts string
	parsing.

.PARAMETER SourceDir
	Absolute path to the pinned armbat source tree (HEAD 65863e1). Required.

.PARAMETER GradlePath
	Gradle launcher. Defaults to <SourceDir>\gradlew.bat.

.PARAMETER SdkRoot
	Android SDK root. Defaults to $env:ANDROID_HOME, then $env:ANDROID_SDK_ROOT.

.PARAMETER JavaHome
	JDK for Gradle. Defaults to $env:JAVA_HOME, then the java.exe on PATH.
	AGP 9.0.1 requires JDK 17 or newer.

.EXAMPLE
	.\compat\arm64-runner\build.ps1 -SourceDir D:\vendor\armbat-65863e1

.EXAMPLE
	.\compat\arm64-runner\build.ps1 -SourceDir D:\vendor\armbat-65863e1 `
		-JavaHome D:\dsh-workspace\tools\jdk-17.0.21+9
#>

[CmdletBinding()]
param(
	[Parameter(Mandatory = $true, Position = 0)]
	[string]$SourceDir,

	[string]$GradlePath,

	[string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }),

	[string]$JavaHome = $env:JAVA_HOME
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# --- pinned inputs -----------------------------------------------------------

$ExpectedHead = '65863e1fad5971f4724b89d07f999f2d8895db4b'

# Path inside the tree -> commit the tree records for it.
$ExpectedSubmodules = [ordered]@{
	'runtime/sysroot' = 'eb115323c1c086b02289815e0a7753bb8c2a2986'
	'third_party/boost' = 'ff5f55bd97a93b23d6e5cb6d192290fb49b3f002'
	'third_party/dynarmic' = 'aeda40978909fe5416c17e63d1377203a88d3a9b'
}

# Values from app/build.gradle.kts of that revision.
$PlatformApi = 'android-36'
$BuildToolsVersion = '36.0.0'
$NdkVersion = '29.0.13113456'
$CmakeVersion = '3.31.6'
$MinimumJavaMajor = 17

$PatchName = 'runner.patch'

# --- helpers -----------------------------------------------------------------

function Fail([string]$Message) {
	Write-Host "ERROR: $Message" -ForegroundColor Red
	exit 1
}

function Say([string]$Message) {
	Write-Host "[runner] $Message"
}

# git is invoked with -C so the caller's working directory stays irrelevant.
# PowerShell 5.1 turns a native stderr line into a terminating error while
# $ErrorActionPreference is 'Stop', which would replace a diagnosable git exit
# code with an exception trace; the preference is relaxed only around the call.
function Invoke-Git {
	param(
		[string]$Root,
		[string[]]$Arguments,
		[switch]$AllowFailure
	)
	$previous = $ErrorActionPreference
	$ErrorActionPreference = 'Continue'
	try {
		$output = & git -C $Root @Arguments 2>&1
		$code = $LASTEXITCODE
	} finally {
		$ErrorActionPreference = $previous
	}
	if ($code -ne 0 -and -not $AllowFailure) {
		Fail "git $($Arguments -join ' ') failed under ${Root} (exit $code): $($output -join ' | ')"
	}
	return [pscustomobject]@{ Code = $code; Text = ($output -join "`n") }
}

# --- 1. source tree identity -------------------------------------------------

if ([string]::IsNullOrWhiteSpace($SourceDir)) {
	Fail '-SourceDir is required: absolute path to the pinned armbat source tree.'
}
if (-not [System.IO.Path]::IsPathRooted($SourceDir)) {
	Fail "-SourceDir must be an absolute path, got '$SourceDir'."
}
if (-not (Test-Path -LiteralPath $SourceDir -PathType Container)) {
	Fail "-SourceDir does not exist or is not a directory: $SourceDir"
}
$sourceRoot = (Resolve-Path -LiteralPath $SourceDir).Path
$gradleRoot = $sourceRoot.TrimEnd('\')

$toplevel = Invoke-Git -Root $sourceRoot -Arguments @('rev-parse', '--show-toplevel')
# git answers with forward slashes and no trailing separator; compare that form.
$toplevelPath = $toplevel.Text.Trim().Replace('/', '\').TrimEnd('\')
if (-not [string]::Equals($toplevelPath, $gradleRoot, [StringComparison]::OrdinalIgnoreCase)) {
	Fail "-SourceDir must be the root of its own work tree. git says '$toplevelPath', argument is '$gradleRoot'."
}

$head = Invoke-Git -Root $sourceRoot -Arguments @('rev-parse', 'HEAD')
$headHash = $head.Text.Trim()
if ($headHash -ne $ExpectedHead) {
	Fail "HEAD is '$headHash', expected pinned '$ExpectedHead'. Check out that commit; this script never moves HEAD."
}
Say "HEAD ok: $headHash"

# Committed gitlink must match the pin before the checkout is even inspected, so
# a locally committed submodule bump is reported as such.
foreach ($path in $ExpectedSubmodules.Keys) {
	$expected = $ExpectedSubmodules[$path]
	$entry = Invoke-Git -Root $sourceRoot -Arguments @('ls-tree', 'HEAD', '--', $path)
	if ([string]::IsNullOrWhiteSpace($entry.Text)) {
		Fail "HEAD does not record a submodule at '$path'."
	}
	$recorded = ($entry.Text -split '\s+')[2]
	if ($recorded -ne $expected) {
		Fail "'$path' is recorded in HEAD as '$recorded', expected '$expected'."
	}
}

foreach ($path in $ExpectedSubmodules.Keys) {
	$expected = $ExpectedSubmodules[$path]
	$full = Join-Path $gradleRoot $path
	if (-not (Test-Path -LiteralPath $full -PathType Container)) {
		Fail "submodule '$path' is not checked out: $full is missing. Run 'git -C `"$gradleRoot`" submodule update --init --recursive' first; this script does not touch the network."
	}
	# An uninitialised submodule directory still sits inside the parent work
	# tree, so --is-inside-work-tree says true; only this checkout's own HEAD
	# proves it was actually populated.
	$headHere = Invoke-Git -Root $full -Arguments @('rev-parse', 'HEAD') -AllowFailure
	if ($headHere.Code -ne 0) {
		Fail "submodule '$path' is empty or broken (no HEAD of its own): $($headHere.Text.Trim()). Run 'git -C `"$gradleRoot`" submodule update --init --recursive' first; this script does not touch the network."
	}
	$actual = $headHere.Text.Trim()
	if ($actual -ne $expected) {
		Fail "submodule '$path' is at '$actual', expected '$expected'. Fix the checkout; this script never moves HEAD."
	}
	Say "submodule ok: $path $actual"
}

# --- 2. Android SDK pieces ---------------------------------------------------

if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
	Fail 'Android SDK not found: set ANDROID_HOME, pass -SdkRoot, or set ANDROID_SDK_ROOT.'
}
if (-not (Test-Path -LiteralPath $SdkRoot -PathType Container)) {
	Fail "-SdkRoot is not a directory: $SdkRoot"
}
$sdk = (Resolve-Path -LiteralPath $SdkRoot).Path

$required = [ordered]@{
	"platform $PlatformApi" = (Join-Path $sdk "platforms\$PlatformApi\android.jar")
	"build-tools $BuildToolsVersion aapt2" = (Join-Path $sdk "build-tools\$BuildToolsVersion\aapt2.exe")
	"build-tools $BuildToolsVersion d8.jar" = (Join-Path $sdk "build-tools\$BuildToolsVersion\lib\d8.jar")
	"ndk $NdkVersion toolchain" = (Join-Path $sdk "ndk\$NdkVersion\build\cmake\android.toolchain.cmake")
	"cmake $CmakeVersion" = (Join-Path $sdk "cmake\$CmakeVersion\bin\cmake.exe")
	"cmake $CmakeVersion ninja" = (Join-Path $sdk "cmake\$CmakeVersion\bin\ninja.exe")
}
foreach ($name in $required.Keys) {
	$file = $required[$name]
	if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
		Fail "Android SDK is missing $name (expected $file). Install '$name' or pass the right -SdkRoot; this script installs nothing."
	}
}
Say "sdk ok: $sdk"

# --- 3. JDK ------------------------------------------------------------------

$javaExe = $null
if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
	$candidate = Join-Path $JavaHome 'bin\java.exe'
	if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
		Fail "-JavaHome does not contain bin\java.exe: $JavaHome"
	}
	$javaExe = $candidate
} else {
	$onPath = Get-Command -Name 'java.exe' -ErrorAction SilentlyContinue
	if ($null -eq $onPath) {
		Fail 'No JDK found: pass -JavaHome or set JAVA_HOME.'
	}
	$javaExe = $onPath.Source
}

$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
	$versionText = (& $javaExe -version 2>&1) -join "`n"
	$versionCode = $LASTEXITCODE
} finally {
	$ErrorActionPreference = $previousPreference
}
if ($versionCode -ne 0) {
	Fail "java -version failed: $versionText"
}
if ($versionText -notmatch 'version "(\d+)') {
	Fail "Cannot read a version from java -version: $versionText"
}
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt $MinimumJavaMajor) {
	Fail "JDK $javaMajor is too old: AGP 9.0.1 needs $MinimumJavaMajor or newer ($javaExe)."
}
Say "jdk ok: $javaMajor ($javaExe)"

# --- 4. Gradle launcher ------------------------------------------------------

if ([string]::IsNullOrWhiteSpace($GradlePath)) {
	$GradlePath = Join-Path $gradleRoot 'gradlew.bat'
}
if (-not (Test-Path -LiteralPath $GradlePath -PathType Leaf)) {
	Fail "Gradle launcher not found: $GradlePath (pass -GradlePath)."
}
$GradlePath = (Resolve-Path -LiteralPath $GradlePath).Path

# --- 5. patch state ----------------------------------------------------------

$patchPath = Join-Path $PSScriptRoot $PatchName
if (-not (Test-Path -LiteralPath $patchPath -PathType Leaf)) {
	Fail "runner.patch is missing next to this script: $patchPath"
}

# Line endings are deliberately not overridden. The patch is stored with LF and
# the tree was checked out with whatever core.autocrlf the machine has, so
# forcing a value here (for example autocrlf=false) makes every CRLF context
# line mismatch and the patch "not apply" even though the tree is clean.
# git apply reads the same configuration the checkout used, and nothing below
# writes to any git configuration file.
$patchArgs = @('--whitespace=nowarn', $patchPath)

$applyCheck = Invoke-Git -Root $gradleRoot -AllowFailure -Arguments (
	@('apply', '--check') + $patchArgs
)

$patchApplied = $false
if ($applyCheck.Code -eq 0) {
	$patchApplied = $false
} else {
	$reverseCheck = Invoke-Git -Root $gradleRoot -AllowFailure -Arguments (
		@('apply', '--reverse', '--check') + $patchArgs
	)
	if ($reverseCheck.Code -eq 0) {
		$patchApplied = $true
	} else {
		Fail @"
runner.patch applies neither forwards nor backwards to this tree.
  forward: $($applyCheck.Text)
  reverse: $($reverseCheck.Text)
The tree is not the pinned revision, or it carries edits that overlap the
patch. Resolve it by hand; this script does not reset, stash or clean anything.
"@
	}
}

# Reported, never touched: unrelated work in the tree is the caller's.
$dirty = Invoke-Git -Root $gradleRoot -Arguments @('status', '--porcelain', '--untracked-files=no') -AllowFailure
$dirtyLines = @($dirty.Text -split "`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($dirtyLines.Count -gt 0) {
	Say "tree already has $($dirtyLines.Count) modified tracked file(s); left as they are:"
	$dirtyLines | ForEach-Object { Say "  $_" }
}

if ($patchApplied) {
	Say "runner.patch is already applied; not applying it again."
} else {
	Invoke-Git -Root $gradleRoot -Arguments (@('apply') + $patchArgs) | Out-Null
	$verify = Invoke-Git -Root $gradleRoot -AllowFailure -Arguments (
		@('apply', '--reverse', '--check') + $patchArgs
	)
	if ($verify.Code -ne 0) {
		Fail "runner.patch reported success but does not reverse-check cleanly: $($verify.Text)"
	}
	Say "runner.patch applied: $patchPath"
}

# --- 6. build ----------------------------------------------------------------

$savedAndroidHome = $env:ANDROID_HOME
$savedSdkRoot = $env:ANDROID_SDK_ROOT
$savedJavaHome = $env:JAVA_HOME
$savedPath = $env:PATH

try {
	$env:ANDROID_HOME = $sdk
	$env:ANDROID_SDK_ROOT = $sdk
	$env:JAVA_HOME = (Split-Path -Parent (Split-Path -Parent $javaExe))
	$javaBin = Join-Path $env:JAVA_HOME 'bin'
	if (-not ($env:PATH -split ';' | Where-Object { $_ -eq $javaBin })) {
		$env:PATH = "$javaBin;$env:PATH"
	}

	$gradleArgs = @(
		'-p', $gradleRoot,
		':app:assembleDebug',
		'--no-daemon',
		'--console=plain'
	)
	Say 'gradle :app:assembleDebug'
	# Gradle writes progress and warnings to stderr; under 'Stop' PowerShell 5.1
	# would turn the first line into a terminating error and hide the exit code.
	$gradlePrevious = $ErrorActionPreference
	$ErrorActionPreference = 'Continue'
	try {
		& $GradlePath @gradleArgs
		$buildCode = $LASTEXITCODE
	} finally {
		$ErrorActionPreference = $gradlePrevious
	}
} finally {
	$env:ANDROID_HOME = $savedAndroidHome
	$env:ANDROID_SDK_ROOT = $savedSdkRoot
	$env:JAVA_HOME = $savedJavaHome
	$env:PATH = $savedPath
}

if ($buildCode -ne 0) {
	Fail "gradle :app:assembleDebug failed (exit $buildCode). The source tree still contains runner.patch."
}

# --- 7. artifact -------------------------------------------------------------

$apk = Join-Path $gradleRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
	Fail "gradle reported success but the debug APK is missing: $apk"
}
$size = (Get-Item -LiteralPath $apk).Length
$sha = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
Say "apk: $apk"
Say "apk.size=$size"
Say "apk.sha256=$sha"
exit 0
