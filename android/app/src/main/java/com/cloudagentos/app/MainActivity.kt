package com.cloudagentos.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloudagentos.app.ui.screens.ChatScreen
import com.cloudagentos.app.ui.theme.Background
import com.cloudagentos.app.ui.theme.CloudAgentOSTheme
import com.cloudagentos.app.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {

    private var chatViewModel: ChatViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CloudAgentOSTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    val vm: ChatViewModel = viewModel()
                    chatViewModel = vm
                    ChatScreen(chatViewModel = vm)
                }
            }
        }
    }
}
