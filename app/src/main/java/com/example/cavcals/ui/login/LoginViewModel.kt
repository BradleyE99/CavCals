package com.example.cavcals.ui.login

import android.util.Patterns
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cavcals.R
import com.example.cavcals.auth.AuthRepository
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the login screen needs to draw itself. Errors are string resource IDs so the
 * ViewModel stays free of Context and the text lives in strings.xml.
 */
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    @param:StringRes val emailError: Int? = null,
    @param:StringRes val passwordError: Int? = null,
    @param:StringRes val loginError: Int? = null,
    val isLoading: Boolean = false,
)

/**
 * Holds the login form state and runs the sign-in request.
 *
 * Unidirectional data flow: the composable sends events (text changed, Log in tapped) to the
 * functions below, the ViewModel updates [uiState], and Compose redraws from the new state.
 * The screen never changes the state directly.
 */
class LoginViewModel(
    private val authRepository: AuthRepository = AuthRepository,
) : ViewModel() {

    // Private and mutable inside the ViewModel; public and read-only for the UI. The UI collects
    // this with collectAsStateWithLifecycle(), which stops collecting while the app is in the
    // background and recomposes the screen whenever a new state is emitted.
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    // Typing in a field clears that field's error and the last Firebase error, so an old message
    // does not linger while the user is fixing the problem.
    fun onEmailChange(email: String) {
        _uiState.update { it.copy(email = email, emailError = null, loginError = null) }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, passwordError = null, loginError = null) }
    }

    fun onLoginClick() {
        val current = _uiState.value

        // Ignore extra taps while a request is already running. The button is also disabled
        // while loading, but this guard covers the keyboard's Done action and fast double taps.
        if (current.isLoading) return

        // Validate locally first so obvious mistakes get instant feedback without a network
        // round trip. Only the email is trimmed (copy-paste often adds spaces); the password is
        // sent exactly as typed because spaces can be part of a real password.
        val email = current.email.trim()
        val password = current.password
        val emailError = when {
            email.isEmpty() -> R.string.login_error_email_blank
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> R.string.login_error_email_invalid
            else -> null
        }
        val passwordError = if (password.isEmpty()) R.string.login_error_password_blank else null

        if (emailError != null || passwordError != null) {
            _uiState.update {
                it.copy(emailError = emailError, passwordError = passwordError, loginError = null)
            }
            return
        }

        // isLoading = true disables the button and shows progress, preventing duplicate
        // requests. viewModelScope cancels this coroutine automatically if the ViewModel is
        // cleared, so a request never outlives the screen that started it.
        _uiState.update { it.copy(email = email, isLoading = true, loginError = null) }
        viewModelScope.launch {
            try {
                authRepository.signIn(email, password)
                // Success: the repository's session listener switches MainActivity to the
                // signed-in screen. Reset the form so the password is not kept in memory.
                _uiState.value = LoginUiState()
            } catch (e: CancellationException) {
                // Coroutine cancellation must propagate; it is not a login failure.
                throw e
            } catch (e: Exception) {
                // Failure: stop loading so the button re-enables and the user can retry.
                // Only a message ID is stored; the password and exception are never logged.
                _uiState.update { it.copy(isLoading = false, loginError = messageFor(e)) }
            }
        }
    }

    // Translate Firebase exceptions into messages a user can act on. Wrong password and
    // unknown email share one message so the app does not reveal which accounts exist.
    @StringRes
    private fun messageFor(error: Exception): Int = when (error) {
        is FirebaseAuthInvalidCredentialsException,
        is FirebaseAuthInvalidUserException -> R.string.login_error_invalid_credentials
        is FirebaseNetworkException -> R.string.login_error_network
        is FirebaseTooManyRequestsException -> R.string.login_error_too_many_requests
        else -> R.string.login_error_unknown
    }
}
