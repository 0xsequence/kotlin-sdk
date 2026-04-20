package com.polygon_wallet.kotlin_sdk

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.polygon_wallet.polygon_kotlin_sdk.PolygonSdk
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceEnvironment

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sdk = PolygonSdk(
            context = this,
            projectAccessKey = DemoConfig.demoProjectAccessKey,
            environment = SequenceEnvironment.demoDefaults(),
        )
        if (sdk.wallet.walletAddress != null) {
            startActivity(Intent(this, AuthDemoActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_picker)
        findViewById<android.view.View>(R.id.pickerRoot).applySafeDrawingInsets()

        findViewById<MaterialButton>(R.id.openTestbedButton).setOnClickListener {
            startActivity(Intent(this, TestbedActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.openAuthDemoButton).setOnClickListener {
            startActivity(Intent(this, AuthDemoActivity::class.java))
        }
    }
}
