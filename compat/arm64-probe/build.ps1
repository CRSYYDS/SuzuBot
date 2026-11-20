<#
.SYNOPSIS
	Standalone Android arm64-v8a probe build (liba32android). Not part of the app.

.DESCRIPTION
	Read-only experiment build: fetches liba32android at a pinned commit and builds
	pico_arm64_probe and its shared libraries for an arm64 device.

	Scope: not part of the Gradle build, adds no native code to SuzuBot, never
	connects to or drives a device, never calls adb, never installs a toolchain.

	This file is intentionally ASCII-only: Windows PowerShell 5.1 decodes BOM-less
	scripts with the ANSI code page, and non-ASCII text corrupts string parsing.

.PARAMETER SdkRoot
	Android SDK root. Defaults to $env:ANDROID_HOME, then $env:ANDROID_SDK_ROOT.

.PARAMETER BuildDir
	Build directory. Defaults to <temp>\pico-arm64-probe-<timestamp>.

.PARAMETER Liba32SourceDir
	Optional local liba32android source tree, mapped to
	FETCHCONTENT_SOURCE_DIR_LIBA32ANDROID to build offline.

.PARAMETER DynarmicSourceDir
	Optional local dynarmic source tree, mapped to FETCHCONTENT_SOURCE_DIR_DYNARMIC.

.PARAMETER BoostIncludeDir
	Boost headers. Defaults to $env:BOOST_INCLUDE_DIR, then $env:BOOST_ROOT.

.EXAMPLE
	.\compat\arm64-probe\build.ps1 -BuildDir D:\tmp\probe
#>

[CmdletBinding()]
param(
	[string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }),
	[string]$BuildDir,
	[string]$NdkVersion = '27.2.12479018',
	[string]$CmakeVersion = '3.31.6',
	[string]$ApiLevel = 'android-24',
	[string]$BuildType = 'Release',
	[string]$BoostIncludeDir = $(if ($env:BOOST_INCLUDE_DIR) { $env:BOOST_INCLUDE_DIR } else { $env:BOOST_ROOT }),
	[string]$Liba32SourceDir,
	[string]$DynarmicSourceDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
	Write-Host "ERROR: $Message" -ForegroundColor Red
	exit 1
}

# Native failures are judged by the real exit code only.
function Invoke-Native([string]$Exe, [string[]]$Arguments) {
	& $Exe @Arguments
	if ($LASTEXITCODE -ne 0) {
		Fail "$Exe failed (exit code $LASTEXITCODE)"
	}
}

$sourceDir = Split-Path -Parent $MyInvocation.MyCommand.Path

if ([string]::IsNullOrWhiteSpace($SdkRoot) -or -not (Test-Path -LiteralPath $SdkRoot -PathType Container)) {
	Fail 'Android SDK not found: set ANDROID_HOME or pass -SdkRoot.'
}
$SdkRoot = (Resolve-Path -LiteralPath $SdkRoot).Path

$toolchainFile = Join-Path $SdkRoot "ndk\$NdkVersion\build\cmake\android.toolchain.cmake"
if (-not (Test-Path -LiteralPath $toolchainFile -PathType Leaf)) {
	Fail "NDK $NdkVersion toolchain not found: $toolchainFile"
}

$cmakeExe = Join-Path $SdkRoot "cmake\$CmakeVersion\bin\cmake.exe"
if (-not (Test-Path -LiteralPath $cmakeExe -PathType Leaf)) {
	Fail "CMake $CmakeVersion not found: $cmakeExe"
}

if ([string]::IsNullOrWhiteSpace($BuildDir)) {
	$BuildDir = Join-Path ([System.IO.Path]::GetTempPath()) "pico-arm64-probe-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}
$buildDirFull = [System.IO.Path]::GetFullPath($BuildDir)

Write-Host "SDK       : $SdkRoot"
Write-Host "Build dir : $buildDirFull"

$ninjaExe = Join-Path $SdkRoot "cmake\$CmakeVersion\bin\ninja.exe"

$configureArgs = @(
	'-S', $sourceDir,
	'-B', $buildDirFull,
	'-G', 'Ninja',
	"-DCMAKE_TOOLCHAIN_FILE=$toolchainFile",
	'-DANDROID_ABI=arm64-v8a',
	"-DANDROID_PLATFORM=$ApiLevel",
	'-DANDROID_STL=c++_shared',
	"-DCMAKE_BUILD_TYPE=$BuildType",
	'-DBUILD_TESTING=OFF',
	'-DLIBA32ANDROID_BUILD_TESTS=OFF',
	'-DDYNARMIC_ENABLE_NO_EXECUTE_SUPPORT=ON'
)
if (Test-Path -LiteralPath $ninjaExe -PathType Leaf) {
	$configureArgs += "-DCMAKE_MAKE_PROGRAM=$ninjaExe"
}
if (-not [string]::IsNullOrWhiteSpace($BoostIncludeDir)) {
	$configureArgs += "-DBoost_INCLUDE_DIR=$BoostIncludeDir"
}
if (-not [string]::IsNullOrWhiteSpace($Liba32SourceDir)) {
	$configureArgs += "-DFETCHCONTENT_SOURCE_DIR_LIBA32ANDROID=$Liba32SourceDir"
}
if (-not [string]::IsNullOrWhiteSpace($DynarmicSourceDir)) {
	$configureArgs += "-DFETCHCONTENT_SOURCE_DIR_DYNARMIC=$DynarmicSourceDir"
}

Invoke-Native $cmakeExe $configureArgs
Invoke-Native $cmakeExe @('--build', $buildDirFull, '--target', 'pico_arm64_probe', '--parallel', '4')

# The probe lands at the top level; liba32android.so is built as a sub-project.
$probePath = Join-Path $buildDirFull 'pico_arm64_probe'
$stagedLib = Join-Path $buildDirFull 'liba32android.so'
$libPath = Join-Path $buildDirFull '_deps\liba32android-build\liba32android.so'
$llvm = Join-Path $SdkRoot "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64"
$stlPath = Join-Path $llvm 'sysroot\usr\lib\aarch64-linux-android\libc++_shared.so'

if (-not (Test-Path -LiteralPath $probePath -PathType Leaf)) {
	Fail "Build finished but pico_arm64_probe is missing: $probePath"
}
if (-not (Test-Path -LiteralPath $libPath -PathType Leaf)) {
	Fail "Build finished but liba32android.so is missing: $libPath"
}

# Copy next to the probe so the $ORIGIN rpath resolves without LD_LIBRARY_PATH.
Copy-Item -LiteralPath $libPath -Destination $stagedLib
Copy-Item -LiteralPath $stlPath -Destination $buildDirFull
if ($BuildType -in @('Release', 'MinSizeRel')) {
	Invoke-Native (Join-Path $llvm 'bin\llvm-strip.exe') @('--strip-unneeded', $probePath, $stagedLib)
}

Write-Host "probe   : $probePath"
Write-Host "runtime : $stagedLib"
Write-Host 'No device was contacted. Push and run manually, see README.md.'
exit 0
