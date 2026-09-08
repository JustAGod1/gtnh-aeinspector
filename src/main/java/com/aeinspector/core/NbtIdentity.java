package com.aeinspector.core;

import java.util.Set;
import java.util.List;
import java.lang.reflect.Field;
import java.io.DataOutput;
import java.io.IOException;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagString;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** Exact structural identity, including float bit patterns and empty-list element types. */
final class NbtIdentity {
    private static final Field LIST = ReflectionHelper.findField(NBTTagList.class, "tagList", "field_74747_a");
    private NbtIdentity() {}

    @SuppressWarnings("unchecked")
    private static List<NBTBase> elements(NBTTagList list) {
        try { return (List<NBTBase>) LIST.get(list); }
        catch (IllegalAccessException e) { throw new IllegalStateException("Cannot access NBT list", e); }
    }

    static boolean equal(NBTBase a, NBTBase b) {
        if (a == b) return true;
        if (a == null || b == null || a.getId() != b.getId()) return false;
        switch (a.getId()) {
            case 5:
                return Float.floatToRawIntBits(((NBTTagFloat) a).func_150288_h())
                        == Float.floatToRawIntBits(((NBTTagFloat) b).func_150288_h());
            case 6:
                return Double.doubleToRawLongBits(((NBTTagDouble) a).func_150286_g())
                        == Double.doubleToRawLongBits(((NBTTagDouble) b).func_150286_g());
            case 9:
                NBTTagList la = (NBTTagList) a;
                NBTTagList lb = (NBTTagList) b;
                if (la.func_150303_d() != lb.func_150303_d() || la.tagCount() != lb.tagCount()) return false;
                List<NBTBase> ea = elements(la), eb = elements(lb);
                for (int i = 0; i < la.tagCount(); i++) if (!equal(ea.get(i), eb.get(i))) return false;
                return true;
            case 10:
                NBTTagCompound ca = (NBTTagCompound) a;
                NBTTagCompound cb = (NBTTagCompound) b;
                Set<?> keys = ca.func_150296_c();
                if (keys.size() != cb.func_150296_c().size()) return false;
                for (Object key : keys) {
                    String name = (String) key;
                    if (!equal(ca.getTag(name), cb.getTag(name))) return false;
                }
                return true;
            default:
                return a.equals(b);
        }
    }

    static int hash(NBTBase tag) {
        if (tag == null) return 0;
        switch (tag.getId()) {
            case 5:
                return 31 * 5 + Float.floatToRawIntBits(((NBTTagFloat) tag).func_150288_h());
            case 6:
                long bits = Double.doubleToRawLongBits(((NBTTagDouble) tag).func_150286_g());
                return 31 * 6 + (int) (bits ^ (bits >>> 32));
            case 9:
                NBTTagList list = (NBTTagList) tag;
                int result = 31 * 9 + list.func_150303_d();
                for (NBTBase element : elements(list)) result = 31 * result + hash(element);
                return result;
            case 10:
                NBTTagCompound compound = (NBTTagCompound) tag;
                int sum = 31 * 10;
                for (Object key : compound.func_150296_c()) {
                    String name = (String) key;
                    sum += name.hashCode() ^ hash(compound.getTag(name));
                }
                return sum;
            default:
                return tag.hashCode();
        }
    }

    /** Vanilla-compatible encoding without canonicalizing NaNs or mutating empty lists. */
    static void writeCompound(NBTTagCompound tag, DataOutput out) throws IOException {
        out.writeByte(10);
        out.writeUTF("");
        writePayload(tag, out);
    }

    private static void writePayload(NBTBase tag, DataOutput out) throws IOException {
        switch (tag.getId()) {
            case 0: break;
            case 1: out.writeByte(((NBTBase.NBTPrimitive) tag).func_150290_f()); break;
            case 2: out.writeShort(((NBTBase.NBTPrimitive) tag).func_150289_e()); break;
            case 3: out.writeInt(((NBTBase.NBTPrimitive) tag).func_150287_d()); break;
            case 4: out.writeLong(((NBTBase.NBTPrimitive) tag).func_150291_c()); break;
            case 5: out.writeInt(Float.floatToRawIntBits(((NBTTagFloat) tag).func_150288_h())); break;
            case 6: out.writeLong(Double.doubleToRawLongBits(((NBTTagDouble) tag).func_150286_g())); break;
            case 7:
                byte[] bytes = ((NBTTagByteArray) tag).func_150292_c();
                out.writeInt(bytes.length);
                out.write(bytes);
                break;
            case 8: out.writeUTF(((NBTTagString) tag).func_150285_a_()); break;
            case 9:
                NBTTagList list = (NBTTagList) tag;
                out.writeByte(list.func_150303_d());
                out.writeInt(list.tagCount());
                for (NBTBase element : elements(list)) writePayload(element, out);
                break;
            case 10:
                NBTTagCompound compound = (NBTTagCompound) tag;
                for (Object key : compound.func_150296_c()) {
                    String name = (String) key;
                    NBTBase value = compound.getTag(name);
                    out.writeByte(value.getId());
                    if (value.getId() == 0) throw new IOException("Named end tag is not valid NBT");
                    out.writeUTF(name);
                    writePayload(value, out);
                }
                out.writeByte(0);
                break;
            case 11:
                int[] integers = ((NBTTagIntArray) tag).func_150302_c();
                out.writeInt(integers.length);
                for (int value : integers) out.writeInt(value);
                break;
            default: throw new IOException("Unsupported NBT tag " + tag.getId());
        }
    }
}
