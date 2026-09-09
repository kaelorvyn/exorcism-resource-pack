package com.kaelorvyn.exorcismpack.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
        id = "exorcismresourcepackproxy",
        name = "ExorcismResourcePackProxy",
        version = "1.0.0",
        description = "Preloads and gates the Exorcism resource pack before server connection.",
        authors = {"Kaelorvyn"}
)
public final class ExorcismResourcePackProxy {
    private static final String EXORCISM_SERVER = "Exorcism";
    private static final PackVariant MODERN_PACK = new PackVariant(
            "modern-1.21.4+",
            UUID.fromString("bd94fa4e-c6b9-4d2c-8efd-cf8c5026307e"),
            "http://43.226.36.115:3213/exorcism-pack.zip",
            hex("8920400E02BFC810E8A1ACDC98C6147EFC8057A7")
    );
    private static final PackVariant LEGACY_PACK = new PackVariant(
            "legacy-1.20.5-1.21.1",
            UUID.fromString("c19b37ed-3d0e-4c11-8b7a-2c1c58bd6cc4"),
            "http://43.226.36.115:3213/exorcism-pack-legacy.zip",
            hex("8193929F3F8A185B0758A246DA7653632BB4C763")
    );

    private final ProxyServer proxy;
    private final Logger logger;
    private final Map<UUID, PendingConnection> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> allowNextConnection = new ConcurrentHashMap<>();

    @Inject
    public ExorcismResourcePackProxy(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        proxy.getScheduler().buildTask(this, this::expirePending)
                .repeat(Duration.ofSeconds(1))
                .schedule();
        logger.info("Exorcism resource pack pre-connect gate enabled");
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPreConnect(ServerPreConnectEvent event) {
        RegisteredServer target = event.getOriginalServer();
        if (!isExorcismServer(target.getServerInfo().getName())) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        PackVariant variant = packFor(player);
        Long allowedUntil = allowNextConnection.get(playerId);
        if (allowedUntil != null) {
            if (allowedUntil >= System.currentTimeMillis()) {
                allowNextConnection.remove(playerId);
                return;
            }
            allowNextConnection.remove(playerId);
        }

        if (hasRequiredPack(player, variant)) {
            return;
        }

        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        PendingConnection current = pending.get(playerId);
        if (current == null || current.expiresAt < System.currentTimeMillis()) {
            pending.put(playerId, new PendingConnection(target, variant, System.currentTimeMillis() + 90_000L));
            player.sendMessage(Component.text("正在安装驱魔传材质包，加载成功后才会进入服务器。"));
            player.sendResourcePackOffer(createPackOffer(variant));
            logger.info("Gated {} before Exorcism connection and sent the resource pack", player.getUsername());
        } else {
            player.sendMessage(Component.text("请先等待驱魔传材质包加载完成。"));
        }
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        PackVariant variant = variantForId(event.getPackId());
        if (variant == null) {
            return;
        }

        Player player = event.getPlayer();
        PendingConnection request = pending.get(player.getUniqueId());
        switch (event.getStatus()) {
            case SUCCESSFUL -> {
                if (request == null) {
                    logger.info("{} loaded the cached Exorcism resource pack ({})", player.getUsername(), variant.label());
                    return;
                }
                pending.remove(player.getUniqueId());
                allowNextConnection.put(player.getUniqueId(), System.currentTimeMillis() + 10_000L);
                player.sendMessage(Component.text("材质包加载成功，正在进入驱魔传。"));
                proxy.getScheduler().buildTask(this, () -> player.createConnectionRequest(request.server).connect())
                        .delay(Duration.ofMillis(150))
                        .schedule();
                logger.info("{} passed the Exorcism resource pack gate", player.getUsername());
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                pending.remove(player.getUniqueId());
                logger.warn("{} failed the Exorcism resource pack gate: {}", player.getUsername(), event.getStatus());
                if (isInExorcism(player)) {
                    redirectToLobby(player);
                } else {
                    player.sendMessage(Component.text("驱魔传材质包加载失败，未进入服务器。请修复客户端后重试。"));
                }
            }
            case DISCARDED -> {
                boolean inExorcism = player.getCurrentServer()
                        .map(connection -> isExorcismServer(connection.getServerInfo().getName()))
                        .orElse(false);
                if (request != null || inExorcism) {
                    player.sendResourcePackOffer(createPackOffer(
                            request == null ? packFor(player) : request.variant()));
                    logger.warn("{} discarded the Exorcism resource pack; re-offered it", player.getUsername());
                }
            }
            default -> logger.info("{} resource pack status: {}", player.getUsername(), event.getStatus());
        }
    }

    private ResourcePackInfo createPackOffer(PackVariant variant) {
        return proxy.createResourcePackBuilder(variant.url())
                .setId(variant.id())
                .setHash(variant.sha1().clone())
                .setShouldForce(true)
                .setPrompt(Component.text("进入驱魔传必须加载材质包"))
                .build();
    }

    private boolean hasRequiredPack(Player player, PackVariant variant) {
        return player.getAppliedResourcePacks().stream().anyMatch(pack ->
                variant.id().equals(pack.getId()) && Arrays.equals(variant.sha1(), pack.getHash()));
    }

    private PackVariant packFor(Player player) {
        return player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_21_4) < 0
                ? LEGACY_PACK
                : MODERN_PACK;
    }

    private PackVariant variantForId(UUID id) {
        if (MODERN_PACK.id().equals(id)) return MODERN_PACK;
        if (LEGACY_PACK.id().equals(id)) return LEGACY_PACK;
        return null;
    }

    private boolean isInExorcism(Player player) {
        return player.getCurrentServer()
                .map(connection -> isExorcismServer(connection.getServerInfo().getName()))
                .orElse(false);
    }

    private boolean isExorcismServer(String name) {
        return EXORCISM_SERVER.equalsIgnoreCase(name)
                || name.regionMatches(true, 0, "ExorcismRoom", 0, "ExorcismRoom".length());
    }

    private void redirectToLobby(Player player) {
        proxy.getServer("lobby").ifPresentOrElse(
                lobby -> player.createConnectionRequest(lobby).connect(),
                () -> player.disconnect(Component.text("驱魔传材质包加载失败，请返回大厅后重试。"))
        );
    }

    private void expirePending() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt >= now) {
                return false;
            }
            proxy.getPlayer(entry.getKey()).ifPresent(player ->
                    player.sendMessage(Component.text("材质包加载超时，仍在大厅，未进入驱魔传。")));
            return true;
        });
        allowNextConnection.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[20];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private record PendingConnection(RegisteredServer server, PackVariant variant, long expiresAt) {
    }

    private record PackVariant(String label, UUID id, String url, byte[] sha1) {
    }
}
