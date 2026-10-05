package org.lsposed.lspd.util;

import static de.robv.android.xposed.XposedBridge.TAG;

import android.os.Build;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.lsposed.lspd.nativebridge.HookBridge;
import org.lsposed.lspd.util.Utils.Log;

import hidden.ByteBufferDexClassLoader;
import io.github.libxposed.api.XposedInterface;
import sun.misc.CompoundEnumeration;

@SuppressWarnings("ConstantConditions")
public final class LspModuleClassLoader extends ByteBufferDexClassLoader {
    private static final String zipSeparator = "!/";
    private static final List<File> systemNativeLibraryDirs =
            splitPaths(System.getProperty("java.library.path"));
    private final String apk;
    private final boolean blockLegacyApi;
    private final List<File> nativeLibraryDirs = new ArrayList<>();

    /** Used when the obfuscation map is unavailable, i.e. on a build that does not obfuscate. */
    private static final String[] literalLegacyApiPrefixes = {
            "de.robv.android.xposed.",
            "android.app.AndroidApp",
            "android.content.res.XRes",
            "android.content.res.XModule",
    };

    /**
     * What the legacy API is called <i>here</i>, which is not what it is called in source: with
     * dex obfuscation on the daemon rewrites {@code de.robv.android.xposed}, {@code AndroidAppHelper}
     * and the {@code XResources} family in the framework dex and in every module dex, so the names
     * a module asks this loader for are a different random string on every boot. Matching the
     * literal package would leave the API 102 rule unenforced on exactly the builds that have
     * obfuscation turned on.
     */
    private static final String[] legacyApiPrefixes = resolveLegacyApiPrefixes();

    private static String[] resolveLegacyApiPrefixes() {
        try {
            var prefixes = HookBridge.legacyApiPrefixes();
            if (prefixes != null && prefixes.length != 0) return prefixes;
        } catch (Throwable t) {
            Log.w(TAG, "Cannot resolve the legacy API prefixes", t);
        }
        return literalLegacyApiPrefixes;
    }

