package com.damn.anotherglass.glass.host;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Entry point for the Glass voice trigger. The actual request is handled by
 * HostService so it can reuse the active RPC connection.
 */
public class SiriVoiceActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startService(new Intent(this, HostService.class).setAction(HostService.ACTION_REQUEST_SIRI));
        finish();
    }
}
