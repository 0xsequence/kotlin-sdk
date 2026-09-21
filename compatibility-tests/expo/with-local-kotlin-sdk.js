const {
  withAppBuildGradle,
  withMainApplication,
  withProjectBuildGradle,
} = require('expo/config-plugins');

module.exports = function withLocalKotlinSdk(config) {
  const sdkVersion = process.env.OMS_KOTLIN_SDK_VERSION;
  if (!sdkVersion) {
    throw new Error('OMS_KOTLIN_SDK_VERSION is required');
  }

  config = withProjectBuildGradle(config, (projectConfig) => {
    const allProjectsRepositories = 'allprojects {\n  repositories {';

    if (!projectConfig.modResults.contents.includes(allProjectsRepositories)) {
      throw new Error('Unable to find the Android repository configuration');
    }

    projectConfig.modResults.contents = projectConfig.modResults.contents.replace(
      allProjectsRepositories,
      `${allProjectsRepositories}\n    mavenLocal()`
    );
    return projectConfig;
  });

  config = withAppBuildGradle(config, (appConfig) => {
    const dependencies = 'dependencies {';
    if (!appConfig.modResults.contents.includes(dependencies)) {
      throw new Error('Unable to find the Android app dependencies');
    }

    appConfig.modResults.contents = appConfig.modResults.contents.replace(
      dependencies,
      `${dependencies}\n    implementation("io.github.0xsequence:oms-wallet-kotlin-sdk:${sdkVersion}")`
    );
    return appConfig;
  });

  config = withMainApplication(config, (applicationConfig) => {
    const classDeclaration =
      'class MainApplication : Application(), ReactApplication {';
    if (!applicationConfig.modResults.contents.includes(classDeclaration)) {
      throw new Error('Unable to find the Android MainApplication class');
    }

    applicationConfig.modResults.contents =
      applicationConfig.modResults.contents.replace(
        classDeclaration,
        `${classDeclaration}\n\n  private val omsWalletCompatibilityProbe = technology.polygon.omswallet.OMSWallet::class.java`
      );
    return applicationConfig;
  });

  return config;
};
