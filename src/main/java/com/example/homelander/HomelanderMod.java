package com.example.homelander;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Rarity;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.recipe.Ingredient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fabric 1.21.1 fan mod. Server is authoritative: clients never choose damage,
 * raycast targets, timers or explosion locations.
 *
 * No structures/terrain are destroyed by the powers, intentionally.
 */
public final class HomelanderMod implements ModInitializer {
    public static final String MOD_ID = "homelander";
    public static final String BOSS_TAG = "homelander_boss";

    private static final long SERUM_TICKS = 20L * 180; // 3 minutes
    private static final int LASER_RANGE = 48;
    private static final DustParticleEffect RED_LASER = new DustParticleEffect(new Vector3f(1f, .04f, .025f), 1.25f);
    private static final DustParticleEffect GOLD_SPARK = new DustParticleEffect(new Vector3f(1f, .70f, .08f), 1.15f);

    private static final Map<UUID, Long> SERUM_EXPIRY = new HashMap<>();
    private static final Map<UUID, Map<Byte, Long>> COOLDOWNS = new HashMap<>();
    private static final Set<UUID> FLIGHT_SELECTED = new HashSet<>();
    private static final Set<UUID> LANDING = new HashSet<>();

    private static final RegistryEntry<ArmorMaterial> SUIT_MATERIAL = Registry.registerReference(
            Registries.ARMOR_MATERIAL, id("homelander"),
            new ArmorMaterial(
                    Map.of(
                            ArmorItem.Type.HELMET, 4,
                            ArmorItem.Type.CHESTPLATE, 9,
                            ArmorItem.Type.LEGGINGS, 7,
                            ArmorItem.Type.BOOTS, 4,
                            ArmorItem.Type.BODY, 0
                    ),
                    22,
                    SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE,
                    () -> Ingredient.ofItems(Items.NETHERITE_INGOT),
                    List.of(new ArmorMaterial.Layer(id("homelander"))),
                    4.0f,
                    0.30f
            )
    );

    public static final Item HELMET = armor("homelander_helmet", ArmorItem.Type.HELMET);
    public static final Item CHESTPLATE = armor("homelander_chestplate", ArmorItem.Type.CHESTPLATE);
    public static final Item LEGGINGS = armor("homelander_leggings", ArmorItem.Type.LEGGINGS);
    public static final Item BOOTS = armor("homelander_boots", ArmorItem.Type.BOOTS);
    public static final Item COMPOUND_V = Registry.register(Registries.ITEM, id("compound_v"),
            new CompoundVItem(new Item.Settings().maxCount(16).rarity(Rarity.EPIC)));

    public static Identifier id(String path) { return Identifier.of(MOD_ID, path); }

