package com.xpl.a2uichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xpl.a2uichat.chat.ChatScreen
import com.xpl.a2uichat.chat.ChatViewModel
import com.xpl.a2uichat.ui.ChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChatTheme {
                val chat: ChatViewModel = viewModel()
                ChatScreen(chat)
            }
        }
    }
}
