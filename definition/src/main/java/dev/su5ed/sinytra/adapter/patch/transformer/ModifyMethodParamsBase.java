package dev.su5ed.sinytra.adapter.patch.transformer;

import com.mojang.logging.LogUtils;
import dev.su5ed.sinytra.adapter.patch.*;
import it.unimi.dsi.fastutil.ints.*;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypeReference;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;

import java.util.*;

import static dev.su5ed.sinytra.adapter.patch.PatchImpl.MIXINPATCH;

public abstract class ModifyMethodParamsBase implements MethodTransform {
    private static final Logger LOGGER = LogUtils.getLogger();
    @Nullable
    protected final LVTFixer lvtFixer;

    protected ModifyMethodParamsBase(@Nullable LVTFixer lvtFixer) {
        this.lvtFixer = lvtFixer;
    }

    protected abstract Type[] getReplacementParameters(Type[] original, AnnotationNode annotation);

    @Override
    public Collection<String> getAcceptedAnnotations() {
        return Set.of(Patch.INJECT, Patch.MODIFY_ARG, Patch.OVERWRITE, Patch.MODIFY_VAR);
    }

    @Override
    public boolean apply(ClassNode classNode, MethodNode methodNode, AnnotationNode annotation, Map<String, AnnotationValueHandle<?>> annotationValues, PatchContext context) {
        Type[] parameterTypes = Type.getArgumentTypes(methodNode.desc);
        Type[] newParameterTypes = getReplacementParameters(parameterTypes, annotation);
        Deque<IntObjectPair<Type>> insertionIndices = new ArrayDeque<>();
        Int2ObjectMap<Type> replacementIndices = new Int2ObjectOpenHashMap<>();
        int offset = (methodNode.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;

        int i = 0;
        for (int j = 0; i < parameterTypes.length; ) {
            if (j < newParameterTypes.length) {
                Type type = newParameterTypes[j];
                if (!parameterTypes[i].equals(type)) {
                    if (i == j && this.lvtFixer != null) {
                        replacementIndices.put(offset + j, type);
                    } else {
                        insertionIndices.add(IntObjectPair.of(j, type));
                        j++;
                        continue;
                    }
                }
                j++;
            }
            i++;
        }
        if (i != methodNode.parameters.size() && this.lvtFixer == null) {
            throw new RuntimeException("Unable to patch LVT capture, incompatible parameters");
        }

        if (annotation.desc.equals(Patch.MODIFY_VAR)) {
            AnnotationValueHandle<Integer> indexHandle = (AnnotationValueHandle<Integer>) annotationValues.get("index");
            if (indexHandle != null) {
                insertionIndices.forEach(pair -> {
                    int localIndex = offset + pair.firstInt();
                    int indexValue = indexHandle.get();
                    if (localIndex >= indexValue) {
                        indexHandle.set(indexValue + 1);
                    }
                });
            }
            return true;
        }

        Type returnType = Type.getReturnType(methodNode.desc);
        String newDesc = Type.getMethodDescriptor(returnType, newParameterTypes);
        LOGGER.info(MIXINPATCH, "Changing descriptor of method {}.{}{} to {}", classNode.name, methodNode.name, methodNode.desc, newDesc);

        LocalVariableNode self = methodNode.localVariables.stream().filter(lvn -> lvn.index == 0).findFirst().orElseThrow();
        while (!insertionIndices.isEmpty()) {
            IntObjectPair<Type> pair = insertionIndices.pop();
            int index = pair.firstInt();
            Type type = pair.second();
            int lvtOrdinal = offset + index;
            int lvtIndex;
            if (index > offset) {
                List<LocalVariableNode> lvt = methodNode.localVariables.stream().sorted(Comparator.comparingInt(lvn -> lvn.index)).toList();
                lvtIndex = lvt.get(lvtOrdinal).index;
            } else {
                lvtIndex = lvtOrdinal;
            }
            ParameterNode newParameter = new ParameterNode(null, Opcodes.ACC_SYNTHETIC);

            if (index < methodNode.parameters.size()) methodNode.parameters.add(index, newParameter);
            else methodNode.parameters.add(newParameter);
            for (LocalVariableNode localVariable : methodNode.localVariables) {
                if (localVariable.index >= lvtIndex) {
                    localVariable.index++;
                }
            }
            methodNode.localVariables.add(new LocalVariableNode("adapter_injected_" + index, type.getDescriptor(), null, self.start, self.end, lvtIndex));

            // TODO All visible/invisible annotations
            if (methodNode.invisibleParameterAnnotations != null) {
                List<List<AnnotationNode>> annotations = new ArrayList<>(Arrays.asList(methodNode.invisibleParameterAnnotations));
                if (index < annotations.size()) {
                    annotations.add(index, null);
                    methodNode.invisibleParameterAnnotations = (List<AnnotationNode>[]) annotations.toArray(List[]::new);
                    methodNode.invisibleAnnotableParameterCount = annotations.size();
                }
            }
            if (methodNode.invisibleTypeAnnotations != null) {
                List<TypeAnnotationNode> invisibleTypeAnnotations = methodNode.invisibleTypeAnnotations;
                for (int j = 0; j < invisibleTypeAnnotations.size(); j++) {
                    TypeAnnotationNode typeAnnotation = invisibleTypeAnnotations.get(j);
                    TypeReference ref = new TypeReference(typeAnnotation.typeRef);
                    int typeIndex = ref.getFormalParameterIndex();
                    if (ref.getSort() == TypeReference.METHOD_FORMAL_PARAMETER && typeIndex >= index) {
                        invisibleTypeAnnotations.set(j, new TypeAnnotationNode(TypeReference.newFormalParameterReference(typeIndex + 1).getValue(), typeAnnotation.typePath, typeAnnotation.desc));
                    }
                }
            }
            if (methodNode.visibleLocalVariableAnnotations != null) {
                for (LocalVariableAnnotationNode localVariableAnnotation : methodNode.visibleLocalVariableAnnotations) {
                    List<Integer> annotationIndices = localVariableAnnotation.index;
                    for (int j = 0; j < annotationIndices.size(); j++) {
                        Integer annoIndex = annotationIndices.get(j);
                        if (annoIndex >= lvtIndex) {
                            annotationIndices.set(j, annoIndex + 1);
                        }
                    }
                }
            }
            for (AbstractInsnNode insn : methodNode.instructions) {
                if (insn instanceof VarInsnNode varInsnNode && varInsnNode.var >= lvtIndex) {
                    varInsnNode.var++;
                }
            }
        }
        replacementIndices.forEach((index, type) -> {
            LocalVariableNode localVar = methodNode.localVariables.get(index);
            localVar.desc = type.getDescriptor();
            localVar.signature = null;
        });
        if (!replacementIndices.isEmpty() && this.lvtFixer != null) {
            //noinspection ForLoopReplaceableByForEach
            for (ListIterator<AbstractInsnNode> iterator = methodNode.instructions.iterator(); iterator.hasNext(); ) {
                AbstractInsnNode insn = iterator.next();
                if (insn instanceof VarInsnNode varInsn && replacementIndices.containsKey(varInsn.var)) {
                    this.lvtFixer.accept(varInsn.var, varInsn, methodNode.instructions);
                }
            }
        }
        methodNode.desc = newDesc;
        return true;
    }
}
