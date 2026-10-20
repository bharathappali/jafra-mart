package com.jaframart.pricing.engine;

import com.jaframart.pricing.rules.PriceRule;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicInteger;

public final class HiddenClassFactory {
  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final ClassDesc PRICE_RULE = ClassDesc.of("com.jaframart.pricing.rules.PriceRule");
  private static final ClassDesc PIPE = ClassDesc.of("com.jaframart.pricing.engine.Pipe");
  private static final MethodTypeDesc CTOR = MethodTypeDesc.of(ConstantDescs.CD_void);
  private static final MethodTypeDesc APPLY = MethodTypeDesc.ofDescriptor("(JI)J");
  private static final MethodTypeDesc HELPER = MethodTypeDesc.ofDescriptor("(J)J");
  private static final MethodTypeDesc RUN = MethodTypeDesc.ofDescriptor("(Lcom/jaframart/pricing/rules/PriceRule;J)J");

  private HiddenClassFactory() {}

  public static PriceRule campaignRule(int salt) throws Throwable {
    String internal = "com/jaframart/pricing/engine/CampaignRule" + SEQ.incrementAndGet();
    ClassDesc thisClass = ClassDesc.ofInternalName(internal);
    byte[] bytes = ClassFile.of().build(thisClass, clb -> {
      clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
      clb.withInterfaceSymbols(PRICE_RULE);
      clb.withMethodBody("<init>", CTOR, ClassFile.ACC_PUBLIC, cob -> {
        cob.aload(0);
        cob.invokespecial(ClassDesc.of("java.lang.Object"), "<init>", CTOR);
        cob.return_();
      });
      clb.withMethodBody("apply", APPLY, ClassFile.ACC_PUBLIC, cob -> {
        cob.lload(1);
        cob.ldc((long) (salt & 0xFF));
        cob.ladd();
        cob.lload(1);
        cob.lxor();
        cob.iload(3);
        cob.i2l();
        cob.ladd();
        cob.invokestatic(thisClass, "helperA", HELPER);
        cob.invokestatic(thisClass, "helperB", HELPER);
        cob.invokestatic(thisClass, "helperC", HELPER);
        cob.lreturn();
      });
      emitHelper(clb, "helperA", 3);
      emitHelper(clb, "helperB", 5);
      emitHelper(clb, "helperC", 7);
    });
    MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(bytes, true);
    return (PriceRule) lookup.lookupClass().getDeclaredConstructor().newInstance();
  }

  public static Pipe pipe() throws Throwable {
    int salt = SEQ.incrementAndGet();
    String internal = "com/jaframart/pricing/engine/PipeImpl" + salt;
    ClassDesc thisClass = ClassDesc.ofInternalName(internal);
    byte[] bytes = ClassFile.of().build(thisClass, clb -> {
      clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
      clb.withInterfaceSymbols(PIPE);
      clb.withMethodBody("<init>", CTOR, ClassFile.ACC_PUBLIC, cob -> {
        cob.aload(0);
        cob.invokespecial(ClassDesc.of("java.lang.Object"), "<init>", CTOR);
        cob.return_();
      });
      clb.withMethodBody("run", RUN, ClassFile.ACC_PUBLIC, cob -> {
        cob.lload(2);
        for (int i = 0; i < 64; i++) {
          cob.ldc((long) (salt * 131 + i * 17 + 1));
          cob.lxor();
          cob.ldc((long) (i + 3));
          cob.ladd();
          cob.ldc((long) ((salt ^ i) + 9));
          cob.lmul();
        }
        cob.lstore(4);
        cob.aload(1);
        cob.lload(4);
        cob.iconst_1();
        cob.invokeinterface(PRICE_RULE, "apply", APPLY);
        cob.lreturn();
      });
    });
    MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(bytes, true);
    return (Pipe) lookup.lookupClass().getDeclaredConstructor().newInstance();
  }

  private static void emitHelper(java.lang.classfile.ClassBuilder clb, String name, int mul) {
    clb.withMethodBody(name, HELPER, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC, cob -> {
      cob.lload(0);
      cob.ldc((long) mul);
      cob.lmul();
      cob.lload(0);
      cob.ladd();
      cob.ldc(17L);
      cob.lxor();
      cob.lload(0);
      cob.ldc(3L);
      cob.lmul();
      cob.ladd();
      for (int i = 0; i < 8; i++) {
        cob.lload(0);
        cob.ldc((long) (i + 1));
        cob.ladd();
        cob.lxor();
      }
      cob.lreturn();
    });
  }
}
