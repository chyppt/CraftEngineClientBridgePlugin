package cc.cebridge.client;

import cc.cebridge.CraftEngineBridgePlugin;
import cc.cebridge.wire.CatalogFrames;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** 不依赖核心的可选协议。无握手玩家不会接收包，也不会承担目录生成开销。 */
public final class OptionalClientBridge implements Listener, PluginMessageListener, AutoCloseable {
    public static final String HELLO="cebridge:hello", CATALOG="cebridge:catalog", BLOCK_QUERY="cebridge:block_query", BLOCK_REPLY="cebridge:block_reply";
    private final CraftEngineBridgePlugin plugin;
    private final ConcurrentMap<UUID,Session> sessions=new ConcurrentHashMap<>();
    private final AtomicBoolean queued=new AtomicBoolean();
    // 目录复制/玩家转换/压缩最多同时两份，避免百人重载时瞬间复制全部 JSON。
    private final Semaphore exportSlots=new Semaphore(2);
    private volatile JsonObject snapshot;
    private volatile long generation;
    private volatile boolean closed;
    private static final class Session {
        private final long requested;
        private final boolean nativeBlocks;
        private volatile boolean ready;
        private volatile boolean targetHud;
        private long lastHud;
        Session(long requested,boolean nativeBlocks) { this.requested=requested;this.nativeBlocks=nativeBlocks; }
        long requested() { return requested; }
        boolean nativeBlocks() { return nativeBlocks && ready; }
    }
    public OptionalClientBridge(CraftEngineBridgePlugin plugin) {
        this.plugin=plugin;
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin,HELLO,this);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin,BLOCK_QUERY,this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,BLOCK_REPLY);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,CATALOG);
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    @Override public void onPluginMessageReceived(String channel,Player player,byte[] bytes) {
        if(channel.equals(BLOCK_QUERY)) { queryBlock(player,bytes);return; }
        if(closed || !channel.equals(HELLO) || bytes.length!=4 || (bytes[0]!=CatalogFrames.VERSION && bytes[0]!=2)) return;
        if(!plugin.getConfig().getBoolean("client-mod.enabled",true)) return;
        if(!Bukkit.getMinecraftVersion().equals("26.1.2")) return;
        if(bytes[0]==2) {
            Session existing=sessions.get(player.getUniqueId());
            if(existing!=null) { existing.ready=true;existing.targetHud=bytes[1]==1;if(existing.nativeBlocks() || existing.targetHud) plugin.carriers().releaseViewer(player.getUniqueId()); }
            return;
        }
        long now=System.nanoTime(); Session previous=sessions.get(player.getUniqueId());
        if(previous!=null && now-previous.requested()<10_000_000_000L) return;
        var user=BukkitAdaptor.adapt(player);
        if(user==null) return;
        Session session=new Session(now,user.hasClientMod() && user.clientCustomBlockEnabled());
        sessions.put(player.getUniqueId(),session);
        if(snapshot==null) requestRefresh(); else send(player,session,snapshot,generation);
    }
    public boolean hasNativeBlocks(UUID id) {
        Session session=sessions.get(id); return session!=null && (session.nativeBlocks() || session.ready && session.targetHud);
    }
    private void queryBlock(Player player,byte[] bytes) {
        Session session=sessions.get(player.getUniqueId());
        if(closed || session==null || !session.ready || !session.targetHud || bytes.length!=12) return;
        // 入队前限速，恶意客户端也不能把大量任务堆入玩家/区域调度器。
        synchronized(session) {
            long now=System.nanoTime();if(now-session.lastHud<100_000_000L) return;session.lastHud=now;
        }
        var input=java.nio.ByteBuffer.wrap(bytes);int serial=input.getInt();long position=input.getLong();
        player.getScheduler().run(plugin,task->{
            if(sessions.get(player.getUniqueId())!=session || !player.isOnline()) return;
            int x=(int)(position>>38),y=(int)(position<<52>>52),z=(int)(position<<26>>38);
            var world=player.getWorld();var eye=player.getEyeLocation();
            double dx=eye.getX()-x-.5,dy=eye.getY()-y-.5,dz=eye.getZ()-z-.5;
            if(dx*dx+dy*dy+dz*dz>100 || y<world.getMinHeight() || y>=world.getMaxHeight() || !world.isChunkLoaded(x>>4,z>>4)) return;
            // 仅确认当前准星的方块；像 CarrierManager 一样先确保整条射线在已加载且归当前区域的区块内。
            var end=eye.clone().add(eye.getDirection().multiply(8));
            for(int cx=Math.min(eye.getBlockX(),end.getBlockX())>>4;cx<=(Math.max(eye.getBlockX(),end.getBlockX())>>4);cx++)
                for(int cz=Math.min(eye.getBlockZ(),end.getBlockZ())>>4;cz<=(Math.max(eye.getBlockZ(),end.getBlockZ())>>4);cz++)
                    if(!world.isChunkLoaded(cx,cz) || !Bukkit.isOwnedByCurrentRegion(world,cx,cz)) return;
            var target=player.getTargetBlockExact(8);
            if(target==null || target.getX()!=x || target.getY()!=y || target.getZ()!=z) return;
            plugin.getServer().getRegionScheduler().run(plugin,world,x>>4,z>>4,region->{
                if(!world.isChunkLoaded(x>>4,z>>4)) return;
                var state=net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.getCustomBlockState(world.getBlockAt(x,y,z));
                int stateId=state==null?-1:state.customBlockState().registryId();
                byte[] reply=java.nio.ByteBuffer.allocate(16).putInt(serial).putLong(position).putInt(stateId).array();
                player.getScheduler().run(plugin,send->{
                    if(player.isOnline() && player.getWorld().equals(world) && sessions.get(player.getUniqueId())==session)
                        player.sendPluginMessage(plugin,BLOCK_REPLY,reply);
                },null);
            });
        },null);
    }
    public int connectedPlayers() { return sessions.size(); }
    public void requestRefresh() {
        if(closed) return;
        snapshot=null;
        if(sessions.isEmpty() || !queued.compareAndSet(false,true)) return;
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin,task->{
            try {
                JsonObject next=ClientCatalogExport.build(plugin); snapshot=next; long version=++generation;
                for(var entry:sessions.entrySet()) {
                    Player player=Bukkit.getPlayer(entry.getKey());
                    if(player!=null) send(player,entry.getValue(),next,version);
                }
            } catch(RuntimeException|LinkageError failure) {
                plugin.getLogger().warning("客户端目录生成失败："+failure);
            } finally { queued.set(false); }
        },2L);
    }
    private void send(Player player,Session session,JsonObject base,long version) {
        player.getScheduler().run(plugin,task->{
            if(!valid(player,session,version)) return;
            if(!exportSlots.tryAcquire()) {
                player.getScheduler().runDelayed(plugin,retry->send(player,session,base,version),null,5L);
                return;
            }
            try {
                JsonObject data=base.deepCopy(); data.addProperty("nativeBlocks",session.nativeBlocks); var available=new HashSet<String>();
                for(var value:data.getAsJsonArray("items")) available.add(value.getAsJsonObject().get("id").getAsString());
                PlayerStackExport.convert(data,player);
                data.add("categories",CategoryExport.forPlayer(player,available));
                byte[] json=data.toString().getBytes(StandardCharsets.UTF_8);
                // 压缩只接收脱离 Bukkit 的字节数组；每人最多保留一代待发送任务。
                Bukkit.getAsyncScheduler().runNow(plugin,async->{
                    try {
                        var frames=CatalogFrames.split(version,json);
                        player.getScheduler().run(plugin,send->sendFrames(player,session,version,frames,0),null);
                    } catch(Exception failure) { plugin.getLogger().warning("目录压缩失败："+failure); }
                    finally { exportSlots.release(); }
                });
            } catch(RuntimeException|LinkageError failure) { exportSlots.release();plugin.getLogger().warning("玩家目录生成失败："+failure); }
        },null);
    }
    private void sendFrames(Player player,Session session,long version,List<byte[]> frames,int first) {
        if(!valid(player,session,version)) return;
        int end=Math.min(first+2,frames.size());
        for(int i=first;i<end;i++) player.sendPluginMessage(plugin,CATALOG,frames.get(i));
        if(end<frames.size()) player.getScheduler().runDelayed(plugin,task->sendFrames(player,session,version,frames,end),null,1L);
    }
    private boolean valid(Player player,Session session,long version) {
        return !closed && player.isOnline() && sessions.get(player.getUniqueId())==session && generation==version;
    }
    @EventHandler(priority=EventPriority.MONITOR) public void reload(CraftEngineReloadEvent event) { requestRefresh(); }
    @EventHandler public void quit(PlayerQuitEvent event) { sessions.remove(event.getPlayer().getUniqueId()); }
    @Override public void close() {
        closed=true; sessions.clear(); snapshot=null;
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin,HELLO,this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin,BLOCK_QUERY,this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin,BLOCK_REPLY);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin,CATALOG);
    }
}
