package com.example.colossalchest;

import cn.nukkit.Player;
import cn.nukkit.block.BlockID;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.inventory.InventoryClickEvent;
import cn.nukkit.event.inventory.InventoryCloseEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.inventory.CustomInventory;
import cn.nukkit.inventory.Inventory;
import cn.nukkit.inventory.InventoryHolder;
import cn.nukkit.inventory.InventoryType;
import cn.nukkit.item.Item;
import cn.nukkit.item.ItemID;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.TextFormat;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ColossalChestPlugin extends PluginBase implements Listener {

    private static final int PAGE_SIZE = 45;
    private static final int MIN_CAPACITY = 10;
    private static final int MAX_CAPACITY = 1000;
    private static final int AUTOSAVE_TICKS = 20 * 60;

    private final Map<UUID, InventoryStorage> chests = new HashMap<>();
    private final Map<Inventory, MenuSession> menus = new IdentityHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        loadChests();
        getServer().getScheduler().scheduleRepeatingTask(this, this::saveChests, AUTOSAVE_TICKS);
        getLogger().info(TextFormat.GREEN + "ColossalChest успішно завантажено!");
    }

    @Override
    public void onDisable() {
        saveChests();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("givecolossal")) return false;
        if (args.length < 2) {
            sender.sendMessage(TextFormat.RED + "Використання: /givecolossal <нік> <слоти (10-1000)> [ender]");
            return true;
        }

        Player target = getServer().getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(TextFormat.RED + "Гравець не знайдений на сервері!");
            return true;
        }

        int capacity;
        try {
            capacity = Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(TextFormat.RED + "Вкажіть числове значення для слотів!");
            return true;
        }
        if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY) {
            sender.sendMessage(TextFormat.RED + "Місткість має бути від 10 до 1000!");
            return true;
        }

        boolean ender = args.length >= 3 && args[2].equalsIgnoreCase("ender");
        int chestId = ender ? BlockID.ENDER_CHEST : BlockID.CHEST;
        Item chest = Item.get(chestId);
        chest.setCustomName(TextFormat.BOLD + "" + TextFormat.GOLD + (ender ? "Колосальна ендер-скриня" : "Колосальна скриня") + " (" + capacity + " слотів)");
        CompoundTag tag = chest.getNamedTag();
        if (tag == null) tag = new CompoundTag();
        tag.putInt("ColossalCapacity", capacity);
        tag.putString("ChestUUID", UUID.randomUUID().toString());
        tag.putString("ChestType", ender ? "ender" : "normal");
        chest.setNamedTag(tag);
        target.getInventory().addItem(chest);
        sender.sendMessage(TextFormat.GREEN + (ender ? "Ендер-скриню" : "Скриню") + " на " + capacity + " слотів видано гравцю " + target.getName());
        return true;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        Item item = event.getItem();
        if (item == null || (item.getId() != BlockID.CHEST && item.getId() != BlockID.ENDER_CHEST) || !item.hasCompoundTag()) return;

        CompoundTag tag = item.getNamedTag();
        if (!tag.contains("ColossalCapacity") || !tag.contains("ChestUUID")) return;
        try {
            UUID chestId = UUID.fromString(tag.getString("ChestUUID"));
            int capacity = tag.getInt("ColossalCapacity");
            event.setCancelled(true);
            openColossalMenu(event.getPlayer(), chestId, capacity, 1);
        } catch (IllegalArgumentException ignored) {
            // Ignore a chest with a damaged UUID instead of crashing the event handler.
        }
    }

    private void openColossalMenu(Player player, UUID chestId, int capacity, int page) {
        InventoryStorage storage = chests.computeIfAbsent(chestId, id -> new InventoryStorage(capacity));
        int totalPages = Math.max(1, (capacity + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(1, Math.min(page, totalPages));

        ColossalInventory inventory = new ColossalInventory(player);
        int start = (page - 1) * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < capacity; slot++) {
            inventory.setItem(slot, storage.getItem(start + slot));
        }
        if (page > 1) inventory.setItem(45, navigationItem("Попередня сторінка"));
        inventory.setItem(49, navigationItem("Сторінка " + page + "/" + totalPages));
        if (page < totalPages) inventory.setItem(53, navigationItem("Наступна сторінка"));

        player.addWindow(inventory);
        menus.put(inventory, new MenuSession(chestId, capacity, page, inventory));
    }

    private Item navigationItem(String name) {
        Item item = Item.get(ItemID.PAPER);
        item.setCustomName(TextFormat.YELLOW + name);
        return item;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        MenuSession session = menus.get(event.getInventory());
        if (session == null) return;

        int slot = event.getSlot();
        if (slot >= PAGE_SIZE) {
            event.setCancelled(true);
            if (slot == 45 && session.page > 1) changePage(event.getPlayer(), session, session.page - 1);
            if (slot == 53 && session.page < pageCount(session.capacity)) changePage(event.getPlayer(), session, session.page + 1);
        }
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        MenuSession session = menus.remove(event.getInventory());
        if (session == null) return;
        savePage(session);
    }

    private int pageCount(int capacity) {
        return Math.max(1, (capacity + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void changePage(Player player, MenuSession session, int page) {
        savePage(session);
        menus.remove(session.inventory);
        player.removeWindow(session.inventory);
        openColossalMenu(player, session.chestId, session.capacity, page);
    }

    private void savePage(MenuSession session) {
        InventoryStorage storage = chests.get(session.chestId);
        int start = (session.page - 1) * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < session.capacity; slot++) {
            storage.setItem(start + slot, session.inventory.getItem(slot).clone());
        }
    }

    private void saveChests() {
        for (MenuSession session : menus.values()) savePage(session);
        for (Map.Entry<UUID, InventoryStorage> entry : chests.entrySet()) {
            List<Map<String, Object>> slots = new ArrayList<>();
            for (Item item : entry.getValue().items) {
                Map<String, Object> saved = new LinkedHashMap<>();
                saved.put("id", item.getId());
                saved.put("damage", item.getDamage());
                saved.put("count", item.getCount());
                if (item.hasCompoundTag()) {
                    saved.put("nbt", Base64.getEncoder().encodeToString(item.writeCompoundTag(item.getNamedTag())));
                }
                slots.add(saved);
            }
            getConfig().set("chests." + entry.getKey() + ".slots", slots);
        }
        saveConfig();
    }

    @SuppressWarnings("unchecked")
    private void loadChests() {
        for (String id : getConfig().getSection("chests").getKeys(false)) {
            try {
                UUID chestId = UUID.fromString(id);
                Object rawSlots = getConfig().get("chests." + id + ".slots");
                if (!(rawSlots instanceof List)) continue;
                List<Object> slots = (List<Object>) rawSlots;
                InventoryStorage storage = new InventoryStorage(slots.size());
                for (int slot = 0; slot < slots.size(); slot++) {
                    if (!(slots.get(slot) instanceof Map)) continue;
                    Map<String, Object> saved = (Map<String, Object>) slots.get(slot);
                    int itemId = number(saved.get("id"));
                    int damage = number(saved.get("damage"));
                    int count = number(saved.get("count"));
                    byte[] nbt = saved.containsKey("nbt")
                            ? Base64.getDecoder().decode(String.valueOf(saved.get("nbt"))) : new byte[0];
                    storage.setItem(slot, Item.get(itemId, damage, count, nbt));
                }
                chests.put(chestId, storage);
            } catch (RuntimeException ignored) {
                getLogger().warning("Не вдалося завантажити колосальну скриню " + id);
            }
        }
    }

    private int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static final class ColossalInventory extends CustomInventory {
        private ColossalInventory(InventoryHolder holder) {
            super(holder, InventoryType.DOUBLE_CHEST, null, 54, "Колосальна скриня");
        }
    }

    private static final class MenuSession {
        private final UUID chestId;
        private final int capacity;
        private final int page;
        private final ColossalInventory inventory;

        private MenuSession(UUID chestId, int capacity, int page, ColossalInventory inventory) {
            this.chestId = chestId;
            this.capacity = capacity;
            this.page = page;
            this.inventory = inventory;
        }
    }

    public static final class InventoryStorage {
        private final Item[] items;

        private InventoryStorage(int capacity) {
            items = new Item[capacity];
            for (int slot = 0; slot < capacity; slot++) items[slot] = Item.get(BlockID.AIR);
        }

        private Item getItem(int slot) {
            return slot >= 0 && slot < items.length ? items[slot] : Item.get(BlockID.AIR);
        }

        private void setItem(int slot, Item item) {
            if (slot >= 0 && slot < items.length) items[slot] = item;
        }
    }
}
