# -*- coding: utf-8 -*-
import io

info_path = r'c:\my\starsector-modding\INFO.md'

with io.open(info_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Let's locate "## 28. v2.0.7"
pos = content.find("## 28. v2.0.7")
if pos != -1:
    # Truncate anything starting from "## 28. v2.0.7" and replace it
    content = content[:pos]

new_section = u"""## 28. v2.0.7 — Разделение настроек цвета, хаотичные полосы и автоматизация релиза (29.05.2026)

Исправлена привязка цвета указателя к ХП, доработаны полосы перегрузки, добавлен новый скрипт генерации иконок и автоматизирована сборка чистого релиза.

### Описание изменений:
1. **Отмена синхронизации цвета**:
   - В [BoundaryCombatPlugin.java](file:///c:/my/starsector-modding/Source/src/ecnamor/hud/boundary/BoundaryCombatPlugin.java) отменена принудительная синхронизация цвета внеэкранного указателя игрока с кругом ХП.
   - Цвет указателя снова считывается из настроек `boundary_marker_color_preset` / `boundary_marker_color` в LunaLib, устраняя противоречие с файлом конфигурации.
2. **Хаотичные полосы перегрузки**:
   - В [DamageMarkerCombatPlugin.java](file:///c:/my/starsector-modding/Source/src/ecnamor/hud/psi/DamageMarkerCombatPlugin.java) анимация фиолетовых полос перегрузки переработана.
   - Теперь каждая полоса движется с уникальной скоростью, имеет собственный начальный сдвиг и подвержена индивидуальной низкочастотной осцилляции. Это убирает ритмичное появление полос друг за другом и делает эффект естественным и хаотичным.
3. **Очистка неиспользуемого кода**:
   - Из [DamageMarkerCombatPlugin.java](file:///c:/my/starsector-modding/Source/src/ecnamor/hud/psi/DamageMarkerCombatPlugin.java) полностью удалены неиспользуемые переменные и константы ripple-эффекта и сжимающихся волн (`ripplePhase`, `waveTimes`, `waveCount`, `MAX_WAVES`, `WAVE_SPEED` и т.д.).
4. **Скрипт генерации иконок**:
   - Создан инструмент [tools/generate_icon.py](file:///c:/my/starsector-modding/tools/generate_icon.py) для генерации новой иконки мода. Иконка имеет сплошной черный фон без полей, оранжевую диагональную штриховку вне рамок, красную прямоугольную рамку с отступом (120px) и круг ХП ванильно-зеленого цвета с уменьшенным центральным шевроном и мягким неоновым свечением.
5. **Автоматизация сборки релиза**:
   - Созданы файлы [build-release.ps1](file:///c:/my/starsector-modding/build-release.ps1) and [tools/build_release.py](file:///c:/my/starsector-modding/tools/build_release.py) для автоматической сборки чистого релиза.
   - При сборке релиза автоматически вырезается класс `Inspector.java` (рефлексивное скрытие ванильных маркеров), удаляется вызов `Inspector.inspect()`, вырезаются отладочные настройки из `LunaSettings.csv` (`psi_hide_ff_indicators` и `fx_header`), а также удаляются все markdown-файлы, логи и исходники. Готовый чистый архив сохраняется как `ecnamor_combat_hud-2.0.7.zip`.
6. **Версионирование**:
   - Версия проекта поднята до `2.0.7` в `mod_info.json`, `LunaSettings.csv` и `INFO.md`.
"""

content = content.rstrip() + "\n\n" + new_section

with io.open(info_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("INFO.md updated successfully with detailed Section 28.")
