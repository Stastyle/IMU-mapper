package com.stastyle.imumapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.stastyle.imumapper.ui.nav.AppNavGraph
import com.stastyle.imumapper.ui.theme.ImuMapperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImuMapperTheme {
                AppNavGraph()
            }
        }
    }
}
