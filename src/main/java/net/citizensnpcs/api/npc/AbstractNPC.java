package net.citizensnpcs.api.npc;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.GoalController;
import net.citizensnpcs.api.ai.SimpleGoalController;
import net.citizensnpcs.api.ai.speech.SpeechController;
import net.citizensnpcs.api.ai.speech.event.NPCSpeechEvent;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCAddTraitEvent;
import net.citizensnpcs.api.event.NPCCloneEvent;
import net.citizensnpcs.api.event.NPCRemoveEvent;
import net.citizensnpcs.api.event.NPCRemoveTraitEvent;
import net.citizensnpcs.api.event.NPCRenameEvent;
import net.citizensnpcs.api.event.NPCTeleportEvent;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.ItemStorage;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.api.util.RemoveReason;
import net.citizensnpcs.api.util.SpigotUtil;

public abstract class AbstractNPC implements NPC {
    private final List<String> clearSaveData = Lists.newArrayList();
    protected Object coloredNameComponentCache;
    protected String coloredNameStringCache;
    private final GoalController goalController = new SimpleGoalController();
    private final int id;
    private Supplier<ItemStack> itemProvider = () -> {
        Material id = data().has(NPC.Metadata.ITEM_ID)
                ? Material.getMaterial(data().<String> get(NPC.Metadata.ITEM_ID), false)
                : null;
        int data = data().get(NPC.Metadata.ITEM_DATA, data().get("falling-block-data", 0));
        if (id == Material.AIR || id == null) {
            id = Material.STONE;
            Messaging.severe(getId(), "invalid Material: converted to stone");
        }
        return new org.bukkit.inventory.ItemStack(id, data().get(NPC.Metadata.ITEM_AMOUNT, 1), (short) data);
    };
    private final MetadataStore metadata = new SimpleMetadataStore();
    private String name;
    private final NPCRegistry registry;
    private final List<Runnable> runnables = Lists.newArrayList();
    private final SpeechController speechController = context -> {
        context.setTalker(getEntity());
        NPCSpeechEvent event = new NPCSpeechEvent(context);
        Bukkit.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled())
            return;
        CitizensAPI.talk(context);
    };
    protected final Map<Class<? extends Trait>, Trait> traits = Maps.newHashMap();
    private final UUID uuid;

    protected AbstractNPC(UUID uuid, int id, String name, NPCRegistry registry) {
        this.uuid = uuid;
        this.id = id;
        this.registry = registry;
        setNameInternal(name);
        CitizensAPI.getTraitFactory().addDefaultTraits(this);
    }

    @Override
    public void addRunnable(Runnable runnable) {
        runnables.add(runnable);
    }

    @Override
    public void addTrait(Class<? extends Trait> clazz) {
        addTrait(getTraitFor(clazz));
    }

    @Override
    public void addTrait(Trait trait) {
        if (trait == null) {
            Messaging.severe("Cannot register a null trait. Was it registered properly?");
            return;
        }
        if (trait.getNPC() == null) {
            trait.linkToNPC(this);
        }
        // if an existing trait is being replaced, we need to remove the
        // currently registered runnable to avoid conflicts
        Class<? extends Trait> clazz = trait.getClass();
        Trait replaced = traits.get(clazz);

        if (CitizensAPI.getPlugin().isEnabled()) {
            Bukkit.getPluginManager().registerEvents(trait, CitizensAPI.getPlugin());
        }
        traits.put(clazz, trait);
        if (isSpawned()) {
            trait.onSpawn();
        }
        if (trait.isRunImplemented()) {
            if (replaced != null) {
                runnables.remove(replaced);
            }
            runnables.add(trait);
        }
        Bukkit.getPluginManager().callEvent(new NPCAddTraitEvent(this, trait));
    }

    @Override
    public NPC clone() {
        return copy();
    }

    @Override
    public NPC copy() {
        NPC copy = registry.createNPC(getOrAddTrait(MobType.class).getType(), getRawName());
        DataKey key = new MemoryDataKey();
        save(key);
        copy.load(key);

        for (Trait trait : copy.getTraits()) {
            trait.onCopy();
        }
        Bukkit.getPluginManager().callEvent(new NPCCloneEvent(this, copy));
        return copy;
    }

    @Override
    public MetadataStore data() {
        return metadata;
    }

    @Override
    public void destroy() {
        Bukkit.getPluginManager().callEvent(new NPCRemoveEvent(this));
        runnables.clear();
        for (Trait trait : traits.values()) {
            HandlerList.unregisterAll(trait);
            trait.onRemove(RemoveReason.DESTROYED);
        }
        traits.clear();
        goalController.clear();
        registry.deregister(this);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        AbstractNPC other = (AbstractNPC) obj;
        if (!Objects.equals(uuid, other.uuid))
            return false;
        return true;
    }

    @Override
    public GoalController getDefaultGoalController() {
        return goalController;
    }

    @Override
    public SpeechController getDefaultSpeechController() {
        return speechController;
    }

    protected EntityType getEntityType() {
        return isSpawned() ? getEntity().getType() : getOrAddTrait(MobType.class).getType();
    }

    @Override
    public String getFullName() {
        int nameLength = SpigotUtil.getMaxNameLength(getEntityType());
        String replaced = Placeholders.replaceName(
                coloredNameStringCache != null ? coloredNameStringCache : Messaging.parseComponents(name), null, this);
        if (Messaging.stripColor(replaced).length() > nameLength) {
            Messaging.severe("ID", id, "created with name length greater than " + nameLength + ", truncating", replaced,
                    "to", replaced.substring(0, nameLength));
            replaced = replaced.substring(0, nameLength);
        }
        return replaced;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public Supplier<ItemStack> getItemProvider() {
        return itemProvider;
    }

    @Override
    public UUID getMinecraftUniqueId() {
        if (getEntityType() == EntityType.PLAYER) {
            UUID uuid = getUniqueId();
            if (uuid.version() == 4) { // set version to 2
                long msb = uuid.getMostSignificantBits();
                msb &= ~0x0000000000004000L;
                msb |= 0x0000000000002000L;
                return new UUID(msb, uuid.getLeastSignificantBits());
            }
        }
        return getUniqueId();
    }

    @Override
    public String getName() {
        return Messaging.stripColor(coloredNameStringCache);
    }

    @Override
    public <T extends Trait> T getOrAddTrait(Class<T> clazz) {
        Trait trait = traits.get(clazz);
        if (trait == null) {
            trait = getTraitFor(clazz);
            addTrait(trait);
        }
        return clazz.cast(trait);
    }

    @Override
    public NPCRegistry getOwningRegistry() {
        return registry;
    }

    @Override
    public String getRawName() {
        return name;
    }

    @Override
    public <T extends Trait> T getTrait(Class<T> trait) {
        return getOrAddTrait(trait);
    }

    protected Trait getTraitFor(Class<? extends Trait> clazz) {
        return CitizensAPI.getTraitFactory().getTrait(clazz);
    }

    @Override
    public <T extends Trait> T getTraitNullable(Class<T> clazz) {
        return clazz.cast(traits.get(clazz));
    }

    @Override
    public Iterable<Trait> getTraits() {
        return traits.values();
    }

    @Override
    public UUID getUniqueId() {
        return uuid;
    }

    @Override
    public int hashCode() {
        return 31 + uuid.hashCode();
    }

    @Override
    public boolean hasTrait(Class<? extends Trait> trait) {
        return traits.containsKey(trait);
    }

    @Override
    public void load(final DataKey root) {
        setNameInternal(root.getString("name"));
        if (root.keyExists("itemprovider")) {
            ItemStack item = ItemStorage.loadItemStack(root.getRelative("itemprovider"));
            setItemProvider(() -> item.clone());
        }
        metadata.loadFrom(root.getRelative("metadata"));

        String traitNames = root.getString("traitnames");
        if (traitNames.isEmpty()) {
            Messaging.severe("Corrupted savedata (empty trait names) for NPC", this);
            return;
        }
        Set<String> loading = Sets.newHashSet(Splitter.on(',').omitEmptyStrings().split(traitNames));
        for (String key : PRIORITY_TRAITS) {
            DataKey pkey = root.getRelative("traits." + key);
            if (pkey.keyExists()) {
                loadTraitFromKey(pkey);
                loading.remove(key);
            }
        }
        for (DataKey key : Iterables.transform(loading, k -> root.getRelative("traits." + k))) {
            loadTraitFromKey(key);
        }
    }

    private void loadTraitFromKey(DataKey traitKey) {
        Class<? extends Trait> clazz = CitizensAPI.getTraitFactory().getTraitClass(traitKey.name());
        Trait trait;
        if (hasTrait(clazz)) {
            trait = getTraitNullable(clazz);
        } else {
            trait = CitizensAPI.getTraitFactory().getTrait(clazz);
            if (trait == null) {
                Messaging.severeTr("citizens.notifications.trait-load-failed", traitKey.name(), getId());
                return;
            }
            addTrait(trait);
        }
        try {
            PersistenceLoader.load(trait, traitKey);
            trait.load(traitKey);
        } catch (Throwable ex) {
            if (Messaging.isDebugging()) {
                ex.printStackTrace();
            }
            Messaging.logTr("citizens.notifications.trait-load-failed", traitKey.name(), getId());
        }
    }

    @Override
    public void removeTrait(Class<? extends Trait> traitClass) {
        Trait trait = traits.remove(traitClass);
        if (trait != null) {
            Bukkit.getPluginManager().callEvent(new NPCRemoveTraitEvent(this, trait));
            clearSaveData.add("traits." + trait.getName());
            if (trait.isRunImplemented()) {
                runnables.remove(trait);
            }
            HandlerList.unregisterAll(trait);
            trait.onRemove(RemoveReason.REMOVAL);
        }
    }

    @Override
    public boolean requiresNameHologram() {
        return (name.length() > 16 && getEntityType() == EntityType.PLAYER)
                || data().get(NPC.Metadata.ALWAYS_USE_NAME_HOLOGRAM, false)
                || (coloredNameStringCache != null && coloredNameStringCache.contains("§x"))
                || !Placeholders.replaceName(name, null, this).equals(name);
    }

    @Override
    public void save(DataKey root) {
        if (!metadata.get(NPC.Metadata.SHOULD_SAVE, true))
            return;

        metadata.saveTo(root.getRelative("metadata"));
        root.setString("name", name);
        root.setString("uuid", uuid.toString());

        if (data().has(NPC.Metadata.ITEM_ID)) {
            ItemStack stack = itemProvider.get();
            ItemStorage.saveItem(root.getRelative("itemprovider"), stack);
        } else {
            root.removeKey("itemprovider");
        }
        Set<String> traitNames = Splitter.on(',').omitEmptyStrings().splitToStream(root.getString("traitnames"))
                .collect(Collectors.toSet());
        for (Trait trait : traits.values()) {
            clearSaveData.remove("traits." + trait.getName());
            traitNames.add(trait.getName());

            DataKey traitKey = root.getRelative("traits." + trait.getName());
            try {
                trait.save(traitKey);
            } catch (Throwable t) {
                Messaging.severe("Saving trait", trait, "failed for NPC", this);
                t.printStackTrace();
                continue;
            }
            try {
                PersistenceLoader.save(trait, traitKey);
            } catch (Throwable t) {
                Messaging.severe("PersistenceLoader failed saving trait", trait, "for NPC", this);
                t.printStackTrace();
                continue;
            }
        }
        for (String clear : clearSaveData) {
            if (clear.startsWith("traits.")) {
                traitNames.remove(clear.replace("traits.", ""));
            }
            root.removeKey(clear);
        }
        root.setString("traitnames", Joiner.on(',').join(traitNames));
        clearSaveData.clear();
    }

    @Override
    public void setItemProvider(Supplier<ItemStack> provider) {
        this.itemProvider = provider;
        ItemStack stack = provider.get();
        if (stack != null) {
            data().set(NPC.Metadata.ITEM_ID, stack.getType().name());
            data().set(NPC.Metadata.ITEM_DATA, stack.getData().getData());
            data().set(NPC.Metadata.ITEM_AMOUNT, stack.getAmount());
        }
    }

    @Override
    public void setName(String name) {
        if (name.equals(this.name))
            return;

        NPCRenameEvent event = new NPCRenameEvent(this, this.name, name);
        Bukkit.getPluginManager().callEvent(event);
        setNameInternal(event.getNewName());

        if (!isSpawned())
            return;

        Entity bukkitEntity = getEntity();

        if (bukkitEntity.getType() == EntityType.PLAYER && !requiresNameHologram()) {
            Location old = bukkitEntity.getLocation();
            despawn(DespawnReason.PENDING_RESPAWN);
            spawn(old);
        }
    }

    protected void setNameInternal(String name) {
        this.name = name;
        coloredNameComponentCache = Messaging.minecraftComponentFromRawMessage(this.name);
        coloredNameStringCache = Messaging.parseComponents(this.name);
    }

    private void teleport(final Entity entity, Location location, int delay, TeleportCause cause) {
        final Entity passenger = entity.getPassenger();
        entity.eject();
        if (!location.getWorld().equals(entity.getWorld())) {
            CitizensAPI.getScheduler().runEntityTaskLater(entity,
                    () -> SpigotUtil.teleportAsync(entity, location, cause), delay++);
        } else {
            SpigotUtil.teleportAsync(entity, location, cause);
        }
        if (passenger == null)
            return;
        teleport(passenger, location, delay++, cause);
        Runnable task = () -> entity.setPassenger(passenger);
        if (!location.getWorld().equals(entity.getWorld())) {
            CitizensAPI.getScheduler().runEntityTaskLater(entity, task, delay);
        } else {
            task.run();
        }
    }

    @Override
    public void teleport(Location location, TeleportCause cause) {
        if (!isSpawned())
            return;
        NPCTeleportEvent event = new NPCTeleportEvent(this, location);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return;
        Entity entity = getEntity();
        while (entity.getVehicle() != null) {
            entity = entity.getVehicle();
        }
        location.getBlock().getChunk();
        teleport(entity, location, 5, cause);
    }

    public void update() {
        // can modify itself during running
        for (int i = 0; i < runnables.size(); i++) {
            runnables.get(i).run();
        }
        if (isSpawned()) {
            goalController.run();
        }
    }

    private static final String[] PRIORITY_TRAITS = { "location", "type" };
}
