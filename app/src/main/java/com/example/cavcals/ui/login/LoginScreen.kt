package com.example.cavcals.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cavcals.R
import com.example.cavcals.ui.theme.CavCalsTheme

/**
 * Connects the login form to [LoginViewModel]. This wrapper is the only part that knows about
 * the ViewModel; [LoginForm] below just draws state and reports events, which keeps it easy to
 * preview.
 */
@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    loginViewModel: LoginViewModel = viewModel(),
) {
    // Collect the ViewModel's StateFlow as Compose state. Each new LoginUiState triggers a
    // recomposition, and collection pauses while the app is in the background.
    val uiState by loginViewModel.uiState.collectAsStateWithLifecycle()
    // Inside an Activity's setContent, LocalContext is that Activity. Google's account picker
    // needs it to draw on top of the app.
    val context = LocalContext.current

    // Pass function references instead of the ViewModel itself: the form sends events up and
    // the ViewModel decides what they mean.
    LoginForm(
        uiState = uiState,
        onEmailChange = loginViewModel::onEmailChange,
        onPasswordChange = loginViewModel::onPasswordChange,
        onLoginClick = loginViewModel::onLoginClick,
        onGoogleSignInClick = { loginViewModel.onGoogleSignInClick(context) },
        modifier = modifier,
    )
}

@Composable
fun LoginForm(
    uiState: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginClick: () -> Unit,
    onGoogleSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Whether the password is shown is purely visual, so it lives here rather than in the
    // ViewModel. It starts as false so the password is masked by default, and rememberSaveable
    // keeps the choice across rotation.
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        OutlinedTextField(
            value = uiState.email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(R.string.login_email_label)) },
            isError = uiState.emailError != null,
            supportingText = uiState.emailError?.let { { Text(stringResource(it)) } },
            singleLine = true,
            enabled = !uiState.isLoading,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = uiState.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.login_password_label)) },
            isError = uiState.passwordError != null,
            supportingText = uiState.passwordError?.let { { Text(stringResource(it)) } },
            singleLine = true,
            enabled = !uiState.isLoading,
            // Masking is a display transformation only; the real text still goes to the ViewModel.
            visualTransformation = if (isPasswordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                val toggleDescription = stringResource(
                    if (isPasswordVisible) R.string.login_hide_password_description
                    else R.string.login_show_password_description
                )
                TextButton(
                    onClick = { isPasswordVisible = !isPasswordVisible },
                    modifier = Modifier.semantics { contentDescription = toggleDescription },
                ) {
                    Text(
                        stringResource(
                            if (isPasswordVisible) R.string.login_hide_password
                            else R.string.login_show_password
                        )
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            // Pressing Done on the keyboard sends the same event as tapping Log in.
            keyboardActions = KeyboardActions(onDone = { onLoginClick() }),
            modifier = Modifier.fillMaxWidth(),
        )

        // The Firebase error is announced by screen readers when it appears (live region).
        uiState.loginError?.let { messageId ->
            Text(
                text = stringResource(messageId),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        // Disabled while loading so one tap sends exactly one Firebase request. It re-enables
        // when the request fails, which is how the user retries.
        val loadingDescription = stringResource(R.string.login_in_progress)
        Button(
            onClick = onLoginClick,
            enabled = !uiState.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = loadingDescription },
                )
            } else {
                Text(stringResource(R.string.login_button))
            }
        }

        Text(
            text = stringResource(R.string.login_or),
            style = MaterialTheme.typography.bodyMedium,
        )

        // Same isLoading guard as the email button, so only one sign-in runs at a time.
        OutlinedButton(
            onClick = onGoogleSignInClick,
            enabled = !uiState.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.login_google_button))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginFormPreview() {
    CavCalsTheme {
        LoginForm(
            uiState = LoginUiState(
                email = "student@example.com",
                loginError = R.string.login_error_invalid_credentials,
            ),
            onEmailChange = {},
            onPasswordChange = {},
            onLoginClick = {},
            onGoogleSignInClick = {},
        )
    }
}
