package choco.ratel.smartmoving.client.windrunner;

import choco.ratel.smartmoving.SmartMovingSounds;
import choco.ratel.smartmoving.windrunner.WindrunnerGlide;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/** The legacy wind recording, retuned to the new airspeed envelope. */
final class WindrunnerGlideSound extends AbstractTickableSoundInstance {
    private static WindrunnerGlideSound playing;
    private final LocalPlayer player;
    private WindrunnerGlideSound(LocalPlayer player) {
        super(SmartMovingSounds.WINDRUNNER_WIND, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.player = player;
        looping = true;
        volume = 0;
    }
    static void update(Minecraft client) {
        if (client.player != null && WindrunnerGlide.of(client.player).active()
                && (playing == null || playing.isStopped() || playing.player != client.player)) {
            playing = new WindrunnerGlideSound(client.player);
            client.getSoundManager().play(playing);
        }
    }
    @Override public boolean canStartSilent() { return true; }
    @Override public void tick() {
        var flight = WindrunnerGlide.of(player);
        if (player.isRemoved() || Minecraft.getInstance().player != player || !flight.active()) { stop(); return; }
        x = player.getX(); y = player.getY(); z = player.getZ();
        float target = (float) Mth.clamp((flight.airspeed() - 0.15) / 1.25, 0, 1) * 0.6F;
        volume = Mth.lerp(0.12F, volume, target);
        pitch = 0.85F + target * 0.35F;
    }
}
