package xyz.gaon.codeclear.mobile

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/** Hosts synthetic UI without the product activity's runtime and configuration callbacks. */
class MobileFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
