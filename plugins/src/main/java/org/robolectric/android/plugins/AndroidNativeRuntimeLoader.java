/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.robolectric.android.plugins;

import static android.os.Build.VERSION_CODES.O;

import static com.google.common.base.StandardSystemProperty.OS_ARCH;
import static com.google.common.base.StandardSystemProperty.OS_NAME;

import static org.robolectric.util.reflector.Reflector.reflector;

import android.graphics.Typeface;
import android.os.Build;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import com.google.common.io.Resources;

import org.robolectric.internal.bytecode.ShadowConstants;
import org.robolectric.nativeruntime.DefaultNativeRuntimeLoader;
import org.robolectric.pluginapi.NativeRuntimeLoader;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.PerfStatsCollector;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.TempDirectory;
import org.robolectric.util.inject.Supersedes;
import org.robolectric.util.reflector.Accessor;
import org.robolectric.util.reflector.ForType;
import org.robolectric.versioning.AndroidVersions;
import org.robolectric.versioning.AndroidVersions.U;
import org.robolectric.versioning.AndroidVersions.V;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;

import javax.annotation.Priority;

/** Loads the Robolectric native runtime. */
@AutoService(NativeRuntimeLoader.class)
@Supersedes(DefaultNativeRuntimeLoader.class)
@Priority(Integer.MIN_VALUE)
public class AndroidNativeRuntimeLoader extends DefaultNativeRuntimeLoader {
  private static final String METHOD_BINDING_FORMAT = "$$robo$$${method}$nativeBinding";

  // Core classes for which native methods are to be registered.
  private static final ImmutableList<String> CORE_CLASS_NATIVES =
      ImmutableList.copyOf(
          new String[] {
            "android.animation.PropertyValuesHolder",
            "android.database.CursorWindow",
            "android.database.sqlite.SQLiteConnection",
            "android.media.ImageReader",
            "android.view.Surface",
            "com.android.internal.util.VirtualRefBasePtr",
            "libcore.util.NativeAllocationRegistry",
          });

  // Graphics classes for which native methods are to be registered.
  private static final ImmutableList<String> GRAPHICS_CLASS_NATIVES =
      ImmutableList.copyOf(
          new String[] {
            "android.graphics.Bitmap",
            "android.graphics.BitmapFactory",
            "android.graphics.ByteBufferStreamAdaptor",
            "android.graphics.Camera",
            "android.graphics.Canvas",
            "android.graphics.CanvasProperty",
            "android.graphics.Color",
            "android.graphics.ColorFilter",
            "android.graphics.ColorSpace",
            "android.graphics.CreateJavaOutputStreamAdaptor",
            "android.graphics.DrawFilter",
            "android.graphics.FontFamily",
            "android.graphics.Gainmap",
            "android.graphics.Graphics",
            "android.graphics.HardwareRenderer",
            "android.graphics.HardwareRendererObserver",
            "android.graphics.ImageDecoder",
            "android.graphics.Interpolator",
            "android.graphics.MaskFilter",
            "android.graphics.Matrix",
            "android.graphics.NinePatch",
            "android.graphics.Paint",
            "android.graphics.Path",
            "android.graphics.PathEffect",
            "android.graphics.PathMeasure",
            "android.graphics.Picture",
            "android.graphics.RecordingCanvas",
            "android.graphics.Region",
            "android.graphics.RenderEffect",
            "android.graphics.RenderNode",
            "android.graphics.Shader",
            "android.graphics.Typeface",
            "android.graphics.YuvImage",
            "android.graphics.animation.NativeInterpolatorFactory",
            "android.graphics.animation.RenderNodeAnimator",
            "android.graphics.drawable.AnimatedVectorDrawable",
            "android.graphics.drawable.AnimatedImageDrawable",
            "android.graphics.drawable.VectorDrawable",
            "android.graphics.fonts.Font",
            "android.graphics.fonts.FontFamily",
            "android.graphics.text.LineBreaker",
            "android.graphics.text.MeasuredText",
            "android.graphics.text.TextRunShaper",
            "android.util.PathParser",
          });

