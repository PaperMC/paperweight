/*
 * Forge Auto Renaming Tool
 * Copyright (c) 2021
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package io.papermc.paperweight.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;

public final class SpongeRecordFixer {
    private SpongeRecordFixer() {
    }

    public static void fix(final ClassNode classNode, final ClassNodeCache classNodeCache) {
        new Fixer(classNode, classNodeCache).fix();
    }

    private static class Fixer {
        private final ClassNodeCache classNodeCache;
        private Map<String, RecordComponentNode> components;
        private TypeParameterCollector paramCollector;
        private boolean isRecord;
        private boolean patchComponents = true;
        private boolean patchSignature = true;
        private ClassNode node;

        public Fixer(ClassNode parent, ClassNodeCache classNodeCache) {
            this.node = parent;
            this.classNodeCache = classNodeCache;
        }

        void fix() {
            this.isRecord = "java/lang/Record".equals(this.node.superName);
            // todo: validate type parameters from superinterfaces
            // this would need to get signature information from bytecode + runtime classes

            boolean hasRecordComponents = false;
            if (this.node.recordComponents != null) {
                hasRecordComponents = true;
                for (RecordComponentNode recordComp : this.node.recordComponents) {
                    if (recordComp.signature != null && patchSignature) { // signature implies non-primitive type
                        if (paramCollector == null) paramCollector = new TypeParameterCollector();
                        paramCollector.baseType = Type.getType(recordComp.descriptor);
                        paramCollector.param = TypeParameterCollector.FIELD;
                        new SignatureReader(recordComp.signature).accept(paramCollector);
                    }
                }
            }

            for (FieldNode field : this.node.fields) {
                // We want any fields that are final and not static. Proguard sometimes increases the visibility of record component fields to be higher than private.
                // These fields still need to have record components generated, so we need to ignore ACC_PRIVATE.
                if (isRecord && patchComponents && (field.access & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC)) == Opcodes.ACC_FINAL) {
                    // Make sure the visibility gets set back to private
                    field.access = field.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PRIVATE;
                    // Manually add the record component back if this class doesn't have any
                    if (components == null)
                        components = new LinkedHashMap<>();
                    components.put(field.name + field.desc, new RecordComponentNode(field.name, field.desc, field.signature));
                }

                if (isRecord && field.signature != null && patchSignature) { // signature implies non-primitive type
                    if (paramCollector == null) paramCollector = new TypeParameterCollector();
                    paramCollector.baseType = Type.getType(field.desc);
                    paramCollector.param = TypeParameterCollector.FIELD;
                    new SignatureReader(field.signature).accept(paramCollector);
                }
            }

            for (MethodNode method : this.node.methods) {
                if (isRecord && method.signature != null && patchSignature) { // signature implies non-primitive type
                    if (paramCollector == null) paramCollector = new TypeParameterCollector();
                    paramCollector.baseType = Type.getType(method.desc);
                    paramCollector.param = TypeParameterCollector.FIELD; // start out before parameters come in
                    new SignatureReader(method.signature).accept(paramCollector);
                    if (paramCollector.declaredParams != null) {
                        paramCollector.declaredParams.clear();
                    }
                }
            }

            if (isRecord && !hasRecordComponents && components != null) {
                List<RecordComponentNode> nodes = new ArrayList<>(this.components.size());
                for (RecordComponentNode entry : this.components.values()) {
                    nodes.add(entry);
                }
                this.node.recordComponents = nodes;
            }
            if (isRecord && patchSignature && paramCollector != null && !paramCollector.typeParameters.isEmpty()) {
                // Proguard also strips the Signature attribute, so we have to reconstruct that, to a point where this class is accepted by
                // javac when on the classpath. This requires every type parameter referenced to have been declared within the class.
                // Records are implicitly static and have a defined superclass of java/lang/Record, so there can be type parameters in play from:
                // - fields
                // - methods (which can declare their own formal parameters)
                // - record components
                // - superinterfaces (less important, we just get raw type warnings)
                //
                // This will not be perfect, but provides enough information to allow compilation and enhance decompiler output.
                // todo: allow type-specific rules to infer deeper levels (for example, T with raw type Comparable is probably Comparable<T>)

                final SignatureWriter sw = new SignatureWriter();
                // Formal parameters
                // find all used type parameters, plus guesstimated bounds
                for (Map.Entry<String, String> param : paramCollector.typeParameters.entrySet()) {
                    sw.visitFormalTypeParameter(param.getKey());
                    if (!param.getValue().equals(TypeParameterCollector.UNKNOWN)) {
                        final ClassNode cls = this.classNodeCache.findClass(param.getValue());
                        if (cls != null) {
                            SignatureVisitor parent;
                            if ((cls.access & Opcodes.ACC_INTERFACE) != 0) {
                                parent = sw.visitInterfaceBound();
                            } else {
                                parent = sw.visitClassBound();
                            }
                            parent.visitClassType(param.getValue());
                            parent.visitEnd();
                            continue;
                        } else {
                            throw new RuntimeException("Unable to find information for type " + param.getValue());
                        }
                    }
                    SignatureVisitor cls = sw.visitClassBound();
                    cls.visitClassType("java/lang/Object");
                    cls.visitEnd();
                }

                // Supertype (always Record)
                final SignatureVisitor sv = sw.visitSuperclass();
                sv.visitClassType(node.superName);
                sv.visitEnd();

                // Superinterfaces
                for (final String superI : node.interfaces) {
                    final SignatureVisitor itfV = sw.visitInterface();
                    itfV.visitClassType(superI);
                    sv.visitEnd();
                }
                String newSignature = sw.toString();
                // debug.accept("New signature for " + node.name + ": " + newSignature);
                node.signature = newSignature;
            }
        }
    }

    static class TypeParameterCollector extends SignatureVisitor {
        private static final int RETURN_TYPE = -2;
        static final int FIELD = -1;
        static final String UNKNOWN = "???";
        Map<String, String> typeParameters = new HashMap<>(); // <Parameter, FieldType>
        Type baseType;
        int param = -1;
        int level;
        Set<String> declaredParams;

        public TypeParameterCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visitFormalTypeParameter(String name) {
            if (declaredParams == null)
                declaredParams = new HashSet<>();
            declaredParams.add(name);
        }

        @Override
        public void visitTypeVariable(String name) {
            if (!typeParameters.containsKey(name) || typeParameters.get(name).equals(UNKNOWN)) {
                if (level == 0 && baseType != null && (declaredParams == null || !declaredParams.contains(name))) {
                    String typeName;
                    switch (param) {
                        case FIELD: // field
                            typeName = baseType.getInternalName();
                            break;
                        case RETURN_TYPE: // method return value
                            typeName = baseType.getReturnType().getInternalName();
                            break;
                        default:
                            typeName = baseType.getArgumentTypes()[param].getInternalName();
                            break;
                    }
                    typeParameters.put(name, typeName);
                } else {
                    typeParameters.put(name, UNKNOWN);
                }
            }
            super.visitTypeVariable(name);
        }

        @Override
        public void visitClassType(String name) {
            level++;
            super.visitClassType(name);
        }

        @Override
        public void visitInnerClassType(String name) {
            level++;
            super.visitInnerClassType(name);
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            level++;
            return super.visitTypeArgument(wildcard);
        }

        @Override
        public void visitEnd() {
            if (level-- <= 0) {
                throw new IllegalStateException("Unbalanced signature levels");
            }
            super.visitEnd();
        }

        // for methods

        @Override
        public SignatureVisitor visitParameterType() {
            this.param++;
            return super.visitParameterType();
        }

        @Override
        public SignatureVisitor visitReturnType() {
            this.param = RETURN_TYPE;
            return super.visitReturnType();
        }
    }
}