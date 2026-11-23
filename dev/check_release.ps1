# Checks a Windows release zip the way a user gets it: unpacks it into a folder with a space in its
# name and builds an example for the SNES and for Windows with what the zip holds. JAVA_HOME points
# nowhere, so a launcher that does not take the zip's own Java fails. Without Emscripten on the
# PATH the target web must say what to install. Linux: check_release.sh.
#
#   pwsh dev/check_release.ps1 build/release/superdevkit-<version>-windows-x64.zip
param([Parameter(Mandatory)][string]$Zip)
$ErrorActionPreference = 'Stop'

$tmp = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
$dir = Join-Path $tmp 'a folder'
New-Item -ItemType Directory -Path $dir | Out-Null
try {
    Expand-Archive -Path $Zip -DestinationPath $dir
    $kitHome = (Get-ChildItem -Path $dir -Directory | Select-Object -First 1).FullName
    $kit = Join-Path $kitHome 'bin\superdevkit.bat'
    $env:JAVA_HOME = 'C:\nonexistent'
    Remove-Item Env:EMCC -ErrorAction SilentlyContinue

    # A program's stderr is no error here (Windows PowerShell 5.1 would stop on it), and an expected
    # failure must not stay in LASTEXITCODE, which the script would end with
    function Invoke-Tool {
        param([string]$File, [string[]]$Arguments)
        $ErrorActionPreference = 'Continue'
        $text = & $File @Arguments 2>&1 | Out-String
        $status = $LASTEXITCODE
        $global:LASTEXITCODE = 0
        Write-Host $text
        return [pscustomobject]@{ Text = $text; Status = $status }
    }

    function Invoke-Kit {
        $result = Invoke-Tool $kit $args
        if ($result.Status -ne 0) { throw "superdevkit $args failed" }
        return $result.Text
    }

    $version = (Get-Content (Join-Path $kitHome 'VERSION')).Trim()
    if ((Invoke-Kit --version).Trim() -ne "superdevkit $version") { throw 'superdevkit: wrong version' }
    $dampf = Invoke-Tool (Join-Path $kitHome 'bin\dampf.bat') @('--version')
    if ($dampf.Status -ne 0 -or -not $dampf.Text.Contains($version)) { throw "dampf: wrong version: $($dampf.Text)" }
    if (-not (Test-Path (Join-Path $kitHome 'raylib\web\include\raylib.h'))) { throw 'no raylib for the web' }

    Set-Location $dir
    Invoke-Kit init hello | Out-Null
    Set-Location (Join-Path $dir 'hello')
    Invoke-Kit build snes | Out-Null
    if ((Get-Item 'build\snes\hello.sfc').Length -eq 0) { throw 'no ROM' }

    $log = Invoke-Kit build win64
    if (-not (Test-Path 'build\win64\hello.exe')) { throw 'no Windows program' }
    if ($log.Contains('fetching')) { throw 'raylib was fetched, the zip should hold it' }

    $path = $env:PATH
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
    $web = Invoke-Tool $kit @('build', 'web')
    $env:PATH = $path
    if ($web.Status -eq 0 -or -not $web.Text.Contains('no Emscripten')) { throw 'the target web should say that Emscripten is missing' }

    Write-Host "release zip checked: superdevkit $version"
} finally {
    Set-Location ([IO.Path]::GetTempPath())
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
