package me.advait.contender.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Map setup and kit availability are independent prerequisites. */
record SetupRequirements(int maps, int kits) {
    boolean ready() { return maps > 0 && kits > 0; }

    Component body() {
        Component status = DialogText.lines(
                DialogText.detail("Maps with both spawns", maps > 0 ? maps + " set up" : "None set up",
                        maps > 0 ? DialogPalette.SUCCESS : DialogPalette.MUTED),
                DialogText.detail("Kits", kits > 0 ? kits + " saved" : "None saved",
                        kits > 0 ? DialogPalette.SUCCESS : DialogPalette.MUTED));
        if (maps == 0) status = DialogText.paragraphs(status,
                DialogText.muted("Open Maps to save a selection and set both team spawns."));
        if (kits == 0) status = DialogText.paragraphs(status,
                DialogText.lines(DialogText.heading("Create a kit"),
                        DialogText.muted("Edit Kits → Create New Kit\nEnter its name, add the items, then choose Save Kit.")));
        return status;
    }
}