    private static boolean isLegacyApi(String name) {
        for (var prefix : legacyApiPrefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static List<File> splitPaths(String searchPath) {
        var result = new ArrayList<File>();
        if (searchPath == null) return result;
        for (var path : searchPath.split(File.pathSeparator)) {
            result.add(new File(path));
        }
        return result;
    }

    private LspModuleClassLoader(ByteBuffer[] dexBuffers,
                                 ClassLoader parent,
                                 String apk,
                                 boolean blockLegacyApi) {
        super(dexBuffers, parent);
        this.apk = apk;
        this.blockLegacyApi = blockLegacyApi;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private LspModuleClassLoader(ByteBuffer[] dexBuffers,
                                 String librarySearchPath,
                                 ClassLoader parent,
                                 String apk,
                                 boolean blockLegacyApi) {
        super(dexBuffers, librarySearchPath, parent);
        initNativeLibraryDirs(librarySearchPath);
        this.apk = apk;
        this.blockLegacyApi = blockLegacyApi;
    }

    private void initNativeLibraryDirs(String librarySearchPath) {
        nativeLibraryDirs.addAll(splitPaths(librarySearchPath));
        nativeLibraryDirs.addAll(systemNativeLibraryDirs);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // The legacy API is part of the boot classpath, so every lookup below would find it. This
        // is the only gate: Class.forName and loadClass both funnel through this override, which
        // is what makes the API 102 rule hold for reflective access as well.
        if (blockLegacyApi && isLegacyApi(name)) {
            throw new ClassNotFoundException(name + " is not available to modules targeting Xposed API "
                    + XposedInterface.API_102 + " or higher");
        }
        var cl = findLoadedClass(name);
        if (cl != null) {
            return cl;
        }
        try {
            return Object.class.getClassLoader().loadClass(name);
        } catch (ClassNotFoundException ignored) {
        }
        ClassNotFoundException fromSuper;
        try {
            return findClass(name);
        } catch (ClassNotFoundException ex) {
            fromSuper = ex;
        }
        try {
            return getParent().loadClass(name);
        } catch (ClassNotFoundException cnfe) {
            throw fromSuper;
        }
    }

    @Override
    public String findLibrary(String libraryName) {
        var fileName = System.mapLibraryName(libraryName);
        for (var file : nativeLibraryDirs) {
            var path = file.getPath();
            if (path.contains(zipSeparator)) {
                var split = path.split(zipSeparator, 2);
                try (var jarFile = new JarFile(split[0])) {
                    var entryName = split[1] + '/' + fileName;
                    var entry = jarFile.getEntry(entryName);
                    if (entry != null && entry.getMethod() == ZipEntry.STORED) {
                        return split[0] + zipSeparator + entryName;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Can not open " + split[0], e);
                }
            } else if (file.isDirectory()) {
                var entryPath = new File(file, fileName).getPath();
                try {
                    var fd = Os.open(entryPath, OsConstants.O_RDONLY, 0);
                    Os.close(fd);
                    return entryPath;
                } catch (ErrnoException ignored) {
                }
            }
        }
        return null;
    }

    @Override
    public String getLdLibraryPath() {
        var result = new StringBuilder();
        for (var directory : nativeLibraryDirs) {
            if (result.length() > 0) {
                result.append(':');
            }
            result.append(directory);
        }
        return result.toString();
    }

    @Override
    protected URL findResource(String name) {
        try {
            var urlHandler = new ClassPathURLStreamHandler(apk);
            var url = urlHandler.getEntryUrlOrNull(name);
            if (url == null) {
                // noinspection FinalizeCalledExplicitly
                urlHandler.finalize();
            }
            return url;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        var result = new ArrayList<URL>();
        var url = findResource(name);
        if (url != null) result.add(url);
        return Collections.enumeration(result);
    }

    @Override
    public URL getResource(String name) {
        var resource = Object.class.getClassLoader().getResource(name);
        if (resource != null) return resource;
        resource = findResource(name);
        if (resource != null) return resource;
        final var cl = getParent();
        return (cl == null) ? null : cl.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        @SuppressWarnings("unchecked") final var resources = (Enumeration<URL>[]) new Enumeration<?>[]{
                Object.class.getClassLoader().getResources(name),
                findResources(name),
                getParent() == null ? null : getParent().getResources(name)};
        return new CompoundEnumeration<>(resources);
    }

    @NonNull
    @Override
    public String toString() {
        if (apk == null) return "LspModuleClassLoader[instantiating]";
        return "LspModuleClassLoader[module=" + apk + ", " + super.toString() + "]";
    }

    public static ClassLoader loadApk(String apk,
                                      List<SharedMemory> dexes,
                                      String librarySearchPath,
                                      ClassLoader parent) {
        return loadApk(apk, dexes, librarySearchPath, parent, false);
    }

    /**
     * Loads a module APK.
     *
     * @param blockLegacyApi whether the legacy {@code de.robv} API should be invisible to this
     *                       module, which is what modules targeting API 102 or higher get
     */
    public static ClassLoader loadApk(String apk,
                                      List<SharedMemory> dexes,
                                      String librarySearchPath,
                                      ClassLoader parent,
                                      boolean blockLegacyApi) {
        var dexBuffers = dexes.stream().parallel().map(dex -> {
            try {
                return dex.mapReadOnly();
            } catch (ErrnoException e) {
                Log.w(TAG, "Can not map " + dex, e);
                return null;
            }
        }).filter(Objects::nonNull).toArray(ByteBuffer[]::new);
        LspModuleClassLoader cl;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cl = new LspModuleClassLoader(dexBuffers, librarySearchPath, parent, apk, blockLegacyApi);
        } else {
            cl = new LspModuleClassLoader(dexBuffers, parent, apk, blockLegacyApi);
            cl.initNativeLibraryDirs(librarySearchPath);
        }
        Arrays.stream(dexBuffers).parallel().forEach(SharedMemory::unmap);
        dexes.stream().parallel().forEach(SharedMemory::close);
        return cl;
    }
}
