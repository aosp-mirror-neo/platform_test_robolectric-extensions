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
import java.util.Properties;
import javax.annotation.Priority;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.pluginapi.config.Configurer;
import org.robolectric.plugins.SQLiteModeConfigurer;
import org.robolectric.plugins.PackagePropertiesLoader;
import org.robolectric.plugins.config.SingleValueConfigurer;
import org.robolectric.util.inject.Supersedes;

/**
 * A {@link org.robolectric.pluginapi.config.Configurer} plugin for sqlite mode for Android. This
 * allows Android to default to {@link SQLiteMode.Mode.LEGACY} until libandroid_runtime is available
 * for Robolectric in AOSP.
 */
@AutoService(Configurer.class)
@Supersedes(SQLiteModeConfigurer.class)
@Priority(Integer.MAX_VALUE)
public class AndroidSQLiteModeConfigurer
    extends SingleValueConfigurer<SQLiteMode, SQLiteMode.Mode> {

  public AndroidSQLiteModeConfigurer(
      Properties systemProperties, PackagePropertiesLoader propertyFileLoader) {

    super(
        SQLiteMode.class,
        SQLiteMode.Mode.class,
        SQLiteMode.Mode.LEGACY,
        propertyFileLoader,
        systemProperties);
  }
  
  @Override
  protected String propertyName() {
    return "sqliteMode";
  }
}
