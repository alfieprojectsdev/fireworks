# The release build is minified (see build.gradle.kts). ShaderSources loads the
# :core shaders by absolute resource path through its class loader, so renaming
# classes doesn't break it.

# No logcat output from the published build: fixes, bearings and device details
# stay off the device log.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
    public static boolean isLoggable(...);
}
