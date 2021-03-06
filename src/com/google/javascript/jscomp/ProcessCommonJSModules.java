/*
 * Copyright 2011 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.deps.ModuleLoader;
import com.google.javascript.jscomp.deps.ModuleLoader.ModulePath;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Rewrites a CommonJS module http://wiki.commonjs.org/wiki/Modules/1.1.1 into a form that can be
 * safely concatenated. Does not add a function around the module body but instead adds suffixes to
 * global variables to avoid conflicts. Calls to require are changed to reference the required
 * module directly.
 */
public final class ProcessCommonJSModules extends NodeTraversal.AbstractPreOrderCallback
    implements CompilerPass {
  private static final String EXPORTS = "exports";
  private static final String MODULE = "module";
  private static final String REQUIRE = "require";
  private static final String EXPORT_PROPERTY_NAME = "default";

  public static final DiagnosticType UNKNOWN_REQUIRE_ENSURE =
      DiagnosticType.warning(
          "JSC_COMMONJS_UNKNOWN_REQUIRE_ENSURE_ERROR", "Unrecognized require.ensure call: {0}");

  public static final DiagnosticType SUSPICIOUS_EXPORTS_ASSIGNMENT =
      DiagnosticType.warning(
          "JSC_COMMONJS_SUSPICIOUS_EXPORTS_ASSIGNMENT",
          "Suspicious re-assignment of \"exports\" variable."
              + " Did you actually intend to export something?");

  private final AbstractCompiler compiler;

  /**
   * Creates a new ProcessCommonJSModules instance which can be used to rewrite CommonJS modules to
   * a concatenable form.
   *
   * @param compiler The compiler
   */
  public ProcessCommonJSModules(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverseEs6(compiler, root, this);
  }

  @Override
  public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
    if (n.isRoot()) {
      return true;
    } else if (n.isScript()) {
      FindImportsAndExports finder = new FindImportsAndExports();
      NodeTraversal.traverseEs6(compiler, n, finder);

      CompilerInput.ModuleType moduleType = compiler.getInput(n.getInputId()).getJsModuleType();

      boolean forceModuleDetection = moduleType == CompilerInput.ModuleType.IMPORTED_SCRIPT;
      boolean defaultExportIsConst = true;

      boolean isCommonJsModule = finder.isCommonJsModule();
      ImmutableList.Builder<ExportInfo> exports = ImmutableList.builder();
      boolean needsRetraverse = false;
      if (isCommonJsModule || forceModuleDetection) {
        finder.reportModuleErrors();

        if (!finder.umdPatterns.isEmpty()) {
          if (finder.replaceUmdPatterns()) {
            needsRetraverse = true;
          }
          // Removing the IIFE rewrites vars. We need to re-traverse
          // to get the new references.
          if (removeIIFEWrapper(n)) {
            needsRetraverse = true;
          }

          if (needsRetraverse) {
            finder = new FindImportsAndExports();
            NodeTraversal.traverseEs6(compiler, n, finder);
          }
        }

        defaultExportIsConst = finder.initializeModule();

        //UMD pattern replacement can leave detached export references - don't include those
        for (ExportInfo export : finder.getModuleExports()) {
          if (NodeUtil.getEnclosingScript(export.node) != null) {
            exports.add(export);
          }
        }
        for (ExportInfo export : finder.getExports()) {
          if (NodeUtil.getEnclosingScript(export.node) != null) {
            exports.add(export);
          }
        }
      } else if (needsRetraverse) {
        finder = new FindImportsAndExports();
        NodeTraversal.traverseEs6(compiler, n, finder);
      }

      NodeTraversal.traverseEs6(
          compiler,
          n,
          new RewriteModule(
              isCommonJsModule || forceModuleDetection, exports.build(), defaultExportIsConst));
    }
    return false;
  }

  public static String getModuleName(CompilerInput input) {
    ModulePath modulePath = input.getPath();
    if (modulePath == null) {
      return null;
    }

    return getModuleName(modulePath);
  }

  public static String getModuleName(ModulePath input) {
    return input.toModuleName();
  }

  public String getBasePropertyImport(String moduleName) {
    CompilerInput.ModuleType moduleType = compiler.getModuleTypeByName(moduleName);
    if (moduleType != null && moduleType != CompilerInput.ModuleType.COMMONJS) {
      return moduleName;
    }

    return moduleName + "." + EXPORT_PROPERTY_NAME;
  }

  /**
   * Recognize if a node is a module import. We recognize the form:
   *
   * <ul>
   *   <li>require("something");
   * </ul>
   */
  public static boolean isCommonJsImport(Node requireCall) {
    if (requireCall.isCall() && requireCall.hasTwoChildren()) {
      if (requireCall.getFirstChild().matchesQualifiedName(REQUIRE)
          && requireCall.getSecondChild().isString()) {
        return true;
      }
    }
    return false;
  }

  public static String getCommonJsImportPath(Node requireCall) {
    return requireCall.getSecondChild().getString();
  }

  private String getImportedModuleName(NodeTraversal t, Node requireCall) {
    return getImportedModuleName(t, requireCall, getCommonJsImportPath(requireCall));
  }

  private String getImportedModuleName(NodeTraversal t, Node n, String importPath) {
    ModulePath modulePath =
        t.getInput()
            .getPath()
            .resolveJsModule(importPath, n.getSourceFileName(), n.getLineno(), n.getCharno());

    if (modulePath == null) {
      return ModuleIdentifier.forFile(importPath).getModuleName();
    }
    return modulePath.toModuleName();
  }

  /**
   * Recognize if a node is a module export. We recognize several forms:
   *
   * <ul>
   *   <li> module.exports = something;
   *   <li> module.exports.something = something;
   *   <li> exports.something = something;
   * </ul>
   *
   * <p>In addition, we only recognize an export if the base export object is not defined or is
   * defined in externs.
   */
  public static boolean isCommonJsExport(NodeTraversal t, Node export) {
    if (export.matchesQualifiedName(MODULE + "." + EXPORTS)) {
      Var v = t.getScope().getVar(MODULE);
      if (v == null || v.isExtern()) {
        return true;
      }
    } else if (export.isName() && EXPORTS.equals(export.getString())) {
      Var v = t.getScope().getVar(export.getString());
      if (v == null || v.isGlobal()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Information on a Universal Module Definition A UMD is an IF statement and a reference to which
   * branch contains the commonjs export
   */
  static class UmdPattern {
    final Node ifRoot;
    final Node activeBranch;

    UmdPattern(Node ifRoot, Node activeBranch) {
      this.ifRoot = ifRoot;
      this.activeBranch = activeBranch;
    }
  }

  static class ExportInfo {
    final Node node;
    final Scope scope;

    ExportInfo(Node node, Scope scope) {
      this.node = node;
      this.scope = scope;
    }
  }

  private Node getBaseQualifiedNameNode(Node n) {
    Node refParent = n;
    while (refParent.getParent() != null && refParent.getParent().isQualifiedName()) {
      refParent = refParent.getParent();
    }

    return refParent;
  }

  /**
   * UMD modules are often wrapped in an IIFE for cases where they are used as scripts instead of
   * modules. Remove the wrapper.
   * @return Whether an IIFE wrapper was found and removed.
   */
  private boolean removeIIFEWrapper(Node root) {
    checkState(root.isScript());
    Node n = root.getFirstChild();

    // Sometimes scripts start with a semicolon for easy concatenation.
    // Skip any empty statements from those
    while (n != null && n.isEmpty()) {
      n = n.getNext();
    }

    // An IIFE wrapper must be the only non-empty statement in the script,
    // and it must be an expression statement.
    if (n == null || !n.isExprResult() || n.getNext() != null) {
      return false;
    }

    // Function expression can be forced with !, just skip !
    // TODO(ChadKillingsworth):
    //   Expression could also be forced with: + - ~ void
    //   ! ~ void can be repeated any number of times
    if (n != null && n.getFirstChild() != null && n.getFirstChild().isNot()) {
      n = n.getFirstChild();
    }

    Node call = n.getFirstChild();
    if (call == null || !call.isCall()) {
      return false;
    }

    // Find the IIFE call and function nodes
    Node fnc;
    if (call.getFirstChild().isFunction()) {
      fnc = n.getFirstFirstChild();
    } else if (call.getFirstChild().isGetProp()
        && call.getFirstFirstChild().isFunction()
        && call.getFirstFirstChild().getNext().isString()
        && call.getFirstFirstChild().getNext().getString().equals("call")) {
      fnc = call.getFirstFirstChild();

      // We only support explicitly binding "this" to the parent "this" or "exports"
      if (!(call.getSecondChild() != null
          && (call.getSecondChild().isThis()
              || call.getSecondChild().matchesQualifiedName(EXPORTS)))) {
        return false;
      }
    } else {
      return false;
    }

    if (NodeUtil.doesFunctionReferenceOwnArgumentsObject(fnc)) {
      return false;
    }

    CompilerInput ci = compiler.getInput(root.getInputId());
    ModulePath modulePath = ci.getPath();
    if (modulePath == null) {
      return false;
    }

    String iifeLabel = getModuleName(modulePath) + "_iifeWrapper";

    FunctionToBlockMutator mutator =
        new FunctionToBlockMutator(compiler, compiler.getUniqueNameIdSupplier());
    Node block = mutator.mutateWithoutRenaming(iifeLabel, fnc, call, null, false, false);
    root.removeChildren();
    root.addChildrenToFront(block.removeChildren());
    reportNestedScopesDeleted(fnc);
    compiler.reportChangeToEnclosingScope(root);

    return true;
  }

  /**
   * Traverse the script. Find all references to CommonJS require (import) and module.exports or
   * export statements. Rewrites any require calls to reference the rewritten module name.
   */
  class FindImportsAndExports implements NodeTraversal.Callback {
    private boolean hasGoogProvideOrModule = false;
    private Node script = null;

    boolean isCommonJsModule() {
      return (exports.size() > 0 || moduleExports.size() > 0) && !hasGoogProvideOrModule;
    }

    List<UmdPattern> umdPatterns = new ArrayList<>();
    List<ExportInfo> moduleExports = new ArrayList<>();
    List<ExportInfo> exports = new ArrayList<>();
    List<JSError> errors = new ArrayList<>();

    public List<ExportInfo> getModuleExports() {
      return ImmutableList.copyOf(moduleExports);
    }

    public List<ExportInfo> getExports() {
      return ImmutableList.copyOf(exports);
    }

    @Override
    public boolean shouldTraverse(NodeTraversal nodeTraversal, Node n, Node parent) {
      if (n.isScript()) {
        checkState(this.script == null);
        this.script = n;
      }
      return true;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (t.inGlobalScope()) {
        // Check for goog.provide or goog.module statements
        if (parent == null
            || NodeUtil.isControlStructure(parent)
            || NodeUtil.isStatementBlock(parent)) {
          if (n.isExprResult()) {
            Node maybeGetProp = n.getFirstFirstChild();
            if (maybeGetProp != null
                && (maybeGetProp.matchesQualifiedName("goog.provide")
                    || maybeGetProp.matchesQualifiedName("goog.module"))) {
              hasGoogProvideOrModule = true;
            }
          }
        }
      }

      // Find require.ensure calls
      if (n.isCall() && n.getFirstChild().matchesQualifiedName("require.ensure")) {
        visitRequireEnsureCall(t, n);
      }

      if (n.matchesQualifiedName(MODULE + "." + EXPORTS)) {
        if (isCommonJsExport(t, n)) {
          moduleExports.add(new ExportInfo(n, t.getScope()));

          // If the module.exports statement is nested in the then branch of an if statement,
          // assume the if statement is an UMD pattern with a common js export in the then branch
          // This seems fragile but has worked well for a long time.
          // TODO(ChadKillingsworth): Discover if there is a better way to detect these.
          Node ifAncestor = getOutermostIfAncestor(parent);
          if (ifAncestor != null && (NodeUtil.isLValue(n) || isInIfTest(n))) {
            UmdPattern existingPattern = findUmdPattern(umdPatterns, ifAncestor);
            if (existingPattern != null) {
              umdPatterns.remove(existingPattern);
            }
            Node enclosingIf =
                NodeUtil.getEnclosingNode(
                    n,
                    new Predicate<Node>() {
                      @Override
                      public boolean apply(Node node) {
                        return node.isIf() || node.isHook();
                      }
                    });
            umdPatterns.add(new UmdPattern(ifAncestor, enclosingIf.getSecondChild()));
          }
        }
      } else if (n.matchesQualifiedName("define.amd")) {
        // If a define.amd statement is nested in the then branch of an if statement,
        // assume the if statement is an UMD pattern with a common js export
        // in the else branch
        // This seems fragile but has worked well for a long time.
        // TODO(ChadKillingsworth): Discover if there is a better way to detect these.
        Node ifAncestor = getOutermostIfAncestor(parent);
        if (ifAncestor != null
            && findUmdPattern(umdPatterns, ifAncestor) == null
            && (NodeUtil.isLValue(n) || isInIfTest(n))) {
          umdPatterns.add(new UmdPattern(ifAncestor, ifAncestor.getChildAtIndex(2)));
        }
      }

      if (n.isName() && EXPORTS.equals(n.getString())) {
        Var v = t.getScope().getVar(EXPORTS);
        if (v == null || v.isGlobal()) {
          Node qNameRoot = getBaseQualifiedNameNode(n);
          if (qNameRoot != null
              && qNameRoot.matchesQualifiedName(EXPORTS)
              && NodeUtil.isLValue(qNameRoot)) {
            // Match the special assignment
            // exports = module.exports
            if (n.getGrandparent().isExprResult()
                && n.getNext() != null
                && ((n.getNext().isGetProp()
                        && n.getNext().matchesQualifiedName(MODULE + "." + EXPORTS))
                    || (n.getNext().isAssign()
                        && n.getNext()
                            .getFirstChild()
                            .matchesQualifiedName(MODULE + "." + EXPORTS)))) {
              exports.add(new ExportInfo(n, t.getScope()));
            } else if (!this.hasGoogProvideOrModule) {
              errors.add(t.makeError(qNameRoot, SUSPICIOUS_EXPORTS_ASSIGNMENT));
            }
          } else {
            exports.add(new ExportInfo(n, t.getScope()));

            // If the exports statement is nested in the then branch of an if statement,
            // assume the if statement is an UMD pattern with a common js export in the then branch
            // This seems fragile but has worked well for a long time.
            // TODO(ChadKillingsworth): Discover if there is a better way to detect these.
            Node ifAncestor = getOutermostIfAncestor(parent);
            if (ifAncestor != null
                && findUmdPattern(umdPatterns, ifAncestor) == null
                && (NodeUtil.isLValue(n) || isInIfTest(n))) {
              umdPatterns.add(new UmdPattern(ifAncestor, ifAncestor.getSecondChild()));
            }
          }
        }
      } else if (n.isThis() && n.getParent().isGetProp() && t.inGlobalScope()) {
        exports.add(new ExportInfo(n, t.getScope()));
      }

      if (ProcessCommonJSModules.isCommonJsImport(n)) {
        visitRequireCall(t, n, parent);
      }
    }

    /** Visit require calls. */
    private void visitRequireCall(NodeTraversal t, Node require, Node parent) {
      // When require("name") is used as a standalone statement (the result isn't used)
      // it indicates that a module is being loaded for the side effects it produces.
      // In this case the require statement should just be removed as the dependency
      // sorting will insert the file for us.
      if (!NodeUtil.isExpressionResultUsed(require)
          && parent.isExprResult()
          && NodeUtil.isStatementBlock(parent.getParent())) {

        // Attempt to resolve the module so that load warnings are issued
        t.getInput()
            .getPath()
            .resolveJsModule(
                getCommonJsImportPath(require),
                require.getSourceFileName(),
                require.getLineno(),
                require.getCharno());
        Node grandparent = parent.getParent();
        parent.detach();
        compiler.reportChangeToEnclosingScope(grandparent);
      }
    }

    /**
     * Visit require.ensure calls. Replace the call with an IIFE. Require.ensure must always be of
     * the form:
     *
     * <p>require.ensure(['module1', ...], function(require) {})
     */
    private void visitRequireEnsureCall(NodeTraversal t, Node call) {
      if (call.getChildCount() != 3) {
        compiler.report(
            t.makeError(
                call,
                UNKNOWN_REQUIRE_ENSURE,
                "Expected the function to have 2 arguments but instead found {0}",
                "" + call.getChildCount()));
        return;
      }

      Node dependencies = call.getSecondChild();
      if (!dependencies.isArrayLit()) {
        compiler.report(
            t.makeError(
                dependencies,
                UNKNOWN_REQUIRE_ENSURE,
                "The first argument must be an array literal of string literals."));
        return;
      }

      for (Node dep : dependencies.children()) {
        if (!dep.isString()) {
          compiler.report(
              t.makeError(
                  dep,
                  UNKNOWN_REQUIRE_ENSURE,
                  "The first argument must be an array literal of string literals."));
          return;
        }
      }
      Node callback = dependencies.getNext();
      if (!(callback.isFunction()
          && callback.getSecondChild().getChildCount() == 1
          && callback.getSecondChild().getFirstChild().isName()
          && "require".equals(callback.getSecondChild().getFirstChild().getString()))) {
        compiler.report(
            t.makeError(
                callback,
                UNKNOWN_REQUIRE_ENSURE,
                "The second argument must be a function"
                    + " whose first argument is named \"require\"."));
        return;
      }

      callback.detach();

      // Remove the "require" argument from the parameter list.
      callback.getSecondChild().removeChildren();
      call.removeChildren();
      call.putBooleanProp(Node.FREE_CALL, true);
      call.addChildToFront(callback);

      t.reportCodeChange();
    }

    void reportModuleErrors() {
      for (JSError error : errors) {
        compiler.report(error);
      }
    }

    /**
     * If the export is directly assigned more than once, or the assignments are not global, declare
     * the module name variable.
     *
     * <p>If all of the assignments are simply property assignments, initialize the module name
     * variable as a namespace.
     *
     * <p>Returns whether the default export can be declared constant
     */
    boolean initializeModule() {
      CompilerInput ci = compiler.getInput(this.script.getInputId());
      ModulePath modulePath = ci.getPath();
      if (modulePath == null) {
        return true;
      }

      String moduleName = getModuleName(ci);

      List<ExportInfo> exportsToRemove = new ArrayList<>();
      for (ExportInfo export : exports) {
        if (NodeUtil.getEnclosingScript(export.node) == null) {
          continue;
        }
        Node qNameBase = getBaseQualifiedNameNode(export.node);
        if (export.node == qNameBase
            && export.node.getParent().isAssign()
            && export.node.getGrandparent().isExprResult()
            && export.node.getPrevious() == null
            && export.node.getNext() != null) {

          // Find any identity assignments and just remove them
          // exports = module.exports;
          if (export.node.getNext().isGetProp()
              && export.node.getNext().matchesQualifiedName(MODULE + "." + EXPORTS)) {
            for (ExportInfo moduleExport : moduleExports) {
              if (moduleExport.node == export.node.getNext()) {
                moduleExports.remove(moduleExport);
                break;
              }
            }

            Node changeRoot = export.node.getGrandparent().getParent();
            export.node.getGrandparent().detach();
            exportsToRemove.add(export);
            compiler.reportChangeToEnclosingScope(changeRoot);

            // Find compound identity assignments and remove the exports = portion
            // exports = module.exports = foo;
          } else if (export.node.getNext().isAssign()
              && export
                  .node
                  .getNext()
                  .getFirstChild()
                  .matchesQualifiedName(MODULE + "." + EXPORTS)) {
            Node assign = export.node.getNext();
            export.node.getParent().replaceWith(assign.detach());
            exportsToRemove.add(export);
            compiler.reportChangeToEnclosingScope(assign);
          }
        }
      }

      exports.removeAll(exportsToRemove);

      // If we assign to the variable more than once or all the assignments
      // are properties, initialize the variable as well.
      int directAssignments = 0;
      for (ExportInfo export : moduleExports) {
        if (NodeUtil.getEnclosingScript(export.node) == null) {
          continue;
        }

        Node base = getBaseQualifiedNameNode(export.node);
        if (base == export.node && export.node.getParent().isAssign()) {
          Node rValue = NodeUtil.getRValueOfLValue(export.node);
          if (rValue == null || !rValue.isObjectLit()) {
            directAssignments++;
          }
        }
      }

      Node initModule = IR.var(IR.name(moduleName), IR.objectlit());
      JSDocInfoBuilder builder = new JSDocInfoBuilder(true);
      builder.recordConstancy();
      initModule.setJSDocInfo(builder.build());
      if (directAssignments == 0) {
        Node defaultProp = IR.stringKey(EXPORT_PROPERTY_NAME);
        defaultProp.addChildToFront(IR.objectlit());
        initModule.getFirstFirstChild().addChildToFront(defaultProp);
        builder = new JSDocInfoBuilder(true);
        builder.recordConstancy();
        defaultProp.setJSDocInfo(builder.build());
      }
      this.script.addChildToFront(initModule.useSourceInfoFromForTree(this.script));
      compiler.reportChangeToEnclosingScope(this.script);

      return directAssignments < 2;
    }

    /** Find the outermost if node ancestor for a node without leaving the function scope */
    private Node getOutermostIfAncestor(Node n) {
      if (n == null || NodeUtil.isTopLevel(n) || n.isFunction()) {
        return null;
      }
      Node parent = n.getParent();
      if (parent == null) {
        return null;
      }

      // When walking up ternary operations (hook), don't check if parent is the condition,
      // because one ternary operation can be then/else branch of another.
      if (parent.isIf() || parent.isHook()) {
        Node outerIf = getOutermostIfAncestor(parent);
        if (outerIf != null) {
          return outerIf;
        }

        return parent;
      }

      return getOutermostIfAncestor(parent);
    }

    /** Return whether the node is within the test portion of an if statement */
    private boolean isInIfTest(Node n) {
      if (n == null || NodeUtil.isTopLevel(n) || n.isFunction()) {
        return false;
      }
      Node parent = n.getParent();
      if (parent == null) {
        return false;
      }

      if ((parent.isIf() || parent.isHook()) && parent.getFirstChild() == n) {
        return true;
      }

      return isInIfTest(parent);
    }

    /** Remove a Universal Module Definition and leave just the commonjs export statement */
    boolean replaceUmdPatterns() {
      boolean needsRetraverse = false;
      Node changeScope;
      for (UmdPattern umdPattern : umdPatterns) {
        if (NodeUtil.getEnclosingScript(umdPattern.ifRoot) == null) {
          reportNestedScopesDeleted(umdPattern.ifRoot);
          continue;
        }

        Node parent = umdPattern.ifRoot.getParent();
        Node newNode = umdPattern.activeBranch;

        if (newNode == null) {
          parent.removeChild(umdPattern.ifRoot);
          reportNestedScopesDeleted(umdPattern.ifRoot);
          compiler.reportChangeToEnclosingScope(parent);
          needsRetraverse = true;
          continue;
        }

        // Remove redundant block node. Not strictly necessary, but makes tests more legible.
        if (umdPattern.activeBranch.isNormalBlock()
            && umdPattern.activeBranch.getChildCount() == 1) {
          newNode = umdPattern.activeBranch.removeFirstChild();
        } else {
          newNode.detach();
        }
        needsRetraverse = true;
        parent.replaceChild(umdPattern.ifRoot, newNode);
        reportNestedScopesDeleted(umdPattern.ifRoot);
        changeScope = NodeUtil.getEnclosingChangeScopeRoot(newNode);
        if (changeScope != null) {
          compiler.reportChangeToEnclosingScope(newNode);
        }

        Node block = parent;
        if (block.isExprResult()) {
          block = block.getParent();
        }

        // Detect UMD Factory Patterns and inline the functions
        if (block.isNormalBlock() && block.getParent().isFunction()
            && block.getGrandparent().isCall()
            && parent.hasOneChild()) {
          Node enclosingFnCall = block.getGrandparent();
          Node fn = block.getParent();

          Node enclosingScript = NodeUtil.getEnclosingScript(enclosingFnCall);
          if (enclosingScript == null) {
            continue;
          }
          CompilerInput ci = compiler.getInput(
              NodeUtil.getEnclosingScript(enclosingFnCall).getInputId());
          ModulePath modulePath = ci.getPath();
          if (modulePath == null) {
            continue;
          }
          needsRetraverse = true;
          String factoryLabel =
              modulePath.toModuleName() + "_factory" + compiler.getUniqueNameIdSupplier().get();

          FunctionToBlockMutator mutator =
              new FunctionToBlockMutator(compiler, compiler.getUniqueNameIdSupplier());
          Node newStatements =
              mutator.mutateWithoutRenaming(factoryLabel, fn, enclosingFnCall, null, false, false);

          // Check to see if the returned block is of the form:
          // {
          //   var jscomp$inline = function() {};
          //   jscomp$inline();
          // }
          //
          // or
          //
          // {
          //   var jscomp$inline = function() {};
          //   module.exports = jscomp$inline();
          // }
          //
          // If so, inline again
          if (newStatements.isNormalBlock()
              && newStatements.hasTwoChildren()
              && newStatements.getFirstChild().isVar()
              && newStatements.getFirstFirstChild().hasOneChild()
              && newStatements.getFirstFirstChild().getFirstChild().isFunction()
              && newStatements.getSecondChild().isExprResult()) {
            Node inlinedFn = newStatements.getFirstFirstChild().getFirstChild();
            Node expr = newStatements.getSecondChild().getFirstChild();
            Node call = null;
            String assignedName = null;
            if (expr.isAssign() && expr.getSecondChild().isCall()) {
              call = expr.getSecondChild();
              assignedName =
                  modulePath.toModuleName() + "_iife" + compiler.getUniqueNameIdSupplier().get();
            } else if (expr.isCall()) {
              call = expr;
            }

            if (call != null) {
              newStatements =
                  mutator.mutateWithoutRenaming(
                      factoryLabel, inlinedFn, call, assignedName, false, false);
              if (assignedName != null) {
                Node newName =
                    IR.var(
                            NodeUtil.newName(
                                compiler,
                                assignedName,
                                fn,
                                expr.getFirstChild().getQualifiedName()))
                        .useSourceInfoFromForTree(fn);
                if (newStatements.hasChildren()
                    && newStatements.getFirstChild().isExprResult()
                    && newStatements.getFirstFirstChild().isAssign()
                    && newStatements.getFirstFirstChild().getFirstChild().isName()
                    && newStatements
                        .getFirstFirstChild()
                        .getFirstChild()
                        .getString()
                        .equals(assignedName)) {
                  newName
                      .getFirstChild()
                      .addChildToFront(
                          newStatements.getFirstFirstChild().getSecondChild().detach());
                  newStatements.replaceChild(newStatements.getFirstChild(), newName);
                } else {
                  newStatements.addChildToFront(newName);
                }
                expr.replaceChild(expr.getSecondChild(), newName.getFirstChild().cloneNode());
                newStatements.addChildToBack(expr.getParent().detach());
              }
            }
          }

          Node callRoot = enclosingFnCall.getParent();
          if (callRoot.isNot()) {
            callRoot = callRoot.getParent();
          }
          if (callRoot.isExprResult()) {
            Node callRootParent = callRoot.getParent();
            callRootParent.addChildrenAfter(newStatements.removeChildren(), callRoot);
            callRoot.detach();
            reportNestedScopesChanged(callRootParent);
            compiler.reportChangeToEnclosingScope(callRootParent);
            reportNestedScopesDeleted(enclosingFnCall);
          } else {
            parent.replaceChild(umdPattern.ifRoot, newNode);
            compiler.reportChangeToEnclosingScope(newNode);
            reportNestedScopesDeleted(umdPattern.ifRoot);
          }
        }
      }
      return needsRetraverse;
    }
  }

  private void reportNestedScopesDeleted(Node n) {
    NodeUtil.visitPreOrder(
        n,
        new NodeUtil.Visitor() {
          @Override
          public void visit(Node n) {
            if (n.isFunction()) {
              compiler.reportFunctionDeleted(n);
            }
          }
        },
        Predicates.<Node>alwaysTrue());
  }

  private void reportNestedScopesChanged(Node n) {
    NodeUtil.visitPreOrder(
        n,
        new NodeUtil.Visitor() {
          @Override
          public void visit(Node n) {
            if (n.isFunction()) {
              compiler.reportChangeToChangeScope(n);
            }
          }
        },
        Predicates.<Node>alwaysTrue());
  }

  private static UmdPattern findUmdPattern(List<UmdPattern> umdPatterns, Node n) {
    for (UmdPattern umd : umdPatterns) {
      if (umd.ifRoot == n) {
        return umd;
      }
    }
    return null;
  }

  /**
   * Traverse a file and rewrite all references to imported names directly to the targeted module
   * name.
   *
   * <p>If a file is a CommonJS module, rewrite export statements. Typically exports create an alias
   * - the rewriting tries to avoid such aliases.
   */
  private class RewriteModule extends AbstractPostOrderCallback {
    private final boolean allowFullRewrite;
    private final ImmutableCollection<ExportInfo> exports;
    private final List<Node> imports = new ArrayList<>();
    private final List<Node> rewrittenClassExpressions = new ArrayList<>();
    private final List<Node> functionsToHoist = new ArrayList<>();
    private final boolean defaultExportIsConst;

    public RewriteModule(
        boolean allowFullRewrite,
        ImmutableCollection<ExportInfo> exports,
        boolean defaultExportIsConst) {
      this.allowFullRewrite = allowFullRewrite;
      this.exports = exports;
      this.defaultExportIsConst = defaultExportIsConst;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      switch (n.getToken()) {
        case SCRIPT:
          // Class names can't be changed during the middle of a traversal. Unlike functions,
          // the name can be the EMPTY token rather than just a zero length string.
          for (Node clazz : rewrittenClassExpressions) {
            clazz.replaceChild(
                clazz.getFirstChild(), IR.empty().useSourceInfoFrom(clazz.getFirstChild()));
            t.reportCodeChange();
          }

          CompilerInput ci = compiler.getInput(n.getInputId());
          String moduleName = getModuleName(ci);

          // If a function is the direct module export, move it to the top.
          for (int i = 1; i < functionsToHoist.size(); i++) {
            if (functionsToHoist
                .get(i)
                .getFirstFirstChild()
                .matchesQualifiedName(getBasePropertyImport(moduleName))) {
              Node fncVar = functionsToHoist.get(i);
              functionsToHoist.remove(i);
              functionsToHoist.add(0, fncVar);
              break;
            }
          }

          // Hoist functions in reverse order so that they maintain the same relative
          // order after hoisting.
          for (int i = functionsToHoist.size() - 1; i >= 0; i--) {
            Node functionExpr = functionsToHoist.get(i);
            Node scopeRoot = t.getClosestHoistScopeRoot();
            Node insertionPoint = scopeRoot.getFirstChild();
            if (insertionPoint == null
                || !(insertionPoint.isVar()
                    && insertionPoint.getFirstChild().getString().equals(moduleName))) {
              insertionPoint = null;
            }

            if (insertionPoint == null) {
              if (scopeRoot.getFirstChild() != functionExpr) {
                scopeRoot.addChildToFront(functionExpr.detach());
              }
            } else if (insertionPoint != functionExpr && insertionPoint.getNext() != functionExpr) {
              scopeRoot.addChildAfter(functionExpr.detach(), insertionPoint);
            }
          }

          for (ExportInfo export : exports) {
            visitExport(t, export);
          }

          for (Node require : imports) {
            visitRequireCall(t, require, require.getParent());
          }

          break;

        case CALL:
          if (isCommonJsImport(n)) {
            imports.add(n);
          }
          break;

        case VAR:
        case LET:
        case CONST:
          // Multiple declarations need split apart so that they can be refactored into
          // property assignments or removed altogether.
          if (n.hasMoreThanOneChild() && !NodeUtil.isAnyFor(parent)) {
            List<Node> vars = splitMultipleDeclarations(n);
            t.reportCodeChange();
            for (Node var : vars) {
              visit(t, var.getFirstChild(), var);
            }
          }

          // UMD Inlining can shadow global variables - these are just removed.
          //
          // var exports = exports;
          if (n.getFirstChild().hasChildren()
              && n.getFirstFirstChild().isName()
              && n.getFirstChild().getString().equals(n.getFirstFirstChild().getString())) {
            n.detach();
            t.reportCodeChange();
            return;
          }
          break;

        case NAME:
          {
            // If this is a name declaration with multiple names, it will be split apart when
            // the parent is visited and then revisit the children.
            if (NodeUtil.isNameDeclaration(n.getParent()) && n.getParent().hasMoreThanOneChild()) {
              break;
            }

            String qName = n.getQualifiedName();
            if (qName == null) {
              break;
            }
            final Var nameDeclaration = t.getScope().getVar(qName);
            if (nameDeclaration != null
                && nameDeclaration.getNode() != null
                && Objects.equals(nameDeclaration.getNode().getInputId(), n.getInputId())) {
              // Avoid renaming a shadowed global
              //
              // var angular = angular;  // value is global ref
              Node enclosingDeclaration =
                  NodeUtil.getEnclosingNode(
                      n,
                      new Predicate<Node>() {
                        @Override
                        public boolean apply(Node node) {
                          return node == nameDeclaration.getNameNode();
                        }
                      });

              if (enclosingDeclaration == null
                  || enclosingDeclaration == n
                  || nameDeclaration.getScope() != t.getScope()) {
                maybeUpdateName(t, n, nameDeclaration);
              }
            }
            break;
          }

          // ES6 object literal shorthand notation can refer to renamed variables
        case STRING_KEY:
          {
            if (n.hasChildren()
                || n.isQuotedString()
                || n.getParent().getParent().isDestructuringLhs()) {
              break;
            }
            Var nameDeclaration = t.getScope().getVar(n.getString());
            if (nameDeclaration == null) {
              break;
            }
            String importedName = getModuleImportName(t, nameDeclaration.getNode());
            if (nameDeclaration.isGlobal() || importedName != null) {
              Node value = IR.name(n.getString()).useSourceInfoFrom(n);
              n.addChildToBack(value);
              maybeUpdateName(t, value, nameDeclaration);
            }
            break;
          }

        case GETPROP:
          if (n.matchesQualifiedName(MODULE + ".id")) {
            Var v = t.getScope().getVar(MODULE);
            if (v == null || v.isExtern()) {
              n.replaceWith(IR.string(t.getInput().getPath().toString()).useSourceInfoFrom(n));
            }
          }
          break;

        case TYPEOF:
          if (allowFullRewrite
              && n.getFirstChild().isName()
              && (n.getFirstChild().getString().equals(MODULE)
                  || n.getFirstChild().getString().equals(EXPORTS))) {
            Var v = t.getScope().getVar(n.getFirstChild().getString());
            if (v == null || v.isExtern()) {
              n.replaceWith(IR.string("object"));
            }
          }
          break;

        default:
          break;
      }

      fixTypeAnnotationsForNode(t, n);
    }

    private void fixTypeAnnotationsForNode(NodeTraversal t, Node n) {
      JSDocInfo info = n.getJSDocInfo();
      if (info != null) {
        for (Node typeNode : info.getTypeNodes()) {
          fixTypeNode(t, typeNode);
        }
      }
    }

    /**
     * Visit require calls. Rewrite require statements to be a direct reference to name of require
     * module. By this point all references to the import alias should have already been renamed.
     */
    private void visitRequireCall(NodeTraversal t, Node require, Node parent) {
      String moduleName = getImportedModuleName(t, require);
      Node moduleRef =
          NodeUtil.newQName(compiler, getBasePropertyImport(moduleName))
              .useSourceInfoFromForTree(require);
      parent.replaceChild(require, moduleRef);

      t.reportCodeChange();
    }

    /**
     * Visit export statements. Export statements can be either a direct assignment: module.exports
     * = foo or a property assignment: module.exports.foo = foo; exports.foo = foo;
     */
    private void visitExport(NodeTraversal t, ExportInfo export) {
      Node root = getBaseQualifiedNameNode(export.node);
      Node rValue = NodeUtil.getRValueOfLValue(root);

      // For object literal assignments to module.exports, convert them to
      // individual property assignments.
      //
      //     module.exports = { foo: bar};
      //
      // becomes
      //
      //     module.exports = {};
      //     module.exports.foo = bar;
      if (root.matchesQualifiedName("module.exports")) {
        if (rValue != null
            && rValue.isObjectLit()
            && root.getParent().isAssign()
            && root.getParent().getParent().isExprResult()) {
          expandObjectLitAssignment(t, root, export.scope);
          return;
        }
      }

      String moduleName = getModuleName(t.getInput());
      Var moduleInitialization = t.getScope().getVar(moduleName);

      // If this is an assignment to module.exports or exports, renaming
      // has already handled this case. Remove the export.
      Var rValueVar = null;
      if (rValue != null && rValue.isQualifiedName()) {
        rValueVar = export.scope.getVar(rValue.getQualifiedName());
      }

      if (root.getParent().isAssign()
          && (root.getNext() != null && (root.getNext().isName() || root.getNext().isGetProp()))
          && root.getParent().getParent().isExprResult()
          && rValueVar != null
          && (NodeUtil.getEnclosingScript(rValueVar.nameNode) == null
              || (rValueVar.nameNode.getParent() != null && !rValueVar.isParam()))) {
        root.getParent().getParent().detach();
        t.reportCodeChange();
        return;
      }

      moduleName = moduleName + "." + EXPORT_PROPERTY_NAME;

      Node updatedExport =
          NodeUtil.newQName(compiler, moduleName, export.node, export.node.getQualifiedName());
      boolean exportIsConst =
          defaultExportIsConst
              && updatedExport.matchesQualifiedName(
                  getBasePropertyImport(getModuleName(t.getInput())))
              && root == export.node
              && NodeUtil.isLValue(export.node);

      Node changeScope = null;

      if (root.matchesQualifiedName("module.exports")
          && rValue != null
          && export.scope.getVar("module.exports") == null
          && root.getParent().isAssign()) {
        if (root.getGrandparent().isExprResult() && moduleInitialization == null) {
          // Rewrite "module.exports = foo;" to "var moduleName = foo;"
          Node parent = root.getParent();
          Node exportName = IR.exprResult(IR.assign(updatedExport, rValue.detach()));
          if (exportIsConst) {
            JSDocInfoBuilder info = new JSDocInfoBuilder(false);
            info.recordConstancy();
            exportName.getFirstChild().setJSDocInfo(info.build());
          }
          parent.getParent().replaceWith(exportName.useSourceInfoFromForTree(root.getParent()));
          changeScope = NodeUtil.getEnclosingChangeScopeRoot(parent);
        } else if (root.getNext() != null
            && root.getNext().isName()
            && rValueVar != null
            && rValueVar.isGlobal()) {
          // This is a where a module export assignment is used in a complex expression.
          // Before: `SOME_VALUE !== undefined && module.exports = SOME_VALUE`
          // After: `SOME_VALUE !== undefined && module$name`
          root.getParent().replaceWith(updatedExport);
          changeScope = NodeUtil.getEnclosingChangeScopeRoot(root);
        } else {
          // Other references to "module.exports" are just replaced with the module name.
          export.node.replaceWith(updatedExport);
          if (updatedExport.getParent().isAssign() && exportIsConst) {
            JSDocInfoBuilder infoBuilder =
                JSDocInfoBuilder.maybeCopyFrom(updatedExport.getParent().getJSDocInfo());
            infoBuilder.recordConstancy();
            updatedExport.getParent().setJSDocInfo(infoBuilder.build());
          }
          changeScope = NodeUtil.getEnclosingChangeScopeRoot(updatedExport);
        }
      } else {
        // Other references to "module.exports" are just replaced with the module name.
        export.node.replaceWith(updatedExport);
        if (updatedExport.getParent().isAssign() && exportIsConst) {
          JSDocInfoBuilder infoBuilder =
              JSDocInfoBuilder.maybeCopyFrom(updatedExport.getParent().getJSDocInfo());
          infoBuilder.recordConstancy();
          updatedExport.getParent().setJSDocInfo(infoBuilder.build());
        }

        changeScope = NodeUtil.getEnclosingChangeScopeRoot(updatedExport);
      }
      if (changeScope != null) {
        compiler.reportChangeToChangeScope(changeScope);
      }
    }

    /**
     * Since CommonJS modules may have only a single export, it's common to see the export be an
     * object literal. We want to expand this to individual property assignments. If any individual
     * property assignment has been renamed, it will be removed.
     *
     * <p>We need to keep assignments which aren't names
     *
     * <p>module.exports = { foo: bar, baz: function() {} }
     *
     * <p>becomes
     *
     * <p>module.exports.foo = bar; // removed later module.exports.baz = function() {};
     */
    private void expandObjectLitAssignment(NodeTraversal t, Node export, Scope scope) {
      checkState(export.getParent().isAssign());
      Node insertionRef = export.getParent().getParent();
      checkState(insertionRef.isExprResult());
      Node insertionParent = insertionRef.getParent();
      checkNotNull(insertionParent);

      Node rValue = NodeUtil.getRValueOfLValue(export);
      Node key = rValue.getFirstChild();

      while (key != null) {
        Node lhs;
        if (key.isQuotedString()) {
          lhs = IR.getelem(export.cloneTree(), IR.string(key.getString()));
        } else {
          lhs = IR.getprop(export.cloneTree(), IR.string(key.getString()));
        }

        Node value = null;
        if (key.isStringKey()) {
          if (key.hasChildren()) {
            value = key.removeFirstChild();
          } else {
            value = IR.name(key.getString());
          }
        } else if (key.isMemberFunctionDef()) {
          value = key.getFirstChild().detach();
        }

        Node expr = null;
        if (!key.isGetterDef()) {
          expr = IR.exprResult(IR.assign(lhs, value)).useSourceInfoIfMissingFromForTree(key);
          insertionParent.addChildAfter(expr, insertionRef);
          ExportInfo newExport = new ExportInfo(lhs.getFirstChild(), scope);
          visitExport(t, newExport);
        } else {
          String moduleName = getModuleName(t.getInput());
          Var moduleVar = t.getScope().getVar(moduleName + "." + EXPORT_PROPERTY_NAME);
          Node defaultProp = null;
          if (moduleVar == null) {
            moduleVar = t.getScope().getVar(moduleName);
            if (moduleVar != null
                && moduleVar.getNode().getFirstChild() != null
                && moduleVar.getNode().getFirstChild().isObjectLit()) {
              defaultProp =
                  NodeUtil.getFirstPropMatchingKey(
                      moduleVar.getNode().getFirstChild(), EXPORT_PROPERTY_NAME);
            }
          } else if (moduleVar.getNode().getFirstChild() != null
              && moduleVar.getNode().getFirstChild().isObjectLit()) {
            defaultProp = moduleVar.getNode().getFirstChild();
          }

          if (defaultProp != null) {
            Node getter = key.detach();
            defaultProp.addChildToBack(getter);
          }
        }

        // Export statements can be removed in visitExport
        if (expr != null && expr.getParent() != null) {
          insertionRef = expr;
        }

        key = key.getNext();
      }

      export.getParent().getParent().detach();
    }

    /**
     * Given a name reference, check to see if it needs renamed.
     *
     * <p>We handle 3 main cases: 1. References to an import alias. These are replaced with a direct
     * reference to the imported module. 2. Names which are exported. These are rewritten to be the
     * export assignment directly. 3. Global names: If a name is global to the script, add a suffix
     * so it doesn't collide with any other global.
     *
     * <p>Rewriting case 1 is safe to perform on all files. Cases 2 and 3 can only be done if this
     * file is a commonjs module.
     */
    private void maybeUpdateName(NodeTraversal t, Node n, Var var) {
      checkNotNull(var);
      checkState(n.isName() || n.isGetProp());
      checkState(n.getParent() != null);
      String importedModuleName = getModuleImportName(t, var.getNode());
      String name = n.getQualifiedName();

      // Check if the name refers to a alias for a require('foo') import.
      if (importedModuleName != null && n != var.getNode()) {
        // Reference the imported name directly, rather than the alias
        updateNameReference(t, n, name, importedModuleName, false, false);

      } else if (allowFullRewrite) {
        String exportedName = getExportedName(t, n, var);

        // We need to exclude the alias created by the require import. We assume dead
        // code elimination will remove these later.
        if ((n != var.getNode() || n.getParent().isClass()) && exportedName == null) {
          // The name is actually the export reference itself.
          // This will be handled later by visitExports.
          if (n.getParent().isClass() && n.getParent().getFirstChild() == n) {
            rewrittenClassExpressions.add(n.getParent());
          }

          return;
        }

        // Check if the name is used as an export
        if (importedModuleName == null
            && exportedName != null
            && !exportedName.equals(name)
            && !var.isParam()) {
          boolean exportPropIsConst =
              defaultExportIsConst
                  && getBasePropertyImport(getModuleName(t.getInput())).equals(exportedName)
                  && getBaseQualifiedNameNode(n) == n
                  && NodeUtil.isLValue(n);
          updateNameReference(t, n, name, exportedName, true, exportPropIsConst);

          // If it's a global name, rename it to prevent conflicts with other scripts
        } else if (var.isGlobal()) {
          String currentModuleName = getModuleName(t.getInput());

          if (currentModuleName.equals(name)) {
            return;
          }

          // refs to 'exports' are handled separately.
          if (EXPORTS.equals(name)) {
            return;
          }

          // closure_test_suite looks for test*() functions
          if (compiler.getOptions().exportTestFunctions && currentModuleName.startsWith("test")) {
            return;
          }

          String newName = name + "$$" + currentModuleName;
          updateNameReference(t, n, name, newName, false, false);
        }
      }
    }

    /**
     * @param nameRef the qualified name node
     * @param originalName of nameRef
     * @param newName for nameRef
     * @param requireFunctionExpressions Whether named class or functions should be rewritten to
     *     variable assignments
     */
    private void updateNameReference(
        NodeTraversal t,
        Node nameRef,
        String originalName,
        String newName,
        boolean requireFunctionExpressions,
        boolean qualifiedNameIsConst) {
      Node parent = nameRef.getParent();
      checkNotNull(parent);
      checkNotNull(newName);
      boolean newNameIsQualified = newName.indexOf('.') >= 0;

      Var newNameDeclaration = t.getScope().getVar(newName);

      switch (parent.getToken()) {
        case CLASS:
          if (parent.getIndexOfChild(nameRef) == 0
              && (newNameIsQualified || requireFunctionExpressions)) {
            // Refactor a named class to a class expression
            // We can't remove the class name during a traversal, so save it for later
            rewrittenClassExpressions.add(parent);

            Node newNameRef = NodeUtil.newQName(compiler, newName, nameRef, originalName);
            Node grandparent = parent.getParent();

            Node expr;
            if (!newNameIsQualified && newNameDeclaration == null) {
              expr = IR.let(newNameRef, IR.nullNode()).useSourceInfoIfMissingFromForTree(nameRef);
            } else {
              expr =
                  IR.exprResult(IR.assign(newNameRef, IR.nullNode()))
                      .useSourceInfoIfMissingFromForTree(nameRef);
              JSDocInfoBuilder info = JSDocInfoBuilder.maybeCopyFrom(parent.getJSDocInfo());
              parent.setJSDocInfo(null);
              if (qualifiedNameIsConst) {
                info.recordConstancy();
              }
              expr.getFirstChild().setJSDocInfo(info.build());
              fixTypeAnnotationsForNode(t, expr.getFirstChild());
            }
            grandparent.replaceChild(parent, expr);
            if (expr.isLet()) {
              expr.getFirstChild().replaceChild(expr.getFirstFirstChild(), parent);
            } else {
              expr.getFirstChild().replaceChild(expr.getFirstChild().getSecondChild(), parent);
            }
          } else if (parent.getIndexOfChild(nameRef) == 1) {
            Node newNameRef = NodeUtil.newQName(compiler, newName, nameRef, originalName);
            parent.replaceChild(nameRef, newNameRef);
          } else {
            nameRef.setString(newName);
            nameRef.setOriginalName(originalName);
          }
          break;

        case FUNCTION:
          if (newNameIsQualified || requireFunctionExpressions) {
            // Refactor a named function to a function expression
            if (NodeUtil.isFunctionExpression(parent)) {
              // Don't refactor if the parent is a named function expression.
              // e.g. var foo = function foo() {};
              return;
            }
            Node newNameRef = NodeUtil.newQName(compiler, newName, nameRef, originalName);
            Node grandparent = parent.getParent();
            nameRef.setString("");

            Node expr;
            if (!newNameIsQualified && newNameDeclaration == null) {
              expr = IR.var(newNameRef, IR.nullNode()).useSourceInfoIfMissingFromForTree(nameRef);
            } else {
              expr =
                  IR.exprResult(IR.assign(newNameRef, IR.nullNode()))
                      .useSourceInfoIfMissingFromForTree(nameRef);
            }
            grandparent.replaceChild(parent, expr);
            if (expr.isVar()) {
              expr.getFirstChild().replaceChild(expr.getFirstFirstChild(), parent);
            } else {
              expr.getFirstChild().replaceChild(expr.getFirstChild().getSecondChild(), parent);
              JSDocInfoBuilder info = JSDocInfoBuilder.maybeCopyFrom(parent.getJSDocInfo());
              parent.setJSDocInfo(null);
              if (qualifiedNameIsConst) {
                info.recordConstancy();
              }
              expr.getFirstChild().setJSDocInfo(info.build());
              fixTypeAnnotationsForNode(t, expr.getFirstChild());
            }
            functionsToHoist.add(expr);
          } else {
            nameRef.setString(newName);
            nameRef.setOriginalName(originalName);
          }
          break;

        case VAR:
        case LET:
        case CONST:
          // Multiple declaration - needs split apart.
          if (parent.getChildCount() > 1) {
            splitMultipleDeclarations(parent);
            parent = nameRef.getParent();
            newNameDeclaration = t.getScope().getVar(newName);
          }

          if (newNameIsQualified) {
            // Var declarations without initialization can simply
            // be removed if they are being converted to a property.
            if (!nameRef.hasChildren() && parent.getJSDocInfo() == null) {
              parent.detach();
              break;
            }

            // Refactor a var declaration to a getprop assignment
            Node getProp = NodeUtil.newQName(compiler, newName, nameRef, originalName);
            JSDocInfo info = parent.getJSDocInfo();
            parent.setJSDocInfo(null);
            if (nameRef.hasChildren()) {
              Node assign = IR.assign(getProp, nameRef.removeFirstChild());
              assign.setJSDocInfo(info);
              Node expr = IR.exprResult(assign).useSourceInfoIfMissingFromForTree(nameRef);
              parent.replaceWith(expr);
              JSDocInfoBuilder infoBuilder = JSDocInfoBuilder.maybeCopyFrom(info);
              parent.setJSDocInfo(null);
              if (qualifiedNameIsConst) {
                infoBuilder.recordConstancy();
              }
              assign.setJSDocInfo(infoBuilder.build());
              fixTypeAnnotationsForNode(t, assign);
            } else {
              getProp.setJSDocInfo(info);
              parent.replaceWith(IR.exprResult(getProp).useSourceInfoFrom(getProp));
            }
          } else if (newNameDeclaration != null && newNameDeclaration.getNameNode() != nameRef) {
            // Variable is already defined. Convert this to an assignment.
            // If the variable declaration has no initialization, we simply
            // remove the node. This can occur when the variable which is exported
            // is declared in an outer scope but assigned in an inner one.
            if (!nameRef.hasChildren()) {
              parent.detachFromParent();
              break;
            }

            Node name = NodeUtil.newName(compiler, newName, nameRef, originalName);
            Node assign = IR.assign(name, nameRef.removeFirstChild());
            JSDocInfo info = parent.getJSDocInfo();
            if (info != null) {
              parent.setJSDocInfo(null);
              assign.setJSDocInfo(info);
            }

            parent.replaceWith(IR.exprResult(assign).useSourceInfoFromForTree(nameRef));
          } else {
            nameRef.setString(newName);
            nameRef.setOriginalName(originalName);
          }
          break;

        default:
          {
            Node name =
                newNameIsQualified
                    ? NodeUtil.newQName(compiler, newName, nameRef, originalName)
                    : NodeUtil.newName(compiler, newName, nameRef, originalName);

            JSDocInfo info = nameRef.getJSDocInfo();
            if (info != null) {
              nameRef.setJSDocInfo(null);
              name.setJSDocInfo(info);
            }
            parent.replaceChild(nameRef, name);
            if (nameRef.hasChildren()) {
              name.addChildrenToFront(nameRef.removeChildren());
            }

            break;
          }
      }

      t.reportCodeChange();
    }

    /**
     * Determine whether the given name Node n is referenced in an export
     *
     * @return string - If the name is not used in an export, return it's own name If the name node
     *     is actually the export target itself, return null;
     */
    private String getExportedName(NodeTraversal t, Node n, Var var) {
      if (var == null || !Objects.equals(var.getNode().getInputId(), n.getInputId())) {
        return n.getQualifiedName();
      }

      String baseExportName = getBasePropertyImport(getModuleName(t.getInput()));

      for (ExportInfo export : this.exports) {
        Node exportBase = getBaseQualifiedNameNode(export.node);
        Node exportRValue = NodeUtil.getRValueOfLValue(exportBase);

        if (exportRValue == null) {
          continue;
        }

        Node exportedName = getExportedNameNode(export);
        // We don't want to handle the export itself
        if (exportRValue == n
            || ((NodeUtil.isClassExpression(exportRValue)
                    || NodeUtil.isFunctionExpression(exportRValue))
                && exportedName == n)) {
          return null;
        }

        String exportBaseQName = exportBase.getQualifiedName();

        if (exportRValue.isObjectLit()) {
          if (!"module.exports".equals(exportBaseQName)) {
            return n.getQualifiedName();
          }

          Node key = exportRValue.getFirstChild();
          boolean keyIsExport = false;
          while (key != null) {
            if (key.isStringKey()
                && !key.isQuotedString()
                && NodeUtil.isValidPropertyName(
                    compiler.getOptions().getLanguageIn().toFeatureSet(), key.getString())) {
              if (key.hasChildren()) {
                if (key.getFirstChild().isQualifiedName()) {
                  if (key.getFirstChild() == n) {
                    return null;
                  }

                  Var valVar = t.getScope().getVar(key.getFirstChild().getQualifiedName());
                  if (valVar != null && valVar.getNameNode() == var.getNameNode()) {
                    keyIsExport = true;
                    break;
                  }
                }
              } else {
                if (key == n) {
                  return null;
                }

                // Handle ES6 object lit shorthand assignments
                Var valVar = t.getScope().getVar(key.getString());
                if (valVar != null && valVar.getNameNode() == var.getNameNode()) {
                  keyIsExport = true;
                  break;
                }
              }
            }

            key = key.getNext();
          }
          if (key != null && keyIsExport) {
            return baseExportName + "." + key.getString();
          }
        } else {
          if (var.getNameNode() == exportedName) {
            String exportPrefix;
            if (exportBaseQName.startsWith(MODULE)) {
              exportPrefix = MODULE + "." + EXPORTS;
            } else {
              exportPrefix = EXPORTS;
            }

            if (exportBaseQName.length() == exportPrefix.length()) {
              return baseExportName;
            }

            return baseExportName + exportBaseQName.substring(exportPrefix.length());
          }
        }
      }
      return n.getQualifiedName();
    }

    private Node getExportedNameNode(ExportInfo info) {
      Node qNameBase = getBaseQualifiedNameNode(info.node);
      Node rValue = NodeUtil.getRValueOfLValue(qNameBase);

      if (rValue == null) {
        return null;
      }

      if (NodeUtil.isFunctionExpression(rValue) || NodeUtil.isClassExpression(rValue)) {
        return rValue.getFirstChild();
      }

      Var var = info.scope.getVar(rValue.getQualifiedName());
      if (var == null) {
        return null;
      }

      return var.getNameNode();
    }

    /**
     * Determine if the given Node n is an alias created by a module import.
     *
     * @return null if it's not an alias or the imported module name
     */
    private String getModuleImportName(NodeTraversal t, Node n) {
      Node rValue = null;
      String propSuffix = "";
      if (n.isStringKey()
          && n.getParent().isObjectPattern()
          && n.getParent().getParent().isDestructuringLhs()) {
        rValue = n.getParent().getNext();
        propSuffix = "." + n.getString();
      } else if (n.getParent() != null) {
        rValue = NodeUtil.getRValueOfLValue(n);
      }

      if (rValue == null) {
        return null;
      }

      if (rValue.isCall() && isCommonJsImport(rValue)) {
        return getBasePropertyImport(getImportedModuleName(t, rValue)) + propSuffix;
      } else if (rValue.isGetProp() && isCommonJsImport(rValue.getFirstChild())) {
        // var foo = require('bar').foo;
        String importName = getBasePropertyImport(getImportedModuleName(t, rValue.getFirstChild()));

        String suffix =
            rValue.getSecondChild().isGetProp()
                ? rValue.getSecondChild().getQualifiedName()
                : rValue.getSecondChild().getString();

        return importName + "." + suffix + propSuffix;
      }

      return null;
    }

    /**
     * Update any type references in JSDoc annotations to account for all the rewriting we've done.
     */
    private void fixTypeNode(NodeTraversal t, Node typeNode) {
      if (typeNode.isString()) {
        String name = typeNode.getString();
        // Type nodes can be module paths.
        if (ModuleLoader.isPathIdentifier(name)) {
          int lastSlash = name.lastIndexOf('/');
          int endIndex = name.indexOf('.', lastSlash);
          String localTypeName = null;
          if (endIndex == -1) {
            endIndex = name.length();
          } else {
            localTypeName = name.substring(endIndex);
          }

          String moduleName = name.substring(0, endIndex);
          String globalModuleName = getImportedModuleName(t, typeNode, moduleName);
          String baseImportProperty = getBasePropertyImport(globalModuleName);
          typeNode.setString(
              localTypeName == null ? baseImportProperty : baseImportProperty + localTypeName);

        } else {
          // A type node can be a getprop. Any portion of the getprop
          // can be either an import alias or export alias. Check each
          // segment.
          boolean wasRewritten = false;
          int endIndex = -1;
          while (endIndex < name.length()) {
            endIndex = name.indexOf('.', endIndex + 1);
            if (endIndex == -1) {
              endIndex = name.length();
            }
            String baseName = name.substring(0, endIndex);
            String suffix = endIndex < name.length() ? name.substring(endIndex) : "";
            Var typeDeclaration = t.getScope().getVar(baseName);

            // Make sure we can find a variable declaration (and it's in this file)
            if (typeDeclaration != null
                && Objects.equals(typeDeclaration.getNode().getInputId(), typeNode.getInputId())) {
              String importedModuleName = getModuleImportName(t, typeDeclaration.getNode());

              // If the name is an import alias, rewrite it to be a reference to the
              // module name directly
              if (importedModuleName != null) {
                typeNode.setString(importedModuleName + suffix);
                typeNode.setOriginalName(name);
                wasRewritten = true;
                break;
              } else if (this.allowFullRewrite) {
                // Names referenced in export statements can only be rewritten in
                // commonjs modules.
                String exportedName = getExportedName(t, typeNode, typeDeclaration);
                if (exportedName != null && !exportedName.equals(name)) {
                  typeNode.setString(exportedName + suffix);
                  typeNode.setOriginalName(name);
                  wasRewritten = true;
                  break;
                }
              }
            }
          }

          // If the name was neither an import alias or referenced in an export,
          // We still may need to rename it if it's global
          if (!wasRewritten && this.allowFullRewrite) {
            endIndex = name.indexOf('.');
            if (endIndex == -1) {
              endIndex = name.length();
            }
            String baseName = name.substring(0, endIndex);
            Var typeDeclaration = t.getScope().getVar(baseName);
            if (typeDeclaration != null && typeDeclaration.isGlobal()) {
              String moduleName = getModuleName(t.getInput());
              String newName = baseName + "$$" + moduleName;
              if (endIndex < name.length()) {
                newName += name.substring(endIndex);
              }

              typeNode.setString(newName);
              typeNode.setOriginalName(name);
            }
          }
        }
      }

      for (Node child = typeNode.getFirstChild(); child != null;
           child = child.getNext()) {
        fixTypeNode(t, child);
      }
    }

    private List<Node> splitMultipleDeclarations(Node var) {
      checkState(NodeUtil.isNameDeclaration(var));
      List<Node> vars = new ArrayList<>();
      JSDocInfo info = var.getJSDocInfo();
      while (var.getSecondChild() != null) {
        Node newVar = new Node(var.getToken(), var.removeFirstChild());

        if (info != null) {
          newVar.setJSDocInfo(info.clone());
        }

        newVar.useSourceInfoFrom(var);
        var.getParent().addChildBefore(newVar, var);
        vars.add(newVar);
      }
      vars.add(var);
      return vars;
    }
  }
}
