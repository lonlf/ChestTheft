package com.lonleaf.chesttheft.recipe;

import com.lonleaf.chesttheft.ChestTheft;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.item.CrossPluginItemUtil;
import com.lonleaf.chesttheft.item.ItemConfigManager;
import com.lonleaf.chesttheft.item.ItemDefinition;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemType;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 配方管理器：已配对钥匙 → 未配对钥匙（recipe.key-crafting-unpair-enabled 控制）。
 * 合成判定按网格内容（同款已配对钥匙）而非配方材质匹配，外部插件物品的钥匙同样可拆解。
 */
public class RecipeManager implements Listener {

    /** 合成结果槽在合成容器中的槽位号。 */
    private static final int RESULT_SLOT = 0;

    private final ChestTheft plugin;
    private final PluginConfig config;
    private final ItemManager itemManager;
    private final ItemConfigManager itemConfigManager;
    /** 已配对钥匙复原配方的命名空间键。 */
    private NamespacedKey unpairKey;

    public RecipeManager(ChestTheft plugin, PluginConfig config,
                         ItemManager itemManager, ItemConfigManager itemConfigManager) {
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
        this.itemConfigManager = itemConfigManager;
    }

    /** 注册配方（幂等：先移除同名旧配方）；返回 false 表示未注册成功。 */
    public boolean registerRecipes() {
        if (!config.isKeyUnpairRecipeEnabled()) {
            return false;
        }
        unpairKey = new NamespacedKey(plugin, "key_unpair");
        Bukkit.removeRecipe(unpairKey);
        ItemStack output = itemManager.createDefault(ItemType.KEY, 1);
        if (output == null) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_RECIPE_UNPAIR_NO_ITEM));
            return false;
        }
        ShapelessRecipe recipe = new ShapelessRecipe(unpairKey, output);
        recipe.addIngredient(new RecipeChoice.MaterialChoice(new ArrayList<>(collectKeyMaterials())));
        return Bukkit.addRecipe(recipe);
    }

    /** 移除配方（插件卸载时调用）。 */
    public void unregisterRecipes() {
        if (unpairKey != null) {
            Bukkit.removeRecipe(unpairKey);
        }
    }

    /** /ct reload：物品定义变化后重建配方（材料集合随之更新）。 */
    public void reloadRecipes() {
        if (config.isKeyUnpairRecipeEnabled()) {
            registerRecipes();
        } else {
            unregisterRecipes();
        }
    }

    /** 合成预览：网格为同款已配对钥匙时显示该款未配对钥匙，其余情况清空结果阻止合成。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!config.isKeyUnpairRecipeEnabled()) {
            return;
        }
        CraftingInventory inventory = event.getInventory();
        boolean ours = isUnpairRecipe(event);
        if (!ours && event.getRecipe() != null) {
            return; // 其他配方已匹配：不干预
        }
        String keyId = pairedKeyId(inventory.getMatrix());
        if (keyId == null) {
            if (ours) {
                inventory.setResult(null); // 材质被本插件配方误命中（如普通金粒）：不产出钥匙
            }
            return;
        }
        int count = keyCount(inventory.getMatrix());
        ItemStack result = itemManager.create(keyId, count);
        inventory.setResult(result != null && count <= result.getMaxStackSize() ? result : null);
    }

    /** 取出结果：取消原版合成并手动拆解（不依赖配方材质匹配，因此外部物品钥匙同样可拆解）。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResultClick(InventoryClickEvent event) {
        if (!config.isKeyUnpairRecipeEnabled() || event.getRawSlot() != RESULT_SLOT) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (!(top instanceof CraftingInventory inventory) || event.getClickedInventory() != top) {
            return;
        }
        String keyId = pairedKeyId(inventory.getMatrix());
        if (keyId == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            unpair(player, inventory, keyId);
        }
    }

    /** 兜底：产出为本插件未配对钥匙的合成一律拦截，合法网格则手动拆解（防物品复制）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent event) {
        if (!config.isKeyUnpairRecipeEnabled()) {
            return;
        }
        CraftingInventory inventory = event.getInventory();
        ItemStack result = inventory.getResult();
        if (result == null || !itemManager.isType(result, ItemType.KEY)
                || itemManager.getPairedLock(result) != null) {
            return; // 不是本插件的未配对钥匙产出
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String keyId = pairedKeyId(inventory.getMatrix());
        if (keyId != null) {
            unpair(player, inventory, keyId);
        }
    }

    /** 手动拆解：清空网格中的同款已配对钥匙，按数量产出该款未配对钥匙（多余掉落脚下）。 */
    private void unpair(Player player, CraftingInventory inventory, String keyId) {
        int count = keyCount(inventory.getMatrix());
        ItemStack[] matrix = inventory.getMatrix();
        for (int i = 0; i < matrix.length; i++) {
            if (isPairedKey(matrix[i])) {
                matrix[i] = null;
            }
        }
        inventory.setResult(null);
        inventory.setMatrix(matrix);
        ItemStack template = itemManager.create(keyId, 1);
        if (template != null) {
            int max = Math.max(1, template.getMaxStackSize());
            for (int remaining = count; remaining > 0; remaining -= max) {
                ItemStack give = template.clone();
                give.setAmount(Math.min(remaining, max));
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(give);
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
        }
        player.updateInventory();
    }

    /** 网格中同款已配对钥匙的定义 ID；含非已配对物品或混用不同款钥匙时返回 null。 */
    @Nullable
    private String pairedKeyId(ItemStack[] matrix) {
        String id = null;
        for (ItemStack slot : matrix) {
            if (slot == null || slot.getType().isAir()) {
                continue;
            }
            if (!isPairedKey(slot)) {
                return null;
            }
            String slotId = itemManager.getId(slot);
            if (slotId == null) {
                return null;
            }
            if (id == null) {
                id = slotId;
            } else if (!id.equals(slotId)) {
                return null;
            }
        }
        return id;
    }

    /** 网格中已配对钥匙的总数量（调用前应已确认网格合法）。 */
    private int keyCount(ItemStack[] matrix) {
        int count = 0;
        for (ItemStack slot : matrix) {
            if (isPairedKey(slot)) {
                count += slot.getAmount();
            }
        }
        return count;
    }

    /** 事件对应的配方是否为本类注册的配方。 */
    private boolean isUnpairRecipe(PrepareItemCraftEvent event) {
        return unpairKey != null
                && event.getRecipe() instanceof Keyed keyed && keyed.getKey().equals(unpairKey);
    }

    /** 是否为已配对钥匙（KEY 类型且带配对位置数据）。 */
    private boolean isPairedKey(ItemStack item) {
        return item != null && itemManager.isType(item, ItemType.KEY)
                && itemManager.getPairedLock(item) != null;
    }

    /** 收集所有钥匙定义的材料（含外部插件物品的实际材质）；无定义时回退默认材料。 */
    private Set<Material> collectKeyMaterials() {
        Set<Material> materials = new HashSet<>();
        for (ItemDefinition def : itemConfigManager.getDefinitions()) {
            if (def.getType() == ItemType.KEY) {
                materials.add(def.getMaterial());
                String externalId = def.getExternalId();
                if (externalId != null) {
                    ItemStack base = CrossPluginItemUtil.getItem(externalId, null);
                    if (base != null) {
                        materials.add(base.getType());
                    }
                }
            }
        }
        if (materials.isEmpty()) {
            materials.add(ItemType.KEY.getDefaultMaterial());
        }
        return materials;
    }
}
