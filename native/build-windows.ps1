# Builds rtlsdr.dll for the host architecture with MSVC and copies it, with the
# libusb DLL, to dist\native\windows-<arch>.
#
# Usage: native\build-windows.ps1 -Arch x64|ARM64 [-Ref <rtl-sdr-blog-git-ref>]
#
# Windows uses the prebuilt libusb binaries published by the libusb project. The
# C runtime is linked statically, so that the DLL needs nothing installed. Only
# the library is built: unlike the command line tools, it does not use pthreads.
param(
    [Parameter(Mandatory = $true)][ValidateSet('x64', 'ARM64')][string]$Arch,
    [string]$Ref = ''
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$Root = Split-Path -Parent $PSScriptRoot
$Versions = @{}
Get-Content "$Root\native\versions.env" | Where-Object { $_ -match '^[A-Z0-9_]+=' } | ForEach-Object {
    $Key, $Value = $_ -split '=', 2
    $Versions[$Key] = $Value
}
if (-not $Ref) { $Ref = $Versions.RTLSDR_REF }
$LibusbVersion = $Versions.LIBUSB_VERSION

if ($Arch -eq 'x64') {
    $Platform = 'windows-x86_64'
    $UsbDir = 'VS2022\MS64\dll'
} else {
    $Platform = 'windows-aarch64'
    $UsbDir = 'VS2025\ARM64\dll'
}
$Out = "$Root\dist\native\$Platform"
$Licenses = "$Root\dist\native\licenses"
$Work = "$Root\build\native-work"
Remove-Item -Recurse -Force $Work, $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Work, $Out, $Licenses | Out-Null
Set-Location $Work

Write-Host "=== libusb $LibusbVersion ==="
$Archive = "libusb-$LibusbVersion.7z"
Invoke-WebRequest "https://github.com/libusb/libusb/releases/download/v$LibusbVersion/$Archive" -OutFile $Archive
$Hash = (Get-FileHash $Archive -Algorithm SHA256).Hash
if ($Hash -ine $Versions.LIBUSB_WINDOWS_7Z_SHA256) {
    throw "libusb archive checksum mismatch: $Hash"
}
7z x $Archive -ousb | Out-Null
$Header = Get-ChildItem -Recurse -Path usb\include -Filter libusb.h | Select-Object -First 1
if (-not $Header) { throw 'libusb.h not found in the libusb archive' }

Write-Host "=== librtlsdr $Ref ==="
git -c advice.detachedHead=false clone --quiet --depth 1 --branch $Ref $Versions.RTLSDR_REPOSITORY rtlsdr
$Commit = (git -C rtlsdr rev-parse HEAD).Trim()

# Upstream's cmake_minimum_required predates CMP0091, which has to be NEW for
# CMAKE_MSVC_RUNTIME_LIBRARY to take effect. pkg-config is disabled so upstream
# takes the libusb paths given here.
cmake -S rtlsdr -B build -A $Arch `
    '-DCMAKE_POLICY_DEFAULT_CMP0091=NEW' `
    '-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded' `
    '-DCMAKE_DISABLE_FIND_PACKAGE_PkgConfig=ON' `
    "-DLIBUSB_INCLUDE_DIRS=$($Header.DirectoryName.Replace('\', '/'))" `
    "-DLIBUSB_LIBRARIES=$("$Work\usb\$UsbDir\libusb-1.0.lib".Replace('\', '/'))"
cmake --build build --config Release --parallel --target rtlsdr

Write-Host "=== packaging $Platform ==="
$Dll = Get-ChildItem -Recurse -Path build -Filter rtlsdr.dll | Select-Object -First 1
if (-not $Dll) { throw 'rtlsdr.dll was not produced' }
Copy-Item $Dll.FullName "$Out\rtlsdr.dll"
Copy-Item "$Work\usb\$UsbDir\libusb-1.0.dll" "$Out\libusb-1.0.dll"
Copy-Item 'rtlsdr\COPYING' "$Licenses\rtl-sdr-COPYING"
Copy-Item "$Work\usb\COPYING" "$Licenses\libusb-COPYING" -ErrorAction SilentlyContinue
@(
    "rtlsdr.ref=$Ref",
    "rtlsdr.commit=$Commit",
    "libusb.version=$LibusbVersion"
) | Set-Content -Encoding ascii "$Out\build-info.properties"
Get-ChildItem $Out
