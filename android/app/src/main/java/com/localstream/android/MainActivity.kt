package com.localstream.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.localstream.android.ui.LocalStreamApp
import com.localstream.android.ui.theme.LocalStreamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LocalStreamTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LocalStreamApp()
                }
            }
        }
    }
}
