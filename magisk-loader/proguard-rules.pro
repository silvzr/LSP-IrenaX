-keepclasseswithmembers class org.lsposed.lspd.core.Main {
    public static void forkCommon(boolean, java.lang.String, java.lang.String, android.os.IBinder);
}
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-keepclasseswithmembers class org.lsposed.lspd.service.BridgeService {
    public static boolean *(android.os.IBinder, int, long, long, int);
}
-keepclassmembers,allowoptimization class ** implements io.github.libxposed.api.XposedInterface$Hooker {
    public *** before(***);
    public *** after(***);
    public static *** before();
    public static *** before(io.github.libxposed.api.XposedInterface$BeforeHookCallback);
    public static void after();
    public static void after(io.github.libxposed.api.XposedInterface$AfterHookCallback);
    public static void after(io.github.libxposed.api.XposedInterface$AfterHookCallback, ***);
}

-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
-repackageclasses
-allowaccessmodification
-dontwarn org.slf4j.impl.StaticLoggerBinder
