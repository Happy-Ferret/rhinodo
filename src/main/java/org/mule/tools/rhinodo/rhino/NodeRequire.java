/**
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.tools.rhinodo.rhino;

import org.apache.commons.io.FilenameUtils;
import org.mozilla.javascript.*;
import org.mozilla.javascript.commonjs.module.ModuleScope;
import org.mozilla.javascript.commonjs.module.ModuleScriptProvider;
import org.mozilla.javascript.commonjs.module.Require;
import org.mule.tools.rhinodo.api.NativeModule;
import org.mule.tools.rhinodo.impl.ExitCallbackExecutor;
import org.mule.tools.rhinodo.impl.NodeModuleImplBuilder;
import org.mule.tools.rhinodo.node.child_process.ChildProcessNativeModule;
import org.mule.tools.rhinodo.node.fs.FsNativeModule;
import org.mule.tools.rhinodo.node.process.ProcessNativeModule;
import org.mule.tools.rhinodo.node.timer.ClearInterval;
import org.mule.tools.rhinodo.node.timer.ClearTimeout;
import org.mule.tools.rhinodo.node.timer.SetInterval;
import org.mule.tools.rhinodo.node.timer.SetTimeout;
import org.mule.tools.rhinodo.node.vm.VmNativeModule;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.File;
import java.io.FilenameFilter;
import java.net.URI;
import java.util.*;

public class NodeRequire extends Require {
    private HashMap<String, Scriptable> nativeModuleMap;

    private Script preExec;
    private Script postExec;
    private final ExitCallbackExecutor exitCallbackExecutor;

    private static void executeOptionalScript(Script script, Context cx,
                                              Scriptable executionScope)
    {
        if(script != null) {
            script.exec(cx, executionScope);
        }
    }

    private BaseFunction compile = new BaseFunction() {
        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {

            final ScriptableObject moduleObject = (ScriptableObject)cx.newObject(
                    thisObj);

            defineReadOnlyProperty(moduleObject, "id", args[0]);

            String path = Context.toString(args[1]);
            File file = new File(path);
            URI uri = file.toURI();
            URI base;

            if (file.exists() && file.isFile()) {
                base = file.getParentFile().toURI();
            } else {
                base = file.toURI();
            }

            final Scriptable executionScope = new ModuleScope(thisObj, uri, base);

            defineReadOnlyProperty(moduleObject, "uri", uri.toString());

            Scriptable exports = cx.newObject(scope);
            executionScope.put("exports", executionScope, exports);
            executionScope.put("module", executionScope, moduleObject);
            moduleObject.put("exports", moduleObject, exports);
            install(executionScope);
            executeOptionalScript(preExec, cx, executionScope);
            cx.compileString(Context.toString(args[0]), path, 0, null).exec(cx, executionScope);
            executeOptionalScript(postExec, cx, executionScope);
            return ScriptRuntime.toObject(scope,
                    ScriptableObject.getProperty(moduleObject, "exports"));
        }
    };

    private static void defineReadOnlyProperty(ScriptableObject obj,
                                               String name, Object value) {
        ScriptableObject.putProperty(obj, name, value);
        obj.setAttributes(name, ScriptableObject.READONLY |
                ScriptableObject.PERMANENT);
    }

    public NodeRequire(final Queue<Function> asyncCallbacksQueue, final Scriptable env, final Context cx,
                       final Scriptable globalScope,
                       final ModuleScriptProvider moduleScriptProvider, final Script preExec, final Script postExec,
                       boolean sandboxed, final ExitCallbackExecutor exitCallbackExecutor) {
        super(cx,globalScope,moduleScriptProvider,preExec,postExec,sandboxed);
        this.exitCallbackExecutor = exitCallbackExecutor;
        loadNativeModules(env, cx, globalScope, asyncCallbacksQueue);

        ScriptableObject.putProperty(this, "cache", new NativeObject());

        //Adding extensions property
        NativeObject extensions = new NativeObject();
        BaseFunction notImplemented = new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                if (ScriptableObject.getProperty(env, "RHINODO_IGNORE_NOT_IMPLEMENTED_EXTENSIONS") != null) {
                    return cx.newObject(scope);
                }
                throw new NotImplementedException();
            }
        };
        ScriptableObject.putProperty(extensions, ".js", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                return NodeRequire.this.callSuperWrapped(cx, scope, thisObj, new Object[]{args[1]});
            }
        });
        ScriptableObject.putProperty(extensions, ".json", notImplemented);
        ScriptableObject.putProperty(extensions, ".node", notImplemented);

        ScriptableObject.putProperty(this, "extensions", extensions);
        Scriptable process = nativeModuleMap.get("process");
        ScriptableObject.putProperty(globalScope, "process", process);

        ScriptableObject.putProperty(globalScope, "clearTimeout", new ClearTimeout());
        ScriptableObject.putProperty(globalScope, "setTimeout", new SetTimeout(asyncCallbacksQueue));
        ScriptableObject.putProperty(globalScope, "clearInterval", new ClearInterval());
        ScriptableObject.putProperty(globalScope, "setInterval", new SetInterval(asyncCallbacksQueue));

        this.preExec = preExec;
        this.postExec = postExec;
    }

    private void loadNativeModules(Scriptable env, Context cx, Scriptable globalScope,
                                   Queue<Function> asyncCallbacksQueue) {
        NativeModule[] nativeModules = {new FsNativeModule(asyncCallbacksQueue),
                new ProcessNativeModule(env, asyncCallbacksQueue, exitCallbackExecutor),
                new ChildProcessNativeModule(asyncCallbacksQueue),
                new VmNativeModule(asyncCallbacksQueue)};
        nativeModuleMap = new HashMap<String, Scriptable>();

        for (NativeModule nativeModule : nativeModules) {
            nativeModuleMap.put(nativeModule.getId(), nativeModule.getModule(cx, globalScope));
        }

    }

    public Object callSuperWrapped(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        try {
            ScriptableObject.putProperty(thisObj, "_compile", compile);
            return super.call(cx, scope, thisObj, args);
        } catch (JavaScriptException e) {
            if ( thisObj instanceof ModuleScope) {
                String id = (String)Context.jsToJava(args[0], String.class);
                ModuleScope moduleScope = (ModuleScope) thisObj;
                URI base = moduleScope.getBase();
                URI current = moduleScope.getUri();
                URI uri = current.resolve(id  + ".js/");

                if (!id.startsWith("./") && !id.startsWith("../") && base != null &&
                        new File(uri.getPath()).exists()) {
                    // try to convert to a relative URI rooted on base
                    return super.call(cx, scope, thisObj, new Object[]{uri.getPath()});
                }
            }
            throw e;
        }
    }

    public static class TryExtensionsResult {
        private final String extensionAsString;
        private final Function callback;
        private final File file;

        public TryExtensionsResult(String extensionAsString, Function value, File file) {
            this.extensionAsString = extensionAsString;
            this.callback = value;
            this.file = file;
        }

        public String getExtensionAsString() {
            return extensionAsString;
        }

        public Function getCallback() {
            return callback;
        }

        public File getFile() {
            return file;
        }
    }

    public TryExtensionsResult tryExtensions(String id, ModuleScope thisObj) {
        File cwdFile = getBasePathForModule(thisObj);
        NativeObject extensions = ScriptableObject.getTypedProperty(this, "extensions", NativeObject.class);
        Object[] propertyIds = ScriptableObject.getPropertyIds(extensions);

        if (!(id.startsWith("./") || id.startsWith("../") || id.startsWith("/"))) {
            return null;
        }

        for (Object extension : propertyIds) {
            String extensionAsString = (String) extension;
            File file;
            if ( cwdFile != null ) {
                file = new File(FilenameUtils.concat(cwdFile.getAbsolutePath(), id + extensionAsString));

                /* Case of ./ only where we need to add index.js */
                if (!file.exists()) {
                    file = new File(FilenameUtils.concat(cwdFile.getAbsolutePath(), id + "index" + extensionAsString));
                }
            } else {
                /* Case of /a/b/c.js */
                file = new File(id + extensionAsString);
            }

            if ( file.exists() ) {
                Function value = ScriptableObject.getTypedProperty(extensions, extensionAsString,
                        Function.class);
                return new TryExtensionsResult(extensionAsString, value, file);
            }
        }

        return null;
    }


    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        String id = Context.toString(args[0]);

        Scriptable moduleToLoad;
        if ((moduleToLoad = nativeModuleMap.get(id)) != null ) {
            return moduleToLoad;
        }

        Scriptable extensions = ScriptableObject.getTypedProperty(this, "extensions", Scriptable.class);

        File file = new File(id);
        File packageJson = new File(id, "package.json");
        /* Case when a path like a/b/c is required and a file a/b/c.js exists */
        TryExtensionsResult extensionsResult;

        /* Absolute path */
        if (id.startsWith("/") && (extensionsResult = tryExtensions(id, null)) != null) {
            return extensionsResult.getCallback().call(cx, scope, thisObj, new Object[]{thisObj,
                    extensionsResult.getFile().getAbsolutePath()});
        /* Relative Path */
        } else if ( thisObj instanceof ModuleScope &&
                (extensionsResult = tryExtensions(id, (ModuleScope)thisObj)) != null ) {
            return extensionsResult.getCallback().call(cx, scope, thisObj,
                    new Object[]{thisObj, extensionsResult.getFile().getAbsolutePath()});
        } else if (  file.exists() && !file.isDirectory() &&
                ScriptableObject.hasProperty(extensions, "." + FilenameUtils.getExtension(id) ) ) {
            return ScriptableObject.getTypedProperty(extensions,  "." + FilenameUtils.getExtension( id),
                    Function.class ).call(cx, scope, thisObj, new Object[]{thisObj, id});
        /* Case when a path like a/b/c and a file named a/b/c/package.json exists */
        } else if ( file.isDirectory() && packageJson.exists() && packageJson.isFile() ) {
            Map<String,String> map = NodeModuleImplBuilder.getPackageJSONMap(packageJson);

            String main;
            if ( map != null && (main = map.get("main")) != null ) {
                /* Fetch entry point */
                File mainFile = new File(FilenameUtils.concat(file.getPath(), main));
                return callSuperWrapped(cx, scope, thisObj, new Object[]{mainFile.getAbsolutePath()});
            }
        } else if (thisObj instanceof ModuleScope) {
            File cwdFile = getBasePathForModule((ModuleScope) thisObj);
            cwdFile = getModuleRootDirectory(cwdFile);

            if (cwdFile == null) {
                return callSuperWrapped(cx, scope, thisObj, args);
            }

            File modulePath = new File(new File(cwdFile, "node_modules"), id);
            File modulePackageJSON = new File(modulePath, "package.json");
            if ( modulePackageJSON.exists()) {
                Map<String,String> map = NodeModuleImplBuilder.getPackageJSONMap(modulePackageJSON);

                String main = map.get("main");
                if (main == null) {
                    main = "index.js";
                }
                /* Fetch entry point */
                File mainFile = new File(FilenameUtils.concat(modulePath.getPath(),
                        main));
                if ( !mainFile.exists() ) {
                    mainFile = new File(FilenameUtils.concat(modulePath.getPath(),
                            main + ".js"));
                }
                return callSuperWrapped(cx, scope, thisObj,
                        new Object[]{mainFile.getAbsolutePath()});
            }

            cwdFile = getModuleRootDirectory(cwdFile.getParentFile());

            if (cwdFile == null) {
                return callSuperWrapped(cx, scope, thisObj, args);
            }

            modulePath = new File(new File(cwdFile, "node_modules"), id);
            modulePackageJSON = new File(modulePath, "package.json");
            if ( modulePackageJSON.exists()) {
                Map<String,String> map = NodeModuleImplBuilder.getPackageJSONMap(modulePackageJSON);

                /* Fetch entry point */
                File mainFile = new File(FilenameUtils.concat(modulePath.getPath(),
                        map.get("main")));
                if ( !mainFile.exists() ) {
                    mainFile = new File(FilenameUtils.concat(modulePath.getPath(),
                            map.get("main")+ ".js"));
                }
                return callSuperWrapped(cx, scope, thisObj,
                        new Object[]{mainFile.getAbsolutePath()});
            }
        }

        return callSuperWrapped(cx, scope, thisObj, args);
    }

    private File getModuleRootDirectory(File cwdFile) {
        String[] list;

        FilenameFilter filenameFilter = new FilenameFilter() {
            @Override
            public boolean accept(File file, String s) {
                return s.equals("package.json");
            }
        };
        list = cwdFile.list(filenameFilter);

        while (list.length != 1 ) {
            cwdFile = cwdFile.getParentFile();
            if ( cwdFile == null ) {
                return null;
            }
            list = cwdFile.list(filenameFilter);
        }

        return cwdFile;
    }

    private File getBasePathForModule(ModuleScope thisObj) {
        ModuleScope moduleScope = thisObj;
        if (moduleScope == null) {
            return null;
        }
        URI base = moduleScope.getUri();
        File cwdFile = new File(base.getPath());
        if ( !cwdFile.isDirectory() ) {
            cwdFile = cwdFile.getParentFile();
        }
        return cwdFile;
    }
}
