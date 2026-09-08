package com.aeinspector.gui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/** Transport mailboxes only. No workers: containers and the GUI consume them on their existing game loops. */
public final class InspectorProtocol {
    public static final int MAX_SELECTION = 8;
    public static final int PAGE_SIZE = 10;
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("aeinspector");
    private static final ConcurrentHashMap<EntityPlayerMP, Request> REQUESTS = new ConcurrentHashMap<>();
    private static final AtomicReference<Snapshot> RESPONSE = new AtomicReference<>();

    private InspectorProtocol() {}
    public static void register() {
        CHANNEL.registerMessage(RequestHandler.class, Request.class, 0, Side.SERVER);
        CHANNEL.registerMessage(SnapshotHandler.class, Snapshot.class, 1, Side.CLIENT);
    }
    public static Request take(EntityPlayerMP player) { return REQUESTS.remove(player); }
    public static void forget(EntityPlayerMP player) { REQUESTS.remove(player); }
    public static void clear() { REQUESTS.clear(); RESPONSE.set(null); }
    public static Snapshot takeSnapshot() { return RESPONSE.getAndSet(null); }

    public static final class Request implements IMessage {
        public int window, level, resourcePage, devicePage, sort;
        public String search = "";
        public int[] selected = new int[0];
        public Request() {}
        @Override public void toBytes(ByteBuf out) {
            out.writeInt(window); out.writeByte(level); out.writeInt(resourcePage); out.writeInt(devicePage);
            ByteBufUtils.writeUTF8String(out, search);
            out.writeByte(selected.length);
            for (int id : selected) out.writeInt(id);
            out.writeByte(sort);
        }
        @Override public void fromBytes(ByteBuf in) {
            if (in.readableBytes() > 600) throw new IllegalArgumentException("Oversized inspector request");
            window = in.readInt(); level = in.readUnsignedByte(); resourcePage = in.readInt(); devicePage = in.readInt();
            search = ByteBufUtils.readUTF8String(in);
            int count = in.readUnsignedByte();
            if (window < 0 || level > 8 || resourcePage < 0 || resourcePage > 1_000_000 || devicePage < 0
                    || devicePage > 1_000_000 || search.length() > 128 || count > MAX_SELECTION) {
                throw new IllegalArgumentException("Invalid inspector request");
            }
            selected = new int[count];
            for (int i = 0; i < count; i++) {
                selected[i] = in.readInt();
                if (selected[i] < 0) throw new IllegalArgumentException("Invalid resource ID");
                for (int j = 0; j < i; j++) if (selected[i] == selected[j]) throw new IllegalArgumentException("Duplicate selection");
            }
            sort = in.readUnsignedByte();
            if (sort > 2 || in.isReadable()) throw new IllegalArgumentException("Invalid sort or trailing request data");
        }
    }
    public static final class Snapshot implements IMessage {
        private byte[] bytes;
        public Snapshot() {}
        public Snapshot(NBTTagCompound data) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(data, out);
            bytes = out.toByteArray();
            if (bytes.length > 512 * 1024) throw new IOException("Inspector snapshot exceeds packet limit");
        }
        public NBTTagCompound decode() throws IOException {
            return CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes));
        }
        @Override public void toBytes(ByteBuf out) { out.writeInt(bytes.length); out.writeBytes(bytes); }
        @Override public void fromBytes(ByteBuf in) {
            int length = in.readInt();
            if (length < 0 || length > 512 * 1024 || length != in.readableBytes()) throw new IllegalArgumentException("Invalid snapshot");
            bytes = new byte[length]; in.readBytes(bytes);
        }
    }
    public static final class RequestHandler implements IMessageHandler<Request, IMessage> {
        @Override public IMessage onMessage(Request request, MessageContext context) {
            // Latest request replaces older ones: at most one pending request per connected player.
            REQUESTS.put(context.getServerHandler().playerEntity, request);
            return null;
        }
    }
    public static final class SnapshotHandler implements IMessageHandler<Snapshot, IMessage> {
        @Override public IMessage onMessage(Snapshot snapshot, MessageContext context) {
            RESPONSE.set(snapshot);
            return null;
        }
    }
}
