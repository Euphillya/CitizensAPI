package net.citizensnpcs.api;

import java.io.File;

import net.citizensnpcs.api.util.SpigotUtil;
import net.citizensnpcs.api.util.schedulers.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import net.citizensnpcs.api.ai.speech.SpeechContext;
import net.citizensnpcs.api.command.CommandManager;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCDataStore;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.npc.NPCSelector;
import net.citizensnpcs.api.npc.templates.TemplateRegistry;
import net.citizensnpcs.api.trait.TraitFactory;

/**
 * Contains methods used in order to utilize the Citizens API.
 */
public final class CitizensAPI {

    private static SchedulerAdapter scheduler;

    private CitizensAPI() {
    }

    /**
     * Creates a new <em>anonymous</em> {@link NPCRegistry} with its own set of {@link NPC}s. This is not stored by the
     * Citizens plugin.
     *
     * @since 2.0.8
     * @param store
     *            The {@link NPCDataStore} to use with the registry
     * @return A new anonymous NPCRegistry that is not accessible via {@link #getNamedNPCRegistry(String)}
     */
    public static NPCRegistry createAnonymousNPCRegistry(NPCDataStore store) {
        return getImplementation().createAnonymousNPCRegistry(store);
    }

    /**
     * Creates a new {@link NPCRegistry} with its own set of {@link NPC}s that does not save to disk.
     */
    public static NPCRegistry createInMemoryNPCRegistry(String name) {
        return getImplementation().createNamedNPCRegistry(name, new MemoryNPCDataStore());
    }

    /**
     * Creates a new {@link NPCRegistry} with its own set of {@link NPC}s. This is stored in memory with the Citizens
     * plugin, and can be accessed via {@link #getNamedNPCRegistry(String)}.
     *
     * @param name
     *            The plugin name
     * @param store
     *            The {@link NPCDataStore} to use with the registry
     * @since 2.0.8
     * @return A new NPCRegistry, that can also be retrieved via {@link #getNamedNPCRegistry(String)}
     */
    public static NPCRegistry createNamedNPCRegistry(String name, NPCDataStore store) {
        return getImplementation().createNamedNPCRegistry(name, store);
    }

    public static CommandManager getCommandManager() {
        return getImplementation().getCommandManager();
    }

    /**
     * @return The data folder of the current implementation
     */
    public static File getDataFolder() {
        return getImplementation().getDataFolder();
    }

    /**
     * @return The default NPC selector
     */
    public static NPCSelector getDefaultNPCSelector() {
        return getImplementation().getDefaultNPCSelector();
    }

    private static CitizensPlugin getImplementation() {
        if (instance == null)
            throw new IllegalStateException("no implementation set");
        return instance;
    }

    private static ClassLoader getImplementationClassLoader() {
        return getImplementation().getOwningClassLoader();
    }

    public static LocationLookup getLocationLookup() {
        return getImplementation().getLocationLookup();
    }

    /**
     * Retrieves the {@link NPCRegistry} previously created via {@link #createNamedNPCRegistry(String, NPCDataStore)}
     * with the given name, or null if not found.
     *
     * @param name
     *            The registry name
     * @since 2.0.8
     * @return A NPCRegistry previously created via {@link #createNamedNPCRegistry(String, NPCDataStore)}, or null if
     *         not found
     */
    public static NPCRegistry getNamedNPCRegistry(String name) {
        return getImplementation().getNamedNPCRegistry(name);
    }

    public static NMSHelper getNMSHelper() {
        return getImplementation().getNMSHelper();
    }

    public static Iterable<NPCRegistry> getNPCRegistries() {
        return getImplementation().getNPCRegistries();
    }

    /**
     * Gets the current implementation's <em>default</em> {@link NPCRegistry}.
     *
     * @return The NPC registry
     */
    public static NPCRegistry getNPCRegistry() {
        return getImplementation().getNPCRegistry();
    }

    /**
     * @return The current {@link Plugin} providing an implementation
     */
    public static Plugin getPlugin() {
        return getImplementation();
    }

    public static TemplateRegistry getTemplateRegistry() {
        return getImplementation().getTemplateRegistry();
    }

    /**
     * Gets the current implementation's <em>default</em> <em>temporary</em> {@link NPCRegistry}.
     *
     * @return The temporary NPC registry
     */
    public static NPCRegistry getTemporaryNPCRegistry() {
        return getImplementation().getTemporaryNPCRegistry();
    }

    /**
     * Gets the current implementation's {@link TraitFactory}.
     *
     * @see CitizensPlugin
     * @return Citizens trait factory
     */
    public static TraitFactory getTraitFactory() {
        return getImplementation().getTraitFactory();
    }

    /**
     * @return Whether a Citizens implementation is currently present
     */
    public static boolean hasImplementation() {
        return instance != null;
    }

    /**
     * A helper method for registering events using the current implementation's {@link Plugin}.
     *
     * @see #getPlugin()
     * @param listener
     *            The listener to register events for
     */
    public static void registerEvents(Listener listener) {
        if (Bukkit.getServer() != null && getPlugin() != null) {
            Bukkit.getPluginManager().registerEvents(listener, getPlugin());
        }
    }

    /**
     * Removes any previously created {@link NPCRegistry} stored under the given name.
     *
     * @since 2.0.8
     * @param name
     *            The name previously given to {@link #createNamedNPCRegistry(String, NPCDataStore)}
     */
    public static void removeNamedNPCRegistry(String name) {
        getImplementation().removeNamedNPCRegistry(name);
    }

    /**
     * Sets the current Citizens implementation.
     *
     * @param implementation
     *            The new implementation
     */
    public static void setImplementation(CitizensPlugin implementation) {
        if (implementation != null && hasImplementation()) {
            getImplementation().onImplementationChanged();
        }
        instance = implementation;
    }

    /**
     * The new scheduler that works on Folia and Spigot
     * @return scheduler Folia or Spigot
     */
    public static SchedulerAdapter getScheduler() {
        if (scheduler == null) {
            if (SpigotUtil.isFoliaServer()) {
                scheduler = new net.citizensnpcs.api.util.schedulers.adapter.FoliaScheduler(getPlugin());
            } else {
                scheduler = new net.citizensnpcs.api.util.schedulers.adapter.SpigotScheduler(getPlugin());
            }
        }
        return scheduler;
    }

    /**
     * Shuts down any resources currently being held.
     */
    public static void shutdown() {
        instance = null;
    }

    public static void talk(SpeechContext context) {
        getImplementation().talk(context);
    }

    private static volatile CitizensPlugin instance = null;
}