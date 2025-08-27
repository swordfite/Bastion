package com.example.bastion;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;

public class SessionTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "net.minecraft.util.Session";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;

        if (TARGET_CLASS.equals(transformedName)) {
            String msg = "[Bastion] Patching Session class: " + name;
            System.out.println(msg);
            BastionCore.getInstance().log(msg);
            return patchSession(basicClass);
        }

        return basicClass;
    }

    private byte[] patchSession(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

                // Hook both getToken() and getSessionID()
                if (("getToken".equals(name) || "getSessionID".equals(name))
                        && "()Ljava/lang/String;".equals(desc)) {
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ARETURN) {
                                // Duplicate the return value
                                mv.visitInsn(Opcodes.DUP);

                                // Store into BastionCore (setSessionToken)
                                mv.visitInsn(Opcodes.DUP); // second copy for saving
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/example/bastion/BastionCore",
                                        "getInstance",
                                        "()Lcom/example/bastion/BastionCore;",
                                        false);
                                mv.visitInsn(Opcodes.SWAP); // swap token and BastionCore instance
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "com/example/bastion/BastionCore",
                                        "setSessionToken",
                                        "(Ljava/lang/String;)V",
                                        false);

                                // Call SessionGuard.onTokenAccess(token, methodName)
                                mv.visitLdcInsn(name);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/example/bastion/SessionGuard",
                                        "onTokenAccess",
                                        "(Ljava/lang/String;Ljava/lang/String;)V",
                                        false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return mv;
            }
        };

        cr.accept(cv, 0);
        return cw.toByteArray();
    }
}
