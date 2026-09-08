package com.aeinspector.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Stable endpoint addresses. ID zero is reserved for a source without a locatable endpoint. */
public final class DeviceDictionary {
    private final ArrayList<Device> devices = new ArrayList<>();
    private final Map<String, Integer> lookup = new HashMap<>();

    public DeviceDictionary() { devices.add(new Device(0, 0, 0, 0, 0, -1, "unknown", "Unknown source")); }

    public int resolve(int dimension, int x, int y, int z, int side, String type, String name) {
        String key = dimension + ":" + x + ":" + y + ":" + z + ":" + side + ":" + type;
        Integer id = lookup.get(key);
        if (id != null) return id;
        int next = devices.size();
        devices.add(new Device(next, dimension, x, y, z, side, type, name));
        lookup.put(key, next);
        return next;
    }

    public int size() { return devices.size(); }
    public Device get(int id) { return devices.get(id); }

    public void write(DataOutput out) throws IOException {
        out.writeInt(devices.size() - 1);
        for (int i = 1; i < devices.size(); i++) {
            Device d = devices.get(i);
            out.writeInt(d.dimension);
            out.writeInt(d.x);
            out.writeInt(d.y);
            out.writeInt(d.z);
            out.writeInt(d.side);
            out.writeUTF(d.type);
            out.writeUTF(d.name);
        }
    }

    public static DeviceDictionary read(DataInput in) throws IOException {
        DeviceDictionary result = new DeviceDictionary();
        int size = in.readInt();
        if (size < 0 || size > 10_000_000) throw new IOException("Invalid endpoint count");
        for (int i = 1; i <= size; i++) {
            int id = result.resolve(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                    in.readUTF(), in.readUTF());
            if (id != i) throw new IOException("Duplicate endpoint");
        }
        return result;
    }

    public static final class Device {
        public final int id, dimension, x, y, z, side;
        public final String type, name;

        private Device(int id, int dimension, int x, int y, int z, int side, String type, String name) {
            this.id = id;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.side = side;
            this.type = type;
            this.name = name;
        }
    }
}
