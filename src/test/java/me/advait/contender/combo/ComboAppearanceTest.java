package me.advait.contender.combo;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.profile.PlayerTextures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComboAppearanceTest {
    @Test void usesBuiltInWideSteveWithoutLookingUpAPlayerAccount() {
        Mannequin bot = mock(Mannequin.class);
        var profile = mock(ResolvableProfile.class);
        var builder = mock(ResolvableProfile.Builder.class, RETURNS_SELF);
        var patch = mock(ResolvableProfile.SkinPatchBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(profile);
        doAnswer(call -> {
            Consumer<ResolvableProfile.SkinPatchBuilder> configure = call.getArgument(0);
            configure.accept(patch);
            return builder;
        }).when(builder).skinPatch(org.mockito.ArgumentMatchers.<Consumer<ResolvableProfile.SkinPatchBuilder>>any());

        try (var profiles = mockStatic(ResolvableProfile.class)) {
            profiles.when(ResolvableProfile::resolvableProfile).thenReturn(builder);
            ComboAppearance.apply(bot);
        }

        verify(patch).body(Key.key("minecraft", "entity/player/wide/steve"));
        verify(patch).model(PlayerTextures.SkinModel.CLASSIC);
        verify(builder, never()).name(any());
        verify(builder, never()).uuid(any());
        verify(bot).setProfile(profile);
        verify(bot).setSilent(true);
        verify(profile, never()).resolve();
    }

    @Test void acceptedHitPlaysOnePlayerHurtSoundAtTheBotForNearbyViewers() {
        Mannequin bot = mock(Mannequin.class);
        World world = mock(World.class);
        Location location = new Location(world, 4, 65, 9);
        when(bot.getLocation()).thenReturn(location);
        when(bot.getMaximumNoDamageTicks()).thenReturn(20);
        when(bot.getNoDamageTicks()).thenReturn(10);

        ComboAppearance.hurt(bot);

        ArgumentCaptor<Float> pitch = ArgumentCaptor.forClass(Float.class);
        verify(world).playSound(eq(location), eq("minecraft:entity.player.hurt"), eq(SoundCategory.PLAYERS), eq(1.0f), pitch.capture());
        assertTrue(pitch.getValue() >= 0.8f && pitch.getValue() <= 1.2f);
        verifyNoMoreInteractions(world);
    }

    @Test void strongerHitDuringTheSameHurtWindowDoesNotRepeatTheSound() {
        Mannequin bot = mock(Mannequin.class);
        when(bot.getMaximumNoDamageTicks()).thenReturn(20);
        when(bot.getNoDamageTicks()).thenReturn(11);

        ComboAppearance.hurt(bot);

        verify(bot, never()).getLocation();
    }
}
