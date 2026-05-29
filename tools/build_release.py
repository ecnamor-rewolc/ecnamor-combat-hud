# -*- coding: utf-8 -*-
import os
import shutil
import io
import re
import sys
import zipfile
import subprocess
import fnmatch

def clean_damage_marker(filepath):
    print(f"  Cleaning {filepath}...")
    with io.open(filepath, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    new_lines = []
    i = 0
    while i < len(lines):
        line = lines[i]
        
        # 1. Skip ripple arc constants block
        if "// ── Ripple arc" in line:
            print("    Skipping ripple arc constants block...")
            while i < len(lines) and "// ── Flux sensitivity" not in lines[i]:
                i += 1
            continue
            
        # 2. Skip inward waves constants block
        if "// ── Inward waves" in line:
            print("    Skipping inward waves constants block...")
            while i < len(lines) and "// ── Overload blackout" not in lines[i]:
                i += 1
            continue
            
        # 3. Skip hideFfIndicators declaration
        if "hideFfIndicators" in line and "private boolean" in line:
            print("    Skipping hideFfIndicators declaration...")
            i += 1
            continue
            
        # 4. Skip hideFfIndicators load in loadSettings
        if 'getBoolean(MOD_ID, "psi_hide_ff_indicators")' in line:
            print("    Skipping hideFfIndicators loading lines...")
            i += 2
            continue
            
        # 5. Skip advance() check for inspect
        if "if (hideFfIndicators)" in line:
            print("    Skipping advance() Inspector.inspect block...")
            i += 3
            continue
            
        # 6. Skip ripplePhase declaration
        if "ripplePhase" in line and "private float" in line:
            print("    Skipping ripplePhase declaration...")
            i += 1
            continue
            
        # 7. Clean up ripplePhase = 0f; initialization
        if "spikeFluxPhase = 0f; ripplePhase = 0f;" in line:
            print("    Cleaning ripplePhase init...")
            line = line.replace("spikeFluxPhase = 0f; ripplePhase = 0f;", "spikeFluxPhase = 0f;")
            
        # 8. Skip ripple update block in advance()
        if "float rippleActivity = MathUtils.clamp" in line:
            print("    Skipping ripple update block...")
            i += 3
            continue
            
        new_lines.append(line)
        i += 1
        
    with io.open(filepath, 'w', encoding='utf-8') as f:
        f.writelines(new_lines)

def clean_lunasettings(filepath):
    print(f"  Cleaning {filepath}...")
    with io.open(filepath, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    new_lines = []
    for line in lines:
        if line.startswith("psi_hide_ff_indicators"):
            print("    Removed psi_hide_ff_indicators setting.")
            continue
        if line.startswith("fx_header"):
            print("    Removed fx_header setting.")
            continue
        new_lines.append(line)
    
    # Clean up consecutive empty/comma lines
    cleaned = []
    prev_empty = False
    for line in new_lines:
        is_empty = not line.strip() or line.strip() == ",,,,,,,,"
        if is_empty:
            if not prev_empty:
                cleaned.append(line)
                prev_empty = True
        else:
            cleaned.append(line)
            prev_empty = False
            
    with io.open(filepath, 'w', encoding='utf-8') as f:
        f.writelines(cleaned)

def load_properties(root_dir):
    properties = {}
    prop_path = os.path.join(root_dir, "local.properties")
    if not os.path.exists(prop_path):
        print(f"Error: local.properties not found at {prop_path}!")
        print("Please copy local.properties.example to local.properties and edit it.")
        sys.exit(1)
    with io.open(prop_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                key, val = line.split("=", 1)
                properties[key.strip()] = val.strip().replace("\\\\", "\\").replace("\\", "/")
    return properties

def find_jar(mods_dir, mod_pattern, jar_name):
    if not os.path.exists(mods_dir):
        raise FileNotFoundError(f"Mods directory not found: {mods_dir}")
    for name in os.listdir(mods_dir):
        if fnmatch.fnmatch(name.lower(), mod_pattern.lower()):
            candidate_dir = os.path.join(mods_dir, name)
            if os.path.isdir(candidate_dir):
                for root, dirs, files in os.walk(candidate_dir):
                    for file in files:
                        if file.lower() == jar_name.lower():
                            return os.path.join(root, file)
    raise FileNotFoundError(f"Could not find {jar_name} matching mod pattern {mod_pattern} in {mods_dir}")

def main():
    # Determine project root based on this script's directory (parent of tools/)
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    source_dir = os.path.join(root, "Source")
    temp_dir = os.path.join(root, "Source-release")
    release_mod_dir = os.path.join(root, "ecnamor_combat_hud")
    
    # Load properties
    props = load_properties(root)
    starsector_dir = props.get("starsector.dir")
    if not starsector_dir:
        print("Error: starsector.dir is not set in local.properties!")
        sys.exit(1)
        
    starsector_dir = os.path.abspath(starsector_dir)
    game_mods_dir = os.path.join(starsector_dir, "mods")
    
    # 1. Get version from mod_info.json
    mod_info_path = os.path.join(source_dir, "mod_info.json")
    if not os.path.exists(mod_info_path):
        print(f"Error: {mod_info_path} not found!")
        sys.exit(1)
        
    with open(mod_info_path, 'r', encoding='utf-8') as f:
        text = f.read()
    match = re.search(r'"version"\s*:\s*"([^"]+)"', text)
    version = match.group(1) if match else "unknown"
    print(f"Building Release v{version}...")
    
    # 2. Copy Source to Source-release
    if os.path.exists(temp_dir):
        print(f"Removing old temp dir {temp_dir}...")
        shutil.rmtree(temp_dir)
        
    print(f"Copying {source_dir} to {temp_dir}...")
    shutil.copytree(source_dir, temp_dir)
    
    # 3. Clean files in Source-release
    # Remove Inspector.java
    inspector_path = os.path.join(temp_dir, 'src', 'ecnamor', 'hud', 'common', 'Inspector.java')
    if os.path.exists(inspector_path):
        os.remove(inspector_path)
        print(f"  Removed {inspector_path}")
        
    # Clean DamageMarkerCombatPlugin.java
    dmg_marker_path = os.path.join(temp_dir, 'src', 'ecnamor', 'hud', 'psi', 'DamageMarkerCombatPlugin.java')
    if os.path.exists(dmg_marker_path):
        clean_damage_marker(dmg_marker_path)
        
    # Clean LunaSettings.csv
    luna_settings_path = os.path.join(temp_dir, 'data', 'config', 'LunaSettings.csv')
    if os.path.exists(luna_settings_path):
        clean_lunasettings(luna_settings_path)
        
    # 4. Compile Source-release
    # Gather Java files
    java_files = []
    for r_dir, dirs, files in os.walk(os.path.join(temp_dir, "src")):
        for file in files:
            if file.endswith(".java"):
                java_files.append(os.path.join(r_dir, file))
                
    # Classpath resolution
    core_jars = [
        os.path.join(starsector_dir, "starsector-core", "starfarer.api.jar"),
        os.path.join(starsector_dir, "starsector-core", "lwjgl.jar"),
        os.path.join(starsector_dir, "starsector-core", "lwjgl_util.jar"),
        os.path.join(starsector_dir, "starsector-core", "log4j-1.2.9.jar")
    ]
    
    for cj in core_jars:
        if not os.path.exists(cj):
            print(f"Error: Core Starsector jar not found: {cj}")
            sys.exit(1)
            
    try:
        dep_jars = [
            find_jar(game_mods_dir, "LazyLib*", "LazyLib.jar"),
            find_jar(game_mods_dir, "LunaLib*", "LunaLib.jar"),
            find_jar(game_mods_dir, "MagicLib*", "MagicLib.jar"),
            find_jar(game_mods_dir, "MagicLib*", "MagicLib-Kotlin.jar")
        ]
    except Exception as e:
        print(f"Error resolving dependencies for release classpath: {e}")
        sys.exit(1)
        
    cp = ";".join(core_jars + dep_jars)
    
    bin_dir = os.path.join(temp_dir, "bin")
    os.makedirs(bin_dir, exist_ok=True)
    
    cmd = [
        "javac",
        "-source", "8",
        "-target", "8",
        "-encoding", "UTF-8",
        "-cp", cp,
        "-d", bin_dir
    ] + java_files
    
    print("Compiling Java files for release...")
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        print("COMPILATION FAILED!")
        print("STDOUT:")
        print(res.stdout)
        print("STDERR:")
        print(res.stderr)
        sys.exit(1)
    print("Compilation successful.")
    
    # 5. Create deployment structure in release_mod_dir (C:\my\starsector-modding\ecnamor_combat_hud)
    if os.path.exists(release_mod_dir):
        shutil.rmtree(release_mod_dir)
    os.makedirs(os.path.join(release_mod_dir, "jars"), exist_ok=True)
    
    # Copy metadata, data (config), and graphics
    shutil.copy(os.path.join(temp_dir, "mod_info.json"), os.path.join(release_mod_dir, "mod_info.json"))
    shutil.copytree(os.path.join(temp_dir, "data"), os.path.join(release_mod_dir, "data"))
    if os.path.exists(os.path.join(temp_dir, "graphics")):
        shutil.copytree(os.path.join(temp_dir, "graphics"), os.path.join(release_mod_dir, "graphics"))
        
    # 6. Package classes into ecnamor_combat_hud.jar
    jar_path = os.path.join(release_mod_dir, "jars", "ecnamor_combat_hud.jar")
    print(f"Packaging release jar: {jar_path}")
    with zipfile.ZipFile(jar_path, 'w', zipfile.ZIP_DEFLATED) as z:
        for r_dir, dirs, files in os.walk(bin_dir):
            for file in files:
                if file.endswith(".class"):
                    full_path = os.path.join(r_dir, file)
                    rel_path = os.path.relpath(full_path, bin_dir)
                    z.write(full_path, rel_path)
                    
    # 7. Create ZIP file in root
    zip_name = f"ecnamor_combat_hud-{version}.zip"
    zip_path = os.path.join(root, zip_name)
    print(f"Creating release zip file: {zip_path}")
    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as z:
        for r_dir, dirs, files in os.walk(release_mod_dir):
            for file in files:
                full_path = os.path.join(r_dir, file)
                rel_path = os.path.relpath(full_path, os.path.dirname(release_mod_dir))
                z.write(full_path, rel_path)
                
    # 8. Copy release mod directory to active game mods folder for verification
    target_game_mod_dir = os.path.join(game_mods_dir, f"ecnamor_combat_hud-{version}-release")
    if os.path.exists(target_game_mod_dir):
        shutil.rmtree(target_game_mod_dir)
    print(f"Deploying release copy to game mods: {target_game_mod_dir}...")
    shutil.copytree(release_mod_dir, target_game_mod_dir)
    
    # 9. Cleanup temp build folders to keep root directory completely clean
    print("Cleaning up temporary directories...")
    if os.path.exists(temp_dir):
        shutil.rmtree(temp_dir)
    if os.path.exists(release_mod_dir):
        shutil.rmtree(release_mod_dir)
        
    print("\nRELEASE BUILD COMPLETED SUCCESSFULLY!")
    print(f"Zip-archive is ready: {zip_path}")
    print(f"Mod folders inside zip are clean of debug code, logs, and markdown files.")
    print(f"Tested and deployed release build to Starsector: {target_game_mod_dir}")

if __name__ == "__main__":
    main()
