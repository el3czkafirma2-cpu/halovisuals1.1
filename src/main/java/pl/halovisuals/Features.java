package pl.halovisuals;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;
import pl.halovisuals.gui.ClickGui;
import pl.halovisuals.gui.Draw;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Event wiring + all renderers/effects. */
public final class Features {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final Random rnd = new Random();

    // combat / target state
    private static LivingEntity target;
    private static long lastHit, hitMarkerAt, comboAt, activeUntil;
    private static int combo;
    private static boolean killHandled;
    private static float shownHp;
    // notifications (Dynamic Island)
    private static String notifText = "";
    private static long notifAt = -10000;
    // misc state
    private static boolean wasOnGround = true, keyWasDown, fbApplied;
    private record Circle(double x, double y, double z, long t) {}
    private static final List<Circle> circles = new ArrayList<>();
    private static Vec3d cam = Vec3d.ZERO;

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    private Features() {}

    public static void notify(String s) { notifText = s; notifAt = System.currentTimeMillis(); }

    private static boolean on(String n) { Module m = ModuleManager.get(n); return m != null && m.enabled; }
    private static Module mod(String n) { return ModuleManager.get(n); }

    public static void init() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient()) onAttack(entity);
            return ActionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(Features::tick);
        HudRenderCallback.EVENT.register(Features::hud);
        WorldRenderEvents.AFTER_ENTITIES.register(Features::world);
    }

    // ------------------------------------------------------------------ helpers
    private static void setTarget(LivingEntity le) {
        if (le != target) { target = le; killHandled = false; shownHp = le.getHealth(); }
    }

    /** Plays the sound layered: every 1.0 of loudness adds another full-volume layer (louder than the vanilla cap of 1.0). */
    private static void play(SoundEvent s, float pitch, float loudness) {
        float v = loudness;
        for (int i = 0; i < 4 && v > 0.01f; i++, v -= 1f)
            mc.getSoundManager().play(PositionedSoundInstance.master(s, pitch, Math.min(1f, v)));
    }

    private static void entityBurst(Entity e, int style, int count, double speed) {
        Particles.spawn(style, e.getX(), e.getY() + e.getHeight() / 2, e.getZ(), e.getWidth() + 0.3, e.getHeight(), speed, count);
    }

    // ------------------------------------------------------------------ combat events
    private static void onAttack(Entity e) {
        if (!(e instanceof LivingEntity le) || mc.world == null) return;
        long now = System.currentTimeMillis();
        setTarget(le);
        lastHit = now; hitMarkerAt = now; activeUntil = Math.max(activeUntil, now + 4000);
        if (now - comboAt > 3000) combo = 0;
        combo++; comboAt = now;

        Module hs = mod("Hit Sounds");
        if (hs.enabled) {
            float vol = (float) hs.num("Loudness");
            switch (hs.mode("Style")) {
                case 1 -> play(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, vol);
                case 2 -> play(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.3f, vol);
                case 3 -> play(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), 0.8f, vol);
                default -> {}
            }
        }
        if (on("Hit Particles")) {
            Module hp = mod("Hit Particles");
            entityBurst(le, hp.mode("Style"), (int) hp.num("Amount"), 0.25);
        }
        if (on("Hit Bubbles")) {
            for (int i = 0; i < (int) mod("Hit Bubbles").num("Amount"); i++)
                mc.world.addParticle(ParticleTypes.BUBBLE_POP, le.getX() + (rnd.nextDouble() - .5) * le.getWidth() * 1.5,
                        le.getBodyY(rnd.nextDouble()), le.getZ() + (rnd.nextDouble() - .5) * le.getWidth() * 1.5,
                        (rnd.nextDouble() - .5) * 0.15, rnd.nextDouble() * 0.15, (rnd.nextDouble() - .5) * 0.15);
        }
    }

    // ------------------------------------------------------------------ tick
    private static void tick(MinecraftClient c) {
        if (c.player == null || c.world == null) return;
        long now = System.currentTimeMillis();

        // configurable ClickGUI key (default I)
        int key = (int) mod("Click GUI").num("Key");
        boolean down = InputUtil.isKeyPressed(c.getWindow().getHandle(), key);
        if (down && !keyWasDown && c.currentScreen == null) c.setScreen(new ClickGui());
        keyWasDown = down;

        // crosshair target -> Target HUD / ESP activate whenever you look at any mob or player
        if (c.targetedEntity instanceof LivingEntity le && le.isAlive()) {
            setTarget(le);
            activeUntil = Math.max(activeUntil, now + 1200);
        }

        // kill detection
        if (target != null && !killHandled && !target.isAlive()) {
            killHandled = true;
            if (now - lastHit < 5000) {
                if (on("Kill Effect")) {
                    entityBurst(target, Particles.ANGEL_DUST, 30, 0.3);
                    entityBurst(target, Particles.SPARKS, 25, 0.3);
                }
                if (on("Kill Sound")) play(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.3f, (float) mod("Kill Sound").num("Loudness"));
            }
        }

        // fullbright (client-side night vision, no particles/icon)
        if (on("Fullbright")) {
            StatusEffectInstance cur = c.player.getStatusEffect(StatusEffects.NIGHT_VISION);
            if (cur == null || cur.getDuration() < 300) {
                c.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 1000, 0, false, false, false));
                fbApplied = true;
            }
        } else if (fbApplied) {
            c.player.removeStatusEffect(StatusEffects.NIGHT_VISION);
            fbApplied = false;
        }

        // jumping
        boolean og = c.player.isOnGround();
        if (wasOnGround && !og && c.player.getVelocity().y > 0.2) {
            if (on("Jump Circles")) circles.add(new Circle(c.player.getX(), c.player.getY(), c.player.getZ(), now));
            if (on("Jump Particles")) {
                Module jp = mod("Jump Particles");
                Particles.spawn(jp.mode("Style"), c.player.getX(), c.player.getY() + 0.1, c.player.getZ(), 0.8, 0.2, 0.18, (int) jp.num("Amount"));
            }
        }
        wasOnGround = og;
        circles.removeIf(ci -> now - ci.t() > 900);

        // ambient particles (every tick, up to 20 per tick)
        if (on("Ambient Particles")) {
            Module ap = mod("Ambient Particles");
            int n = (int) ap.num("Amount"), st = ap.mode("Style");
            for (int i = 0; i < n; i++)
                Particles.spawn(st, c.player.getX() + (rnd.nextDouble() * 2 - 1) * 12, c.player.getY() + rnd.nextDouble() * 7 - 1,
                        c.player.getZ() + (rnd.nextDouble() * 2 - 1) * 12, 0.2, 0.2, 0.05, 1);
        }

        // trails
        if (on("Trails") && c.player.getVelocity().horizontalLengthSquared() > 0.001) {
            Particles.spawn(mod("Trails").mode("Style") == 1 ? Particles.SPARKS : Particles.ANGEL_DUST,
                    c.player.getX(), c.player.getY() + 0.1, c.player.getZ(), 0.2, 0.1, 0.02, 2);
        }
    }

    // ------------------------------------------------------------------ HUD
    private static void hud(DrawContext g, RenderTickCounter tc) {
        if (mc.player == null || mc.options.hudHidden) return;
        long now = System.currentTimeMillis();
        int w = g.getScaledWindowWidth(), h = g.getScaledWindowHeight();
        int cx = w / 2, cy = h / 2;

        // Watermark
        if (on("Watermark")) {
            PlayerListEntry pe = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            String name = "Halo Visuals", rest = "  /  " + mc.getCurrentFps() + " FPS  /  " + (pe == null ? 0 : pe.getLatency()) + " ms";
            int tw = Draw.width(name) + Draw.width(rest);
            Draw.panel(g, 8, 8, tw + 20, 20, 10);
            Draw.text(g, name, 18, 14, Draw.YELLOW);
            Draw.text(g, rest, 18 + Draw.width(name), 14, Draw.WHITE);
        }

        // Dynamic Island
        if (on("Dynamic Island")) {
            long el = now - notifAt;
            if (el < 2400) {
                float f = el < 220 ? el / 220f : (el > 2100 ? (2400 - el) / 300f : 1f);
                f = Math.max(0f, Math.min(1f, f));
                int tw = Draw.width(notifText) + 28, pw = (int) (tw * f), ph = (int) (8 + 14 * f);
                Draw.panel(g, cx - pw / 2, 8, pw, ph, ph / 2);
                if (f > 0.9f) {
                    Draw.rrect(g, cx - tw / 2 + 8, 8 + ph / 2 - 2, 4, 4, 2, Draw.ORANGE);
                    Draw.text(g, notifText, cx - tw / 2 + 16, 8 + ph / 2 - 4, Draw.WHITE);
                }
            }
        }

        // Target HUD (hit OR crosshair) + equipment
        if (on("Target HUD") && target != null && now < activeUntil && (target.isAlive() || now - lastHit < 1500)) {
            boolean eq = mod("Target HUD").on("Equipment");
            float hp = Math.max(0, target.getHealth()), max = Math.max(1, target.getMaxHealth());
            shownHp += (hp - shownHp) * 0.15f;
            int pw = 150, ph = eq ? 70 : 44, x = cx + 40, y = cy + 30;
            Draw.panel(g, x, y, pw, ph, 10);
            Draw.rrect(g, x + 6, y + 6, 32, 32, 8, Draw.a(Draw.WHITE, 40));
            if (target instanceof AbstractClientPlayerEntity p) {
                PlayerSkinDrawer.draw(g, p.getSkinTextures(), x + 8, y + 8, 28);
            } else {
                String nm0 = target.getName().getString();
                Draw.ctext(g, nm0.isEmpty() ? "?" : nm0.substring(0, 1).toUpperCase(), x + 22, y + 18, Draw.YELLOW);
            }
            String nm = target.getName().getString();
            Draw.text(g, nm.length() > 14 ? nm.substring(0, 14) : nm, x + 44, y + 9, Draw.WHITE);
            String hs = String.format("%.1f", hp);
            Draw.text(g, hs, x + pw - 8 - Draw.width(hs), y + 9, Draw.ORANGE);
            Draw.bar(g, x + 44, y + 26, pw - 54, 7, shownHp / max);

            if (eq) {
                for (int i = 0; i < SLOTS.length; i++) {
                    ItemStack st = target.getEquippedStack(SLOTS[i]);
                    int ix = x + 9 + i * 22, iy = y + 47;
                    Draw.rrect(g, ix - 1, iy - 1, 18, 18, 4, Draw.a(Draw.WHITE, 30));
                    if (!st.isEmpty()) {
                        g.drawItem(st, ix, iy);
                        g.drawStackOverlay(mc.textRenderer, st, ix, iy); // durability bar + item count
                    }
                }
            }
        }

        // Potions
        if (on("Potions")) {
            int y = 40;
            for (StatusEffectInstance e : mc.player.getStatusEffects()) {
                if (fbApplied && e.getEffectType().equals(StatusEffects.NIGHT_VISION)) continue;
                String nm = e.getEffectType().value().getName().getString() + (e.getAmplifier() > 0 ? " " + (e.getAmplifier() + 1) : "");
                int sec = e.getDuration() / 20;
                String tm = e.isInfinite() ? "inf" : String.format("%d:%02d", sec / 60, sec % 60);
                int pw = Draw.width(nm) + Draw.width(tm) + 30;
                int x = w - 8 - pw;
                Draw.panel(g, x, y, pw, 18, 9);
                Draw.rrect(g, x + 6, y + 7, 4, 4, 2, e.getEffectType().value().isBeneficial() ? Draw.YELLOW : Draw.ORANGE);
                Draw.text(g, nm, x + 14, y + 5, Draw.WHITE);
                Draw.text(g, tm, x + pw - 8 - Draw.width(tm), y + 5, Draw.GRAY);
                y += 24;
            }
        }

        // Cooldowns
        float cd = mc.player.getAttackCooldownProgress(0f);
        if (on("Cooldowns") && cd < 1f) {
            Draw.panel(g, cx - 32, h - 66, 64, 10, 5);
            Draw.bar(g, cx - 28, h - 63, 56, 4, cd);
        }

        // Combo
        if (on("Combo Counter") && combo > 0) {
            if (now - comboAt > 3000) combo = 0;
            else Draw.ctext(g, "x" + combo + " COMBO", cx, cy + 22, Draw.lerp(Draw.YELLOW, Draw.ORANGE, Math.min(1f, combo / 15f)));
        }

        // Cooldown ring
        if (on("Cooldown Ring") && cd < 1f) {
            Draw.ring(g, cx, cy, 12, 1f, Draw.a(Draw.WHITE, 40));
            Draw.ring(g, cx, cy, 12, cd, Draw.ORANGE);
        }

        // Custom crosshair
        Module ch = mod("Crosshair");
        if (ch.enabled) {
            int s = (int) ch.num("Size"), gp = (int) ch.num("Gap");
            switch (ch.mode("Style")) {
                case 1 -> Draw.rrect(g, cx - 2, cy - 2, 4, 4, 2, Draw.a(Draw.YELLOW, 230));
                case 2 -> Draw.ring(g, cx, cy, s + gp, 1f, Draw.a(Draw.YELLOW, 230));
                default -> {
                    int col = Draw.a(Draw.WHITE, 230), sh = 0x66000000;
                    g.fill(cx - gp - s - 1, cy - 1, cx - gp + 1, cy + 2, sh); g.fill(cx - gp - s, cy, cx - gp, cy + 1, col);
                    g.fill(cx + gp - 1, cy - 1, cx + gp + s + 1, cy + 2, sh); g.fill(cx + gp, cy, cx + gp + s, cy + 1, col);
                    g.fill(cx - 1, cy - gp - s - 1, cx + 2, cy - gp + 1, sh); g.fill(cx, cy - gp - s, cx + 1, cy - gp, col);
                    g.fill(cx - 1, cy + gp - 1, cx + 2, cy + gp + s + 1, sh); g.fill(cx, cy + gp, cx + 1, cy + gp + s, col);
                    g.fill(cx, cy, cx + 1, cy + 1, Draw.ORANGE);
                }
            }
        }

        // Hit marker
        Module hm = mod("Hit Marker");
        if (hm.enabled) {
            float dur = (float) hm.num("Duration"), el = now - hitMarkerAt;
            if (el < dur) {
                int a = (int) (255 * (1 - el / dur)), size = (int) hm.num("Size"), gap = 3;
                int col = Draw.a(Draw.lerp(Draw.WHITE, Draw.ORANGE, el / dur), a);
                for (int i = gap; i < gap + size; i++)
                    for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2)
                        g.fill(cx + sx * i, cy + sy * i, cx + sx * i + 1, cy + sy * i + 1, col);
            }
        }
    }

    // ------------------------------------------------------------------ world rendering
    private static void world(WorldRenderContext c) {
        if (mc.world == null || mc.player == null) return;
        MatrixStack m = c.matrixStack();
        VertexConsumerProvider vp = c.consumers();
        if (m == null || vp == null) return;
        long now = System.currentTimeMillis();
        boolean halo = on("Halo"), hat = on("China Hat");
        boolean esp = on("Target ESP") && target != null && target.isAlive() && now < activeUntil;
        if (!halo && !hat && !esp && circles.isEmpty()) return;

        cam = c.camera().getPos();
        float td = c.tickCounter().getTickDelta(false);
        double t = now / 1000.0;
        VertexConsumer vc = vp.getBuffer(RenderLayer.getLines());
        MatrixStack.Entry e = m.peek();
        boolean fp = mc.options.getPerspective().isFirstPerson();

        // Halo + China Hat: ONLY above the local player's head (hidden in first person)
        if ((halo || hat) && !fp) {
            Vec3d pos = mc.player.getLerpedPos(td);
            double top = pos.y + mc.player.getHeight();
            if (halo) {
                Module hm = mod("Halo");
                float r = (float) hm.num("Radius");
                double y = top + 0.28 + Math.sin(t * 2) * 0.03;
                for (int k = 0; k < 3; k++) ring(e, vc, pos.x, y + k * 0.004, pos.z, r + k * 0.006f, 40, Draw.lerp(Draw.YELLOW, Draw.ORANGE, k / 2f));
                if (hm.on("Glow")) {
                    ring(e, vc, pos.x, y, pos.z, r + 0.035f, 40, Draw.a(Draw.YELLOW, 90));
                    ring(e, vc, pos.x, y, pos.z, r - 0.03f, 40, Draw.a(Draw.WHITE, 90));
                    ring(e, vc, pos.x, y, pos.z, r + 0.07f, 40, Draw.a(Draw.ORANGE, 40));
                }
            }
            if (hat) {
                float r = (float) mod("China Hat").num("Radius");
                double base = top - 0.05, apex = base + 0.32;
                for (int k = 0; k < 4; k++) {
                    float f = k / 4f;
                    ring(e, vc, pos.x, base + f * (apex - base), pos.z, r * (1 - f), 32, Draw.accent(f));
                }
                for (int i = 0; i < 24; i++) {
                    double a = i / 24.0 * Math.PI * 2;
                    line(e, vc, pos.x + Math.cos(a) * r, base, pos.z + Math.sin(a) * r, pos.x, apex, pos.z, Draw.a(i % 2 == 0 ? Draw.YELLOW : Draw.ORANGE, 220));
                }
            }
        }

        // Target ESP: rotating, shimmering gold-white angelic circles
        if (esp) {
            Module em = mod("Target ESP");
            int rings = (int) em.num("Rings");
            double sp = em.num("Speed");
            Vec3d p = target.getLerpedPos(td);
            double r = target.getWidth() / 2 + 0.45, hh = target.getHeight();
            for (int k = 0; k < rings; k++) {
                double dir = k % 2 == 0 ? 1 : -1;
                arcRing(e, vc, p.x, p.y + 0.03 + k * 0.01, p.z, (float) (r + k * 0.18), 72, t * sp * 2.2 * dir + k * 1.3, 0.55);
            }
            // a bright ring gliding up and down the body
            double by = p.y + (Math.sin(t * sp * 1.5) * 0.5 + 0.5) * hh;
            arcRing(e, vc, p.x, by, p.z, (float) (r * 0.9), 72, -t * sp * 3.0, 0.75);
        }

        // Jump circles
        for (Circle ci : circles) {
            float age = (now - ci.t()) / 900f;
            ring(e, vc, ci.x(), ci.y() + 0.02, ci.z(), 0.3f + age * 1.6f, 40, Draw.a(Draw.lerp(Draw.YELLOW, Draw.ORANGE, age), (int) (255 * (1 - age))));
        }

        if (vp instanceof VertexConsumerProvider.Immediate imm) imm.draw(RenderLayer.getLines());
    }

    /** Ring made of rotating arcs; brightest (white) at the head of each arc, fading to gold, with a soft glow. */
    private static void arcRing(MatrixStack.Entry e, VertexConsumer vc, double cx, double cy, double cz, float r, int seg, double phase, double duty) {
        for (int i = 0; i < seg; i++) {
            double f = i / (double) seg;
            double cyc = ((f * 3 - phase / (Math.PI * 2)) % 1 + 1) % 1;
            if (cyc > duty) continue;
            float tt = (float) (cyc / duty);
            int core = Draw.a(Draw.lerp(Draw.WHITE, Draw.YELLOW, tt), (int) (255 * (1 - tt * 0.6)));
            int glow = Draw.a(Draw.YELLOW, (int) (80 * (1 - tt)));
            double a0 = f * Math.PI * 2, a1 = (i + 1) / (double) seg * Math.PI * 2;
            for (int k = 0; k < 2; k++) {
                double rr = r + k * 0.015, yy = cy + k * 0.012;
                line(e, vc, cx + Math.cos(a0) * rr, yy, cz + Math.sin(a0) * rr, cx + Math.cos(a1) * rr, yy, cz + Math.sin(a1) * rr, core);
            }
            double ro = r + 0.055, ri = r - 0.04;
            line(e, vc, cx + Math.cos(a0) * ro, cy, cz + Math.sin(a0) * ro, cx + Math.cos(a1) * ro, cy, cz + Math.sin(a1) * ro, glow);
            line(e, vc, cx + Math.cos(a0) * ri, cy, cz + Math.sin(a0) * ri, cx + Math.cos(a1) * ri, cy, cz + Math.sin(a1) * ri, glow);
        }
    }

    private static void ring(MatrixStack.Entry e, VertexConsumer vc, double cx, double cy, double cz, float r, int seg, int argb) {
        for (int i = 0; i < seg; i++) {
            double a0 = i / (double) seg * Math.PI * 2, a1 = (i + 1) / (double) seg * Math.PI * 2;
            line(e, vc, cx + Math.cos(a0) * r, cy, cz + Math.sin(a0) * r, cx + Math.cos(a1) * r, cy, cz + Math.sin(a1) * r, argb);
        }
    }

    private static void line(MatrixStack.Entry e, VertexConsumer vc, double x0, double y0, double z0, double x1, double y1, double z1, int argb) {
        float dx = (float) (x1 - x0), dy = (float) (y1 - y0), dz = (float) (z1 - z0);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return;
        dx /= len; dy /= len; dz /= len;
        vc.vertex(e, (float) (x0 - cam.x), (float) (y0 - cam.y), (float) (z0 - cam.z)).color(argb).normal(e, dx, dy, dz);
        vc.vertex(e, (float) (x1 - cam.x), (float) (y1 - cam.y), (float) (z1 - cam.z)).color(argb).normal(e, dx, dy, dz);
    }
}
