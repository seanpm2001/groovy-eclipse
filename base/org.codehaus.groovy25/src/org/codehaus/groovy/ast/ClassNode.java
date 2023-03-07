/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.ast;

import org.apache.groovy.ast.tools.ClassNodeUtils;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.tools.ParameterUtils;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.transform.ASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;
import org.codehaus.groovy.vmplugin.VMPluginFactory;
import groovyjarjarasm.asm.Opcodes;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

/**
 * Represents a class in the AST.
 * <p>
 * A ClassNode should be created using the methods in ClassHelper.
 * This ClassNode may be used to represent a class declaration or
 * any other type. This class uses a proxy mechanism allowing to
 * create a class for a plain name at AST creation time. In another
 * phase of the compiler the real ClassNode for the plain name may be
 * found. To avoid the need of exchanging this ClassNode with an
 * instance of the correct ClassNode the correct ClassNode is set as
 * redirect. Most method calls are then redirected to that ClassNode.
 * <p>
 * There are three types of ClassNodes:
 * <ol>
 * <li> Primary ClassNodes:<br>
 * A primary ClassNode is one where we have a source representation
 * which is to be compiled by Groovy and which we have an AST for.
 * The groovy compiler will output one class for each such ClassNode
 * that passes through AsmBytecodeGenerator... not more, not less.
 * That means for example Closures become such ClassNodes too at
 * some point.
 * <li> ClassNodes create through different sources (typically created
 * from a java.lang.reflect.Class object):<br>
 * The compiler will not output classes from these, the methods
 * usually do not contain bodies. These kind of ClassNodes will be
 * used in different checks, but not checks that work on the method
 * bodies. For example if such a ClassNode is a super class to a primary
 * ClassNode, then the abstract method test and others will be done
 * with data based on these. Theoretically it is also possible to mix both
 * (1 and 2) kind of classes in a hierarchy, but this probably works only
 *  in the newest Groovy versions. Such ClassNodes normally have to
 *  isResolved() returning true without having a redirect.In the Groovy
 *  compiler the only version of this, that exists, is a ClassNode created
 *  through a Class instance
 * <li> Labels:<br>
 * ClassNodes created through ClassHelper.makeWithoutCaching. They
 * are place holders, its redirect points to the real structure, which can
 * be a label too, but following all redirects it should end with a ClassNode
 * from one of the other two categories. If ResolveVisitor finds such a
 * node, it tries to set the redirects. Any such label created after
 * ResolveVisitor has done its work needs to have a redirect pointing to
 * case 1 or 2. If not the compiler may react strange... this can be considered
 * as a kind of dangling pointer.
 * </ol>
 * <b>Note:</b> the redirect mechanism is only allowed for classes
 * that are not primary ClassNodes. Typically this is done for classes
 * created by name only.  The redirect itself can be any type of ClassNode.
 * <p>
 * To describe generic type signature see {@link #getGenericsTypes()} and
 * {@link #setGenericsTypes(GenericsType[])}. These methods are not proxied,
 * they describe the type signature used at the point of declaration or the
 * type signatures provided by the class. If the type signatures provided
 * by the class are needed, then a call to {@link #redirect()} will help.
 *
 * @see org.codehaus.groovy.ast.ClassHelper
 */
public class ClassNode extends AnnotatedNode implements Opcodes {

    // GRECLIPSE private->protected
    protected static class MapOfLists {
        // GRECLIPSE private->protected
        protected Map<Object, List<MethodNode>> map;

        List<MethodNode> get(Object key) {
            return map == null ? null : map.get(key);
        }
        // GRECLIPSE private->public
        public List<MethodNode> getNotNull(Object key) {
            List<MethodNode> list = get(key);
            if (list == null) list = Collections.emptyList();
            return list;
        }

        void put(Object key, MethodNode value) {
            if (map == null) {
                 map = new LinkedHashMap<Object, List<MethodNode>>();
            }
            if (map.containsKey(key)) {
                get(key).add(value);
            } else {
                List<MethodNode> list = new ArrayList<MethodNode>(2);
                list.add(value);
                map.put(key, list);
            }
        }

        void remove(Object key, MethodNode value) {
            get(key).remove(value);
        }
    }

    public static final ClassNode[] EMPTY_ARRAY = new ClassNode[0];
    public static final ClassNode THIS = new ImmutableClassNode(Object.class);
    public static final ClassNode SUPER = new ImmutableClassNode(Object.class);

    private String name;
    private int modifiers;
    private boolean syntheticPublic;
    private ClassNode[] interfaces;
    private MixinNode[] mixins;
    // GRECLIPSE private->protected
    protected List<ConstructorNode> constructors;
    private List<Statement> objectInitializers;
    // GRECLIPSE private->protected
    protected MapOfLists methods;
    private List<MethodNode> methodsList;
    private LinkedList<FieldNode> fields;
    private List<PropertyNode> properties;
    private Map<String, FieldNode> fieldIndex;
    private ModuleNode module;
    private CompileUnit compileUnit;
    private boolean staticClass;
    private boolean scriptBody;
    private boolean script;
    private ClassNode superClass;
    protected boolean isPrimaryNode;
    protected List<InnerClassNode> innerClasses;

    /**
     * The AST Transformations to be applied during compilation.
     */
    private Map<CompilePhase, Map<Class<? extends ASTTransformation>, Set<ASTNode>>> transformInstances;

    // use this to synchronize access for the lazy init
    protected final Object lazyInitLock = new Object();

    // clazz!=null when resolved
    protected Class clazz;
    // only false when this classNode is constructed from a class
    // GRECLIPSE private->protected
    protected volatile boolean lazyInitDone = true;
    // not null if if the ClassNode is an array
    private ClassNode componentType;
    // if not null this instance is handled as proxy
    // for the redirect
    private ClassNode redirect;
    // flag if the classes or its members are annotated
    private boolean annotated;

    // type spec for generics
    private GenericsType[] genericsTypes;
    private boolean usesGenerics;

    // if set to true the name getGenericsTypes consists
    // of 1 element describing the name of the placeholder
    private boolean placeholder;

    /**
     * Returns the {@code ClassNode} this node is a proxy for or the node itself.
     */
    public ClassNode redirect() {
        return (redirect == null ? this : redirect.redirect());
    }

