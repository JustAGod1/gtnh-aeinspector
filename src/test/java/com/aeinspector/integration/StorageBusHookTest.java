package com.aeinspector.integration;

import static org.junit.Assert.*;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.AnnotationNode;

/** Inspect the pinned dependency bytecode without starting Minecraft or initializing its registries. */
public class StorageBusHookTest {
    @Test public void multiTargetShadowsDisableRemappingExplicitly() throws Exception {
        // Mixin validates each @Shadow separately: @Mixin(remap = false) does not
        // satisfy its multi-target validation. This rejected the whole 0.1.9 hook.
        int checked = 0;
        for (String mixin : new InspectorLateMixins().getMixins(java.util.Collections.emptySet())) {
            ClassNode node = read("com/aeinspector/mixin/" + mixin);
            AnnotationNode annotation = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
            assertNotNull(mixin, annotation);
            if (size(value(annotation, "value")) + size(value(annotation, "targets")) <= 1) continue;
            for (FieldNode field : node.fields) {
                AnnotationNode shadow = annotation(field.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;");
                if (shadow != null) {
                    assertEquals(mixin + "." + field.name + " requires @Shadow(remap = false)",
                            Boolean.FALSE, value(shadow, "remap"));
                    checked++;
                }
            }
            for (MethodNode method : node.methods) {
                AnnotationNode shadow = annotation(method.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;");
                if (shadow != null) {
                    assertEquals(mixin + "." + method.name + " requires @Shadow(remap = false)",
                            Boolean.FALSE, value(shadow, "remap"));
                    checked++;
                }
            }
        }
        assertTrue("Must inspect the Storage Bus handler shadow", checked > 0);
    }

    private static AnnotationNode annotation(java.util.List<AnnotationNode> annotations, String descriptor) {
        if (annotations != null) for (AnnotationNode annotation : annotations)
            if (annotation.desc.equals(descriptor)) return annotation;
        return null;
    }
    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        return null;
    }
    private static int size(Object value) { return value instanceof java.util.List ? ((java.util.List<?>) value).size() : 0; }

    @Test public void loadedDeviceCatalogIncludesBothStorageBusClasses() throws Exception {
        java.util.Set<String> supported = new java.util.HashSet<>();
        for (MethodNode method : read("com/aeinspector/integration/DeviceCatalog").methods) if (method.name.equals("supported")) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof LdcInsnNode) {
                Object constant = ((LdcInsnNode) instruction).cst;
                if (constant instanceof org.objectweb.asm.Type) supported.add(((org.objectweb.asm.Type) constant).getInternalName());
            }
        }
        assertTrue(supported.contains("appeng/parts/misc/PartStorageBus"));
        assertTrue(supported.contains("com/glodblock/github/common/parts/PartFluidStorageBus"));
        assertEquals(6, supported.size());
    }
    @Test public void bothStorageBusImplementationsConstructTheHookedHandler() throws Exception {
        for (String type : new String[] {"appeng/parts/misc/PartStorageBus", "com/glodblock/github/common/parts/PartFluidStorageBus"}) {
            ClassNode node = read(type); int constructors = 0, methods = 0, statusMethods = 0;
            int handlers = 0;
            for (FieldNode field : node.fields) if (field.name.equals("handler")) {
                assertEquals("Lappeng/me/storage/MEInventoryHandler;", field.desc); handlers++;
            }
            assertEquals(type, 1, handlers);
            for (MethodNode method : node.methods) if (method.name.equals("updateStatus")) {
                assertEquals("()V", method.desc); statusMethods++;
            }
            for (MethodNode method : node.methods) if (method.name.equals("getInternalHandler")) {
                assertEquals("()Lappeng/me/storage/MEInventoryHandler;", method.desc); methods++;
                for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof TypeInsnNode
                        && instruction.getOpcode() == org.objectweb.asm.Opcodes.NEW
                        && ((TypeInsnNode) instruction).desc.equals("appeng/me/storage/StorageBusInventoryHandler")) constructors++;
            }
            assertEquals(type, 1, methods); assertEquals(type, 1, constructors); assertEquals(type, 1, statusMethods);
        }
    }
    @Test public void storageBusTransfersUseTheHookedBaseMethods() throws Exception {
        ClassNode node = read("appeng/me/storage/StorageBusInventoryHandler");
        assertEquals("appeng/me/storage/MEInventoryHandler", node.superName);
        for (MethodNode method : node.methods) assertFalse(method.name.equals("injectItems") || method.name.equals("extractItems"));
        node = read(node.superName); int methods = 0;
        for (MethodNode method : node.methods) if (method.name.equals("injectItems") || method.name.equals("extractItems")) {
            assertEquals("(Lappeng/api/storage/data/IAEStack;Lappeng/api/config/Actionable;Lappeng/api/networking/security/BaseActionSource;)Lappeng/api/storage/data/IAEStack;", method.desc);
            methods++;
        }
        assertEquals(2, methods);
    }
    private static ClassNode read(String type) throws Exception {
        try (InputStream stream = StorageBusHookTest.class.getClassLoader().getResourceAsStream(type + ".class")) {
            assertNotNull(type, stream); ClassNode node = new ClassNode(); new ClassReader(stream).accept(node, 0); return node;
        }
    }
}
