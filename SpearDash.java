package dev.speardash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SpearDash extends JavaPlugin implements Listener {

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private NamespacedKey spearKey;

    private double damage;
    private double dashSpeed;
    private int dashTicks;
    private long cooldownMs;
    private double hitRadius;
    private boolean requireSneak;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        spearKey = new NamespacedKey(this, "dash_spear");
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void loadSettings() {
        damage = getConfig().getDouble("damage", 12.0);
        dashSpeed = getConfig().getDouble("dash-speed", 1.6);
        dashTicks = getConfig().getInt("dash-ticks", 7);
        cooldownMs = (long) (getConfig().getDouble("cooldown-seconds", 60.0) * 1000);
        hitRadius = getConfig().getDouble("hit-radius", 1.2);
        requireSneak = getConfig().getBoolean("require-sneak", true);
    }

    // ---------- Custom item ----------

    private Material spearMaterial() {
        for (String name : new String[]{"NETHERITE_SPEAR", "DIAMOND_SPEAR", "IRON_SPEAR", "NETHERITE_SWORD"}) {
            Material m = Material.matchMaterial(name);
            if (m != null) return m;
        }
        return Material.TRIDENT;
    }

    private ItemStack createSpear() {
        ItemStack item = new ItemStack(spearMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Dash Spear", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Crouch + right-click to dash", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("through enemies for 6 hearts", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(spearKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isDashSpear(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(spearKey, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("speardash.give")) {
            sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
            return true;
        }

        Player target;
        if (args.length >= 1) {
            target = getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Usage: /speardash <player>", NamedTextColor.RED));
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createSpear());
        leftover.values().forEach(i -> target.getWorld().dropItemNaturally(target.getLocation(), i));
        sender.sendMessage(Component.text("Gave a Dash Spear to " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    // ---------- Dash ----------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (requireSneak && !player.isSneaking()) return;
        if (!isDashSpear(player.getInventory().getItemInMainHand())) return;
        if (player.getGameMode() == GameMode.SPECTATOR) return;

        event.setCancelled(true);

        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < readyAt) {
            double left = (readyAt - now) / 1000.0;
            player.sendActionBar(Component.text(
                    String.format("Dash ready in %.1fs", left), NamedTextColor.RED));
            return;
        }
        cooldowns.put(player.getUniqueId(), now + cooldownMs);

        startDash(player);
    }

    private void startDash(Player player) {
        Vector direction = player.getLocation().getDirection().normalize();
        Set<UUID> alreadyHit = new HashSet<>();

        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 1.0f, 1.2f);

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || tick >= dashTicks) {
                    cancel();
                    return;
                }
                tick++;

                player.setVelocity(direction.clone().multiply(dashSpeed));
                player.setFallDistance(0f);

                player.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                        player.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0);
                player.getWorld().spawnParticle(Particle.CRIT,
                        player.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0.05);

                for (Entity nearby : player.getNearbyEntities(hitRadius, hitRadius, hitRadius)) {
                    if (!(nearby instanceof LivingEntity target)) continue;
                    if (target.equals(player) || target.isDead()) continue;
                    if (target instanceof Player other
                            && other.getGameMode() == GameMode.SPECTATOR) continue;
                    if (!alreadyHit.add(target.getUniqueId())) continue;

                    target.setNoDamageTicks(0);
                    target.damage(damage, player);

                    target.getWorld().playSound(target.getLocation(),
                            Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.8f);
                    target.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                            target.getLocation().add(0, 1, 0), 8, 0.3, 0.4, 0.3, 0.1);
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }
}
