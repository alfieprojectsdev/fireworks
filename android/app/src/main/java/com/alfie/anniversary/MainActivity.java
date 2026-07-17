package com.alfie.anniversary;

import android.os.Bundle;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // App-local plugins are NOT auto-discovered: Capacitor's generated
        // capacitor.plugins.json only lists npm-installed plugins, so the
        // @CapacitorPlugin annotation alone never registers SensorBridgePlugin.
        // Must be called before super.onCreate() so the bridge picks it up.
        registerPlugin(SensorBridgePlugin.class);
        super.onCreate(savedInstanceState);

        // The AR experience is a continuous hold-up-the-phone session with no
        // touch input; without this flag the display sleeps mid-experience.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
