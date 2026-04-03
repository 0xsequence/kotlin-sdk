package com.polygon_wallet.kotlin_sdk

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.polygon_wallet.polygon_kotlin_sdk.SequenceSdk
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceEnvironment

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sdk = SequenceSdk(
            context = this,
            projectAccessKey = DemoConfig.demoProjectAccessKey,
        )
        sdk.wallet.restorePersistedSession()
        if (sdk.wallet.isSignedIn) {
            startActivity(Intent(this, EmailLoginDemoActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_picker)
        findViewById<android.view.View>(R.id.pickerRoot).applySafeDrawingInsets()

        findViewById<MaterialButton>(R.id.openTestbedButton).setOnClickListener {
            startActivity(Intent(this, TestbedActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.openEmailLoginDemoButton).setOnClickListener {
            startActivity(Intent(this, EmailLoginDemoActivity::class.java))
        }
    }
}
