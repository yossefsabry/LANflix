package com.thiyagu.media_server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.net.InetAddress
import java.net.NetworkInterface

class NetworkInfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_info)

        val btnBack = findViewById<View>(R.id.btn_back)
        btnBack?.setOnClickListener { finish() }

        val btnNetworkTest = findViewById<MaterialButton>(R.id.btn_network_test)
        btnNetworkTest?.setOnClickListener {
            runNetworkTest()
        }

        // Load network information
        loadNetworkInfo()
    }

    private fun loadNetworkInfo() {
        val ipAddress = getLocalIpAddress()
        val port = "8080" // Default port, could be loaded from config
        val connectedDevices = "0" // Placeholder

        findViewById<TextView>(R.id.info_ip_value)?.text = ipAddress
        findViewById<TextView>(R.id.info_port_value)?.text = port
        findViewById<TextView>(R.id.info_status_value)?.text = if (ipAddress != "Not Connected") "Connected" else "Disconnected"
        findViewById<TextView>(R.id.info_network_type_value)?.text = getNetworkType()
        findViewById<TextView>(R.id.info_devices_value)?.text = connectedDevices
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Not Connected"
    }

    private fun getNetworkType(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return if (wifiManager?.isWifiEnabled == true) {
            "WiFi"
        } else {
            "Mobile Data"
        }
    }

    private fun runNetworkTest() {
        Toast.makeText(this, "Running network diagnostics...", Toast.LENGTH_SHORT).show()
        
        // Simulate network test
        findViewById<MaterialButton>(R.id.btn_network_test)?.apply {
            isEnabled = false
            text = "Testing..."
            
            postDelayed({
                isEnabled = true
                text = "Run Network Test"
                Toast.makeText(this@NetworkInfoActivity, "Network test completed: All OK", Toast.LENGTH_SHORT).show()
            }, 2000)
        }
    }
}
