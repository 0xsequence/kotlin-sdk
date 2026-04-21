package com.omswallet.kotlin_sdk_samples

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.omswallet.kotlin_sdk.OmsWallet
import com.omswallet.kotlin_sdk.network.OmsWalletEnvironment

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sdk = OmsWallet(
            context = this,
            projectAccessKey = DemoConfig.demoProjectAccessKey,
            environment = OmsWalletEnvironment.demoDefaults(),
        )
        if (sdk.walletAddress != null) {
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
