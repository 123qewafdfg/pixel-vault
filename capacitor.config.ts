import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.trustmark.share",
  appName: "隐像",
  webDir: "apps/web/dist",
  android: {
    allowMixedContent: false,
  },
};

export default config;
