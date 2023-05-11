package net.citizensnpcs.api.trait.trait;

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.google.common.collect.Sets;

import net.citizensnpcs.api.npc.NPC.NPCUpdate;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;

@TraitName("playerfilter")
public class PlayerFilter extends Trait {
    private Function<Player, Boolean> filter;
    @Persist
    private Set<String> groups = null;
    private final Set<UUID> hiddenPlayers = Sets.newHashSet();
    private BiConsumer<Player, Entity> hideFunction;
    @Persist
    private Mode mode = Mode.DENYLIST;
    @Persist
    private Set<UUID> players = null;
    private BiConsumer<Player, Entity> viewFunction;
    private final Set<UUID> viewingPlayers = Sets.newHashSet();

    public PlayerFilter() {
        super("playerfilter");
    }

    public PlayerFilter(BiConsumer<Player, Entity> hideFunction, BiConsumer<Player, Entity> viewFunction) {
        this();
        this.filter = p -> {
            switch (mode) {
                case DENYLIST:
                    if (players != null && players.contains(p.getUniqueId()))
                        return true;
                    if (groups != null) {
                        net.milkbowl.vault.permission.Permission permission = Bukkit.getServicesManager()
                                .getRegistration(net.milkbowl.vault.permission.Permission.class).getProvider();
                        if (groups.stream().anyMatch(group -> permission.playerInGroup(p, group)))
                            return true;
                    }
                    break;
                case ALLOWLIST:
                    if (players != null && !players.contains(p.getUniqueId()))
                        return true;
                    if (groups != null) {
                        net.milkbowl.vault.permission.Permission permission = Bukkit.getServicesManager()
                                .getRegistration(net.milkbowl.vault.permission.Permission.class).getProvider();
                        if (!groups.stream().anyMatch(group -> permission.playerInGroup(p, group)))
                            return true;
                    }
                    break;
            }
            return false;
        };
        this.hideFunction = hideFunction;
        this.viewFunction = viewFunction;
    }

    /**
     * Hides the NPC from the given permissions group
     */
    public void addGroup(String group) {
        if (groups == null) {
            groups = Sets.newHashSet();
        }
        groups.add(group);
        recalculate();
    }

    /**
     * Hides the NPC from the given Player UUID.
     *
     * @param uuid
     */
    public void addPlayer(UUID uuid) {
        if (players == null) {
            players = Sets.newHashSet();
        }
        players.add(uuid);
        getSet().add(uuid);
        recalculate();
    }

    /**
     * Clears all set UUID filters.
     */
    public void clear() {
        players = null;
        groups = null;
    }

    /**
     * Implementation detail: may change in the future.
     */
    public Set<String> getGroups() {
        return groups;
    }

    private Set<UUID> getInverseSet() {
        return mode == Mode.ALLOWLIST ? viewingPlayers : hiddenPlayers;
    }

    /**
     * Implementation detail: may change in the future.
     */
    public Set<UUID> getPlayerUUIDs() {
        return players;
    }

    private Set<UUID> getSet() {
        return mode == Mode.DENYLIST ? viewingPlayers : hiddenPlayers;
    }

    /**
     * Whether the NPC should be hidden from the given Player
     */
    public boolean isHidden(Player player) {
        return filter == null ? false : filter.apply(player);
    }

    @Override
    public void onDespawn() {
        hiddenPlayers.clear();
        viewingPlayers.clear();
    }

    /**
     * For internal use. Method signature may be changed at any time.
     */
    public boolean onSeenByPlayer(Player player) {
        if (isHidden(player)) {
            this.hiddenPlayers.add(player.getUniqueId());
            return true;
        }
        this.viewingPlayers.add(player.getUniqueId());
        return false;
    }

    /**
     * Explicit recalculation of which {@link Player}s should be viewing the {@link NPC}. Sends hide packets for players
     * that should no longer view the NPC.
     */
    public void recalculate() {
        for (Iterator<UUID> itr = viewingPlayers.iterator(); itr.hasNext();) {
            UUID uuid = itr.next();
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                itr.remove();
                continue;
            }
            if (hideFunction != null && filter.apply(player)) {
                hideFunction.accept(player, npc.getEntity());
                itr.remove();
            }
        }
        for (Iterator<UUID> itr = hiddenPlayers.iterator(); itr.hasNext();) {
            UUID uuid = itr.next();
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                itr.remove();
                continue;
            }
            if (viewFunction != null && !filter.apply(player)) {
                viewFunction.accept(player, npc.getEntity());
                itr.remove();
            }
        }
    }

    /**
     * Unhides the given permissions group
     */
    public void removeGroup(String group) {
        if (groups != null) {
            groups.remove(group);
        }
        recalculate();
    }

    /**
     * Unhides the given Player UUID
     */
    public void removePlayer(UUID uuid) {
        if (players != null) {
            players.remove(uuid);
        }
        getInverseSet().add(uuid);
        recalculate();
    }

    @Override
    public void run() {
        if (!npc.isSpawned() || !npc.isUpdating(NPCUpdate.PACKET))
            return;
        recalculate();
    }

    public void setAllowlist() {
        this.mode = Mode.ALLOWLIST;
        recalculate();
    }

    /**
     * Implementation detail: may change in the future.
     */
    public void setDenylist() {
        this.mode = Mode.DENYLIST;
        recalculate();
    }

    /**
     * Sets the filter function, which returns {@code true} if the {@link NPC} should be hidden from the given
     * {@link Player}.
     */
    public void setPlayerFilter(Function<Player, Boolean> filter) {
        this.filter = filter;
        recalculate();
    }

    /**
     * Implementation detail: may change in the future.
     */
    public void setPlayers(Set<UUID> players) {
        this.players = players == null ? null : Sets.newHashSet(players);
    }

    public enum Mode {
        ALLOWLIST,
        DENYLIST;
    }
}
