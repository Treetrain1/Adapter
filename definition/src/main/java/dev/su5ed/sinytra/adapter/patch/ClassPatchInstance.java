package dev.su5ed.sinytra.adapter.patch;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.su5ed.sinytra.adapter.patch.selector.*;
import dev.su5ed.sinytra.adapter.patch.serialization.MethodTransformSerialization;
import dev.su5ed.sinytra.adapter.patch.transformer.DisableMixin;
import dev.su5ed.sinytra.adapter.patch.transformer.ModifyInjectionPoint;
import dev.su5ed.sinytra.adapter.patch.transformer.RedirectShadowMethod;
import dev.su5ed.sinytra.adapter.patch.util.MethodQualifier;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

public final class ClassPatchInstance extends PatchInstance {
    public static final Codec<ClassPatchInstance> CODEC = RecordCodecBuilder
        .<ClassPatchInstance>create(instance -> instance.group(
            Codec.STRING.listOf().optionalFieldOf("targetClasses", List.of()).forGetter(p -> p.targetClasses),
            MethodMatcher.CODEC.listOf().optionalFieldOf("targetMethods", List.of()).forGetter(p -> p.targetMethods),
            InjectionPointMatcher.CODEC.listOf().optionalFieldOf("targetInjectionPoints", List.of()).forGetter(p -> p.targetInjectionPoints),
            Codec.STRING.listOf().optionalFieldOf("targetAnnotations", List.of()).forGetter(p -> p.targetAnnotations),
            MethodTransformSerialization.METHOD_TRANSFORM_CODEC.listOf().fieldOf("transforms").forGetter(p -> p.transforms)
        ).apply(instance, ClassPatchInstance::new))
        .flatComapMap(Function.identity(), obj -> obj.targetAnnotationValues != null ? DataResult.error(() -> "Cannot serialize targetAnnotationValues") : DataResult.success(obj));

    private final List<MethodMatcher> targetMethods;
    private final List<InjectionPointMatcher> targetInjectionPoints;

    private ClassPatchInstance(List<String> targetClasses, List<MethodMatcher> targetMethods, List<InjectionPointMatcher> targetInjectionPoints, List<String> targetAnnotations, List<MethodTransform> transforms) {
        this(targetClasses, targetMethods, targetInjectionPoints, targetAnnotations, map -> true, List.of(), transforms);
    }

    private ClassPatchInstance(List<String> targetClasses, List<MethodMatcher> targetMethods, List<InjectionPointMatcher> targetInjectionPoints, List<String> targetAnnotations, Predicate<Map<String, AnnotationValueHandle<?>>> targetAnnotationValues, List<ClassTransform> classTransforms, List<MethodTransform> transforms) {
        super(targetClasses, targetAnnotations, targetAnnotationValues, classTransforms, transforms);

        this.targetMethods = targetMethods;
        this.targetInjectionPoints = targetInjectionPoints;
    }

    @Override
    public Codec<? extends PatchInstance> codec() {
        return CODEC;
    }

    protected boolean checkAnnotation(String owner, MethodNode method, AnnotationHandle methodAnnotation, PatchEnvironment remaper, MethodContext.Builder builder) {
        builder.methodAnnotation(methodAnnotation);
        if (methodAnnotation.matchesDesc(Patch.OVERWRITE)) {
            return this.targetMethods.isEmpty() || this.targetMethods.stream().anyMatch(matcher -> matcher.matches(method.name, method.desc));
        } else if (KNOWN_MIXIN_TYPES.contains(methodAnnotation.getDesc())) {
            return methodAnnotation.<List<String>>getValue("method")
                .map(value -> {
                    for (String target : value.get()) {
                        String remappedTarget = remaper.remap(owner, target);
                        MethodQualifier qualifier = MethodQualifier.create(remappedTarget).filter(q -> q.name() != null).orElse(null);
                        if (qualifier == null) {
                            continue;
                        }
                        String targetName = qualifier.name();
                        String targetDesc = qualifier.desc();
                        return (this.targetMethods.isEmpty() || this.targetMethods.stream().anyMatch(matcher -> matcher.matches(targetName, targetDesc)))
                            // Must call checkInjectionPoint first so that any present @At annotation is added to the method context builder
                            && (checkInjectionPoint(owner, methodAnnotation, remaper, builder) || this.targetInjectionPoints.isEmpty());
                    }
                    return false;
                })
                .orElse(false);
        }
        return false;
    }

    private boolean checkInjectionPoint(String owner, AnnotationHandle methodAnnotation, PatchEnvironment environment, MethodContext.Builder builder) {
        return methodAnnotation.getNested("at")
            .map(node -> checkInjectionPointAnnotation(owner, node, environment, builder))
            // Check slice.from target
            .or(() -> methodAnnotation.<AnnotationNode>getValue("slice")
                .flatMap(slice -> slice.findNested("from")
                    .map(from -> checkInjectionPointAnnotation(owner, from, environment, builder))))
            .orElse(false);
    }

    private boolean checkInjectionPointAnnotation(String owner, AnnotationHandle injectionPointAnnotation, PatchEnvironment environment, MethodContext.Builder builder) {
        return injectionPointAnnotation.<String>getValue("target")
            .map(target -> {
                AnnotationValueHandle<String> value = injectionPointAnnotation.<String>getValue("value").orElse(null);
                String valueStr = value != null ? value.get() : null;
                String targetStr = environment.remap(owner, target.get());
                if (this.targetInjectionPoints.isEmpty() || this.targetInjectionPoints.stream().anyMatch(pred -> pred.test(valueStr, targetStr))) {
                    builder.injectionPointAnnotation(injectionPointAnnotation);
                    return true;
                }
                return false;
            })
            .orElse(false);
    }

    protected static class ClassPatchBuilderImpl extends BaseBuilder<ClassPatchBuilder> implements ClassPatchBuilder {
        private final Set<MethodMatcher> targetMethods = new HashSet<>();
        private final Set<InjectionPointMatcher> targetInjectionPoints = new HashSet<>();

        @Override
        public ClassPatchBuilder targetMethod(String... targets) {
            for (String target : targets) {
                this.targetMethods.add(new MethodMatcher(target));
            }
            return this;
        }

        @Override
        public ClassPatchBuilder targetInjectionPoint(String value, String target) {
            this.targetInjectionPoints.add(new InjectionPointMatcher(value, target));
            return this;
        }

        @Override
        public ClassPatchBuilder modifyInjectionPoint(String value, String target, boolean resetValues, @Nullable Integer ordinal) {
            return transform(new ModifyInjectionPoint(value, target, resetValues, ordinal));
        }

        @Override
        public ClassPatchBuilder redirectShadowMethod(String original, String target, BiConsumer<MethodInsnNode, InsnList> callFixer) {
            return transform(new RedirectShadowMethod(original, target, callFixer));
        }

        @Override
        public ClassPatchBuilder disable() {
            return transform(DisableMixin.INSTANCE);
        }

        @Override
        public PatchInstance build() {
            return new ClassPatchInstance(
                List.copyOf(this.targetClasses),
                List.copyOf(this.targetMethods),
                List.copyOf(this.targetInjectionPoints),
                List.copyOf(this.targetAnnotations),
                this.targetAnnotationValues,
                List.copyOf(this.classTransforms),
                List.copyOf(this.transforms)
            );
        }
    }
}
