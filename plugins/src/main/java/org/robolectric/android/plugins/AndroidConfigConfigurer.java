package org.robolectric.android.plugins;

import com.google.auto.service.AutoService;

import org.robolectric.pluginapi.config.Configurer;
import org.robolectric.pluginapi.config.GlobalConfigProvider;
import org.robolectric.plugins.ConfigConfigurer;
import org.robolectric.plugins.PackagePropertiesLoader;
import org.robolectric.util.Logger;
import org.robolectric.util.inject.Supersedes;

@AutoService(Configurer.class)
@Supersedes(ConfigConfigurer.class)
public class AndroidConfigConfigurer extends ConfigConfigurer {

  static {
    // Enables utils/src/main/java/org/robolectric/util/Logger.java
    System.setProperty("robolectric.logging.enabled", "true");
    // Set to enable logging to stdout in
    // shadows/framework/src/main/java/org/robolectric/shadows/ShadowLog.java
    System.setProperty("robolectric.logging", "stdout");
    Logger.info("Logging turned on by AndroidConfigConfigurer.class");
  }

  protected AndroidConfigConfigurer(
          PackagePropertiesLoader packagePropertiesLoader) {
    super(packagePropertiesLoader);
  }

  public AndroidConfigConfigurer(PackagePropertiesLoader packagePropertiesLoader,
          GlobalConfigProvider defaultConfigProvider) {
    super(packagePropertiesLoader, defaultConfigProvider);
  }
}