    public boolean isRedirectNode() {
        return (redirect != null);
    }

    /**
     * Sets this instance as proxy for the given {@code ClassNode}.
     *
     * @param node the class to redirect to; if {@code null} the redirect is removed
     */
    public void setRedirect(ClassNode cn) {
        if (isPrimaryNode) throw new GroovyBugError("tried to set a redirect for a primary ClassNode ("+getName()+"->"+cn.getName()+").");
        if (cn != null && !isGenericsPlaceHolder()) cn = cn.redirect();
        if (cn == this) return;
        redirect = cn;
    }

    /**
     * Returns a {@code ClassNode} representing an array of the type represented
     * by this.
     */
    public ClassNode makeArray() {
        if (redirect != null) {
            ClassNode res = redirect().makeArray();
            res.componentType = this;
            return res;
        }
        ClassNode cn;
        if (clazz != null) {
            Class ret = Array.newInstance(clazz, 0).getClass();
            // don't use the ClassHelper here!
            cn = new ClassNode(ret, this);
        } else {
            cn = new ClassNode(this);
        }
        return cn;
    }

    /**
     * @return {@code true} if this instance is a primary {@code ClassNode}
     */
    public boolean isPrimaryClassNode() {
        return redirect().isPrimaryNode || (componentType != null && componentType.isPrimaryClassNode());
    }

    /**
     * Constructor used by {@code makeArray()} if no real class is available.
     */
    private ClassNode(ClassNode componentType) {
        /* GRECLIPSE edit
        this(componentType.getName() + "[]", ACC_PUBLIC, ClassHelper.OBJECT_TYPE);
        */
        this(computeArrayName(componentType), ACC_PUBLIC, ClassHelper.OBJECT_TYPE);
        // GRECLIPSE end
        this.componentType = componentType.redirect();
        isPrimaryNode = false;
    }

    // GRECLIPSE add
    /**
     * For a given component type compute the right 'name'.  Rules are as follows:
     * <ul>
     * <li> primitive component types: result is a name like "[I" or "[Z"
     * <li> array component types: follow the pattern for the component, if it starts '[' add another leading. if it ends with '[]' then do that
     * <li> reference types: Create [Lcom.foo.Bar; - this isn't quite right really as it should have '/' in...
     * </ul>
     */
    private static String computeArrayName(ClassNode componentType) {
        String componentName = componentType.getName();
        if (ClassHelper.isPrimitiveType(componentType)) {
            int len = componentName.length();
            if (len == 7) {
                return "[Z"; //boolean
            } else if (len == 6) {
                return "[D"; //double
            } else if (len == 5) {
                if (componentName.charAt(0) == 'f') {
                    return "[F"; //float
                } else {
                    return "[S"; //short
                }
            } else if (len == 4) {
                 switch (componentName.charAt(0)) {
                 case 'b': return "[B"; //byte
                 case 'c': return "[C"; //char
                 default:  return "[J"; //long
                 }
            } else {
                return "[I"; //int
            }
        } else if (componentType.isArray()) {
            // follow the pattern:
            if (componentName.charAt(0) == '[') {
                return new StringBuilder("[").append(componentName).toString();
            } else {
                return new StringBuilder(componentName).append("[]").toString();
            }
        } else {
            // reference type:
            return new StringBuilder("[L").append(componentType.getName()).append(";").toString();
        }
    }
    // GRECLIPSE end

    /**
     * Constructor used by {@code makeArray()} if a real class is available.
     */
    private ClassNode(Class c, ClassNode componentType) {
        this(c);
        this.componentType = componentType;
        isPrimaryNode = false;
    }

    /**
     * Creates a non-primary {@code ClassNode} from a real class.
     */
    public ClassNode(Class c) {
        this(c.getName(), c.getModifiers(), null, null, MixinNode.EMPTY_ARRAY);
        clazz = c;
        lazyInitDone = false;
        isPrimaryNode = false;
    }

    /**
     * The complete class structure will be initialized only when really needed
     * to avoid having too many objects during compilation.
     */
    // GRECLIPSE private->public
    public void lazyClassInit() {
        if (lazyInitDone) return;
        synchronized (lazyInitLock) {
            if (redirect != null) {
                throw new GroovyBugError("lazyClassInit called on a proxy ClassNode, that must not happen. " +
                                         "A redirect() call is missing somewhere!");
            }
            if (lazyInitDone) return;
            VMPluginFactory.getPlugin().configureClassNode(compileUnit, this);
            lazyInitDone = true;
        }
    }

    /**
     * Tracks the enclosing method for local inner classes.
     */
    private MethodNode enclosingMethod;

    public MethodNode getEnclosingMethod() {
        return redirect().enclosingMethod;
    }

    public void setEnclosingMethod(MethodNode enclosingMethod) {
        redirect().enclosingMethod = enclosingMethod;
    }

    /**
     * Indicates that this class has been "promoted" to public by Groovy when in
     * fact there was no public modifier explicitly in the source code. That is,
     * it remembers that it has applied Groovy's "public classes by default" rule.
     * This property is typically only of interest to AST transform writers.
     *
     * @return {@code true} if node is public but had no explicit public modifier
     */
    public boolean isSyntheticPublic() {
        return syntheticPublic;
    }

    public void setSyntheticPublic(boolean syntheticPublic) {
        this.syntheticPublic = syntheticPublic;
    }

    /**
     * @param name       the fully-qualified name of the class
     * @param modifiers  the modifiers; see {@link groovyjarjarasm.asm.Opcodes}
     * @param superClass the base class; use "java.lang.Object" if no direct base class
     */
    public ClassNode(String name, int modifiers, ClassNode superClass) {
        this(name, modifiers, superClass, EMPTY_ARRAY, MixinNode.EMPTY_ARRAY);
    }

