package com.aeinspector.gui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final SnapshotBatch.Inbox RESPONSE = new SnapshotBatch.Inbox();

    private InspectorProtocol() {}
    public static void register() {
        CHANNEL.registerMessage(RequestHandler.class, Request.class, 0, Side.SERVER);
        CHANNEL.registerMessage(SnapshotHandler.class, Snapshot.class, 1, Side.CLIENT);
    }
    public static Request take(EntityPlayerMP player) { return REQUESTS.remove(player); }
    public static void forget(EntityPlayerMP player) { REQUESTS.remove(player); }
    public static void clear() { REQUESTS.clear(); RESPONSE.clear(); }
    static SnapshotBatch takeSnapshot() { return RESPONSE.take(); }
    static void expectSnapshot(int window, int sequence) { RESPONSE.expect(window, sequence); }
    static void closeSnapshots() { RESPONSE.close(); }

    public static final class Request implements IMessage {
        public int window, level, resourceOffset, deviceOffset, sort, sequence, filter;
        public int rows = PAGE_SIZE;
        public boolean devices;
        public String search = "";
        public int[] selected = new int[0];
        public Request() {}
        @Override public void toBytes(ByteBuf out) {
            out.writeInt(window); out.writeByte(level); out.writeInt(resourceOffset); out.writeInt(deviceOffset);
            ByteBufUtils.writeUTF8String(out, search);
            out.writeByte(selected.length);
            for (int id : selected) out.writeInt(id);
            out.writeByte(sort);
            out.writeInt(sequence); out.writeByte(rows); out.writeBoolean(devices); out.writeByte(filter);
        }
        @Override public void fromBytes(ByteBuf in) {
            if (in.readableBytes() > 600) throw new IllegalArgumentException("Oversized inspector request");
            window = in.readInt(); level = in.readUnsignedByte(); resourceOffset = in.readInt(); deviceOffset = in.readInt();
            search = ByteBufUtils.readUTF8String(in);
            int count = in.readUnsignedByte();
            if (window < 0 || level > 8 || resourceOffset < 0 || resourceOffset > 1_000_000 || deviceOffset < 0
                    || deviceOffset > 1_000_000 || search.length() > 128 || count > MAX_SELECTION) {
                throw new IllegalArgumentException("Invalid inspector request");
            }
            selected = new int[count];
            for (int i = 0; i < count; i++) {
                selected[i] = in.readInt();
                if (selected[i] < 0) throw new IllegalArgumentException("Invalid resource ID");
                for (int j = 0; j < i; j++) if (selected[i] == selected[j]) throw new IllegalArgumentException("Duplicate selection");
            }
            sort = in.readUnsignedByte();
            sequence = in.readInt(); rows = in.readUnsignedByte(); devices = in.readBoolean(); filter = in.readUnsignedByte();
            if (sort > 2 || filter > ResourceFilter.FLUIDS || sequence < 0 || rows < 1 || rows > PAGE_SIZE || in.isReadable()) throw new IllegalArgumentException("Invalid request options");
        }
        public Request copy() {
            Request copy = new Request();
            copy.window = window; copy.level = level; copy.resourceOffset = resourceOffset; copy.deviceOffset = deviceOffset;
            copy.sort = sort; copy.sequence = sequence; copy.rows = rows; copy.devices = devices; copy.filter = filter;
            copy.search = search; copy.selected = selected.clone(); return copy;
        }
    }
    public static final class Snapshot implements IMessage {
        int window, sequence, batch, part;
        boolean last;
        private byte[] bytes;
        public Snapshot() {}
        public Snapshot(NBTTagCompound data) throws IOException { this(data, 0, 0, 0, 0, true); }
        Snapshot(NBTTagCompound data, int window, int sequence, int batch, int part, boolean last) throws IOException {
            this.window = window; this.sequence = sequence; this.batch = batch; this.part = part; this.last = last;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(data, out);
            bytes = out.toByteArray();
            if (bytes.length > 512 * 1024) throw new IOException("Inspector snapshot exceeds packet limit");
        }
        public NBTTagCompound decode() throws IOException {
            return CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes));
        }
        int byteSize() { return bytes.length; }
        @Override public void toBytes(ByteBuf out) {
            out.writeInt(window); out.writeInt(sequence); out.writeInt(batch); out.writeInt(part); out.writeBoolean(last);
            out.writeInt(bytes.length); out.writeBytes(bytes);
        }
        @Override public void fromBytes(ByteBuf in) {
            window = in.readInt(); sequence = in.readInt(); batch = in.readInt(); part = in.readInt(); last = in.readBoolean();
            if (window < 0 || sequence < 0 || batch < 0 || part < 0 || part >= 65536) throw new IllegalArgumentException("Invalid snapshot header");
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
            RESPONSE.receive(snapshot);
            return null;
        }
    }
}
