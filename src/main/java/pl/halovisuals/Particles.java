package pl.halovisuals;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;

import java.util.Random;

/** Shared particle styles used by Hit / Jump / Ambient particles and effects. */
public final class Particles {
    public static final int ANGEL_DUST = 0, SPARKS = 1, PETALS = 2, BLOOD = 3;
    private static final Random R = new Random();
    private static final ParticleEffect GOLD = new DustParticleEffect(0xFFD84D, 1.1f);
    private static final ParticleEffect GOLD_SMALL = new DustParticleEffect(0xFF9A2E, 0.8f);
    private static final ParticleEffect RED = new DustParticleEffect(0xB0101C, 1.2f);

    private Particles() {}

    private static double rr() { return R.nextDouble() - 0.5; }

    /**
     * @param spreadXZ horizontal random offset range, spreadY vertical random offset range
     * @param speed    base velocity of the particles
     */
    public static void spawn(int style, double x, double y, double z, double spreadXZ, double spreadY, double speed, int count) {
        ClientWorld w = MinecraftClient.getInstance().world;
        if (w == null) return;
        for (int i = 0; i < count; i++) {
            double px = x + rr() * spreadXZ, py = y + rr() * spreadY, pz = z + rr() * spreadXZ;
            double vx = rr() * speed, vy = rr() * speed, vz = rr() * speed;
            switch (style) {
                case SPARKS -> {
                    w.addParticle(ParticleTypes.ELECTRIC_SPARK, px, py, pz, vx * 3, vy * 3 + 0.05, vz * 3);
                    if (R.nextInt(3) == 0) w.addParticle(ParticleTypes.CRIT, px, py, pz, vx * 2.5, vy * 2.5, vz * 2.5);
                }
                case PETALS -> {
                    w.addParticle(R.nextBoolean() ? GOLD : GOLD_SMALL, px, py, pz, vx * 0.6, -0.03 - R.nextDouble() * 0.05, vz * 0.6);
                    if (R.nextInt(4) == 0) w.addParticle(ParticleTypes.FALLING_HONEY, px, py, pz, 0, 0, 0);
                }
                case BLOOD -> {
                    w.addParticle(RED, px, py, pz, vx, vy + 0.03, vz);
                    if (R.nextBoolean()) w.addParticle(ParticleTypes.CRIT, px, py, pz, vx * 2, vy * 2, vz * 2);
                }
                default -> { // Angel Dust
                    w.addParticle(ParticleTypes.END_ROD, px, py, pz, vx * 0.4, 0.02 + R.nextDouble() * 0.05, vz * 0.4);
                    if (R.nextInt(5) == 0) w.addParticle(GOLD_SMALL, px, py, pz, vx * 0.3, 0.02, vz * 0.3);
                }
            }
        }
    }
}
