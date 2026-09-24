param(
    [string]$PackageName = "com.example.englishword"
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$syncDirectory = Join-Path $projectRoot "sync"
$databasePath = Join-Path $syncDirectory "tablet-word_pocket.db"
$progressPath = Join-Path $syncDirectory "word_pocket-progress.tsv"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"

if (-not (Test-Path -LiteralPath $adb)) {
    throw "Android SDK의 adb를 찾지 못했습니다: $adb"
}

New-Item -ItemType Directory -Force -Path $syncDirectory | Out-Null

$devices = & $adb devices
$connected = @($devices | Select-String "\sdevice$")
if ($connected.Count -ne 1) {
    throw "USB 디버깅이 허용된 Android 기기 한 대를 연결해 주세요."
}

& $adb shell am force-stop $PackageName

try {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $adb
    $startInfo.ArgumentList.Add("exec-out")
    $startInfo.ArgumentList.Add("run-as")
    $startInfo.ArgumentList.Add($PackageName)
    $startInfo.ArgumentList.Add("cat")
    $startInfo.ArgumentList.Add("databases/wordly.db")
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::Start($startInfo)
    $file = [System.IO.File]::Open(
        $databasePath,
        [System.IO.FileMode]::Create,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None
    )
    try {
        $process.StandardOutput.BaseStream.CopyTo($file)
    } finally {
        $file.Dispose()
    }
    $errorText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "태블릿 SQLite를 가져오지 못했습니다: $errorText"
    }

    & python (Join-Path $PSScriptRoot "export_progress_from_db.py") $databasePath $progressPath
    if ($LASTEXITCODE -ne 0) {
        throw "동기화 파일을 생성하지 못했습니다."
    }

    Write-Output "SQLite snapshot: $databasePath"
    Write-Output "Import file: $progressPath"
} finally {
    & $adb shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 | Out-Null
}
