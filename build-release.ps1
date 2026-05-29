Write-Host "Starting Release Build process..." -ForegroundColor Cyan
python tools/build_release.py
if ($LASTEXITCODE -ne 0) {
    Write-Host "Release Build Failed!" -ForegroundColor Red
    Exit 1
}
Write-Host "Done!" -ForegroundColor Green
