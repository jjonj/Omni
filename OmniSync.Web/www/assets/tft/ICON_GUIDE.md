# Icon Harvesting Guide for TFT

To populate the icons for Set 16 units and emblems:

1.  **Sources:**
    -   Mobalytics: `https://mobalytics.gg/tft/new-set-release`
    -   Tactic.tools
    -   Community Dragon (Riot API)

2.  **Naming Convention:**
    -   **Champions:** `OmniSync.Web/www/assets/tft/set16/champions/{UnitName}.png`
        -   Remove spaces and apostrophes (e.g., "Jarvan IV" -> "JarvanIV.png", "Kai'Sa" -> "KaiSa.png").
    -   **Emblems/Traits:** `OmniSync.Web/www/assets/tft/set16/traits/{TraitName}.png`
        -   (e.g., "Demacia.png")
    -   **Items:** `OmniSync.Web/www/assets/tft/set16/items/{ItemName}.png`

3.  **Manual Action Required:**
    -   Download images for all units listed in `set16.json`.
    -   Download images for all emblems.
    -   Place them in the respective folders.

4.  **Placeholder Generation (Automated):**
    -   The system currently uses a default SVG placeholder if an image fails to load.
