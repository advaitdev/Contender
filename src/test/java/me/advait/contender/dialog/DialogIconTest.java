package me.advait.contender.dialog;

import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DialogIconTest {
    @Test void spritesDoNotPassTheirNoShadowStyleToTheButtonText() {
        for (DialogIcon icon : DialogIcon.values()) {
            var label = icon.label("Save");
            assertNull(label.shadowColor());
            assertInstanceOf(ObjectComponent.class, label.children().getFirst());
            assertEquals(ShadowColor.none(), label.children().getFirst().shadowColor());
            assertNull(label.children().getLast().shadowColor());
            assertEquals(" Save", PlainTextComponentSerializer.plainText().serialize(label.children().getLast()));
        }
    }
}
