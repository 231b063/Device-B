package com.smartassessment.bdevice;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity implements RelayService.RelayCallback {

    EditText etMac;
    Button btnStart;
    TextView tvLog;

    RelayService relay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etMac = findViewById(R.id.etCAddress);
        btnStart = findViewById(R.id.btnStart);
        tvLog = findViewById(R.id.tvLog);

        relay = new RelayService(this,this);

        // ---- REQUEST PERMISSIONS ----
        requestBtPermissions();

        btnStart.setOnClickListener(v -> {
            String mac = etMac.getText().toString().trim();
            relay.startRelay(mac);
            tvLog.append("\nRelay started...");
        });
    }

    // -------------------------------
    // PERMISSION CHECKER
    // -------------------------------
    private boolean hasBtPermissions() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        return true; // Below Android M (API 23)
    }

    // -------------------------------
    // PERMISSION REQUEST
    // -------------------------------
    private void requestBtPermissions() {
        if (hasBtPermissions()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    },
                    101);

        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    102);
        }
    }

    @Override
    public void onStatus(String msg) {
        runOnUiThread(() -> tvLog.append("\n" + msg));
    }
}
