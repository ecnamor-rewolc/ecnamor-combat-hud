# Compile and Deploy Combat HUD Mod for Development
# Uses local.properties for the Starsector directory path.

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
if ([string]::IsNullOrEmpty($scriptDir)) {
    $scriptDir = $PSScriptRoot
}
if ([string]::IsNullOrEmpty($scriptDir)) {
    $scriptDir = "."
}

# Resolve absolute path of the script directory
$scriptDir = (Resolve-Path $scriptDir).Path
$S = Join-Path $scriptDir "Source"

# Load local.properties to read starsector.dir
$propertiesFile = Join-Path $scriptDir "local.properties"
if (-not (Test-Path $propertiesFile)) {
    Write-Error "local.properties file not found! Please copy local.properties.example to local.properties and configure starsector.dir."
    Exit 1
}

$properties = @{}
Get-Content $propertiesFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
        $parts = $line.Split("=", 2)
        $key = $parts[0].Trim()
        $value = $parts[1].Trim()
        $value = $value.Replace("\\", "\") # Resolve escaped backslashes
        $properties[$key] = $value
    }
}

$starsectorDir = $properties["starsector.dir"]
if (-not $starsectorDir) {
    Write-Error "starsector.dir is not set in local.properties!"
    Exit 1
}

if (-not (Test-Path $starsectorDir)) {
    Write-Error "Starsector directory not found: $starsectorDir"
    Exit 1
}

$MOD = Join-Path $starsectorDir "mods"
$coreDir = Join-Path $starsectorDir "starsector-core"

# Core jars
$starfarerJar = Join-Path $coreDir "starfarer.api.jar"
$lwjglJar = Join-Path $coreDir "lwjgl.jar"
$lwjglUtilJar = Join-Path $coreDir "lwjgl_util.jar"
$log4jJar = Join-Path $coreDir "log4j-1.2.9.jar"

foreach ($jar in @($starfarerJar, $lwjglJar, $lwjglUtilJar, $log4jJar)) {
    if (-not (Test-Path $jar)) {
        Write-Error "Required core library not found: $jar"
        Exit 1
    }
}

# Helper to dynamically find a library jar in mods/ matching a folder pattern and filename
function Resolve-DependencyJar {
    param(
        [string]$ModsDir,
        [string]$ModFolderPattern,
        [string]$JarName
    )
    $matches = Get-ChildItem -Path (Join-Path $ModsDir $ModFolderPattern) -Directory -ErrorAction SilentlyContinue
    if ($matches) {
        foreach ($folder in $matches) {
            $jarFile = Get-ChildItem -Path $folder.FullName -Filter $JarName -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($jarFile) {
                return $jarFile.FullName
            }
        }
    }
    return $null
}

Write-Host "Resolving dependency paths..."
$lazyLibJar = Resolve-DependencyJar -ModsDir $MOD -ModFolderPattern "LazyLib*" -JarName "LazyLib.jar"
$lunaLibJar = Resolve-DependencyJar -ModsDir $MOD -ModFolderPattern "LunaLib*" -JarName "LunaLib.jar"
$magicLibJar = Resolve-DependencyJar -ModsDir $MOD -ModFolderPattern "MagicLib*" -JarName "MagicLib.jar"
$magicLibKotlinJar = Resolve-DependencyJar -ModsDir $MOD -ModFolderPattern "MagicLib*" -JarName "MagicLib-Kotlin.jar"

if (-not $lazyLibJar) { Write-Error "Could not find LazyLib.jar in mods folder matching LazyLib*"; Exit 1 }
if (-not $lunaLibJar) { Write-Error "Could not find LunaLib.jar in mods folder matching LunaLib*"; Exit 1 }
if (-not $magicLibJar) { Write-Error "Could not find MagicLib.jar in mods folder matching MagicLib*"; Exit 1 }
if (-not $magicLibKotlinJar) { Write-Error "Could not find MagicLib-Kotlin.jar in mods folder matching MagicLib*"; Exit 1 }

$cp = "$starfarerJar;$lwjglJar;$lwjglUtilJar;$log4jJar;$lazyLibJar;$lunaLibJar;$magicLibJar;$magicLibKotlinJar"

$ver = (Get-Content "$S\mod_info.json" | ConvertFrom-Json).version
$D   = "$MOD\ecnamor_combat_hud-$ver"

Write-Host "Cleaning bin folder..."
if (Test-Path "$S\bin") {
    Get-ChildItem "$S\bin\*" -Recurse -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
} else {
    New-Item -ItemType Directory -Force "$S\bin" | Out-Null
}

Write-Host "Compiling Java source files..."
$src = (Get-ChildItem "$S\src" -Recurse -Filter "*.java").FullName
$out = javac -source 8 -target 8 -encoding UTF-8 -cp $cp -d "$S\bin" $src 2>&1
$err = $out | Where-Object { $_ -match "error:" }

if ($err) {
    Write-Host "COMPILATION ERRORS:" -ForegroundColor Red
    $err
    Exit 1
}

Write-Host "Cleaning old deployments..."
Get-ChildItem "$MOD\ecnamor_combat_hud-*" -Directory | Where-Object { $_.FullName -ne $D } | Remove-Item -Recurse -Force

Write-Host "Creating deployment directory: $D..."
New-Item -ItemType Directory -Force "$D\jars" | Out-Null
Copy-Item "$S\mod_info.json" "$D\mod_info.json" -Force

if (Test-Path "$D\data") { Remove-Item -Recurse -Force "$D\data" }
Copy-Item -Recurse -Force "$S\data" "$D\data"

if (Test-Path "$S\graphics") {
    if (Test-Path "$D\graphics") { Remove-Item -Recurse -Force "$D\graphics" }
    Copy-Item -Recurse -Force "$S\graphics" "$D\graphics"
}

Write-Host "Packaging classes to jar..."
$S_py = $S.ToString().Replace('\', '/')
$D_py = $D.ToString().Replace('\', '/')
python -c "
import zipfile, pathlib
b = pathlib.Path('$S_py/bin')
z = zipfile.ZipFile('$D_py/jars/ecnamor_combat_hud.jar', 'w', zipfile.ZIP_DEFLATED)
for f in b.rglob('*.class'):
    z.write(f, f.relative_to(b))
z.close()
jar_size = pathlib.Path('$D_py/jars/ecnamor_combat_hud.jar').stat().st_size
print(f'Deployed ecnamor_combat_hud-$ver successfully. Jar size: {jar_size} bytes')
"

Write-Host "Build complete!" -ForegroundColor Green