    /**
     * @param name       the fully-qualified name of the class
     * @param modifiers  the modifiers; see {@link groovyjarjarasm.asm.Opcodes}
     * @param superClass the base class; use "java.lang.Object" if no direct base class
     * @param interfaces the interfaces for this class
     * @param mixins     the mixins for this class
     * @see org.objectweb.asm.Opcodes
     */
    public ClassNode(String name, int modifiers, ClassNode superClass, ClassNode[] interfaces, MixinNode[] mixins) {
        this.name = name;
        this.modifiers = modifiers;
        this.superClass = superClass;
        this.interfaces = interfaces;
        this.mixins = mixins;
        isPrimaryNode = true;
        if (superClass != null) {
            usesGenerics = superClass.isUsingGenerics();
        }
        if (!usesGenerics && interfaces != null) {
            for (ClassNode anInterface : interfaces) {
                usesGenerics = usesGenerics || anInterface.isUsingGenerics();
                if (usesGenerics) break;
            }
        }
        this.methods = new MapOfLists();
        this.methodsList = Collections.emptyList();
    }

    /**
     * Sets the superclass of this {@code ClassNode}.
     */
    public void setSuperClass(ClassNode superClass) {
        redirect().superClass = superClass;
    }

    /**
     * @return the fields associated with this {@code ClassNode}
     */
    public List<FieldNode> getFields() {
        if (redirect != null)
            return redirect().getFields();
        lazyClassInit();
        if (fields == null)
            fields = new LinkedList<FieldNode>();
        return fields;
    }

    /**
     * @return the array of interfaces which this ClassNode implements
     */
    public ClassNode[] getInterfaces() {
        // GRECLIPSE add
        if (hasInconsistentHierarchy()) return EMPTY_ARRAY;
        // GRECLIPSE end
        if (redirect != null)
            return redirect().getInterfaces();
        lazyClassInit();
        return interfaces;
    }

    public void setInterfaces(ClassNode[] interfaces) {
        if (redirect != null) {
            redirect().setInterfaces(interfaces);
        } else {
            this.interfaces = interfaces;
        }
    }

    /**
     * @return the mixins associated with this {@code ClassNode}
     */
    public MixinNode[] getMixins() {
        return redirect().mixins;
    }

    /**
     * @return the methods associated with this {@code ClassNode}
     */
    public List<MethodNode> getMethods() {
        if (redirect != null)
            return redirect().getMethods();
        lazyClassInit();
        return methodsList;
    }

    /**
     * @return the abstract methods associated with this {@code ClassNode}
     */
    public List<MethodNode> getAbstractMethods() {
        List<MethodNode> result = new ArrayList<MethodNode>(3);
        for (MethodNode method : getDeclaredMethodsMap().values()) {
            if (method.isAbstract()) {
                result.add(method);
            }
        }
        if (!result.isEmpty()) {
            return result;
        }
        return null;
    }

    public List<MethodNode> getAllDeclaredMethods() {
        return new ArrayList<MethodNode>(getDeclaredMethodsMap().values());
    }

    public Set<ClassNode> getAllInterfaces() {
        Set<ClassNode> res = new LinkedHashSet<ClassNode>();
        getAllInterfaces(res);
        return res;
    }

    private void getAllInterfaces(Set<ClassNode> res) {
        if (isInterface()) {
            res.add(this);
        }
        for (ClassNode anInterface : getInterfaces()) {
            res.add(anInterface);
            anInterface.getAllInterfaces(res);
        }
    }

    public Map<String, MethodNode> getDeclaredMethodsMap() {
        Map<String, MethodNode> result = ClassNodeUtils.getDeclaredMethodsFromSuper(this);
        ClassNodeUtils.addDeclaredMethodsFromInterfaces(this, result);

        // And add in the methods implemented in this class.
        for (MethodNode method : getMethods()) {
            String sig = method.getTypeDescriptor();
            result.put(sig, method);
        }
        return result;
    }

    public String getName() {
        return redirect().name;
    }

    public String getUnresolvedName() {
        return name;
    }

    public String setName(String name) {
        return redirect().name = name;
    }

    public int getModifiers() {
        return redirect().modifiers;
    }

    public void setModifiers(int modifiers) {
        redirect().modifiers = modifiers;
    }

    public List<PropertyNode> getProperties() {
        ClassNode r = redirect();
        // GRECLIPSE add
        if (r != this) return r.getProperties();
        // GRECLIPSE end
        if (r.properties == null)
            r.properties = new ArrayList<PropertyNode>();
        return r.properties;
    }

    public List<ConstructorNode> getDeclaredConstructors() {
        if (redirect != null)
            return redirect().getDeclaredConstructors();
        lazyClassInit();
        if (constructors == null)
            constructors = new ArrayList<ConstructorNode>();
        return constructors;
    }

    /**
     * @return the constructor matching the given parameters or {@code null}
     */
    public ConstructorNode getDeclaredConstructor(Parameter[] parameters) {
        for (ConstructorNode method : getDeclaredConstructors()) {
            if (parametersEqual(method.getParameters(), parameters)) {
                return method;
            }
        }
        return null;
    }

    public void removeConstructor(ConstructorNode node) {
        getDeclaredConstructors().remove(node);
    }

    public ModuleNode getModule() {
        return redirect().module;
    }

    public PackageNode getPackage() {
        return getModule() != null ? getModule().getPackage() : null;
    }

    public void setModule(ModuleNode module) {
        redirect().module = module;
        if (module != null) {
            redirect().compileUnit = module.getUnit();
        }
    }

    public void addField(FieldNode node) {
        addField(node, false);
    }

    public void addFieldFirst(FieldNode node) {
        addField(node, true);
    }

    private void addField(FieldNode node, boolean isFirst) {
        ClassNode r = redirect();
        node.setDeclaringClass(r);
        node.setOwner(r);
        if (r.fields == null)
            r.fields = new LinkedList<FieldNode>();
        if (r.fieldIndex == null)
            r.fieldIndex = new LinkedHashMap<String, FieldNode>();

        if (isFirst) {
            r.fields.addFirst(node);
        } else {
            r.fields.add(node);
        }
        r.fieldIndex.put(node.getName(), node);
    }

    public Map<String, FieldNode> getFieldIndex() {
        return fieldIndex;
    }

    public void addProperty(PropertyNode node) {
        node.setDeclaringClass(redirect());
        getProperties().add(node);
        addField(node.getField());
    }

