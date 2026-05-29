# -*- coding: utf-8 -*-
import io

info_path = r'c:\my\starsector-modding\INFO.md'

new_section = u"""

## 28. v2.0.7 — Разделение настроек цвета и хаотичные полосы перегрузки (29.05.2026)

Исправлена привязка цвета указателя к ХП и переработана анимация полос перегрузки.

### Описание изменений:
1. **Отмена синхронизации цвета**:
   - В [BoundaryCombatPlugin.java](file:///c:/my/starsector-modding/Source/src/ecnamor/hud/boundary/BoundaryCombatPlugin.java) отменена принудительная синхронизация цвета внеэкранного указателя игрока с кругом ХП.
   - Цвет указателя снова считывается из настроек `boundary_marker_color_preset` / `boundary_marker_color` в LunaLib, устраняя противоречие с файлом конфигурации.
2. **Хаотичные полосы перегрузки**:
   - В [DamageMarkerCombatPlugin.java](file:///c:/my/starsector-modding/Source/src/ecnamor/hud/psi/DamageMarkerCombatPlugin.java) анимация фиолетовых полос перегрузки переработана.
   - Теперь каждая полоса движется с уникальной скоростью, имеет собственный начальный сдвиг и подвержена индивидуальной низкочастотной осцилляции. Это полностью убирает ритмичное, синхронное появление полос друг за другом и делает эффект естественным и хаотичным.
3. **Версионирование**:
   - Версия проекта поднята до `2.0.7` в `mod_info.json`, `LunaSettings.csv` и `INFO.md`.
"""

with io.open(info_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Let's ensure the file doesn't end with extra blank lines, then append
content = content.rstrip() + new_section

with io.open(info_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("INFO.md updated successfully with Section 28.")
