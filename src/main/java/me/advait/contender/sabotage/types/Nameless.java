package me.advait.contender.sabotage.types;

import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.sabotage.Sabotage;
import me.advait.contender.sabotage.SabotageContext;

/** Nametags disappear for everyone it hits, so nobody knows who they are fighting. */
public final class Nameless implements Sabotage {
    @Override public String id() { return "nameless"; }
    @Override public String name() { return "Nameless"; }
    @Override public String description() { return "Every nametag disappears."; }
    @Override public DialogIcon icon() { return DialogIcon.NAME; }

    @Override public void start(SabotageContext context) { context.plugin().getNameTagManager().hide(context, context::affects); }
    @Override public void stop(SabotageContext context) { context.plugin().getNameTagManager().show(context); }
}
