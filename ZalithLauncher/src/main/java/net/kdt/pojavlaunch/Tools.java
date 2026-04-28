package net.kdt.pojavlaunch;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.P;
import static com.movtery.zalithlauncher.setting.AllStaticSettings.notchSize;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.movtery.zalithlauncher.InfoDistributor;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.context.ContextExecutor;
import com.movtery.zalithlauncher.utils.LauncherProfiles;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.task.Task;
import com.movtery.zalithlauncher.ui.activity.BaseActivity;
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog;
import com.movtery.zalithlauncher.utils.path.PathManager;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.runtime.SelectRuntimeUtils;
import com.movtery.zalithlauncher.utils.stringutils.StringUtils;

import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;
import net.kdt.pojavlaunch.memory.MemoryHoleFinder;
import net.kdt.pojavlaunch.memory.SelfMapsParser;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SuppressWarnings("IOStreamConstructor")
public final class Tools {

    // -------------------------------------------------------
    // Constants
    // -------------------------------------------------------
    public static final String TAG = InfoDistributor.LAUNCHER_NAME;
    public static final String NOTIFICATION_CHANNEL_DEFAULT = "channel_id";
    public static final float BYTE_TO_MB = 1024 * 1024;
    public static final Gson GLOBAL_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    public static final String LAUNCHERPROFILES_RTPREFIX = "pojav://";

    // Client jar classpath position:
    // false = libraries pehle, client baad mein (standard Minecraft order)
    private static final boolean IS_CLIENT_FIRST = false;

    public static int DEVICE_ARCHITECTURE;
    public static String DIRNAME_HOME_JRE = "lib";
    public static DisplayMetrics currentDisplayMetrics;

    // -------------------------------------------------------
    // Storage
    // -------------------------------------------------------

    /**
     * Check karta hai ki Pojav ka storage root accessible aur read-write hai ya nahi.
     *
     * @return true agar storage mounted hai, false otherwise
     */
    public static boolean checkStorageRoot() {
        File externalFilesDir = new File(PathManager.DIR_GAME_HOME);
        return Environment
                .getExternalStorageState(externalFilesDir)
                .equals(Environment.MEDIA_MOUNTED);
    }

    // -------------------------------------------------------
    // Notification
    // -------------------------------------------------------