  /**
   * {@link #DEFERRED_STATIC_INITIALIZERS} that invoke their own native methods in static
   * initializers. Unlike libcore, registering JNI on the JVM causes static initialization to be
   * performed on the class. Because of this, static initializers cannot invoke the native methods
   * of the class under registration. Executing these static initializers must be deferred until
   * after JNI has been registered.
   */
  private static final ImmutableList<String> DEFERRED_STATIC_INITIALIZERS =
      ImmutableList.copyOf(
          new String[] {
            "android.graphics.FontFamily",
            "android.graphics.Path",
            "android.graphics.Typeface",
            "android.graphics.text.MeasuredText$Builder",
            "android.media.ImageReader",
          });

  @Override
  public synchronized void ensureLoaded() {
    DefaultNativeRuntimeLoaderReflector accessor = reflector(DefaultNativeRuntimeLoaderReflector.class, this);
    if (loaded.get()) {
      return;
    }

    if (!accessor.isSupported()) {
      String errorMessage =
          String.format(
              "The Robolectric native runtime is not supported on %s (%s)",
              OS_NAME.value(), OS_ARCH.value());
      throw new AssertionError(errorMessage);
    }
    loaded.set(true);

    try {
      PerfStatsCollector.getInstance()
          .measure(
              "loadNativeRuntime",
              () -> {
                TempDirectory extractDirectory = new TempDirectory("nativeruntime");
                accessor.setExtractDirectory(extractDirectory);
                System.setProperty("icu.locale.default", Locale.getDefault().toLanguageTag());
                if (Build.VERSION.SDK_INT >= O) {
                  accessor.maybeCopyFonts(extractDirectory);
                }
                maybeCopyIcuData(extractDirectory);
                maybeCopyArscFile(extractDirectory);
                if (isAndroidVOrAbove()) {
                  // Load per-sdk Robolectric Native Runtime (RNR)
                  System.setProperty("core_native_classes", String.join(",", CORE_CLASS_NATIVES));
                  System.setProperty(
                      "graphics_native_classes", String.join(",", GRAPHICS_CLASS_NATIVES));
                  System.setProperty("method_binding_format", METHOD_BINDING_FORMAT);
                  if (Boolean.parseBoolean(System.getProperty(
                          "android.robolectric.loadLibraryFromPath", "false"))) {
                    loadLibraryFromPath();
                  } else {
                    loadLibrary(extractDirectory);
                  }
                  invokeDeferredStaticInitializers();
                  Typeface.loadPreinstalledSystemFontMap();
                } else {
                  loadLibrary(extractDirectory);
                }

              });
    } catch (IOException e) {
      throw new AssertionError("Unable to load Robolectric native runtime library", e);
    }
  }

  private void loadLibraryFromPath() {
    // find the libandroid_runtime.so file in java.library.path, and create a copy of it so
    // it can be loaded across different sandboxes
    var path = System.getProperty("java.library.path");
    var filename = "libandroid_runtime.so";


    try {
      if (path == null) {
          throw new UnsatisfiedLinkError("Cannot load library " + filename + "."
                + " Property java.library.path not set!");
      }
      for (var dir : path.split(":")) {
          var libraryPath = Paths.get(dir, filename);
          if (java.nio.file.Files.exists(libraryPath)) {
              // create a copy of the file
              File tmpLibraryFile = java.nio.file.Files.createTempFile("", "android_runtime").toFile();
              tmpLibraryFile.deleteOnExit();
              Files.copy(libraryPath.toFile().getAbsoluteFile(), tmpLibraryFile);
              System.load(tmpLibraryFile.getAbsolutePath());
              return;
         }
      }
      throw new UnsatisfiedLinkError("Library " + filename + " not found in "
              + "java.library.path: " + path);
    } catch (IOException e) {
      throw new AssertionError("Failed to copy " + filename, e);
    }
  }

