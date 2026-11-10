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
import com.example.cavcals.ui.analyze.AnalyzeMacrosScreen
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
                // the signed-in screen directly. After a successful login, Firebase's auth state
                // listener emits the new user and this branch switches on its own; the login
                // code never navigates.
                val currentUser by AuthRepository.currentUser.collectAsStateWithLifecycle()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // The two screens replace each other instead of being stacked, so there is
                    // no Login entry behind the signed-in screen. Back from the signed-in screen
                    // leaves the app rather than reopening Login while the session is active.
                    val user = currentUser
                    if (user == null) {
                        LoginScreen(modifier = Modifier.padding(innerPadding))
                    } else {
                        AnalyzeMacrosScreen(
                            signedInEmail = user.email,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }
}
