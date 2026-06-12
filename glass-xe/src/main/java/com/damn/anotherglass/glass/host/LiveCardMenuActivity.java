package com.damn.anotherglass.glass.host;

import android.app.Activity;
import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.damn.anotherglass.glass.host.wifi.WiFiPower;

/**
 * A transparent {@link Activity} displaying a "Stop" options menu to remove the {@link LiveCard}.
 */
public class LiveCardMenuActivity extends Activity {

    private static final int ACTION_TOGGLE_WIFI = 1001;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Open the options menu right away.
        openOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.host_card, menu);
        menu.add(Menu.NONE, ACTION_TOGGLE_WIFI, 2, getWiFiToggleTitle());
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem wifiItem = menu.findItem(ACTION_TOGGLE_WIFI);
        if (wifiItem != null) {
            wifiItem.setTitle(getWiFiToggleTitle());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_call:
                startService(new Intent(this, HostService.class).setAction(HostService.ACTION_REQUEST_CONTACTS));
                return true;
            case R.id.action_siri:
                startService(new Intent(this, HostService.class).setAction(HostService.ACTION_REQUEST_SIRI));
                return true;
            case R.id.action_stop:
                // Stop the service which will unpublish the live card.
                stopService(new Intent(this, HostService.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private String getWiFiToggleTitle() {
        return getString(WiFiPower.isEnabled(this) ? R.string.action_wifi_off : R.string.action_wifi_on);
    }

    private void toggleWiFi() {
        WiFiPower.Result result = WiFiPower.toggle(this);
        if (!result.success) {
            Toast.makeText(this, R.string.msg_wifi_toggle_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(
                this,
                result.enabled ? R.string.msg_wifi_enabled : R.string.msg_wifi_disabled,
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    public void onOptionsMenuClosed(Menu menu) {
        super.onOptionsMenuClosed(menu);
        // Nothing else to do, finish the Activity.
        finish();
    }
}