  /** Attempts to load the ICU dat file. This is only relevant for native graphics. */
  private void maybeCopyIcuData(TempDirectory tempDirectory) throws IOException {
    String icuDatFile = isAndroidVOrAbove() ? "icudt.dat" : "icudt68l.dat";

    URL icuDatUrl;
    try {
      icuDatUrl = Resources.getResource("icu/" + icuDatFile);
    } catch (IllegalArgumentException e) {
      return;
    }
    Path icuPath = tempDirectory.create("icu");
    Path icuDatPath = icuPath.resolve(icuDatFile);
    Resources.asByteSource(icuDatUrl).copyTo(Files.asByteSink(icuDatPath.toFile()));
    System.setProperty("icu.data.path", icuDatPath.toAbsolutePath().toString());
  }

  /** Attempts to load the ARSC file. This is only relevant for native graphics. */
  private void maybeCopyArscFile(TempDirectory tempDirectory) throws IOException {
    URL arscUrl;
    final String arscFileName = "font_resources.arsc";
    try {
      arscUrl = Resources.getResource(arscFileName);
    } catch (IllegalArgumentException e) {
      return;
    }
    Path arscPath = tempDirectory.create("arsc");
    Path arscFilePath = arscPath.resolve(arscFileName);
    Resources.asByteSource(arscUrl).copyTo(Files.asByteSink(arscFilePath.toFile()));
    System.setProperty("arsc.file.path", arscFilePath.toAbsolutePath().toString());
  }

  protected void invokeDeferredStaticInitializers() {
    for (String className : DEFERRED_STATIC_INITIALIZERS) {
      ReflectionHelpers.callStaticMethod(
              Shadow.class.getClassLoader(), className, ShadowConstants.STATIC_INITIALIZER_METHOD_NAME);
    }
  }

  private void loadLibrary(TempDirectory tempDirectory) throws IOException {
    String libraryName = System.mapLibraryName("robolectric-nativeruntime");
    Path libraryPath = tempDirectory.getBasePath().resolve(libraryName);
    URL libraryResource = Resources.getResource(nativeLibraryPath());
    Resources.asByteSource(libraryResource).copyTo(Files.asByteSink(libraryPath.toFile()));
    System.load(libraryPath.toAbsolutePath().toString());
  }

  /** For V and above, insert "V" folder for V and above lib path. */
  private String nativeLibraryPath() {
    String defaultPath = defaultNativeLibraryPath();
    if (isAndroidVOrAbove()) {
      int index = defaultPath.lastIndexOf(System.mapLibraryName("robolectric-nativeruntime"));
      if (index < 0) {
        return defaultPath;
      }
      String result = defaultPath.substring(0,index) + "V/" + defaultPath.substring(index);
      return result;
    }
    return defaultPath;
  }

  private String defaultNativeLibraryPath() {
    DefaultNativeRuntimeLoaderReflector accessor =
            reflector(DefaultNativeRuntimeLoaderReflector.class, this);
    String os = accessor.osName();
    String arch = accessor.arch();
    return String.format(
            "native/%s/%s/%s",
            os,
            arch,
            System.mapLibraryName("robolectric-nativeruntime"));
  }

  private boolean isAndroidVOrAbove() {
    return (Objects.equals(AndroidVersions.CURRENT.getShortCode(), V.SHORT_CODE) &&
            AndroidVersions.CURRENT.getSdkInt() >= U.SDK_INT) ||
        AndroidVersions.CURRENT.getSdkInt() >= V.SDK_INT;
  }

  @ForType(DefaultNativeRuntimeLoader.class)
  private interface DefaultNativeRuntimeLoaderReflector {
    @Accessor("extractDirectory")
    void setExtractDirectory(TempDirectory dir);

    boolean isSupported();
    void maybeCopyFonts(TempDirectory tempDirectory);
    String osName();
    String arch();
  }
}
