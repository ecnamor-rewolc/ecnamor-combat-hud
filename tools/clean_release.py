# -*- coding: utf-8 -*-
import os
import shutil
import io
import sys

def clean_damage_marker(filepath):
    print(f"Cleaning {filepath}...")
    with io.open(filepath, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    new_lines = []
    i = 0
    while i < len(lines):
        line = lines[i]
        
        # 1. Skip ripple arc constants block
        if "// ── Ripple arc" in line:
            print("  Skipping ripple arc constants block...")
            while i < len(lines) and "// ── Flux sensitivity" not in lines[i]:
                i += 1
            continue
            
        # 2. Skip inward waves constants block
        if "// ── Inward waves" in line:
            print("  Skipping inward waves constants block...")
            while i < len(lines) and "// ── Overload blackout" not in lines[i]:
                i += 1
            continue
            
        # 3. Skip hideFfIndicators declaration
        if "hideFfIndicators" in line and "private boolean" in line:
            print("  Skipping hideFfIndicators declaration...")
            i += 1
            continue
            
        # 4. Skip hideFfIndicators load in loadSettings
        if 'getBoolean(MOD_ID, "psi_hide_ff_indicators")' in line:
            print("  Skipping hideFfIndicators loading lines...")
            i += 2
            continue
            
        # 5. Skip advance() check for inspect
        if "if (hideFfIndicators)" in line:
            print("  Skipping advance() Inspector.inspect block...")
            i += 3
            continue
            
        # 6. Skip ripplePhase declaration
        if "ripplePhase" in line and "private float" in line:
            print("  Skipping ripplePhase declaration...")
            i += 1
            continue
            
        # 7. Clean up ripplePhase = 0f; initialization
        if "spikeFluxPhase = 0f; ripplePhase = 0f;" in line:
            print("  Cleaning ripplePhase init...")
            line = line.replace("spikeFluxPhase = 0f; ripplePhase = 0f;", "spikeFluxPhase = 0f;")
            
        # 8. Skip ripple update block in advance()
        if "float rippleActivity = MathUtils.clamp" in line:
            print("  Skipping ripple update block...")
            i += 3
            continue
            
        new_lines.append(line)
        i += 1
        
    with io.open(filepath, 'w', encoding='utf-8') as f:
        f.writelines(new_lines)
    print("  Done cleaning damage marker.")

def clean_lunasettings(filepath):
    print(f"Cleaning {filepath}...")
    with io.open(filepath, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    new_lines = []
    for line in lines:
        if line.startswith("psi_hide_ff_indicators"):
            print("  Removed psi_hide_ff_indicators setting.")
            continue
        if line.startswith("fx_header"):
            print("  Removed fx_header setting.")
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
    print("  Done cleaning LunaSettings.csv.")

def main():
    if len(sys.argv) < 2:
        print("Usage: python clean_release.py <path_to_source_release_dir>")
        sys.exit(1)
        
    release_dir = sys.argv[1]
    
    # 1. Delete Inspector.java
    inspector_path = os.path.join(release_dir, 'src', 'ecnamor', 'hud', 'common', 'Inspector.java')
    if os.path.exists(inspector_path):
        os.remove(inspector_path)
        print(f"Removed {inspector_path}")
        
    # 2. Clean DamageMarkerCombatPlugin.java
    dmg_marker_path = os.path.join(release_dir, 'src', 'ecnamor', 'hud', 'psi', 'DamageMarkerCombatPlugin.java')
    if os.path.exists(dmg_marker_path):
        clean_damage_marker(dmg_marker_path)
        
    # 3. Clean LunaSettings.csv
    luna_settings_path = os.path.join(release_dir, 'data', 'config', 'LunaSettings.csv')
    if os.path.exists(luna_settings_path):
        clean_lunasettings(luna_settings_path)

if __name__ == "__main__":
    main()
