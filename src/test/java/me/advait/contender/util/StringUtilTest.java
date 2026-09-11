package me.advait.contender.util;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StringUtilTest {
    private static final UUID ID = UUID.fromString("8b0d3707-3712-4929-a38d-d56c1f836718");
    private static final GsonComponentSerializer JSON = GsonComponentSerializer.gson();

    @Test
    void headsKeepBothIdentityFieldsEvenWithoutTextures() {
        for (var properties : List.of(List.<ProfileProperty>of(),
                List.of(new ProfileProperty("textures", "skin", "signature")))) {
            Component head = StringUtil.resolvedHead(ID, "Miner", properties);
            var profile = JsonParser.parseString(JSON.serialize(head)).getAsJsonObject().getAsJsonObject("player");
            assertTrue(profile.has("id"));
            assertEquals("Miner", profile.get("name").getAsString());
            assertEquals(ShadowColor.none(), head.shadowColor());
            assertEquals(head, JSON.deserialize(JSON.serialize(head)));
        }
    }

    @Test
    void signedTexturesAreCopiedAndUnrelatedPropertiesAreIgnored() {
        var properties = new ArrayList<>(List.of(new ProfileProperty("textures", "skin", "signature"),
                new ProfileProperty("unrelated", "discard")));
        Component head = StringUtil.resolvedHead(ID, "Miner", properties);
        properties.clear();
        String json = JSON.serialize(head);
        assertTrue(json.contains("skin"));
        assertTrue(json.contains("signature"));
        assertFalse(json.contains("discard"));
    }

    @Test
    void unknownNamesHaveAValidStaticFallback() {
        var profile = JsonParser.parseString(JSON.serialize(StringUtil.resolvedHead(ID, "Invalid name", List.of())))
                .getAsJsonObject().getAsJsonObject("player");
        assertTrue(profile.has("id"));
        assertEquals("Player", profile.get("name").getAsString());
    }

    @Test
    void siblingTextDoesNotInheritTheIconsShadowOverride() {
        Component head = StringUtil.resolvedHead(ID, "Miner", List.of());
        Component name = Component.text(" Miner");
        Component line = Component.textOfChildren(head, name);
        assertNull(line.shadowColor());
        assertEquals(ShadowColor.none(), line.children().getFirst().shadowColor());
        assertNull(line.children().get(1).shadowColor());
    }
}
