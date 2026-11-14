package com.gab.bell;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BellBrainPlugin.class);
        registerPlugin(BellEyesPlugin.class);
        registerPlugin(BellWebPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
