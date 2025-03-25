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

import com.google.auto.service.AutoService;
import com.google.common.io.Files;
import com.google.common.io.Resources;

import org.robolectric.nativeruntime.DefaultNativeRuntimeLoader;
import org.robolectric.pluginapi.NativeRuntimeLoader;
import org.robolectric.util.TempDirectory;
import org.robolectric.util.inject.Supersedes;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Priority;

/** Loads the Robolectric native runtime. */
@AutoService(NativeRuntimeLoader.class)
@Supersedes(DefaultNativeRuntimeLoader.class)
@Priority(Integer.MIN_VALUE)
public class AndroidNativeRuntimeLoader extends DefaultNativeRuntimeLoader {

  protected  List<String> getDeferredStaticInitializers() {
    List<String> initializers = new ArrayList<>(super.getDeferredStaticInitializers());
    //initializers.remove("android.graphics.PathIterator");
    return initializers;
  }

  protected  List<String> getGraphicsNatives() {
    List<String> initializers = new ArrayList<>(super.getGraphicsNatives());
    //initializers.remove("android.graphics.PathIterator");
    return initializers;
  }

  protected  List<String> getCoreClassNatives() {
    List<String> initializers = new ArrayList<>(super.getCoreClassNatives());
    //initializers.add("android.database.sqlite.SQLiteRawStatement");
    return initializers;
  }

  protected void maybeCopyExtraResources(TempDirectory dir) {
    try {
      maybeCopyArscFile(dir);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
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

}
