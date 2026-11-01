package io.github.calamech.rloaded.runtime.engine;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinService;

/**
 * Applies a Mixin configuration after startup, including to classes that are already loaded.
 *
 * <p>Mixin selects its configurations once, before the first class is transformed, so a late
 * configuration is selected here by calling its processor directly. Each target that is already
 * loaded is then rebuilt from its pre-Mixin bytes with every Mixin that targets it, and redefined.
 * Mixin's check for targets loaded too early is skipped for such a configuration, since its loaded
 * targets are redefined here. Adding methods, fields and interfaces to a loaded class needs the
 * JetBrains Runtime started with {@code -XX:+AllowEnhancedClassRedefinition}.
 */
public final class LateMixins {
  private LateMixins() {}

  /**
   * Adds the Mixin configuration {@code config} and redefines its loaded targets.
   *
   * @return the names of the classes that were redefined
   */
  public static List<String> apply(String config, Instrumentation instrumentation)
      throws ReflectiveOperationException, UnmodifiableClassException {
    IMixinTransformer transformer =
        (IMixinTransformer) MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
    Object processor = processorOf(transformer);
    Object knotClassLoader = knotClassLoader();
    List<ClassDefinition> definitions = new ArrayList<>();
    // Every class transformation holds the transformer's monitor, then its processor's, while it
    // reads the configurations, and may select pending ones itself. Without it, a class loaded on
    // another thread sees the lists change under it, or selects this configuration before its
    // tracker is replaced. Taking the processor's monitor alone deadlocks against that order.
    synchronized (transformer) {
      Mixins.addConfiguration(config);
      skipLoadedTargetCheck(config);
      select(processor);
      Set<String> targets = targetsOf(processor, config);
      for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
        if (targets.contains(loaded.getName())) {
          byte[] preMixin = preMixinBytes(knotClassLoader, loaded.getName());
          byte[] transformed =
              transformer.transformClassBytes(loaded.getName(), loaded.getName(), preMixin);
          definitions.add(new ClassDefinition(loaded, transformed));
        }
      }
    }
    instrumentation.redefineClasses(definitions.toArray(new ClassDefinition[0]));
    return definitions.stream()
        .map(definition -> definition.getDefinitionClass().getName())
        .toList();
  }

  // MixinInfo refuses a mixin whose target is loaded, because Mixin alone could not apply it any
  // more. It asks the class tracker of its configuration's service, and nothing else in Mixin does.
  private static void skipLoadedTargetCheck(String config) throws ReflectiveOperationException {
    Object pending =
        Mixins.getConfigs().stream()
            .filter(candidate -> candidate.getName().equals(config))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Mixin did not register " + config))
            .getConfig();
    Field serviceField = pending.getClass().getDeclaredField("service");
    serviceField.setAccessible(true);
    IMixinService service = (IMixinService) serviceField.get(pending);
    IClassTracker tracker =
        new UnloadedClassTracker(
            Objects.requireNonNull(
                service.getClassTracker(), "the Mixin service has no class tracker"));
    IMixinService withTracker =
        (IMixinService)
            Proxy.newProxyInstance(
                IMixinService.class.getClassLoader(),
                new Class<?>[] {IMixinService.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("getClassTracker")) {
                    return tracker;
                  }
                  try {
                    return method.invoke(service, arguments);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    serviceField.set(pending, withTracker);
  }

  /** Reports every class as not loaded, and forwards the rest to Mixin's own tracker. */
  private record UnloadedClassTracker(IClassTracker delegate) implements IClassTracker {
    @Override
    public void registerInvalidClass(String className) {
      delegate.registerInvalidClass(className);
    }

    @Override
    public boolean isClassLoaded(String className) {
      return false;
    }

    @Override
    public String getClassRestrictions(String className) {
      return delegate.getClassRestrictions(className);
    }
  }

  private static Object processorOf(IMixinTransformer transformer)
      throws ReflectiveOperationException {
    Field processor = transformer.getClass().getDeclaredField("processor");
    processor.setAccessible(true);
    return processor.get(transformer);
  }

  // MixinProcessor.checkSelect only selects new configurations before the first transformation.
  private static void select(Object processor) throws ReflectiveOperationException {
    Method select = processor.getClass().getDeclaredMethod("select", MixinEnvironment.class);
    select.setAccessible(true);
    invoke(select, processor, MixinEnvironment.getCurrentEnvironment());
  }

  // Selected configurations leave Mixins.getConfigs() for the processor's own list.
  private static Set<String> targetsOf(Object processor, String config)
      throws ReflectiveOperationException {
    Field configs = processor.getClass().getDeclaredField("configs");
    configs.setAccessible(true);
    return ((List<?>) configs.get(processor))
        .stream()
            .map(IMixinConfig.class::cast)
            .filter(candidate -> candidate.getName().equals(config))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Mixin did not select " + config))
            .getTargets()
            .stream()
            .map(name -> name.replace('/', '.'))
            .collect(Collectors.toSet());
  }

  private static Object knotClassLoader() throws ReflectiveOperationException {
    Object launcher = FabricLauncherBase.getLauncher();
    Field classLoader = launcher.getClass().getDeclaredField("classLoader");
    classLoader.setAccessible(true);
    return classLoader.get(launcher);
  }

  private static byte[] preMixinBytes(Object knotClassLoader, String className)
      throws ReflectiveOperationException {
    Method method = knotClassLoader.getClass().getMethod("getPreMixinClassBytes", String.class);
    method.setAccessible(true);
    return (byte[]) invoke(method, knotClassLoader, className);
  }

  private static Object invoke(Method method, Object target, Object... arguments)
      throws IllegalAccessException, InvocationTargetException {
    return method.invoke(target, arguments);
  }
}
