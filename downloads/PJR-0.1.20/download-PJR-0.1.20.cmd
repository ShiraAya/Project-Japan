@echo off
setlocal
set "PJR_DOWNLOADER=%~f0"
set "PJR_DOWNLOAD_DIR=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$s=Get-Content -LiteralPath $env:PJR_DOWNLOADER -Raw; & ([scriptblock]::Create(($s -split '(?m)^# POWERSHELL_START\r?$',2)[1]))"
if errorlevel 1 (
  echo Download failed. You can run this file again to retry.
  pause
  exit /b 1
)
echo Download complete. The JAR is beside this script.
pause
exit /b 0
# POWERSHELL_START
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$base = 'https://raw.githubusercontent.com/ShiraAya/Project-Japan/main/downloads/PJR-0.1.20'
$expected = '986bfb13568392d782ce66e943d8aa82fc6bccde87e73af5fba81b71e537ac62'
$name = 'ProjectJapanRefined-0.1.20-alpha.jar'
$destination = Join-Path $env:PJR_DOWNLOAD_DIR $name
if (Test-Path -LiteralPath $destination) {
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -eq $expected) {
        Write-Host 'The complete JAR already exists and its checksum is correct.'
        exit 0
    }
    throw 'A different file already exists at the destination. Move or rename it first.'
}
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('pjr-download-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temporary) | Out-Null
$staged = Join-Path $temporary $name
try {
    $manifest = Invoke-RestMethod -Uri ($base + '/manifest.json')
    if ($manifest.sha256 -ne $expected -or $manifest.file -ne $name -or $manifest.size -ne 49970776) { throw 'Unexpected download manifest.' }
    $output = [IO.File]::Create($staged)
    try {
        $index = 0
        foreach ($part in $manifest.parts) {
            $index++
            Write-Host ('Downloading part {0}/{1}...' -f $index, $manifest.parts.Count)
            if ($part.name -notmatch '^ProjectJapanRefined-0\.1\.20-alpha\.jar\.part[0-9]{2}$') { throw 'Invalid part name.' }
            $piece = Join-Path $temporary $part.name
            Invoke-WebRequest -UseBasicParsing -Uri ($base + '/' + $part.name) -OutFile $piece
            if ((Get-Item -LiteralPath $piece).Length -ne $part.size -or (Get-FileHash -LiteralPath $piece -Algorithm SHA256).Hash.ToLowerInvariant() -ne $part.sha256) { throw 'Part checksum mismatch.' }
            $inputFile = [IO.File]::OpenRead($piece)
            try { $inputFile.CopyTo($output) } finally { $inputFile.Dispose() }
        }
    } finally { $output.Dispose() }
    if ((Get-Item -LiteralPath $staged).Length -ne 49970776 -or (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Final JAR checksum mismatch.' }
    Move-Item -LiteralPath $staged -Destination $destination
    Write-Host ('Verified: ' + $destination)
} finally {
    Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
}
