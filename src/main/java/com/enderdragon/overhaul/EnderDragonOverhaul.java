package com.enderdragon.overhaul;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.EndCrystal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EnderDragonOverhaul implements ModInitializer {
    private static final float MAX_HP = 600.0F;
    private static final double RADIUS = 128.0D;
    private static final Map<UUID, State> STATES = new HashMap<>();

    @Override
    public void onInitialize() {
        ServerTickEvents.END_LEVEL_TICK.register(EnderDragonOverhaul::tickLevel);
    }

    private static void tickLevel(ServerLevel level) {
        if (level.dimension() != Level.END) return;

        // Only inspect the End level once per tick. No global server scan.
        for (EnderDragon dragon : level.getEntities().getAll().stream()
                .filter(e -> e instanceof EnderDragon)
                .map(e -> (EnderDragon)e)
                .toList()) {
            tickDragon(level, dragon);
        }
    }

    private static void tickDragon(ServerLevel level, EnderDragon dragon) {
        UUID id = dragon.getUUID();
        State s = STATES.computeIfAbsent(id, k -> new State());

        ensureHealth(dragon);
        s.tick++;

        s.phase = phase(dragon.getHealth());
        s.attackCooldown = Math.max(0, s.attackCooldown - 1);
        s.diveCooldown = Math.max(0, s.diveCooldown - 1);
        s.laserCooldown = Math.max(0, s.laserCooldown - 1);
        s.orbCooldown = Math.max(0, s.orbCooldown - 1);

        if (s.diveTimer > 0) {
            s.diveTimer--;
            if (s.diveTimer == 0) {
                Vec3 p = targetPosition(level, s.target);
                if (p != null) shockwave(level, p, 9.0D, 10.0F + s.phase * 2.0F);
            }
        }

        if (s.laserTimer > 0) {
            s.laserTimer--;
            ServerPlayer p = player(level, s.target);
            if (p != null) {
                Vec3 origin = dragon.position().add(0, 1.5, 0);
                Vec3 d = p.position().add(0, 1, 0).subtract(origin);
                if (d.lengthSqr() > 0.001D) {
                    Vec3 n = d.normalize();
                    double along = Math.min(40.0D, d.length());
                    Vec3 beamEnd = origin.add(n.scale(along));
                    level.sendParticles(ParticleTypes.END_ROD,
                            beamEnd.x, beamEnd.y, beamEnd.z, 2, .25, .25, .25, 0);
                    if (distancePointLine(p.position(), origin, n) < 4.0D) {
                        p.hurt(level.damageSources().dragonBreath(), 7.0F + s.phase * 2.0F);
                    }
                }
            }
        }

        if (s.orbTimer > 0) {
            s.orbTimer--;
            if (s.tick % 6 == 0) {
                ServerPlayer p = player(level, s.target);
                if (p != null && dragon.distanceToSqr(p) < 20 * 20) {
                    p.hurt(level.damageSources().dragonBreath(), 4.0F + s.phase);
                    level.sendParticles(ParticleTypes.PORTAL, p.getX(), p.getY()+1, p.getZ(),
                            5, .4, .6, .4, .01);
                }
            }
        }

        updateCrystalCounter(level, dragon);

        if (dragon.getHealth() <= 60.0F) {
            finalPhase(level, dragon, s);
            return;
        }

        ServerPlayer target = nearest(level, dragon);
        if (target != null && s.attackCooldown == 0) {
            chooseAttack(level, dragon, target, s);
        }

        // Sparse ambience: no per-player particle spam and no custom entities.
        if (s.tick % 10 == 0) {
            int count = s.phase >= 3 ? 2 : 1;
            level.sendParticles(ParticleTypes.PORTAL,
                    dragon.getX(), dragon.getY(), dragon.getZ(),
                    count, 3, 2, 3, .015);
        }
    }

    private static void ensureHealth(EnderDragon dragon) {
        var attribute = dragon.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (attribute != null && attribute.getBaseValue() != MAX_HP) {
            attribute.setBaseValue(MAX_HP);
        }
        if (dragon.getHealth() > MAX_HP) dragon.setHealth(MAX_HP);
    }

    private static void chooseAttack(ServerLevel level, EnderDragon dragon,
                                     ServerPlayer target, State s) {
        s.target = target.getUUID();
        double d2 = dragon.distanceToSqr(target);
        int players = participants(level);

        if (s.phase >= 2 && s.diveCooldown == 0 && d2 > 8 * 8) {
            s.attackCooldown = cooldown(s.phase, 150);
            s.diveCooldown = cooldown(s.phase, 170);
            Vec3 dir = target.position().add(0, .5, 0)
                    .subtract(dragon.position()).normalize();
            dragon.setDeltaMovement(dir.scale(1.2D));
            dragon.hasImpulse = true;
            warning(level, target.position());
            s.diveTimer = 35;
            return;
        }

        if (s.phase >= 2 && s.laserCooldown == 0 && d2 > 12 * 12) {
            s.attackCooldown = cooldown(s.phase, 190);
            s.laserCooldown = cooldown(s.phase, 210);
            s.laserTimer = 55 + s.phase * 5;
            dragon.playSound(SoundEvents.BEACON_ACTIVATE, 4.0F, .45F);
            warning(level, target.position());
            return;
        }

        if (s.phase >= 2 && s.orbCooldown == 0) {
            s.attackCooldown = cooldown(s.phase, 145 - Math.min(30, players * 5));
            s.orbCooldown = cooldown(s.phase, 180);
            s.orbTimer = 45 + s.phase * 8;
            dragon.playSound(SoundEvents.RESPAWN_ANCHOR_CHARGE, 2.5F, 1.35F);
            return;
        }

        // Chain fireball-style breath pressure using vanilla dragon-breath particles/damage.
        s.attackCooldown = cooldown(s.phase, 90 - Math.min(25, players * 4));
        for (int i = 1; i <= Math.min(7, 3 + s.phase + players); i++) {
            Vec3 p = target.position().add(0, 1, 0);
            level.sendParticles(ParticleTypes.DRAGON_BREATH,
                    p.x, p.y, p.z, 3, .7, .4, .7, .01);
        }
        if (d2 < 20 * 20) {
            target.hurt(level.damageSources().dragonBreath(), 5.0F);
        }
        dragon.playSound(SoundEvents.ENDER_DRAGON_GROWL, 3.0F, .75F);
    }

    private static void finalPhase(ServerLevel level, EnderDragon dragon, State s) {
        if (!s.finalAnnounced) {
            s.finalAnnounced = true;
            for (ServerPlayer p : participantsList(level)) {
                p.displayClientMessage(
                        Component.literal("The Ender Dragon enters its final phase."), true);
            }
            level.playSound(null, dragon.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL,
                    SoundSource.HOSTILE, 5.0F, .55F);
        }

        // Hold the dragon at the 10% threshold while it is away from the intended final area.
        double centerDistance = Math.hypot(dragon.getX() - .5, dragon.getZ() - .5);
        if (centerDistance > 32.0D) {
            if (dragon.getHealth() < 60.0F) dragon.setHealth(60.0F);
            s.finalTimer++;
            if (s.finalTimer > 600) {
                s.finalTimer = 0;
                dragon.teleportTo(level, .5, 72, .5, java.util.Set.of(),
                        dragon.getYRot(), dragon.getXRot());
            }
        } else if (s.tick % 10 == 0) {
            level.sendParticles(ParticleTypes.DRAGON_BREATH,
                    dragon.getX(), dragon.getY(), dragon.getZ(),
                    10, 2, 1, 2, .03);
        }
    }

    private static void updateCrystalCounter(ServerLevel level, EnderDragon dragon) {
        State s = STATES.get(dragon.getUUID());
        if (s == null || s.tick % 5 != 0) return;

        int count = level.getEntitiesOfClass(
                EndCrystal.class,
                new AABB(-128, 0, -128, 128, 256, 128)).size();

        if (count != s.lastCrystalCount) {
            s.lastCrystalCount = count;
            for (ServerPlayer p : participantsList(level)) {
                p.displayClientMessage(
                        Component.literal("End Crystals Remaining: " + count), true);
            }
        }
    }

    private static void shockwave(ServerLevel level, Vec3 center,
                                   double radius, float damage) {
        level.sendParticles(ParticleTypes.EXPLOSION,
                center.x, center.y + .2, center.z,
                8, radius / 3, .2, radius / 3, .02);
        level.playSound(null, center.x, center.y, center.z,
                SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 3.0F, .7F);

        AABB box = new AABB(center, center).inflate(radius);
        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, box)) {
            double d = Math.max(.1, p.position().distanceTo(center));
            double scale = 1.0 - Math.min(1.0, d / radius);
            p.hurt(level.damageSources().dragonBreath(),
                    damage * (float)Math.max(.35, scale));
            Vec3 push = p.position().subtract(center).normalize()
                    .scale(.8 * Math.max(.35, scale));
            p.push(push.x, .3 + .4 * scale, push.z);
        }
    }

    private static void warning(ServerLevel level, Vec3 p) {
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                p.x, p.y + .2, p.z, 12, 1.4, .2, 1.4, .02);
        level.playSound(null, p.x, p.y, p.z,
                SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 2.5F, .8F);
    }

    private static int phase(float hp) {
        if (hp > 450) return 1;
        if (hp > 300) return 2;
        if (hp > 150) return 3;
        return 4;
    }

    private static int cooldown(int phase, int base) {
        return Math.max(25, base - (phase - 1) * 12);
    }

    private static int participants(ServerLevel level) {
        return participantsList(level).size();
    }

    private static java.util.List<ServerPlayer> participantsList(ServerLevel level) {
        return level.getEntitiesOfClass(ServerPlayer.class,
                new AABB(-RADIUS, 0, -RADIUS, RADIUS, 256, RADIUS),
                p -> !p.isSpectator());
    }

    private static ServerPlayer nearest(ServerLevel level, Entity entity) {
        return level.getEntitiesOfClass(ServerPlayer.class,
                entity.getBoundingBox().inflate(RADIUS),
                p -> !p.isSpectator())
                .stream()
                .min(Comparator.comparingDouble(entity::distanceToSqr))
                .orElse(null);
    }

    private static ServerPlayer player(ServerLevel level, UUID id) {
        if (id == null) return null;
        for (ServerPlayer p : level.players()) {
            if (id.equals(p.getUUID())) return p;
        }
        return null;
    }

    private static Vec3 targetPosition(ServerLevel level, UUID id) {
        ServerPlayer p = player(level, id);
        return p == null ? null : p.position();
    }

    private static double distancePointLine(Vec3 point, Vec3 origin, Vec3 direction) {
        Vec3 v = point.subtract(origin);
        return v.subtract(direction.scale(v.dot(direction))).length();
    }

    private static final class State {
        int tick;
        int phase;
        int attackCooldown;
        int diveCooldown;
        int laserCooldown;
        int orbCooldown;
        int diveTimer;
        int laserTimer;
        int orbTimer;
        int finalTimer;
        int lastCrystalCount = -1;
        boolean finalAnnounced;
        UUID target;
    }
}
