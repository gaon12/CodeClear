package xyz.gaon.codeclear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import xyz.gaon.codeclear.mobile.ResultNotifications
import xyz.gaon.codeclear.mobile.mobileRuntime
import xyz.gaon.codeclear.ui.theme.CodeClearTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDestination(intent)
        enableEdgeToEdge()
        setContent {
            CodeClearTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDestination(intent)
    }

    override fun onResume() {
        super.onResume()
        mobileRuntime.controller.start()
        mobileRuntime.controller.refresh()
    }

    private fun openDestination(intent: Intent) {
        ResultNotifications.destination(intent.data)?.let {
            mobileRuntime.controller.openDetail(it.id, it.target)
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CodeClearTheme {
        Greeting("Android")
    }
}
