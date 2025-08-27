package com.example.bastion.coremod;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.util.HashMap;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

public class BastionTransformer implements net.minecraft.launchwrapper.IClassTransformer {

    private static final Map<String, PatchTarget> targets = new HashMap<>();

    static {
        // Socket.connect(SocketAddress, int)
        targets.put("java.net.Socket", new PatchTarget(
                "connect",
                "(Ljava/net/SocketAddress;I)V",
                "com/example/bastion/BastionHooks",
                "checkSocketConnect",
                "(Ljava/net/SocketAddress;I)V"
        ));

        // HttpURLConnection.connect()
        targets.put("java.net.HttpURLConnection", new PatchTarget(
                "connect",
                "()V",
                "com/example/bastion/BastionHooks",
                "checkHttpConnect",
                "()V"
        ));

        // SocketChannel.connect(SocketAddress)
        targets.put("java.nio.channels.SocketChannel", new PatchTarget(
                "connect",
                "(Ljava/net/SocketAddress;)Z",
                "com/example/bastion/BastionHooks",
                "checkChannelConnect",
                "(Ljava/net/SocketAddress;)V"
        ));
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!targets.containsKey(transformedName)) return basicClass;

        PatchTarget patch = targets.get(transformedName);

        ClassReader cr = new ClassReader(basicClass);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (name.equals(patch.methodName) && desc.equals(patch.methodDesc)) {
                    return new AdviceAdapter(ASM5, mv, access, name, desc) {
                        @Override
                        protected void onMethodEnter() {
                            // Inject call to BastionHooks
                            visitVarInsn(ALOAD, 1); // push SocketAddress arg
                            if (patch.hookDesc.contains("I")) {
                                // Socket.connect(SocketAddress, int)
                                visitVarInsn(ILOAD, 2); // push int timeout
                                visitMethodInsn(INVOKESTATIC, patch.hookOwner, patch.hookMethod, patch.hookDesc, false);
                            } else {
                                // SocketChannel.connect(SocketAddress)
                                visitMethodInsn(INVOKESTATIC, patch.hookOwner, patch.hookMethod, patch.hookDesc, false);
                            }
                        }
                    };
                }
                return mv;
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    private static class PatchTarget {
        final String methodName, methodDesc, hookOwner, hookMethod, hookDesc;
        PatchTarget(String mn, String md, String ho, String hm, String hd) {
            this.methodName = mn;
            this.methodDesc = md;
            this.hookOwner = ho.replace('.', '/');
            this.hookMethod = hm;
            this.hookDesc = hd;
        }
    }
}
