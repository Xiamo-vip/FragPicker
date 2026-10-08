package com.fragpicker.knowledge.embedding;

import java.nio.file.*;

/** Prepare native extraction before Spring/Tomcat can cache the system temporary directory. */
public final class EmbeddingRuntime {
    private EmbeddingRuntime() {}
    private static Path directory() {
        String configured=System.getenv("INDEX_RUNTIME_DIRECTORY");
        return Path.of(configured==null || configured.isBlank()
                ? Path.of(System.getProperty("user.dir"),".fragpicker-runtime").toString():configured).toAbsolutePath().normalize();
    }
    public static void prepare() {
        Path root=directory();
        try {
            Path temp = Files.createDirectories(root.resolve("tmp"));
            Path cache = Files.createDirectories(root.resolve("djl"));
            Path probe = Files.createTempFile(temp, "write-check-", ".tmp"); Files.delete(probe);
            System.setProperty("java.io.tmpdir", temp.toString());
            if (System.getenv("DJL_CACHE_DIR") == null && System.getProperty("DJL_CACHE_DIR") == null)
                System.setProperty("DJL_CACHE_DIR", cache.toString());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot prepare local embedding runtime; check INDEX_RUNTIME_DIRECTORY permissions and free disk space", failure);
        }
    }
    public static synchronized void prepareNativeLibraries() {
        if (System.getProperty("onnxruntime.native.path") != null) return;
        // Boot's launcher can initialize Java's cached temp path before main(). Extract using explicit paths.
        try {
            String os=System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
            os=os.contains("win")?"win":os.contains("mac")?"osx":"linux";
            String arch=System.getProperty("os.arch"); arch=arch.equals("amd64") || arch.equals("x86_64")?"x64":arch;
            String platform=os+"-"+arch;
            String version=Class.forName("ai.onnxruntime.OrtEnvironment",false,EmbeddingRuntime.class.getClassLoader()).getPackage().getImplementationVersion();
            Path nativeDir=Files.createDirectories(directory().resolve("onnx-"+(version==null?"bundled":version)+"-"+platform));
            for(String name:java.util.List.of("onnxruntime","onnxruntime4j_jni")) {
                String library=System.mapLibraryName(name).replace("jnilib","dylib");
                try(var input=EmbeddingRuntime.class.getResourceAsStream("/ai/onnxruntime/native/"+platform+"/"+library)) {
                    if(input==null)throw new IllegalStateException("Unsupported local embedding platform");
                    byte[] bundled=input.readAllBytes(); Path target=nativeDir.resolve(library);
                    if(!Files.exists(target) || !java.util.Arrays.equals(java.security.MessageDigest.getInstance("SHA-256").digest(bundled),java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target)))) {
                        Path staging=Files.createTempFile(nativeDir,"native-",".tmp");
                        try { Files.write(staging,bundled); Files.move(staging,target,StandardCopyOption.REPLACE_EXISTING); }
                        finally { Files.deleteIfExists(staging); }
                    }
                }
            }
            if(System.getenv("DJL_CACHE_DIR")==null && System.getProperty("DJL_CACHE_DIR")==null)
                System.setProperty("DJL_CACHE_DIR",Files.createDirectories(directory().resolve("djl")).toString());
            System.setProperty("onnxruntime.native.path",nativeDir.toString());
        } catch(Exception failure) {
            throw new IllegalStateException("Local embedding native libraries unavailable; check INDEX_RUNTIME_DIRECTORY, disk space and supported CPU/OS",failure);
        }
    }
}
