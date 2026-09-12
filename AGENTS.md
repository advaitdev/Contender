# In-game writing

Use the [Humanizer skill](https://github.com/blader/humanizer) when writing or editing player-facing text, including dialogs, inventory menus, titles, action bars, and chat messages. The local skill is installed at `/Users/advait/.codex/skills/humanizer/SKILL.md`.

Keep labels and instructions short and natural. Use the skill in embedded mode and put only the final wording in the game.

Use Minecraft's default font and built-in sprites. Text and icons must work with vanilla client assets.

# Dialog layout

Close buttons have no icon. Put page and column navigation on a dedicated row, with Previous on the left and Next on the right. Use inactive `---` buttons to fill missing navigation slots or finish the preceding row. Use `Dialogs.navigationRow` for this layout.

# Dialog appearance

Never use bold text in dialogs. Use `DialogPalette`: gold for titles and primary actions, warm white for ordinary controls and values, muted text for hints and Back, and coral only for destructive actions. Color actions by purpose, not by their sprite.

Tournament category menus use one centered column, with the primary action first. Put setup tools in submenus. Setup forms use 300-pixel inputs and a final paired Back / Next row with 150-pixel buttons. Use title case for button labels. Keep the actual title preview on the Review screen before creation.