    public static void buildNotificationChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_DEFAULT,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        NotificationManagerCompat.from(context).createNotificationChannel(channel);
    }

    // -------------------------------------------------------
    // Forge splash disable
    // -------------------------------------------------------

    public static void disableSplash(File dir) {
        File configDir = new File(dir, "config");
        if (!FileUtils.ensureDirectorySilently(configDir)) {
            Logging.w(TAG, "Failed to create the configuration directory");
            return;
        }

        File forgeSplashFile = new File(configDir, "splash.properties");
        try {
            String content = forgeSplashFile.exists()
                    ? Tools.read(forgeSplashFile.getAbsolutePath())
                    : "enabled=true";

            if (content.contains("enabled=true")) {
                Tools.write(
                        forgeSplashFile.getAbsolutePath(),
                        content.replace("enabled=true", "enabled=false")
                );
            }
        } catch (IOException e) {
            Logging.w(TAG, "Could not disable Forge 1.12.2 splash screen!", e);
        }
    }

    // -------------------------------------------------------
    // String utilities
    // -------------------------------------------------------

    public static String fromStringArray(String[] strArr) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < strArr.length; i++) {
            if (i > 0) builder.append(' ');
            builder.append(strArr[i]);
        }
        return builder.toString();
    }

    public static String extractUntilCharacter(
            String input,
            String whatFor,
            char terminator
    ) {
        int start = input.indexOf(whatFor);
        if (start == -1) return null;
        start += whatFor.length();
        int end = input.indexOf(terminator, start);
        if (end == -1) return null;
        return input.substring(start, end);
    }

    public static boolean isValidString(String string) {
        return string != null && !string.isEmpty();
    }

    // -------------------------------------------------------
    // Library / classpath helpers
    // -------------------------------------------------------

    /**
     * Library ke Maven artifact path ko resolve karta hai.
     * Format: groupId:artifactId:version[:classifier]
     *
     * @return relative path jaise "com/example/lib/1.0/lib-1.0.jar"
     *         ya null agar name format invalid ho
     */
    public static String artifactToPath(DependentLibrary library) {
        // Agar downloads mein explicit path diya hai toh wahi use karo
        if (library.downloads != null
                && library.downloads.artifact != null
                && library.downloads.artifact.path != null) {
            return library.downloads.artifact.path;
        }

        String[] parts = library.name.split(":");
        if (parts.length < 3) {
            Logging.e("Tools_artifactToPath",
                    "Invalid library name format: " + library.name);
            return null;
        }

        String groupPath   = parts[0].replace('.', '/');
        String artifactId  = parts[1];
        String version     = parts[2];
        String classifier  = (parts.length > 3) ? "-" + parts[3] : "";

        return String.format(
                "%s/%s/%s/%s-%s%s.jar",
                groupPath, artifactId, version,
                artifactId, version, classifier
        );
    }

    public static String getClientClasspath(Version version) {
        return new File(
                version.getVersionPath(),
                version.getVersionName() + ".jar"
        ).getAbsolutePath();
    }

    /**
     * LWJGL3 folder ke saare JAR files ka classpath banata hai.
     */
    public static String getLWJGL3ClassPath() {
        StringBuilder libStr = new StringBuilder();
        File lwjgl3Folder = new File(PathManager.DIR_GAME_HOME, "lwjgl3");
        File[] lwjgl3Files = lwjgl3Folder.listFiles();

        if (lwjgl3Files == null || lwjgl3Files.length == 0) {
            Logging.w(TAG, "LWJGL3 folder empty or not found: "
                    + lwjgl3Folder.getAbsolutePath());
            return "";
        }

        for (File file : lwjgl3Files) {
            if (file.getName().endsWith(".jar")) {
                libStr.append(file.getAbsolutePath()).append(':');
            }
        }

        // Trailing ':' remove karo
        if (libStr.length() > 0) {
            libStr.setLength(libStr.length() - 1);
        }
        return libStr.toString();
    }

    /**
     * Launch ke liye complete classpath string generate karta hai.
     * Order: libraries + client.jar (IS_CLIENT_FIRST = false)
     */
    public static String generateLaunchClassPath(
            JMinecraftVersionList.Version info,
            Version minecraftVersion
    ) {
        StringBuilder finalClasspath = new StringBuilder();
        String[] classpath = generateLibClasspath(info);
        String clientClasspath = getClientClasspath(minecraftVersion);

        if (IS_CLIENT_FIRST) {
            finalClasspath.append(clientClasspath).append(':');
        }

        for (String jarFile : classpath) {
            if (!FileUtils.exists(jarFile)) {
                Logging.d(TAG, "Ignored non-existing file: " + jarFile);
                continue;
            }
            finalClasspath.append(jarFile).append(':');
        }

        if (!IS_CLIENT_FIRST) {
            finalClasspath.append(clientClasspath);
        } else {
            // Trailing ':' remove karo agar client pehle diya tha
            if (finalClasspath.length() > 0
                    && finalClasspath.charAt(finalClasspath.length() - 1) == ':') {
                finalClasspath.setLength(finalClasspath.length() - 1);
            }
        }

        return finalClasspath.toString();
    }

    // -------------------------------------------------------
    // Display helpers
    // -------------------------------------------------------

    public static DisplayMetrics getDisplayMetrics(BaseActivity activity) {
        DisplayMetrics displayMetrics = new DisplayMetrics();

        if (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode()) {
            displayMetrics = activity.getResources().getDisplayMetrics();
        } else {
            if (SDK_INT >= Build.VERSION_CODES.R) {
                activity.getDisplay().getRealMetrics(displayMetrics);
            } else {
                activity.getWindowManager()
                        .getDefaultDisplay()
                        .getRealMetrics(displayMetrics);
            }

            // Notch size subtract karo agar required ho
            if (!activity.shouldIgnoreNotch()) {
                if (activity.getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_PORTRAIT) {
                    displayMetrics.heightPixels -= notchSize;
                } else {
                    displayMetrics.widthPixels -= notchSize;
                }
            }
        }

        currentDisplayMetrics = displayMetrics;
        return displayMetrics;
    }

    public static void updateWindowSize(BaseActivity activity) {
        currentDisplayMetrics = getDisplayMetrics(activity);
        CallbackBridge.physicalWidth  = currentDisplayMetrics.widthPixels;
        CallbackBridge.physicalHeight = currentDisplayMetrics.heightPixels;
    }

    public static float dpToPx(float dp) {
        return dp * currentDisplayMetrics.density;
    }

    public static float pxToDp(float px) {
        return px / currentDisplayMetrics.density;
    }

    /**
     * Display resolution ko scaling ke saath calculate karta hai.
     * Result hamesha even number hoga (rendering compatibility ke liye).
     *
     * @param displaySideRes original pixel size
     * @param scaling        scale factor (0.0 - 1.0+)
     * @return scaled even resolution, minimum 2
     */
    public static int getDisplayFriendlyRes(int displaySideRes, float scaling) {
        int display = (int) (displaySideRes * scaling);
        if (display % 2 != 0) display--;
        return Math.max(display, 2);
    }

    public static void setFullscreen(Activity activity) {
        final View decorView = activity.getWindow().getDecorView();

        View.OnSystemUiVisibilityChangeListener listener = visibility -> {
            if (activity.isInMultiWindowMode()) {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                return;
            }
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                );
            }
        };

        decorView.setOnSystemUiVisibilityChangeListener(listener);
        listener.onSystemUiVisibilityChange(decorView.getSystemUiVisibility());
    }

    public static void ignoreNotch(boolean shouldIgnore, BaseActivity activity) {
        if (SDK_INT >= P) {
            activity.getWindow().getAttributes().layoutInDisplayCutoutMode =
                    shouldIgnore
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;

            activity.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            );
            updateWindowSize(activity);
        }
    }

    // -------------------------------------------------------
    // Asset copy
    // -------------------------------------------------------

    public static void copyAssetFile(
            Context ctx,
            String fileName,
            String output,
            boolean overwrite
    ) throws IOException {
        copyAssetFile(ctx, fileName, output, new File(fileName).getName(), overwrite);
    }

    public static void copyAssetFile(
            Context ctx,
            String fileName,
            String output,
            String outputName,
            boolean overwrite
    ) throws IOException {
        File parentFolder = new File(output);
        FileUtils.ensureDirectory(parentFolder);
        File dest = new File(output, outputName);

        if (!dest.exists() || overwrite) {
            try (InputStream in  = ctx.getAssets().open(fileName);
                 OutputStream out = new FileOutputStream(dest)) {
                IOUtils.copy(in, out);
            }
        }
    }

    // -------------------------------------------------------
    // Error display
    // -------------------------------------------------------

    public static String printToString(Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public static void showError(Context ctx, Throwable e) {
        showError(ctx, e, false);
    }

    public static void showError(Context ctx, Throwable e, boolean exitIfOk) {
        showError(ctx, R.string.generic_error, null, e, exitIfOk, false);
    }

    public static void showError(Context ctx, int rolledMessage, Throwable e) {
        showError(ctx, R.string.generic_error,
                ctx.getString(rolledMessage), e, false, false);
    }

    public static void showError(Context ctx, String rolledMessage, Throwable e) {
        showError(ctx, R.string.generic_error, rolledMessage, e, false, false);
    }

    public static void showError(
            Context ctx,
            String rolledMessage,
            Throwable e,
            boolean exitIfOk
    ) {
        showError(ctx, R.string.generic_error, rolledMessage, e, exitIfOk, false);
    }

    public static void showError(
            Context ctx,
            int titleId,
            Throwable e,
            boolean exitIfOk
    ) {
        showError(ctx, titleId, null, e, exitIfOk, false);
    }

    private static void showError(
            final Context ctx,
            final int titleId,
            final String rolledMessage,
            final Throwable e,
            final boolean exitIfOk,
            final boolean showMore
    ) {
        // Agar error khud ek ContextExecutorTask hai toh usse execute karo
        if (e instanceof ContextExecutorTask) {
            ContextExecutor.executeTask((ContextExecutorTask) e);
            return;
        }

        Logging.e("ShowError", printToString(e));

        Runnable runnable = () -> {
            final String errMsg = showMore
                    ? printToString(e)
                    : (rolledMessage != null ? rolledMessage : e.getMessage());

            AlertDialog.Builder builder = new AlertDialog.Builder(
                    ctx, R.style.CustomAlertDialogTheme)
                    .setTitle(titleId)
                    .setMessage(errMsg)
                    .setCancelable(!exitIfOk)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        if (!exitIfOk) return;
                        if (ctx instanceof MainActivity) {
                            ZHTools.killProcess();
                        } else if (ctx instanceof Activity) {
                            ((Activity) ctx).finish();
                        }
                    })
                    .setNegativeButton(
                            showMore ? R.string.error_show_less : R.string.error_show_more,
                            (d, w) -> showError(ctx, titleId, rolledMessage,
                                    e, exitIfOk, !showMore)
                    )
                    .setNeutralButton(android.R.string.copy, (d, w) -> {
                        StringUtils.copyText("error", printToString(e), ctx);
                        if (!exitIfOk) return;
                        if (ctx instanceof MainActivity) {
                            ZHTools.killProcess();
                        } else if (ctx instanceof Activity) {
                            ((Activity) ctx).finish();
                        }
                    });

            try {
                builder.show();
            } catch (Throwable th) {
                Logging.e(TAG, "Failed to show error dialog", th);
            }
        };

        if (ctx instanceof Activity) {
            ((Activity) ctx).runOnUiThread(runnable);
        } else {
            runnable.run();
        }
    }

    public static void showErrorRemote(Throwable e) {
        showErrorRemote(null, e);
    }

    public static void showErrorRemote(Context context, int rolledMessage, Throwable e) {
        showErrorRemote(context.getString(rolledMessage), e);
    }

    public static void showErrorRemote(String rolledMessage, Throwable e) {
        ContextExecutor.executeTask(
                new ShowErrorActivity.RemoteErrorTask(e, rolledMessage)
        );
    }

    // -------------------------------------------------------
    // Library rules & preprocessing
    // -------------------------------------------------------

    /**
     * Rules check karta hai.
     * macOS-only rules ko reject karta hai (Android par applicable nahi).
     *
     * @return true agar library include karni chahiye
     */
    private static boolean checkRules(
            JMinecraftVersionList.Arguments.ArgValue.ArgRules[] rules
    ) {
        if (rules == null) return true;
        for (JMinecraftVersionList.Arguments.ArgValue.ArgRules rule : rules) {
            if ("allow".equals(rule.action)
                    && rule.os != null
                    && "osx".equals(rule.os.name)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Known problematic libraries ko compatible versions se replace karta hai.
     */
    public static void preProcessLibraries(DependentLibrary[] libraries) {
        for (DependentLibrary libItem : libraries) {
            String[] nameParts = libItem.name.split(":");
            if (nameParts.length < 3) continue;

            String[] version = nameParts[2].split("\\.");
            if (version.length < 2) continue;

            try {
                if (libItem.name.startsWith("net.java.dev.jna:jna:")) {
                    handleJnaLibrary(libItem, version);

                } else if (libItem.name.startsWith("com.github.oshi:oshi-core:")) {
                    handleOshiLibrary(libItem, version);

                } else if (libItem.name.startsWith("org.ow2.asm:asm-all:")) {
                    handleAsmLibrary(libItem, version);
                }
            } catch (NumberFormatException e) {
                Logging.w(TAG, "Could not parse version for library: " + libItem.name);
            }
        }
    }

    private static void handleJnaLibrary(DependentLibrary lib, String[] version) {
        int major = Integer.parseInt(version[0]);
        int minor = Integer.parseInt(version[1]);
        if (major >= 5 && minor >= 13) return;

        Logging.d(TAG, "Library " + lib.name + " -> 5.13.0");
        createLibraryInfo(lib);
        lib.name                        = "net.java.dev.jna:jna:5.13.0";
        lib.downloads.artifact.path     = "net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar";
        lib.downloads.artifact.sha1     = "1200e7ebeedbe0d10062093f32925a912020e747";
        lib.downloads.artifact.url      =
                "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar";
    }

    private static void handleOshiLibrary(DependentLibrary lib, String[] version) {
        int major = Integer.parseInt(version[0]);
        int minor = Integer.parseInt(version[1]);
        if (major != 6 || minor != 2) return;

        Logging.d(TAG, "Library " + lib.name + " -> 6.3.0");
        createLibraryInfo(lib);
        lib.name                        = "com.github.oshi:oshi-core:6.3.0";
        lib.downloads.artifact.path     =
                "com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar";
        lib.downloads.artifact.sha1     = "9e98cf55be371cafdb9c70c35d04ec2a8c2b42ac";
        lib.downloads.artifact.url      =
                "https://repo1.maven.org/maven2/com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar";
    }

    private static void handleAsmLibrary(DependentLibrary lib, String[] version) {
        int major = Integer.parseInt(version[0]);
        if (major >= 5) return;

        Logging.d(TAG, "Library " + lib.name + " -> 5.0.4");
        createLibraryInfo(lib);
        lib.name                        = "org.ow2.asm:asm-all:5.0.4";
        lib.url                         = null;
        lib.downloads.artifact.path     = "org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar";
        lib.downloads.artifact.sha1     = "e6244859997b3d4237a552669279780876228909";
        lib.downloads.artifact.url      =
                "https://repo1.maven.org/maven2/org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar";
    }

    private static void createLibraryInfo(DependentLibrary library) {
        if (library.downloads == null || library.downloads.artifact == null) {
            library.downloads = new DependentLibrary.LibraryDownloads(
                    new MinecraftLibraryArtifact()
            );
        }
    }

    /**
     * Info object se library classpath generate karta hai.
     * LWJGL aur platform-specific libraries skip karta hai.
     */
    public static String[] generateLibClasspath(JMinecraftVersionList.Version info) {
        List<String> libDir = new ArrayList<>();

        for (DependentLibrary libItem : info.libraries) {
            if (!checkRules(libItem.rules)) continue;

            String libName = libItem.name;
            if (libName == null) continue;

            // Android par yeh libraries incompatible hain
            if (libName.contains("org.lwjgl")
                    || libName.contains("jinput-platform")
                    || libName.contains("twitch-platform")) {
                Logging.d(TAG, "Ignored incompatible dependency: " + libName);
                continue;
            }

            String libArtifactPath = artifactToPath(libItem);
            if (libArtifactPath == null) continue;

            libDir.add(ProfilePathHome.getLibrariesHome() + "/" + libArtifactPath);
        }

        return libDir.toArray(new String[0]);
    }

    // -------------------------------------------------------
    // Version info
    // -------------------------------------------------------

    public static JMinecraftVersionList.Version getVersionInfo(Version version) {
        return getVersionInfo(version, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static JMinecraftVersionList.Version getVersionInfo(
            Version version,
            boolean skipInheriting
    ) {
        try {
            File versionJson = new File(
                    version.getVersionPath(),
                    version.getVersionName() + ".json"
            );

            JMinecraftVersionList.Version customVer = GLOBAL_GSON.fromJson(
                    read(versionJson),
                    JMinecraftVersionList.Version.class
            );

            // Inheriting nahi hai ya skip karna hai
            if (skipInheriting
                    || customVer.inheritsFrom == null
                    || customVer.inheritsFrom.equals(customVer.id)) {
                preProcessLibraries(customVer.libraries);
                return finalizeVersion(customVer);
            }

            // Parent version load karo
            String parentJsonPath = version.getVersionsFolder()
                    + "/" + customVer.inheritsFrom
                    + "/" + customVer.inheritsFrom + ".json";

            JMinecraftVersionList.Version inheritsVer;
            try {
                inheritsVer = GLOBAL_GSON.fromJson(
                        read(parentJsonPath),
                        JMinecraftVersionList.Version.class
                );
            } catch (IOException e) {
                throw new RuntimeException(
                        "Can't find source version for '"
                        + version.getVersionName()
                        + "' (requires: " + customVer.inheritsFrom + ")"
                );
            }

            // Parent mein child ke fields insert karo
            insertSafety(inheritsVer, customVer,
                    "assetIndex", "assets", "id",
                    "mainClass", "minecraftArguments",
                    "releaseTime", "time", "type"
            );

            // Library merging: child ki libraries parent ki override karengi
            mergeLibraries(inheritsVer, customVer);

            // Arguments merge karo
            mergeArguments(inheritsVer, customVer);

            preProcessLibraries(inheritsVer.libraries);
            return finalizeVersion(inheritsVer);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * javaVersion field finalize karta hai.
     */
    private static JMinecraftVersionList.Version finalizeVersion(
            JMinecraftVersionList.Version ver
    ) {
        if (ver.javaVersion != null && ver.javaVersion.majorVersion == 0) {
            ver.javaVersion.majorVersion = ver.javaVersion.version;
        }
        return ver;
    }

    /**
     * Child version ki libraries ko parent mein merge karta hai.
     * Same group:artifact wali parent library replace ho jaati hai.
     */
    private static void mergeLibraries(
            JMinecraftVersionList.Version parent,
            JMinecraftVersionList.Version child
    ) {
        List<DependentLibrary> merged = new ArrayList<>(
                Arrays.asList(parent.libraries)
        );

        outer:
        for (DependentLibrary childLib : child.libraries) {
            String childBase = getLibraryBase(childLib.name);

            for (DependentLibrary parentLib : merged) {
                String parentBase = getLibraryBase(parentLib.name);

                if (childBase.equals(parentBase)) {
                    String oldVer = getLibraryVersion(parentLib.name);
                    String newVer = getLibraryVersion(childLib.name);
                    Logging.d(TAG, "Library " + childBase
                            + ": replaced " + oldVer + " with " + newVer);
                    merged.remove(parentLib);
                    continue outer;
                }
            }
        }

        merged.addAll(Arrays.asList(child.libraries));
        parent.libraries = merged.toArray(new DependentLibrary[0]);
    }

    /** "group:artifact:version" se "group:artifact" return karta hai */
    private static String getLibraryBase(String fullName) {
        int last = fullName.lastIndexOf(':');
        return last == -1 ? fullName : fullName.substring(0, last);
    }

    /** "group:artifact:version" se "version" return karta hai */
    private static String getLibraryVersion(String fullName) {
        int last = fullName.lastIndexOf(':');
        return last == -1 ? "" : fullName.substring(last + 1);
    }

    /**
     * Child ke game arguments ko parent mein merge karta hai.
     * Duplicate arguments skip hote hain.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void mergeArguments(
            JMinecraftVersionList.Version parent,
            JMinecraftVersionList.Version child
    ) {
        if (parent.arguments == null || child.arguments == null) return;

        List totalArgList = new ArrayList(Arrays.asList(parent.arguments.game));
        int skipCount = 0;

        for (int i = 0; i < child.arguments.game.length; i++) {
            if (skipCount > 0) {
                skipCount--;
                continue;
            }

            Object arg = child.arguments.game[i];

            if (arg instanceof String) {
                String argStr = (String) arg;

                if (argStr.startsWith("--") && totalArgList.contains(argStr)) {
                    // Agla argument value hai - usse bhi skip karo
                    if (i + 1 < child.arguments.game.length) {
                        Object nextArg = child.arguments.game[i + 1];
                        if (nextArg instanceof String
                                && !((String) nextArg).startsWith("--")) {
                            skipCount++;
                        }
                    }
                } else {
                    totalArgList.add(argStr);
                }
            } else if (!totalArgList.contains(arg)) {
                totalArgList.add(arg);
            }
        }

        parent.arguments.game = totalArgList.toArray(new Object[0]);
    }

    private static void insertSafety(
            JMinecraftVersionList.Version target,
            JMinecraftVersionList.Version source,
            String... keys
    ) {
        for (String key : keys) {
            Object value = null;
            try {
                Field srcField = source.getClass().getDeclaredField(key);
                srcField.setAccessible(true);
                value = srcField.get(source);

                boolean hasValue = (value instanceof String)
                        ? !((String) value).isEmpty()
                        : value != null;

                if (hasValue) {
                    Field dstField = target.getClass().getDeclaredField(key);
                    dstField.setAccessible(true);
                    dstField.set(target, value);
                }
            } catch (Throwable th) {
                Logging.w(TAG, "Unable to insert " + key + "=" + value, th);
            }
        }
    }

    // -------------------------------------------------------
    // File I/O
    // -------------------------------------------------------

    public static String read(InputStream is) throws IOException {
        try {
            return IOUtils.toString(is, StandardCharsets.UTF_8);
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    public static String read(String path) throws IOException {
        return read(new FileInputStream(path));
    }

    public static String read(File file) throws IOException {
        return read(new FileInputStream(file));
    }

    public static void write(String path, String content) throws IOException {
        File file = new File(path);
        FileUtils.ensureParentDirectory(file);
        try (FileOutputStream out = new FileOutputStream(file)) {
            IOUtils.write(content, out, StandardCharsets.UTF_8);
        }
    }

    // -------------------------------------------------------
    // SHA1 verification
    // -------------------------------------------------------

    /**
     * File ka SHA1 hash source se compare karta hai.
     *
     * @return true agar match karta hai ya sourceSHA null hai
     */
    public static boolean compareSHA1(File f, String sourceSHA) {
        try {
            String fileSha1;
            try (InputStream is = new FileInputStream(f)) {
                fileSha1 = new String(
                        Hex.encodeHex(
                                org.apache.commons.codec.digest.DigestUtils.sha1(is)
                        )
                );
            }
            // Source SHA nahi diya toh assume valid hai
            return sourceSHA == null || fileSha1.equalsIgnoreCase(sourceSHA);
        } catch (IOException e) {
            Logging.i("SHA1", "Read error - treating as valid", e);
            return true;
        }
    }

    // -------------------------------------------------------
    // Memory
    // -------------------------------------------------------

    /**
     * @return total RAM in MB, ya 4000 agar context null ho
     */
    public static int getTotalDeviceMemory(Context ctx) {
        if (ctx == null) return 4000;
        ActivityManager am = (ActivityManager)
                ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        return (int) (memInfo.totalMem / 1048576L);
    }

    /**
     * @return available RAM in MB, ya 2000 agar context null ho
     */
    public static int getFreeDeviceMemory(Context ctx) {
        if (ctx == null) return 2000;
        ActivityManager am = (ActivityManager)
                ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        return (int) (memInfo.availMem / 1048576L);
    }

    public static int getMaxContinuousAddressSpaceSize() {
        try {
            MemoryHoleFinder finder = new MemoryHoleFinder();
            new SelfMapsParser(finder).run();
            long largest = finder.getLargestHole();
            return largest == -1 ? -1 : (int) (largest / 1048576L);
        } catch (Exception e) {
            Logging.w(TAG, "Failed to find largest uninterrupted address space");
            return -1;
        }
    }

    // -------------------------------------------------------
    // URI / File name
    // -------------------------------------------------------

    /**
     * URI se file name nikalta hai.
     * Cursor properly close hoga try-with-resources se.
     */
    public static String getFileName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver()
                .query(uri, null, null, null, null)) {

            if (c == null || !c.moveToFirst()) {
                return uri.getLastPathSegment();
            }

            int columnIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (columnIndex == -1) return uri.getLastPathSegment();

            String name = c.getString(columnIndex);
            return name != null ? name : uri.getLastPathSegment();
        }
    }

    // -------------------------------------------------------
    // Navigation
    // -------------------------------------------------------

    public static void backToMainMenu(FragmentActivity activity) {
        activity.getSupportFragmentManager()
                .popBackStack(MainMenuFragment.TAG, 0);
    }

    public static void removeCurrentFragment(FragmentActivity activity) {
        activity.getSupportFragmentManager().popBackStack();
    }

    // -------------------------------------------------------
    // Mod installation
    // -------------------------------------------------------

    public static void installMod(Activity activity, boolean customJavaArgs) {
        if (MultiRTUtils.getExactJreName(8) == null) {
            Toast.makeText(activity,
                    R.string.multirt_nojava8rt, Toast.LENGTH_LONG).show();
            return;
        }

        if (!customJavaArgs) {
            if (!(activity instanceof LauncherActivity)) {
                throw new IllegalStateException(
                        "Cannot start Mod Installer without LauncherActivity"
                );
            }
            ((LauncherActivity) activity).modInstallerLauncher.launch(null);
            return;
        }

        new EditTextDialog.Builder(activity)
                .setTitle(R.string.dialog_select_jar)
                .setHintText("-jar/-cp /path/to/file.jar ...")
                .setAsRequired()
                .setConfirmListener((editBox, checked) -> {
                    Intent intent = new Intent(activity, JavaGUILauncherActivity.class);
                    intent.putExtra("javaArgs", editBox.getText().toString());
                    SelectRuntimeUtils.selectRuntime(activity, null, jreName -> {
                        intent.putExtra(
                                JavaGUILauncherActivity.EXTRAS_JRE_NAME, jreName);
                        activity.startActivity(intent);
                    });
                    return true;
                })
                .showDialog();
    }

    public static void launchModInstaller(Activity activity, @NonNull Uri uri) {
        Intent intent = new Intent(activity, JavaGUILauncherActivity.class);
        intent.putExtra("modUri", uri);
        SelectRuntimeUtils.selectRuntime(activity, null, jreName -> {
            LauncherProfiles.generateLauncherProfiles();
            intent.putExtra(JavaGUILauncherActivity.EXTRAS_JRE_NAME, jreName);
            activity.startActivity(intent);
        });
    }

    public static void installRuntimeFromUri(Context context, Uri uri) {
        Task.runTask(() -> {
            String name = getFileName(context, uri);
            MultiRTUtils.installRuntimeNamed(
                    PathManager.DIR_NATIVE_LIB,
                    context.getContentResolver().openInputStream(uri),
                    name
            );
            MultiRTUtils.postPrepare(name);
            return null;
        })
        .onThrowable(e -> Tools.showError(context, e))
        .execute();
    }

    // -------------------------------------------------------
    // Vulkan check
    // -------------------------------------------------------

    public static boolean checkVulkanSupport(PackageManager packageManager) {
        return packageManager.hasSystemFeature(
                PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
                && packageManager.hasSystemFeature(
                PackageManager.FEATURE_VULKAN_HARDWARE_VERSION);
    }

    // -------------------------------------------------------
    // Weak reference helper
    // -------------------------------------------------------

    public static <T> T getWeakReference(WeakReference<T> weakReference) {
        return weakReference == null ? null : weakReference.get();
    }

    // -------------------------------------------------------
    // Interfaces
    // -------------------------------------------------------

    public interface DownloaderFeedback {
        void updateProgress(long curr, long max);
    }
}
