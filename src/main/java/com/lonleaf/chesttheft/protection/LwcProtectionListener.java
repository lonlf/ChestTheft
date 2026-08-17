package com.lonleaf.chesttheft.protection;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/**
 * LWC 保护插件集成（软依赖）：通过 ModuleLoader 脚本模块订阅保护注册后回调（onPostRegistration），
 * 已上锁箱子被 LWC 保护且关闭撬锁时自动卸锁（逻辑由 {@link ProtectionListener} 提供）。
 */
public class LwcProtectionListener {

    private final Plugin plugin;
    /** 保护创建回调（自动卸锁逻辑，由 ProtectionListener 提供）。 */
    private final Consumer<Block> protectionCreatedHandler;
    /** LWC 实例引用（Object 持有，避免字段类型解析 LWC 类）。 */
    private Object lwc;

    public LwcProtectionListener(Plugin plugin, Consumer<Block> protectionCreatedHandler) {
        this.plugin = plugin;
        this.protectionCreatedHandler = protectionCreatedHandler;
    }

    /** 订阅 LWC 保护注册后回调（仅 LWC 插件存在时）。 */
    public void register() {
        // 先用 Object 接收插件实例：getPlugin 返回 null 时不进入分支，避免执行 LWC 类强转
        Object lwcPlugin = Bukkit.getPluginManager().getPlugin("LWC");
        if (lwcPlugin != null) {
            lwc = ((com.griefcraft.lwc.LWCPlugin) lwcPlugin).getLWC();
            Object module = createLwcModule();
            ((com.griefcraft.lwc.LWC) lwc).getModuleLoader().registerModule(plugin, (com.griefcraft.scripting.Module) module);
        }
    }

    /** 注销监听（插件禁用时调用）：按插件移除模块。 */
    public void unregister() {
        if (lwc != null) {
            try {
                ((com.griefcraft.lwc.LWC) lwc).getModuleLoader().removeModules(plugin);
            } catch (Exception e) {
                // LWC 已随禁用流程卸载时忽略
            }
            lwc = null;
        }
    }

    /**
     * 创建 LWC 脚本模块：仅保护注册后回调有逻辑，其余方法空实现。
     * 返回 Object 而非 Module（方法签名在类验证期解析，未装 LWC 时会导致类加载失败）；
     * 仅在 LWC 插件存在时调用，此时 LWC 类必已加载。
     */
    private Object createLwcModule() {
        return new com.griefcraft.scripting.Module() {
            @Override
            public void load(com.griefcraft.lwc.LWC lwc) {
            }

            @Override
            public void onReload(com.griefcraft.scripting.event.LWCReloadEvent event) {
            }

            @Override
            public void onAccessRequest(com.griefcraft.scripting.event.LWCAccessEvent event) {
            }

            @Override
            public void onDropItem(com.griefcraft.scripting.event.LWCDropItemEvent event) {
            }

            @Override
            public void onCommand(com.griefcraft.scripting.event.LWCCommandEvent event) {
            }

            @Override
            public void onRedstone(com.griefcraft.scripting.event.LWCRedstoneEvent event) {
            }

            @Override
            public void onDestroyProtection(com.griefcraft.scripting.event.LWCProtectionDestroyEvent event) {
            }

            @Override
            public void onProtectionInteract(com.griefcraft.scripting.event.LWCProtectionInteractEvent event) {
            }

            @Override
            public void onBlockInteract(com.griefcraft.scripting.event.LWCBlockInteractEvent event) {
            }

            @Override
            public void onEntityInteract(com.griefcraft.scripting.event.LWCEntityInteractEvent event) {
            }

            @Override
            public void onRegisterProtection(com.griefcraft.scripting.event.LWCProtectionRegisterEvent event) {
            }

            @Override
            public void onEntityInteractProtection(com.griefcraft.scripting.event.LWCProtectionInteractEntityEvent event) {
            }

            @Override
            public void onPostRegistration(com.griefcraft.scripting.event.LWCProtectionRegistrationPostEvent event) {
                // 保护注册完成后：已上锁的箱子被 LWC 保护时自动卸锁；异常隔离避免影响 LWC 模块分发
                try {
                    com.griefcraft.model.Protection protection = event.getProtection();
                    if (protection != null) {
                        protectionCreatedHandler.accept(protection.getBlock());
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("LWC 保护回调处理失败: " + e.getMessage());
                }
            }

            @Override
            public void onPostRemoval(com.griefcraft.scripting.event.LWCProtectionRemovePostEvent event) {
            }

            @Override
            public void onSendLocale(com.griefcraft.scripting.event.LWCSendLocaleEvent event) {
            }

            @Override
            public void onMagnetPull(com.griefcraft.scripting.event.LWCMagnetPullEvent event) {
            }

            @Override
            public void onRegisterEntity(com.griefcraft.scripting.event.LWCProtectionRegisterEntityEvent event) {
            }
        };
    }
}