    public PropertyNode addProperty(String name,
                                    int modifiers,
                                    ClassNode type,
                                    Expression initialValueExpression,
                                    Statement getterBlock,
                                    Statement setterBlock) {
        for (PropertyNode pn : getProperties()) {
            if (pn.getName().equals(name)) {
                if (pn.getInitialExpression() == null && initialValueExpression != null)
                    pn.getField().setInitialValueExpression(initialValueExpression);

                if (pn.getGetterBlock() == null && getterBlock != null)
                    pn.setGetterBlock(getterBlock);

                if (pn.getSetterBlock() == null && setterBlock != null)
                    pn.setSetterBlock(setterBlock);

                return pn;
            }
        }
        PropertyNode node = new PropertyNode(name, modifiers, type, redirect(), initialValueExpression, getterBlock, setterBlock);
        addProperty(node);
        return node;
    }

    public boolean hasProperty(String name) {
        return getProperty(name) != null;
    }

    public PropertyNode getProperty(String name) {
        for (PropertyNode pn : getProperties()) {
            if (pn.getName().equals(name)) return pn;
        }
        return null;
    }

    public void addConstructor(ConstructorNode node) {
        node.setDeclaringClass(this);
        ClassNode r = redirect();
        if (r.constructors == null)
            r.constructors = new ArrayList<ConstructorNode>();
        r.constructors.add(node);
    }

    public ConstructorNode addConstructor(int modifiers, Parameter[] parameters, ClassNode[] exceptions, Statement code) {
        ConstructorNode node = new ConstructorNode(modifiers, parameters, exceptions, code);
        addConstructor(node);
        return node;
    }

    public void addMethod(MethodNode node) {
        node.setDeclaringClass(this);
        ClassNode r = redirect();
        if (r.methodsList.isEmpty()) {
            r.methodsList = new ArrayList<MethodNode>();
        }
        r.methodsList.add(node);
        r.methods.put(node.getName(), node);
    }

    public void removeMethod(MethodNode node) {
        ClassNode r = redirect();
        if (!r.methodsList.isEmpty()) {
            r.methodsList.remove(node);
        }
        r.methods.remove(node.getName(), node);
    }

    /**
     * If a method with the given name and parameters is already defined then it is returned
     * otherwise the given method is added to this node. This method is useful for
     * default method adding like getProperty() or invokeMethod() where there may already
     * be a method defined in a class and so the default implementations should not be added
     * if already present.
     */
    public MethodNode addMethod(String name,
                                int modifiers,
                                ClassNode returnType,
                                Parameter[] parameters,
                                ClassNode[] exceptions,
                                Statement code) {
        MethodNode other = getDeclaredMethod(name, parameters);
        // don't add duplicate methods
        if (other != null) {
            return other;
        }
        MethodNode node = new MethodNode(name, modifiers, returnType, parameters, exceptions, code);
        addMethod(node);
        return node;
    }

    /**
     * @see #getDeclaredMethod(String, Parameter[])
     */
    public boolean hasDeclaredMethod(String name, Parameter[] parameters) {
        MethodNode other = getDeclaredMethod(name, parameters);
        return other != null;
    }

    /**
     * @see #getMethod(String, Parameter[])
     */
    public boolean hasMethod(String name, Parameter[] parameters) {
        MethodNode other = getMethod(name, parameters);
        return other != null;
    }

    /**
     * Adds a synthetic method as part of the compilation process.
     */
    public MethodNode addSyntheticMethod(String name,
                                         int modifiers,
                                         ClassNode returnType,
                                         Parameter[] parameters,
                                         ClassNode[] exceptions,
                                         Statement code) {
        MethodNode node = addMethod(name, modifiers|ACC_SYNTHETIC, returnType, parameters, exceptions, code);
        node.setSynthetic(true);
        return node;
    }

    public FieldNode addField(String name, int modifiers, ClassNode type, Expression initialValue) {
        FieldNode node = new FieldNode(name, modifiers, type, redirect(), initialValue);
        addField(node);
        return node;
    }

    public FieldNode addFieldFirst(String name, int modifiers, ClassNode type, Expression initialValue) {
        FieldNode node = new FieldNode(name, modifiers, type, redirect(), initialValue);
        addFieldFirst(node);
        return node;
    }

    public void addInterface(ClassNode type) {
        ClassNode[] interfaces = getInterfaces();
        for (ClassNode face : interfaces) {
            if (face.equals(type)) return;
        }
        final int n = interfaces.length;

        System.arraycopy(interfaces, 0, interfaces = new ClassNode[n + 1], 0, n);
        interfaces[n] = type; // append interface
        setInterfaces(interfaces);
    }

    public boolean equals(Object that) {
        if (that == this) return true;
        if (!(that instanceof ClassNode)) return false;
        if (redirect != null) return redirect.equals(that);
        if (componentType != null) return componentType.equals(((ClassNode) that).componentType);
        return ((ClassNode) that).getText().equals(getText()); // arrays could be "T[]" or "[LT;"
    }

    public int hashCode() {
        if (redirect != null) return redirect().hashCode();
        return getName().hashCode();
    }

    public void addMixin(MixinNode mixin) {
        // let's check if it already uses a mixin
        MixinNode[] mixins = redirect().mixins;
        boolean skip = false;
        for (MixinNode existing : mixins) {
            if (mixin.equals(existing)) {
                skip = true;
                break;
            }
        }
        if (!skip) {
            MixinNode[] newMixins = new MixinNode[mixins.length + 1];
            System.arraycopy(mixins, 0, newMixins, 0, mixins.length);
            newMixins[mixins.length] = mixin;
            redirect().mixins = newMixins;
        }
    }

    /**
     * Finds a field matching the given name in this class.
     *
     * @param name the name of the field of interest
     * @return the method matching the given name and parameters or null
     */
    public FieldNode getDeclaredField(String name) {
        if (redirect != null)
            return redirect().getDeclaredField(name);
        lazyClassInit();
        return fieldIndex != null ? fieldIndex.get(name) : null;
    }

    /**
     * Finds a field matching the given name in this class or a parent class.
     *
     * @param name the name of the field of interest
     * @return the method matching the given name and parameters or null
     */
    public FieldNode getField(String name) {
        for (ClassNode node = this; node != null; node = node.getSuperClass()) {
            FieldNode fn = node.getDeclaredField(name);
            if (fn != null) return fn;
        }
        return null;
    }