    private static Item armor(String name, ArmorItem.Type type) {
        return Registry.register(Registries.ITEM, id(name),
                new ArmorItem(SUIT_MATERIAL, type,
                        new Item.Settings().maxDamage(type.getMaxDamage(45)).rarity(Rarity.EPIC)));
    }

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> {
            entries.add(HELMET);
            entries.add(CHESTPLATE);
            entries.add(LEGGINGS);
            entries.add(BOOTS);
            entries.add(COMPOUND_V);
        });

        PayloadTypeRegistry.playC2S().register(AbilityPayload.ID, AbilityPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AbilityPayload.ID, (payload, context) -> {
            byte ability = payload.ability();
            // Validation, energy/cooldowns and raycast are all SERVER SIDE.
            if (ability >= 0 && ability <= 5) executeAbility(context.player(), ability);
        });

        ServerTickEvents.END_SERVER_TICK.register(HomelanderMod::serverTick);
        ServerTickEvents.END_WORLD_TICK.register(HomelanderMod::bossTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            SERUM_EXPIRY.clear();
            COOLDOWNS.clear();
            FLIGHT_SELECTED.clear();
            LANDING.clear();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = CommandManager.literal("homelander")
                    .then(CommandManager.literal("help").executes(ctx -> help(ctx.getSource())))
                    .then(CommandManager.literal("laser").executes(ctx -> commandAbility(ctx, (byte) 0)))
                    .then(CommandManager.literal("fly").executes(ctx -> commandAbility(ctx, (byte) 1)))
                    .then(CommandManager.literal("clap").executes(ctx -> commandAbility(ctx, (byte) 2)))
                    .then(CommandManager.literal("dash").executes(ctx -> commandAbility(ctx, (byte) 3)))
                    .then(CommandManager.literal("punch").executes(ctx -> commandAbility(ctx, (byte) 4)))
                    .then(CommandManager.literal("land").executes(ctx -> commandAbility(ctx, (byte) 5)))
                    .then(CommandManager.literal("kit")
                            .requires(src -> src.hasPermissionLevel(2))
                            .executes(ctx -> giveKit(ctx.getSource())))
                    .then(CommandManager.literal("boss")
                            .requires(src -> src.hasPermissionLevel(2))
                            .executes(ctx -> summonBoss(ctx.getSource())));
            dispatcher.register(root);
        });
    }

    private static int commandAbility(CommandContext<ServerCommandSource> ctx, byte ability) {
        try {
            return executeAbility(ctx.getSource().getPlayerOrThrow(), ability) ? 1 : 0;
        } catch (Exception ignored) {
            ctx.getSource().sendError(Text.literal("Эта команда доступна только игроку."));
            return 0;
        }
    }

    private static int help(ServerCommandSource src) {
        src.sendFeedback(() -> Text.literal("§cHOMELANDER §f— H лазер, V полёт, G волна, R рывок, B удар, N приземление"), false);
        src.sendFeedback(() -> Text.literal("§7/homelander kit — костюм (читы); /homelander boss — призвать босса (читы)"), false);
        src.sendFeedback(() -> Text.literal("§eНадень все 4 части костюма или выпей Compound V, чтобы получить способности."), false);
        return 1;
    }

    private static void give(ServerPlayerEntity player, Item item) {
        ItemStack stack = new ItemStack(item);
        if (!player.getInventory().insertStack(stack)) player.dropItem(stack, false);
    }

    private static int giveKit(ServerCommandSource src) {
        try {
            ServerPlayerEntity player = src.getPlayerOrThrow();
            give(player, HELMET);
            give(player, CHESTPLATE);
            give(player, LEGGINGS);
            give(player, BOOTS);
            give(player, COMPOUND_V);
            src.sendFeedback(() -> Text.literal("§6Костюм Хоумлендера и Compound V получены!"), false);
            return 1;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int summonBoss(ServerCommandSource src) {
        ServerWorld world = src.getWorld();
        ZombieEntity boss = EntityType.ZOMBIE.create(world);
        if (boss == null) return 0;
        Vec3d pos = src.getPosition().add(3, 0, 3);
        boss.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0, 0);
        boss.setCustomName(Text.literal("§c§lHOMELANDER"));
        boss.setCustomNameVisible(true);
        boss.addCommandTag(BOSS_TAG);
        boss.equipStack(EquipmentSlot.HEAD, new ItemStack(HELMET));
        boss.equipStack(EquipmentSlot.CHEST, new ItemStack(CHESTPLATE));
        boss.equipStack(EquipmentSlot.LEGS, new ItemStack(LEGGINGS));
        boss.equipStack(EquipmentSlot.FEET, new ItemStack(BOOTS));
        EntityAttributeInstance health = boss.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (health != null) health.setBaseValue(200.0);
        EntityAttributeInstance speed = boss.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(.34);
        EntityAttributeInstance damage = boss.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (damage != null) damage.setBaseValue(14.0);
        boss.setHealth(200.0f);
        boss.setPersistent();
        world.spawnEntity(boss);
        src.sendFeedback(() -> Text.literal("§cБосс Хоумлендер призван! Здоровье: 200. Лазер — каждые 5 сек."), true);
        return 1;
    }

    public static boolean hasFullSuit(PlayerEntity p) {
        return p.getEquippedStack(EquipmentSlot.HEAD).isOf(HELMET)
                && p.getEquippedStack(EquipmentSlot.CHEST).isOf(CHESTPLATE)
                && p.getEquippedStack(EquipmentSlot.LEGS).isOf(LEGGINGS)
                && p.getEquippedStack(EquipmentSlot.FEET).isOf(BOOTS);
    }

    public static boolean powered(ServerPlayerEntity p) {
        return hasFullSuit(p) || SERUM_EXPIRY.getOrDefault(p.getUuid(), 0L) > p.getServerWorld().getTime();
    }

    public static void activateSerum(ServerPlayerEntity p) {
        long now = p.getServerWorld().getTime();
        SERUM_EXPIRY.put(p.getUuid(), now + SERUM_TICKS);
        p.sendMessage(Text.literal("§bCOMPOUND V! §fСверхспособности на 3 минуты!"), true);
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1f, 1.25f);
        p.getServerWorld().spawnParticles(GOLD_SPARK, p.getX(), p.getBodyY(.6), p.getZ(), 40, .55, .9, .55, .03);
    }

    public static boolean executeAbility(ServerPlayerEntity p, byte ability) {
        if (!p.isAlive() || p.isSpectator()) return false;
        if (!powered(p)) {
            p.sendMessage(Text.literal("§cНужен полный костюм Хоумлендера или Compound V!"), true);
            return false;
        }
        ServerWorld world = p.getServerWorld();
        UUID key = p.getUuid();
        if (ability == 1) {
            if (FLIGHT_SELECTED.remove(key)) {
                endFlight(p);
                p.sendMessage(Text.literal("§7Полёт выключен"), true);
            } else {
                FLIGHT_SELECTED.add(key);
                p.sendMessage(Text.literal("§bПОЛЁТ включён — двойной пробел!"), true);
            }
            return true;
        }
        final int[] cooldown = {18, 0, 90, 35, 26, 100};
        long now = world.getTime();
        Map<Byte, Long> playerCooldowns = COOLDOWNS.computeIfAbsent(key, k -> new HashMap<>());
        long remaining = playerCooldowns.getOrDefault(ability, 0L) - now;
        if (remaining > 0) {
            p.sendMessage(Text.literal("§7Перезарядка: " + ((remaining + 19) / 20) + " с"), true);
            return false;
        }
        if (ability == 5 && p.isOnGround()) {
            p.sendMessage(Text.literal("§7Для суперприземления сначала взлети."), true);
            return false;
        }
        boolean success = switch (ability) {
            case 0 -> { fireLaser(world, p, p.getEyePos(), p.getRotationVec(1.0f), 20f, false); yield true; }
            case 2 -> { shockwave(world, p, p.getPos(), 9, 10f, 2.1); yield true; }
            case 3 -> { dash(p); yield true; }
            case 4 -> powerPunch(world, p);
            case 5 -> { beginLanding(p); yield true; }
            default -> false;
        };
        if (success) playerCooldowns.put(ability, now + cooldown[ability]);
        return success;
    }

    private static void endFlight(ServerPlayerEntity p) {
        if (p.isCreative() || p.isSpectator()) return;
        PlayerAbilities abilities = p.getAbilities();
        if (abilities.allowFlying || abilities.flying) {
            abilities.flying = false;
            abilities.allowFlying = false;
            abilities.setFlySpeed(.05f);
            p.sendAbilitiesUpdate();
        }
    }

    private static void serverTick(MinecraftServer server) {
        long ticks = server.getTicks();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID id = player.getUuid();
            boolean hasPower = powered(player);
            boolean diving = LANDING.contains(id);
            if (hasPower && ticks % 40 == 0) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 85, 2, true, false));
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 85, 2, true, false));
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 85, 1, true, false));
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 85, 0, true, false));
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 85, 0, true, false));
            }
            if (hasPower) player.fallDistance = 0;
            if (diving) {
                if (player.isOnGround()) {
                    LANDING.remove(id);
                    shockwave(player.getServerWorld(), player, player.getPos(), 12, 18f, 2.8);
                    player.sendMessage(Text.literal("§6ГЕРОИЧЕСКОЕ ПРИЗЕМЛЕНИЕ!"), true);
                } else {
                    player.setVelocity(player.getVelocity().x, Math.min(-1.5, player.getVelocity().y), player.getVelocity().z);
                }
            }
            boolean needFlight = hasPower && FLIGHT_SELECTED.contains(id) && !LANDING.contains(id);
            if (!hasPower) {
                FLIGHT_SELECTED.remove(id);
                LANDING.remove(id);
            }
            if (!player.isCreative() && !player.isSpectator()) {
                PlayerAbilities abilities = player.getAbilities();
                if (needFlight != abilities.allowFlying) {
                    abilities.allowFlying = needFlight;
                    if (!needFlight) abilities.flying = false;
                    abilities.setFlySpeed(needFlight ? .18f : .05f);
                    player.sendAbilitiesUpdate();
                }
            }
        }
    }

    private static void fireLaser(ServerWorld world, LivingEntity shooter, Vec3d eyes, Vec3d direction,
                                  float damage, boolean playersOnly) {
        Vec3d dir = direction.normalize();
        if (dir.lengthSquared() < .01) return;
        Vec3d target = eyes.add(dir.multiply(LASER_RANGE));
        BlockHitResult hit = world.raycast(new RaycastContext(
                eyes, target, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, shooter));
        if (hit.getType() != HitResult.Type.MISS) target = hit.getPos();
        final Vec3d end = target;
        final double distance = eyes.distanceTo(end);

        Vec3d right = new Vec3d(-dir.z, 0, dir.x);
        if (right.lengthSquared() < .005) right = new Vec3d(1, 0, 0);
        right = right.normalize();
        for (double d = .2; d <= distance; d += .65) {
            Vec3d spot = eyes.add(dir.multiply(d));
            // Two red streaks, not a huge particle packet flood.
            for (int eye = -1; eye <= 1; eye += 2) {
                Vec3d laser = spot.add(right.multiply(.13 * eye));
                world.spawnParticles(RED_LASER, laser.x, laser.y, laser.z, 1, 0, 0, 0, 0);
            }
            if (((int) (d * 10)) % 11 < 6) {
                world.spawnParticles(ParticleTypes.FLAME, spot.x, spot.y, spot.z, 1, 0, 0, 0, 0);
            }
        }
        if (hit.getType() == HitResult.Type.BLOCK) {
            world.spawnParticles(ParticleTypes.LAVA, end.x, end.y, end.z, 6, .22, .22, .22, .02);
        }

        int victims = 0;
        for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class,
                new Box(eyes, end).expand(1.8), targetEntity -> targetEntity.isAlive() && targetEntity != shooter)) {
            if (playersOnly && !(entity instanceof PlayerEntity)) continue;
            if (entity instanceof PlayerEntity player && (player.isSpectator() || player.isCreative())) continue;
            Vec3d center = entity.getBoundingBox().getCenter();
            double projected = center.subtract(eyes).dotProduct(dir);
            if (projected < 0 || projected > distance) continue;
            double beamDistance = center.squaredDistanceTo(eyes.add(dir.multiply(projected)));
            double radius = .60 + entity.getWidth() * .5;
            if (beamDistance > radius * radius) continue;
            boolean wasHit = entity.damage(shooter instanceof PlayerEntity p
                    ? shooter.getDamageSources().playerAttack(p)
                    : shooter.getDamageSources().mobAttack(shooter), damage);
            if (!wasHit) continue;
            entity.setOnFireFor(4);
            entity.addVelocity(dir.x * .55, .16, dir.z * .55);
            entity.velocityModified = true;
            if (++victims >= 8) break;
        }
        world.playSound(null, shooter.getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 2.0f, .52f);
    }

    private static void shockwave(ServerWorld world, LivingEntity origin, Vec3d center,
                                  double radius, float damage, double force) {
        int hit = 0;
        for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class,
                new Box(center, center).expand(radius), e -> e.isAlive() && e != origin)) {
            if (entity instanceof PlayerEntity player && (player.isCreative() || player.isSpectator())) continue;
            Vec3d delta = entity.getPos().subtract(center);
            double dist = delta.length();
            if (dist > radius || dist < .01) continue;
            entity.damage(origin instanceof PlayerEntity p
                    ? origin.getDamageSources().playerAttack(p)
                    : origin.getDamageSources().mobAttack(origin), damage * (float) (1 - .45 * dist / radius));
            Vec3d push = delta.normalize().multiply(force * (1 - .4 * dist / radius));
            entity.addVelocity(push.x, Math.max(.42, push.y + .62), push.z);
            entity.velocityModified = true;
            hit++;
            if (hit >= 50) break; // prevent griefing/lag with enormous mob farms
        }
        for (int r = 1; r <= (int) radius; r += 2) {
            for (int i = 0; i < 36; i++) {
                double angle = Math.PI * 2 * i / 36;
                world.spawnParticles(GOLD_SPARK,
                        center.x + Math.cos(angle) * r, center.y + .25,
                        center.z + Math.sin(angle) * r, 1, 0, 0, 0, 0);
            }
        }
        world.spawnParticles(ParticleTypes.EXPLOSION, center.x, center.y + 1, center.z, 4, 1.7, .2, 1.7, 0);
        world.playSound(null, BlockPos.ofFloored(center), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.2f, .8f);
    }

    private static void dash(ServerPlayerEntity p) {
        Vec3d look = p.getRotationVec(1.0f).normalize();
        Vec3d velocity = look.multiply(2.5).add(0, .20, 0);
        p.setVelocity(velocity);
        p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
        p.getServerWorld().spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + .6, p.getZ(),
                16, .28, .28, .28, .1);
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.PLAYERS, 1f, 1.7f);
    }

    private static boolean powerPunch(ServerWorld world, ServerPlayerEntity p) {
        Vec3d start = p.getEyePos();
        Vec3d direction = p.getRotationVec(1.0f).normalize();
        LivingEntity selected = null;
        double nearest = Double.MAX_VALUE;
        for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class,
                p.getBoundingBox().expand(6), e -> e.isAlive() && e != p)) {
            Vec3d delta = entity.getBoundingBox().getCenter().subtract(start);
            double distance = delta.length();
            if (distance > 5.5 || distance < .01) continue;
            if (delta.normalize().dotProduct(direction) < .84) continue;
            if (distance < nearest) { nearest = distance; selected = entity; }
        }
        if (selected == null) {
            p.sendMessage(Text.literal("§7В радиусе 5 блоков перед тобой нет цели."), true);
            return false;
        }
        selected.damage(p.getDamageSources().playerAttack(p), 26f);
        selected.addVelocity(direction.x * 2.6, .75, direction.z * 2.6);
        selected.velocityModified = true;
        world.spawnParticles(ParticleTypes.CRIT, selected.getX(), selected.getBodyY(.5), selected.getZ(),
                30, .5, .6, .5, .25);
        world.playSound(null, selected.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1.8f, .5f);
        return true;
    }

    private static void beginLanding(ServerPlayerEntity p) {
        FLIGHT_SELECTED.remove(p.getUuid());
        LANDING.add(p.getUuid());
        endFlight(p);
        p.setVelocity(p.getVelocity().x * .2, -2.0, p.getVelocity().z * .2);
        p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
        p.sendMessage(Text.literal("§6СУПЕРПРИЗЕМЛЕНИЕ!"), true);
    }

    private static void bossTick(ServerWorld world) {
        if (world.getTime() % 100 != 0) return;
        Set<UUID> seen = new HashSet<>();
        // Only search loaded areas around players. Avoid world-wide entity iteration.
        for (ServerPlayerEntity observer : world.getPlayers()) {
            if (observer.isSpectator() || observer.isCreative()) continue;
            for (ZombieEntity boss : world.getEntitiesByClass(ZombieEntity.class,
                    observer.getBoundingBox().expand(35), z -> z.isAlive() && z.getCommandTags().contains(BOSS_TAG))) {
                if (!seen.add(boss.getUuid())) continue;
                ServerPlayerEntity target = null;
                double best = 30 * 30;
                for (ServerPlayerEntity candidate : world.getPlayers()) {
                    if (candidate.isSpectator() || candidate.isCreative()) continue;
                    double dist = candidate.squaredDistanceTo(boss);
                    if (dist < best) { best = dist; target = candidate; }
                }
                if (target != null) {
                    Vec3d direction = target.getEyePos().subtract(boss.getEyePos());
                    fireLaser(world, boss, boss.getEyePos(), direction, 8f, true);
                }
            }
        }
    }

    private static final class CompoundVItem extends Item {
        private CompoundVItem(Settings settings) { super(settings); }

        @Override
        public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
            ItemStack stack = user.getStackInHand(hand);
            if (!world.isClient && user instanceof ServerPlayerEntity player) {
                activateSerum(player);
                if (!user.isCreative()) stack.decrement(1);
            }
            return TypedActionResult.success(stack, world.isClient());
        }
    }
}
