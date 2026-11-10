package com.example.ragdollmod;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Mod(RagdollMod.MODID)
public class RagdollMod {
    public static final String MODID = "ragdollmod";
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals
    );
    public static final Map<UUID, RagdollRig> RIGS = new ConcurrentHashMap<>();

    public RagdollMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            CHANNEL.registerMessage(0, GrabPacket.class, GrabPacket::encode, GrabPacket::decode, GrabPacket::handle);
        });
    }

    public static RagdollRig getRig(Player p) {
        return RIGS.computeIfAbsent(p.getUUID(), k -> new RagdollRig());
    }

    public static class RagdollRig {
        public final Vector3f[] pos = new Vector3f[6];
        public final Vector3f[] prevPos = new Vector3f[6];
        public final Quaternionf[] rot = new Quaternionf[6];
        public final Vector3f[] angVel = new Vector3f[6];
        public boolean init = false, isRagdolled = false, leftGrab = false, rightGrab = false;
        public boolean leftAnchored = false, rightAnchored = false;
        public final Vector3f leftAnchor = new Vector3f(), rightAnchor = new Vector3f();
        public int recoveryTimer = 0;
        public Vec3 lookVec = new Vec3(0, 0, 1);

        public RagdollRig() {
            for (int i = 0; i < 6; i++) {
                pos[i] = new Vector3f();
                prevPos[i] = new Vector3f();
                rot[i] = new Quaternionf();
                angVel[i] = new Vector3f();
            }
        }

        public void collapse(Vec3 impulse) {
            isRagdolled = true;
            recoveryTimer = 60;
            leftAnchored = false;
            rightAnchored = false;
            for (int i = 0; i < 6; i++) {
                pos[i].add((float) impulse.x * 0.35f, (float) impulse.y * 0.35f, (float) impulse.z * 0.35f);
                angVel[i].add((float) (-impulse.z * 7.5f), (float) (impulse.x * 3.0f), (float) (impulse.x * 7.5f));
            }
        }

        public float pdStrength() {
            if (!isRagdolled && recoveryTimer <= 0) return 1.0f;
            if (recoveryTimer > 40) return 0.0f;
            float t = Mth.clamp(1.0f - (recoveryTimer / 40.0f), 0.0f, 1.0f);
            return t * t * (3.0f - 2.0f * t);
        }

        public void tick(Player p) {
            Vec3 base = p.position();
            if (!init || p.distanceToSqr(pos[1].x, pos[1].y, pos[1].z) > 16.0) {
                for (int i = 0; i < 6; i++) {
                    pos[i].set((float) base.x, (float) base.y + 1.1f, (float) base.z);
                    prevPos[i].set(pos[i]);
                    rot[i].identity();
                    angVel[i].set(0, 0, 0);
                }
                init = true;
            }
            if (recoveryTimer > 0 && --recoveryTimer == 0) isRagdolled = false;

            float dt = 0.05f, pd = pdStrength(), yaw = (float) Math.toRadians(-p.yBodyRot);
            if (pd > 0.001f) {
                float walk = p.walkAnimation.position() * 0.6662f, spd = p.walkAnimation.speed();
                float tilt = Mth.clamp((float) p.getDeltaMovement().length() * 0.35f, 0.0f, 0.28f);
                applyPd(1, new Quaternionf().rotateY(yaw).rotateX(tilt).rotateZ(Mth.sin(walk) * 0.18f * spd), 38f * pd, 7.5f * pd, dt);
                applyPd(0, new Quaternionf().rotateY((float) Math.toRadians(-p.getYHeadRot())).rotateX((float) Math.toRadians(p.getXRot() * 0.65f)), 30f * pd, 6f * pd, dt);
                float leg = Mth.cos(walk) * 0.85f * spd;
                applyPd(4, new Quaternionf().rotateY(yaw).rotateX(leg), 24f * pd, 5f * pd, dt);
                applyPd(5, new Quaternionf().rotateY(yaw).rotateX(-leg), 24f * pd, 5f * pd, dt);
                pos[1].fma(0.45f * pd, new Vector3f((float) base.x - pos[1].x, (float) base.y + 1.125f - pos[1].y, (float) base.z - pos[1].z));
            }
            updateArm(p, 2, true, leftGrab, leftAnchor, yaw, pd, dt);
            updateArm(p, 3, false, rightGrab, rightAnchor, yaw, pd, dt);
            for (int i = 0; i < 6; i++) {
                float vx = (pos[i].x - prevPos[i].x) * 0.92f, vy = (pos[i].y - prevPos[i].y) * 0.92f, vz = (pos[i].z - prevPos[i].z) * 0.92f;
                prevPos[i].set(pos[i]);
                pos[i].add(vx, vy - 9.81f * dt * dt, vz);
                angVel[i].mul(0.84f);
                float sp = angVel[i].length();
                if (sp > 1e-5f) new Quaternionf().fromAxisAngleRad(angVel[i].x / sp, angVel[i].y / sp, angVel[i].z / sp, sp * dt).mul(rot[i], rot[i]).normalize();
                if (!p.level().noCollision(new AABB(pos[i].x - 0.15, pos[i].y - 0.25, pos[i].z - 0.15, pos[i].x + 0.15, pos[i].y + 0.25, pos[i].z + 0.15))) pos[i].y += 0.04f;
            }
            int grabs = (leftAnchored ? 1 : 0) + (rightAnchored ? 1 : 0);
            if (grabs > 0) {
                Vector3f anc = new Vector3f();
                if (leftAnchored) anc.add(leftAnchor);
                if (rightAnchored) anc.add(rightAnchor);
                anc.div(grabs).sub(pos[1].x, pos[1].y + 0.32f, pos[1].z);
                Vec3 v = p.getDeltaMovement();
                p.setDeltaMovement(v.x * 0.7 + Mth.clamp(anc.x * 0.18, -0.25, 0.25), Math.max(v.y * 0.6, Mth.clamp(anc.y * 0.22 - lookVec.y * 0.12, -0.15, 0.32)), v.z * 0.7 + Mth.clamp(anc.z * 0.18, -0.25, 0.25));
                p.fallDistance = 0.0f;
            }
        }

        private void updateArm(Player p, int idx, boolean isLeft, boolean held, Vector3f anchor, float yaw, float pd, float dt) {
            if (held && !isRagdolled) {
                float ly = (float) Math.atan2(-lookVec.x, lookVec.z), lp = (float) Math.asin(Mth.clamp(lookVec.y, -1.0, 1.0));
                applyPd(idx, new Quaternionf().rotateY(ly).rotateX(-lp - (float) (Math.PI * 0.5)), 55f, 8.5f, dt);
                boolean anchored = isLeft ? leftAnchored : rightAnchored;
                if (!anchored) {
                    Vec3 start = p.getEyePosition().add(isLeft ? 0.25 : -0.25, -0.2, 0.0);
                    BlockHitResult hit = p.level().clip(new ClipContext(start, start.add(lookVec.scale(1.5)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                    if (hit.getType() == HitResult.Type.BLOCK && !p.level().getBlockState(hit.getBlockPos()).isAir()) {
                        anchor.set((float) hit.getLocation().x, (float) hit.getLocation().y, (float) hit.getLocation().z);
                        if (isLeft) leftAnchored = true; else rightAnchored = true;
                    }
                }
            } else {
                if (isLeft) leftAnchored = false; else rightAnchored = false;
                if (pd > 0.001f) {
                    float sw = Mth.cos(p.walkAnimation.position() * 0.6662f + (isLeft ? 0f : (float) Math.PI)) * 0.7f * p.walkAnimation.speed();
                    applyPd(idx, new Quaternionf().rotateY(yaw).rotateX(sw).rotateZ(isLeft ? -0.08f : 0.08f), 20f * pd, 4.5f * pd, dt);
                }
            }
        }

        private void applyPd(int idx, Quaternionf target, float kp, float kd, float dt) {
            Quaternionf d = new Quaternionf(target).mul(new Quaternionf(rot[idx]).conjugate()).normalize();
            if (d.w < 0f) d.set(-d.x, -d.y, -d.z, -d.w);
            float ang = 2f * (float) Math.acos(Mth.clamp(d.w, -1f, 1f)), s = (float) Math.sqrt(Math.max(0f, 1f - d.w * d.w));
            Vector3f ax = s > 1e-4f ? new Vector3f(d.x / s, d.y / s, d.z / s) : new Vector3f();
            angVel[idx].fma(dt, ax.mul(ang * kp).sub(new Vector3f(angVel[idx]).mul(kd)));
        }
    }

    public static class GrabPacket {
        public final int id;
        public final boolean l, r;
        public final float x, y, z;
        public GrabPacket(int id, boolean l, boolean r, float x, float y, float z) {
            this.id = id; this.l = l; this.r = r; this.x = x; this.y = y; this.z = z;
        }
        public static void encode(GrabPacket m, FriendlyByteBuf b) {
            b.writeInt(m.id); b.writeBoolean(m.l); b.writeBoolean(m.r); b.writeFloat(m.x); b.writeFloat(m.y); b.writeFloat(m.z);
        }
        public static GrabPacket decode(FriendlyByteBuf b) {
            return new GrabPacket(b.readInt(), b.readBoolean(), b.readBoolean(), b.readFloat(), b.readFloat(), b.readFloat());
        }
        public static void handle(GrabPacket m, Supplier<NetworkEvent.Context> c) {
            c.get().enqueueWork(() -> {
                Vec3 lk = new Vec3(m.x, m.y, m.z).normalize();
                if (c.get().getDirection() == NetworkDirection.PLAY_TO_SERVER) {
                    ServerPlayer sp = c.get().getSender();
                    if (sp != null) {
                        RagdollRig rig = getRig(sp);
                        rig.leftGrab = m.l; rig.rightGrab = m.r; rig.lookVec = lk;
                        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> sp), new GrabPacket(sp.getId(), m.l, m.r, m.x, m.y, m.z));
                    }
                } else if (Minecraft.getInstance().level != null && Minecraft.getInstance().level.getEntity(m.id) instanceof Player p) {
                    RagdollRig rig = getRig(p);
                    rig.leftGrab = m.l; rig.rightGrab = m.r; rig.lookVec = lk;
                }
            });
            c.get().setPacketHandled(true);
        }
    }

    private static final Map<ResourceKey<Level>, Map<BlockPos, float[]>> BLOCK_DMG = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase == TickEvent.Phase.END) getRig(e.player).tick(e.player);
    }

    @SubscribeEvent
    public void onHurt(LivingHurtEvent e) {
        if (e.getEntity() instanceof Player p && (e.getSource().is(DamageTypeTags.IS_EXPLOSION) || e.getAmount() >= 4.0f)) {
            getRig(p).collapse(p.getDeltaMovement().add(0, 0.35, 0));
        }
    }

    @SubscribeEvent
    public void onKnockback(LivingKnockBackEvent e) {
        if (e.getEntity() instanceof Player p && e.getStrength() >= 0.45f) {
            getRig(p).collapse(new Vec3(-e.getRatioX(), 0.45, -e.getRatioZ()).normalize().scale(e.getStrength() * 1.3));
        }
    }

    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate e) {
        Explosion ex = e.getExplosion();
        for (Entity ent : e.getAffectedEntities()) {
            if (ent instanceof Player p) {
                Vec3 d = p.position().add(0, 0.9, 0).subtract(ex.getPosition());
                getRig(p).collapse(d.normalize().scale(Math.min(2.5, 4.0 / Math.max(0.5, d.length()))));
            }
        }
    }

    @SubscribeEvent
    public void onJump(LivingEvent.LivingJumpEvent e) {
        if (e.getEntity() instanceof Player p) {
            Vec3 m = p.getDeltaMovement();
            p.setDeltaMovement(m.x, getRig(p).isRagdolled ? 0.0 : (0.395D + p.getJumpBoostPower()), m.z);
        }
    }

    @SubscribeEvent
    public void onBreakSpeed(PlayerEvent.BreakSpeed e) {
        Player p = e.getEntity();
        e.getPosition().ifPresent(pos -> {
            e.setNewSpeed(0.0f);
            if (!p.level().isClientSide && p instanceof ServerPlayer sp && p.level() instanceof ServerLevel sl) {
                BlockState st = e.getState();
                float spd = st.getDestroySpeed(sl, pos);
                if (spd < 0f) return;
                float inc = spd == 0f ? 1f : (sp.getDigSpeed(st, pos) / spd / (ForgeHooks.isCorrectToolForDrops(st, sp) ? 30f : 100f));
                Map<BlockPos, float[]> map = BLOCK_DMG.computeIfAbsent(sl.dimension(), k -> new HashMap<>());
                float[] data = map.computeIfAbsent(pos.immutable(), k -> new float[]{0f, sl.getGameTime(), (k.hashCode() & 0x7FFFFFFF)});
                data[0] += inc;
                data[1] = sl.getGameTime();
                if (data[0] >= 1f) {
                    map.remove(pos);
                    sl.destroyBlockProgress((int) data[2], pos, -1);
                    sp.gameMode.destroyBlock(pos);
                } else {
                    sl.destroyBlockProgress((int) data[2], pos, Math.min(9, (int) (data[0] * 10f)));
                }
            }
        });
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase == TickEvent.Phase.END && !e.level.isClientSide && e.level instanceof ServerLevel sl) {
            Map<BlockPos, float[]> map = BLOCK_DMG.get(sl.dimension());
            if (map == null) return;
            long now = sl.getGameTime();
            map.entrySet().removeIf(en -> {
                if (now - (long) en.getValue()[1] >= 100L || sl.getBlockState(en.getKey()).isAir()) {
                    sl.destroyBlockProgress((int) en.getValue()[2], en.getKey(), -1);
                    return true;
                }
                return false;
            });
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ClientModEvents {
        public static final KeyMapping LEFT_KEY = new KeyMapping("key.ragdollmod.left_grab", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.ragdollmod");
        public static final KeyMapping RIGHT_KEY = new KeyMapping("key.ragdollmod.right_grab", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "key.categories.ragdollmod");
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent e) {
            e.register(LEFT_KEY);
            e.register(RIGHT_KEY);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class ClientForgeEvents {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Player p = Minecraft.getInstance().player;
            if (p == null) return;
            boolean l = ClientModEvents.LEFT_KEY.isDown(), r = ClientModEvents.RIGHT_KEY.isDown();
            Vec3 lk = p.getLookAngle();
            RagdollRig rig = getRig(p);
            rig.leftGrab = l; rig.rightGrab = r; rig.lookVec = lk;
            CHANNEL.sendToServer(new GrabPacket(p.getId(), l, r, (float) lk.x, (float) lk.y, (float) lk.z));
        }

        @SubscribeEvent
        public static void onRenderPlayerPre(RenderPlayerEvent.Pre e) {
            Player p = e.getEntity();
            RagdollRig rig = getRig(p);
            PoseStack ps = e.getPoseStack();
            ps.pushPose();
            ps.translate(rig.pos[1].x - (float) p.getX(), (rig.pos[1].y - 1.125f) - (float) p.getY(), rig.pos[1].z - (float) p.getZ());
            PlayerModel<AbstractClientPlayer> m = e.getRenderer().getModel();
            Quaternionf inv = new Quaternionf().rotateY((float) Math.toRadians(p.yBodyRot));
            applyPart(m.head, rig.rot[0], inv);
            applyPart(m.body, rig.rot[1], inv);
            applyPart(m.leftArm, rig.rot[2], inv);
            applyPart(m.rightArm, rig.rot[3], inv);
            applyPart(m.leftLeg, rig.rot[4], inv);
            applyPart(m.rightLeg, rig.rot[5], inv);
            m.hat.copyFrom(m.head);
        }

        @SubscribeEvent
        public static void onRenderPlayerPost(RenderPlayerEvent.Post e) {
            e.getPoseStack().popPose();
        }

        private static void applyPart(ModelPart pt, Quaternionf w, Quaternionf inv) {
            Quaternionf q = new Quaternionf(inv).mul(w).normalize();
            pt.xRot = (float) Math.atan2(2f * (q.w * q.x + q.y * q.z), 1f - 2f * (q.x * q.x + q.y * q.y));
            pt.yRot = -(float) Math.asin(Mth.clamp(2f * (q.w * q.y - q.z * q.x), -1f, 1f));
            pt.zRot = -(float) Math.atan2(2f * (q.w * q.z + q.x * q.y), 1f - 2f * (q.y * q.y + q.z * q.z));
        }
    }
}