    /**
     * @return the field on the outer class or {@code null} if this is not an inner class
     */
    public FieldNode getOuterField(final String name) {
        if (redirect != null) {
            return redirect.getOuterField(name);
        }
        return null;
    }

    public ClassNode getOuterClass() {
        if (redirect != null) {
            return redirect.getOuterClass();
        }
        return null;
    }

    public List<ClassNode> getOuterClasses() {
        ClassNode outer = getOuterClass();
        if (outer == null) {
            return Collections.emptyList();
        }
        List<ClassNode> result = new LinkedList<>();
        do {
            result.add(outer);
        } while ((outer = outer.getOuterClass()) != null);

        return result;
    }

    /**
     * Adds a statement to the object initializer.
     *
     * @param statements the statement to be added
     */
    public void addObjectInitializerStatements(Statement statements) {
        getObjectInitializerStatements().add(statements);
    }

    public List<Statement> getObjectInitializerStatements() {
        if (objectInitializers == null)
            objectInitializers = new LinkedList<Statement>();
        return objectInitializers;
    }

    private MethodNode getOrAddStaticConstructorNode() {
        MethodNode method = null;
        List<MethodNode> declaredMethods = getDeclaredMethods("<clinit>");
        if (declaredMethods.isEmpty()) {
            method = addMethod("<clinit>", ACC_STATIC, ClassHelper.VOID_TYPE, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, new BlockStatement());
            method.setSynthetic(true);
        }
        else {
            method = declaredMethods.get(0);
        }
        return method;
    }

    public void addStaticInitializerStatements(List<Statement> staticStatements, boolean fieldInit) {
        MethodNode method = getOrAddStaticConstructorNode();
        BlockStatement block = null;
        Statement statement = method.getCode();
        if (statement == null) {
            block = new BlockStatement();
        } else if (statement instanceof BlockStatement) {
            block = (BlockStatement) statement;
        } else {
            block = new BlockStatement();
            block.addStatement(statement);
        }
        // while anything inside a static initializer block is appended
        // we don't want to append in the case we have a initialization
        // expression of a static field. In that case we want to add
        // before the other statements
        if (!fieldInit) {
            block.addStatements(staticStatements);
        } else {
            List<Statement> blockStatements = block.getStatements();
            staticStatements.addAll(blockStatements);
            blockStatements.clear();
            blockStatements.addAll(staticStatements);
        }
    }

