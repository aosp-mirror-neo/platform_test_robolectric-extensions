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

package org.robolectric.plugins;

import static com.google.common.base.StandardSystemProperty.OS_ARCH;

import com.google.auto.service.AutoService;
import java.util.Locale;
import java.util.Properties;
import javax.annotation.Priority;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.ConscryptMode.Mode;
import org.robolectric.pluginapi.config.Configurer;
import org.robolectric.plugins.config.SingleValueConfigurer;
import org.robolectric.util.OsUtil;
import org.robolectric.util.inject.Supercedes;

/** 
 * Provides configuration to Robolectric for its @{@link ConscryptMode} annotation. 
 * Due to instability in the librobolectric-runtime.so default to mode OFF in android
 * until we can build the .so in branch.
 */
@AutoService(Configurer.class)
@Supercedes(ConscryptModeConfigurer.class)
@Priority(Integer.MAX_VALUE)
public class AndroidConscryptModeConfigurer extends SingleValueConfigurer<ConscryptMode, ConscryptMode.Mode> {

  public AndroidConscryptModeConfigurer(
      Properties systemProperties, PackagePropertiesLoader propertyFileLoader) {
    super(
        ConscryptMode.class,
        ConscryptMode.Mode.class,
        Mode.OFF,
        propertyFileLoader,
        systemProperties);
  }
}
