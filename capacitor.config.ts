import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.butler.app',
  appName: 'Butler',
  webDir: 'dist',
  bundledWebRuntime: false,
  server: {
    androidScheme: 'https',
    iosScheme: 'capacitor',
    cleartext: true,
  },
  android: {
    allowMixedContent: true,
    backgroundColor: '#0a0a0f',
  },
  ios: {
    backgroundColor: '#0a0a0f',
    contentInset: 'always',
    scrollEnabled: false,
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 1500,
      backgroundColor: '#0a0a0f',
      showSpinner: false,
      androidScaleType: 'CENTER_CROP',
    },
    WebView: {
      androidOverwrite: true,
    },
  },
};

export default config;