    public void positionStmtsAfterEnumInitStmts(List<Statement> staticFieldStatements) {
        MethodNode method = getOrAddStaticConstructorNode();
        Statement statement = method.getCode();
        if (statement instanceof BlockStatement) {
            BlockStatement block = (BlockStatement) statement;
            // add given statements for explicitly declared static fields just after enum-special fields
            // are found - the $VALUES binary expression marks the end of such fields.
            List<Statement> blockStatements = block.getStatements();
            ListIterator<Statement> litr = blockStatements.listIterator();
            while (litr.hasNext()) {
                Statement stmt = litr.next();
                if (stmt instanceof ExpressionStatement &&
                        ((ExpressionStatement) stmt).getExpression() instanceof BinaryExpression) {
                    BinaryExpression bExp = (BinaryExpression) ((ExpressionStatement) stmt).getExpression();
                    if (bExp.getLeftExpression() instanceof FieldExpression) {
                        FieldExpression fExp = (FieldExpression) bExp.getLeftExpression();
                        if (fExp.getFieldName().equals("$VALUES")) {
                            for (Statement tmpStmt : staticFieldStatements) {
                                litr.add(tmpStmt);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * This methods returns a list of all methods of the given name
     * defined in the current class
     * @return the method list
     * @see #getMethods(String)
     */
    public List<MethodNode> getDeclaredMethods(String name) {
        if (redirect != null)
            return redirect().getDeclaredMethods(name);
        lazyClassInit();
        return methods.getNotNull(name);
    }

    /**
     * This methods creates a list of all methods with this name of the
     * current class and of all super classes
     * @return the methods list
     * @see #getDeclaredMethods(String)
     */
    public List<MethodNode> getMethods(String name) {
        List<MethodNode> answer = new ArrayList<MethodNode>();
        for (ClassNode node = this; node != null; node = node.getSuperClass()) {
            answer.addAll(node.getDeclaredMethods(name));
        }
        return answer;
    }

    /**
     * Finds a method matching the given name and parameters in this class.
     *
     * @return the method matching the given name and parameters or null
     */
    public MethodNode getDeclaredMethod(String name, Parameter[] parameters) {
        for (MethodNode method : getDeclaredMethods(name)) {
            if (parametersEqual(method.getParameters(), parameters)) {
                return method;
            }
        }
        return null;
    }

    /**
     * Finds a method matching the given name and parameters in this class
     * or any parent class.
     *
     * @return the method matching the given name and parameters or null
     */
    public MethodNode getMethod(String name, Parameter[] parameters) {
        for (MethodNode method : getMethods(name)) {
            if (parametersEqual(method.getParameters(), parameters)) {
                return method;
            }
        }
        return null;
    }

    /**
     * @param type the ClassNode of interest
     * @return true if this node is derived from the given ClassNode
     */
    public boolean isDerivedFrom(ClassNode type) {
        if (this.equals(ClassHelper.VOID_TYPE)) {
            return type.equals(ClassHelper.VOID_TYPE);
        }
        if (type.equals(ClassHelper.OBJECT_TYPE)) {
            return true;
        }
        for (ClassNode node = this; node != null; node = node.getSuperClass()) {
            if (type.equals(node)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} if this type implements {@code GroovyObject}
     */
    public boolean isDerivedFromGroovyObject() {
        return implementsInterface(ClassHelper.GROOVY_OBJECT_TYPE);
    }

    /**
     * @param classNode the class node for the interface
     * @return {@code true} if this type implements the given interface
     */
    public boolean implementsInterface(ClassNode classNode) {
        for (ClassNode node = redirect(); node != null; node = node.getSuperClass()) {
            if (node.declaresInterface(classNode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param classNode the class node for the interface
     * @return {@code true} if this class declares that it implements the given
     * interface or if one of its interfaces extends directly/indirectly the interface
     *
     * NOTE: Doesn't consider an interface to implement itself.
     * I think this is intended to be called on ClassNodes representing
     * classes, not interfaces.
     */
    public boolean declaresInterface(ClassNode classNode) {
        ClassNode[] interfaces = getInterfaces();
        for (ClassNode cn : interfaces) {
            if (cn.equals(classNode)) return true;
        }
        for (ClassNode cn : interfaces) {
            if (cn.declaresInterface(classNode)) return true;
        }
        return false;
    }

    /**
     * @return the {@code ClassNode} of the super class of this type
     */
    public ClassNode getSuperClass() {
        if (!lazyInitDone && !isResolved()) {
            throw new GroovyBugError("ClassNode#getSuperClass for " + getName() + " called before class resolving");
        }
        // GRECLIPSE add
        if (hasInconsistentHierarchy()) {
            return ClassHelper.OBJECT_TYPE;
        }
        // GRECLIPSE end
        ClassNode sn = redirect().getUnresolvedSuperClass();
        if (sn != null) sn = sn.redirect();
        return sn;
    }

    public ClassNode getUnresolvedSuperClass() {
        return getUnresolvedSuperClass(true);
    }

    public ClassNode getUnresolvedSuperClass(boolean useRedirect) {
        // GRECLIPSE add
        if (hasInconsistentHierarchy()) {
            return ClassHelper.OBJECT_TYPE;
        }
        // GRECLIPSE end
        if (!useRedirect) return superClass;
        if (redirect != null) return redirect().getUnresolvedSuperClass(true);
        lazyClassInit();
        return superClass;
    }

    public void setUnresolvedSuperClass(ClassNode sn) {
        superClass = sn;
    }

    public ClassNode [] getUnresolvedInterfaces() {
        return getUnresolvedInterfaces(true);
    }

    public ClassNode [] getUnresolvedInterfaces(boolean useRedirect) {
        // GRECLIPSE add
        if (hasInconsistentHierarchy()) {
            return EMPTY_ARRAY;
        }
        // GRECLIPSE end
        if (!useRedirect) return interfaces;
        if (redirect != null) return redirect().getUnresolvedInterfaces(true);
        lazyClassInit();
        return interfaces;
    }

    public CompileUnit getCompileUnit() {
        if (redirect != null) return redirect().getCompileUnit();
        if (compileUnit == null && module != null) {
            compileUnit = module.getUnit();
        }
        return compileUnit;
    }

    protected void setCompileUnit(CompileUnit cu) {
        if (redirect != null) redirect().setCompileUnit(cu);
        if (compileUnit != null) compileUnit = cu;
    }

    /**
     * @return {@code true} if the two arrays are of the same size and have the same contents
     * @deprecated
     */
    @Deprecated
    protected boolean parametersEqual(Parameter[] a, Parameter[] b) {
        return ParameterUtils.parametersEqual(a, b);
    }

    public String getPackageName() {
        int idx = getName().lastIndexOf('.');
        if (idx > 0) {
            return getName().substring(0, idx);
        }
        return null;
    }

    public String getNameWithoutPackage() {
        int idx = getName().lastIndexOf('.');
        if (idx > 0) {
            return getName().substring(idx + 1);
        }
        return getName();
    }

    public void visitContents(GroovyClassVisitor visitor) {
        // now let's visit the contents of the class
        for (PropertyNode pn : getProperties()) {
            visitor.visitProperty(pn);
        }

        for (FieldNode fn : getFields()) {
            visitor.visitField(fn);
        }

        for (ConstructorNode cn : getDeclaredConstructors()) {
            visitor.visitConstructor(cn);
        }

        for (MethodNode mn : getMethods()) {
            visitor.visitMethod(mn);
        }
    }

    public MethodNode getGetterMethod(String getterName) {
        return getGetterMethod(getterName, true);
    }

    public MethodNode getGetterMethod(String getterName, boolean searchSuperClasses) {
        MethodNode getterMethod = null;
        boolean booleanReturnOnly = getterName.startsWith("is");
        for (MethodNode method : getDeclaredMethods(getterName)) {
            if (getterName.equals(method.getName())
                    && method.getParameters().length == 0 && !method.isVoidMethod()
                    && (!booleanReturnOnly || ClassHelper.Boolean_TYPE.equals(ClassHelper.getWrapper(method.getReturnType())))) {
                // GROOVY-7363: There can be multiple matches for a getter returning a generic parameter type, due to
                // the generation of a bridge method. The real getter is really the non-bridge, non-synthetic one as it
                // has the most specific and exact return type of the two. Picking the bridge method results in loss of
                // type information, as it down-casts the return type to the lower bound of the generic parameter.
                if (getterMethod == null || (getterMethod.getModifiers() & ACC_SYNTHETIC) != 0) {
                    getterMethod = method;
                }
            }
        }
        if (getterMethod != null) return getterMethod;
        if (searchSuperClasses) {
            ClassNode parent = getSuperClass();
            if (parent != null) return parent.getGetterMethod(getterName);
        }
        return null;
    }

    public MethodNode getSetterMethod(String setterName) {
        return getSetterMethod(setterName, true);
    }

    public MethodNode getSetterMethod(String setterName, boolean voidOnly) {
        for (MethodNode method : getDeclaredMethods(setterName)) {
            if (setterName.equals(method.getName())
                    && method.getParameters().length == 1
                    && (!voidOnly || method.isVoidMethod())) {
                return method;
            }
        }
        ClassNode parent = getSuperClass();
        if (parent != null) return parent.getSetterMethod(setterName, voidOnly);

        return null;
    }

    /**
     * Is this class declared in a static method (such as a closure / inner class declared in a static method)
     */
    public boolean isStaticClass() {
        return redirect().staticClass;
    }

    public void setStaticClass(boolean staticClass) {
        redirect().staticClass = staticClass;
    }

    /**
     * @return {@code true} if this inner class or closure was declared inside a script body
     */
    public boolean isScriptBody() {
        return redirect().scriptBody;
    }

    public void setScriptBody(boolean scriptBody) {
        redirect().scriptBody = scriptBody;
    }

    public boolean isScript() {
        return redirect().script || isDerivedFrom(ClassHelper.SCRIPT_TYPE);
    }

    public void setScript(boolean script) {
        redirect().script = script;
    }

    public String toString() {
        return toString(true);
    }

    public String toString(boolean showRedirect) {
        if (isArray()) {
            return getComponentType().toString(showRedirect) + "[]";
        }
        boolean placeholder = isGenericsPlaceHolder();
        StringBuilder ret = new StringBuilder(!placeholder ? getName() : getUnresolvedName());
        GenericsType[] genericsTypes = getGenericsTypes();
        if (!placeholder && genericsTypes != null && genericsTypes.length > 0) {
            /* GRECLIPSE edit -- GROOVY-9800
            ret.append(" <");
            for (int i = 0, n = genericsTypes.length; i < n; i += 1) {
                if (i != 0) ret.append(", ");
                ret.append(genericTypeAsString(genericsTypes[i]));
            }
            ret.append(">");
            */
            ret.append('<');
            for (int i = 0, n = genericsTypes.length; i < n; i += 1) {
                if (i != 0) ret.append(", ");
                ret.append(genericsTypes[i]);
            }
            ret.append('>');
            // GRECLIPSE end
        }
        if (showRedirect && redirect != null) {
            ret.append(" -> ").append(redirect);
        }
        return ret.toString();
    }

    /**
     * Determines if the type has a possibly-matching instance method with the given name and arguments.
     *
     * @param name      the name of the method of interest
     * @param arguments the arguments to match against
     * @return true if a matching method was found
     */
    public boolean hasPossibleMethod(final String name, final Expression arguments) {
        int count;
        if (arguments instanceof TupleExpression) {
            // TODO: this won't strictly be true when using list expansion in argument calls
            count = ((TupleExpression) arguments).getExpressions().size();
        } else {
            count = 0;
        }

        for (ClassNode cn = this; cn != null; cn = cn.getSuperClass()) {
            for (MethodNode mn : cn.getDeclaredMethods(name)) {
                if (!mn.isStatic() && hasCompatibleNumberOfArgs(mn, count)) {
                    return true;
                }
            }
            for (ClassNode in : cn.getAllInterfaces()) {
                for (MethodNode mn : in.getDeclaredMethods(name)) {
                    if (mn.isDefault() && hasCompatibleNumberOfArgs(mn, count)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public MethodNode tryFindPossibleMethod(final String name, final Expression arguments) {
        if (!(arguments instanceof TupleExpression)) {
            return null;
        }

        // TODO: this won't strictly be true when using list expansion in argument calls
        TupleExpression args = (TupleExpression) arguments;
        int count = args.getExpressions().size();
        MethodNode res = null;

        for (ClassNode cn = this; cn != null; cn = cn.getSuperClass()) {
            for (MethodNode mn : cn.getDeclaredMethods(name)) {
                if (hasCompatibleNumberOfArgs(mn, count)) {
                    boolean match = true;
                    for (int i = 0; i < count; i += 1) {
                        if (!hasCompatibleType(args, mn, i)) {
                            match = false;
                            break;
                        }
                    }

                    if (match) {
                        if (res == null) {
                            res = mn;
                        } else {
                            if (res.getParameters().length != count)
                                return null;
                            if (cn.equals(this))
                                return null;

                            match = true;
                            for (int i = 0; i != count; i += 1)
                                // prefer super method if it matches better
                                if (!hasExactMatchingCompatibleType(res, mn, i)) {
                                    match = false;
                                    break;
                                }
                            if (!match)
                                return null;
                        }
                    }
                }
            }
        }

        return res;
    }

    private boolean hasExactMatchingCompatibleType(MethodNode current, MethodNode newCandidate, int i) {
        int lastParamIndex = newCandidate.getParameters().length - 1;
        return (i <= lastParamIndex && current.getParameters()[i].getType().equals(newCandidate.getParameters()[i].getType()))
                || (i >= lastParamIndex && isPotentialVarArg(newCandidate, lastParamIndex) && current.getParameters()[i].getType().equals(newCandidate.getParameters()[lastParamIndex].getType().getComponentType()));
    }

    private boolean hasCompatibleType(TupleExpression args, MethodNode method, int i) {
        int lastParamIndex = method.getParameters().length - 1;
        return (i <= lastParamIndex && args.getExpression(i).getType().isDerivedFrom(method.getParameters()[i].getType()))
                || (isPotentialVarArg(method, lastParamIndex) && i >= lastParamIndex  && args.getExpression(i).getType().isDerivedFrom(method.getParameters()[lastParamIndex].getType().componentType));
    }

    private boolean hasCompatibleNumberOfArgs(MethodNode method, int count) {
        int lastParamIndex = method.getParameters().length - 1;
        return method.getParameters().length == count || (isPotentialVarArg(method, lastParamIndex) && count >= lastParamIndex);
    }

    private boolean isPotentialVarArg(MethodNode newCandidate, int lastParamIndex) {
        return lastParamIndex >= 0 && newCandidate.getParameters()[lastParamIndex].getType().isArray();
    }

    /**
     * Checks if the given method has a possibly matching static method with the
     * given name and arguments.
     *
     * @param name      the name of the method of interest
     * @param arguments the arguments to match against
     * @return {@code true} if a matching method was found
     */
    public boolean hasPossibleStaticMethod(String name, Expression arguments) {
        return ClassNodeUtils.hasPossibleStaticMethod(this, name, arguments, false);
    }

    public boolean isInterface() {
        return (getModifiers() & ACC_INTERFACE) != 0;
    }

    public boolean isAbstract() {
        return (getModifiers() & ACC_ABSTRACT) != 0;
    }

    public boolean isResolved() {
        if (clazz != null) return true;
        if (redirect != null) return redirect.isResolved();
        return componentType != null && componentType.isResolved();
    }

    public boolean isArray() {
        return componentType != null;
    }

    public ClassNode getComponentType() {
        return componentType;
    }

    /**
     * Returns the concrete class this classnode relates to. However, this method
     * is inherently unsafe as it may return null depending on the compile phase you are
     * using. AST transformations should never use this method directly, but rather obtain
     * a new class node using {@link #getPlainNodeReference()}.
     * @return the class this classnode relates to. May return null.
     */
    public Class getTypeClass() {
        if (clazz != null) return clazz;
        if (redirect != null) return redirect.getTypeClass();

        ClassNode component = redirect().componentType;
        if (component != null && component.isResolved()) {
            return Array.newInstance(component.getTypeClass(), 0).getClass();
        }
        throw new GroovyBugError("ClassNode#getTypeClass for " + getName() + " called before the type class is set");
    }

    public boolean hasPackageName() {
        return redirect().name.indexOf('.') > 0;
    }

    /**
     * Marks if the current class uses annotations or not.
     */
    public void setAnnotated(boolean flag) {
        this.annotated = flag;
    }

    public boolean isAnnotated() {
        return this.annotated;
    }

    public GenericsType asGenericsType() {
        if (!isGenericsPlaceHolder()) {
            return new GenericsType(this);
        } else {
            ClassNode upper = (redirect != null ? redirect : this);
            return new GenericsType(this, new ClassNode[]{upper}, null);
        }
    }

    public GenericsType[] getGenericsTypes() {
        return genericsTypes;
    }

    public void setGenericsTypes(GenericsType[] genericsTypes) {
        usesGenerics = usesGenerics || genericsTypes != null;
        this.genericsTypes = genericsTypes;
    }

    public void setGenericsPlaceHolder(boolean b) {
        usesGenerics = usesGenerics || b;
        placeholder = b;
    }

    public boolean isGenericsPlaceHolder() {
        return placeholder;
    }

    public boolean isUsingGenerics() {
        return usesGenerics;
    }

    public void setUsingGenerics(boolean b) {
        usesGenerics = b;
    }

    public ClassNode getPlainNodeReference() {
        if (ClassHelper.isPrimitiveType(this)) return this;
        ClassNode n = new ClassNode(name, modifiers, superClass, null, null);
        n.isPrimaryNode = false;
        n.setRedirect(redirect());
        if (isArray()) {
            n.componentType = redirect().getComponentType();
        }
        return n;
    }

    public boolean isAnnotationDefinition() {
        return isInterface() && (getModifiers() & ACC_ANNOTATION) != 0;
    }

    public List<AnnotationNode> getAnnotations() {
        if (redirect != null)
            return redirect.getAnnotations();
        lazyClassInit();
        return super.getAnnotations();
    }

    public List<AnnotationNode> getAnnotations(ClassNode type) {
        if (redirect != null)
            return redirect.getAnnotations(type);
        lazyClassInit();
        return super.getAnnotations(type);
    }

    public void addTransform(Class<? extends ASTTransformation> transform, ASTNode node) {
        GroovyASTTransformation annotation = transform.getAnnotation(GroovyASTTransformation.class);
        if (annotation == null) return;

        Set<ASTNode> nodes = getTransformInstances().get(annotation.phase()).get(transform);
        if (nodes == null) {
            nodes = new LinkedHashSet<ASTNode>();
            getTransformInstances().get(annotation.phase()).put(transform, nodes);
        }
        nodes.add(node);
    }

    public Map<Class <? extends ASTTransformation>, Set<ASTNode>> getTransforms(CompilePhase phase) {
        return getTransformInstances().get(phase);
    }

    public void renameField(String oldName, String newName) {
        ClassNode r = redirect();
        if (r.fieldIndex == null)
            r.fieldIndex = new LinkedHashMap<String, FieldNode>();
        final Map<String,FieldNode> index = r.fieldIndex;
        index.put(newName, index.remove(oldName));
    }

    public void removeField(String oldName) {
        ClassNode r = redirect();
        if (r.fieldIndex == null)
            r.fieldIndex = new LinkedHashMap<String, FieldNode>();
        final Map<String,FieldNode> index = r.fieldIndex;
        r.fields.remove(index.get(oldName));
        index.remove(oldName);
    }

    public boolean isEnum() {
        return (getModifiers() & ACC_ENUM) != 0;
    }

    /**
     * @return iterator of inner classes defined inside this one
     */
    public Iterator<InnerClassNode> getInnerClasses() {
        return (innerClasses == null ? Collections.<InnerClassNode>emptyList() : innerClasses).iterator();
    }

    private Map<CompilePhase, Map<Class<? extends ASTTransformation>, Set<ASTNode>>> getTransformInstances() {
        if (transformInstances == null) {
            transformInstances = new EnumMap<CompilePhase, Map<Class <? extends ASTTransformation>, Set<ASTNode>>>(CompilePhase.class);
            for (CompilePhase phase : CompilePhase.values()) {
                transformInstances.put(phase, new LinkedHashMap<Class <? extends ASTTransformation>, Set<ASTNode>>());
            }
        }
        return transformInstances;
    }

    @Override
    public String getText() {
        return getName();
    }

    // GRECLIPSE add
    public boolean hasClass() {
        return clazz != null || (redirect != null && redirect.hasClass());
    }

    public boolean hasMultiRedirect() {
        return redirect != null && redirect != redirect();
    }

    public boolean hasInconsistentHierarchy() {
        return redirect().cycle;
    }

    public void setHasInconsistentHierarchy(final boolean cycle) {
        redirect().cycle = cycle;
    }

    private boolean cycle;
    private int nameStart;

    /**
     * Returns the offset of 'M' in "java.util.Map" or 'E' in "java.util.Map.Entry".
     */
    public int getNameStart2() {
        return nameStart > 0 ? nameStart : getStart();
    }

    public void setNameStart2(final int offset) {
        nameStart = offset;
    }

    @Override
    public void setSourcePosition(final ASTNode node) {
        super.setSourcePosition(node);
        if (node instanceof ClassNode) {
            setNameStart2(((ClassNode) node).getNameStart2());
        }
    }

    public void addTypeAnnotations(final List<AnnotationNode> x) {
    }

    public List<AnnotationNode> getTypeAnnotations() {
        return Collections.emptyList();
    }

    public List<ClassNode> getPermittedSubclasses() {
        return Collections.emptyList();
    }
    // GRECLIPSE end
}
