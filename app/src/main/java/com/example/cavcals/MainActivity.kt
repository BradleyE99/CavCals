package com.example.cavcals

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cavcals.auth.AuthRepository
import com.example.cavcals.ui.home.CavCalsApp
import com.example.cavcals.ui.login.LoginScreen
import com.example.cavcals.ui.theme.CavCalsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CavCalsTheme {
                // The Firebase session decides which screen is shown. AuthRepository starts with
                // the user Firebase restored from disk, so a relaunch with a valid session opens
                // the signed-in app directly. After a successful login (or a sign-out), Firebase's
                // auth state listener emits the change and this branch switches on its own; the
                // login and sign-out code never navigate.
                val currentUser by AuthRepository.currentUser.collectAsStateWithLifecycle()

                // Login and the signed-in app replace each other instead of being stacked, so
                // there is no Login entry behind the signed-in app. Back from Home leaves the app
                // rather than reopening Login while the session is active.
                if (currentUser == null) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        LoginScreen(modifier = Modifier.padding(innerPadding))
                    }
                } else {
                    // CavCalsApp has its own Scaffold with the bottom navigation bar.
                    CavCalsApp()
                }
            }
        }
    }
}
