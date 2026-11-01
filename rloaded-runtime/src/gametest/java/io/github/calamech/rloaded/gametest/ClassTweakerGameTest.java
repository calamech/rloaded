package io.github.calamech.rloaded.gametest;

import io.github.calamech.rloaded.runtime.engine.HotAdd;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Checks that a mod's access widener applies to a game class loaded long before the add: the mod's
 * initializer reads {@code MinecraftServer.LOGGER}, a private field, which only works once the
 * running class was redefined with the field made accessible.
 */
public final class ClassTweakerGameTest {
  private static final String INIT = "io/github/calamech/rloaded/gametest/widen/Init";

  @GameTest
  public void accessWidenerAppliesToLoadedClasses(GameTestHelper helper) throws Exception {
    helper.assertTrue(
        Modifier.isPrivate(MinecraftServer.class.getDeclaredField("LOGGER").getModifiers()),
        "MinecraftServer.LOGGER is not private before the add");
    Path jar = Files.createTempDirectory("rloaded-widener").resolve("widener.jar");
    try (OutputStream file = Files.newOutputStream(jar);
        ZipOutputStream zip = new ZipOutputStream(file)) {
      put(
          zip,
          "fabric.mod.json",
          ("{\"schemaVersion\": 1, \"id\": \"widens-logger\", \"version\": \"1.0.0\","
                  + " \"accessWidener\": \"widens.accesswidener\","
                  + " \"entrypoints\": {\"main\": [\""
                  + INIT.replace('/', '.')
                  + "\"]}}")
              .getBytes(StandardCharsets.UTF_8));
      put(
          zip,
          "widens.accesswidener",
          ("accessWidener\tv2\t"
                  + FabricLauncherBase.getLauncher().getMappingConfiguration().getRuntimeNamespace()
                  + "\naccessible\tfield\tnet/minecraft/server/MinecraftServer\tLOGGER"
                  + "\tLorg/slf4j/Logger;\n")
              .getBytes(StandardCharsets.UTF_8));
      put(zip, INIT + ".class", initializer());
    }

    List<String> loaded = HotAdd.load(List.of(jar), Agent.instrumentation());

    helper.assertValueEqual(loaded, List.of("widens-logger"), "loaded mods");
    helper.assertTrue(
        Modifier.isPublic(MinecraftServer.class.getDeclaredField("LOGGER").getModifiers()),
        "MinecraftServer.LOGGER is not public after the add");
    Object seen =
        Class.forName(INIT.replace('/', '.'), true, ClassTweakerGameTest.class.getClassLoader())
            .getField("seen")
            .get(null);
    helper.assertTrue(seen != null, "the mod's initializer did not read MinecraftServer.LOGGER");
    helper.succeed();
  }

  private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }

  // A ModInitializer that stores MinecraftServer.LOGGER in its public static field "seen".
  private static byte[] initializer() {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(
        Opcodes.V21,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
        INIT,
        null,
        "java/lang/Object",
        new String[] {"net/fabricmc/api/ModInitializer"});
    writer
        .visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "seen", "Ljava/lang/Object;", null, null)
        .visitEnd();
    MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    constructor.visitCode();
    constructor.visitVarInsn(Opcodes.ALOAD, 0);
    constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    constructor.visitInsn(Opcodes.RETURN);
    constructor.visitMaxs(0, 0);
    constructor.visitEnd();
    MethodVisitor initialize =
        writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
    initialize.visitCode();
    initialize.visitFieldInsn(
        Opcodes.GETSTATIC, "net/minecraft/server/MinecraftServer", "LOGGER", "Lorg/slf4j/Logger;");
    initialize.visitFieldInsn(Opcodes.PUTSTATIC, INIT, "seen", "Ljava/lang/Object;");
    initialize.visitInsn(Opcodes.RETURN);
    initialize.visitMaxs(0, 0);
    initialize.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }
}
