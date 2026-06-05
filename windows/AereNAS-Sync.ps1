# AereNAS Sync Script
# Moves files from a local folder to the mapped AereNAS drive
# Run manually or schedule via Task Scheduler
#
# Usage: .\AereNAS-Sync.ps1 -Source "C:\Users\YourName\ToOffload" -Dest "Z:\Backup"
# GitHub: https://github.com/Ishu1519/AereNAS

param(
    [string]$Source   = "C:\Users\$env:USERNAME\Documents\AereNAS-Sync",
    [string]$Dest     = "Z:\",
    [string]$LogFile  = "$env:APPDATA\AereNAS\sync.log"
)

function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $entry = "[$timestamp][$Level] $Message"
    Write-Host $entry
    $null = New-Item -ItemType Directory -Force -Path (Split-Path $LogFile)
    Add-Content -Path $LogFile -Value $entry
}

function Format-Bytes {
    param([long]$Bytes)
    if ($Bytes -ge 1GB) { return "{0:N2} GB" -f ($Bytes / 1GB) }
    if ($Bytes -ge 1MB) { return "{0:N2} MB" -f ($Bytes / 1MB) }
    if ($Bytes -ge 1KB) { return "{0:N2} KB" -f ($Bytes / 1KB) }
    return "$Bytes B"
}

Write-Log "AereNAS Sync started"
Write-Log "Source : $Source"
Write-Log "Dest   : $Dest"

# Verify source exists
if (-not (Test-Path $Source)) {
    Write-Log "Source folder not found: $Source" "WARN"
    exit 0
}

# Verify drive is accessible
if (-not (Test-Path $Dest)) {
    Write-Log "Destination not accessible: $Dest — is phone connected?" "ERROR"
    exit 1
}

$files = Get-ChildItem -Path $Source -Recurse -File
if ($files.Count -eq 0) {
    Write-Log "No files to sync"
    exit 0
}

Write-Log "Found $($files.Count) file(s) to move"

$moved   = 0
$failed  = 0
$skipped = 0
$totalBytes = 0L

foreach ($file in $files) {
    $relativePath = $file.FullName.Substring($Source.Length).TrimStart('\')
    $targetPath   = Join-Path $Dest $relativePath
    $targetDir    = Split-Path $targetPath

    try {
        # Create destination directory if needed
        if (-not (Test-Path $targetDir)) {
            New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        }

        # Copy file
        Copy-Item -Path $file.FullName -Destination $targetPath -Force -ErrorAction Stop

        # Verify by size
        $destInfo = Get-Item $targetPath
        if ($destInfo.Length -eq $file.Length) {
            Remove-Item -Path $file.FullName -Force -ErrorAction Stop
            $moved++
            $totalBytes += $file.Length
            Write-Log "Moved: $relativePath ($(Format-Bytes $file.Length))"
        } else {
            $failed++
            Write-Log "Size mismatch, kept source: $relativePath" "WARN"
            # Remove failed dest copy
            Remove-Item $targetPath -Force -ErrorAction SilentlyContinue
        }
    }
    catch {
        $failed++
        Write-Log "Failed: $relativePath — $_" "ERROR"
    }
}

# Clean up empty source directories
Get-ChildItem -Path $Source -Recurse -Directory |
    Sort-Object FullName -Descending |
    Where-Object { (Get-ChildItem $_.FullName -Recurse -File).Count -eq 0 } |
    Remove-Item -Force -ErrorAction SilentlyContinue

Write-Log "Sync complete — Moved: $moved, Failed: $failed, Skipped: $skipped, Total: $(Format-Bytes $totalBytes)"
