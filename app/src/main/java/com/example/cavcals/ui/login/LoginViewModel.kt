package com.example.cavcals.ui.login

import android.content.Context
import android.util.Log
import android.util.Patterns
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.cavcals.R
import com.example.cavcals.auth.AuthRepository
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
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
    val confirmPassword: String = "",
    // false = "Log in" form, true = "Create account" form. Both share the same fields; create
    // mode adds the confirm-password field and calls a different Firebase method.
    val isCreateAccountMode: Boolean = false,
    @param:StringRes val emailError: Int? = null,
    @param:StringRes val passwordError: Int? = null,
    @param:StringRes val confirmPasswordError: Int? = null,
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

    fun onConfirmPasswordChange(confirmPassword: String) {
        _uiState.update {
            it.copy(confirmPassword = confirmPassword, confirmPasswordError = null, loginError = null)
        }
    }

    /**
     * Switches between "Log in" and "Create account". The email is kept (people often realize
     * they need an account after typing it), but passwords and errors are cleared.
     */
    fun onToggleModeClick() {
        _uiState.update {
            if (it.isLoading) it
            else LoginUiState(email = it.email, isCreateAccountMode = !it.isCreateAccountMode)
        }
    }

    /** Log in / Create account tapped (or Done pressed on the keyboard). */
    fun onSubmitClick() {
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
        val isCreate = current.isCreateAccountMode
        val passwordError = when {
            password.isEmpty() -> R.string.login_error_password_blank
            // Firebase rejects passwords under 6 characters; checking here gives instant feedback.
            isCreate && password.length < MIN_PASSWORD_LENGTH -> R.string.signup_error_password_short
            else -> null
        }
        val confirmPasswordError =
            if (isCreate && current.confirmPassword != password) R.string.signup_error_password_mismatch
            else null

        if (emailError != null || passwordError != null || confirmPasswordError != null) {
            _uiState.update {
                it.copy(
                    emailError = emailError,
                    passwordError = passwordError,
                    confirmPasswordError = confirmPasswordError,
                    loginError = null,
                )
            }
            return
        }

        // isLoading = true disables the button and shows progress, preventing duplicate
        // requests. viewModelScope cancels this coroutine automatically if the ViewModel is
        // cleared, so a request never outlives the screen that started it.
        _uiState.update { it.copy(email = email, isLoading = true, loginError = null) }
        viewModelScope.launch {
            try {
                if (isCreate) {
                    authRepository.createAccount(email, password)
                } else {
                    authRepository.signIn(email, password)
                }
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

    /**
     * "Sign in with Google" tapped.
     *
     * Two steps: Credential Manager shows Google's account picker and hands back an ID token,
     * then the repository trades that token for a Firebase session. [activityContext] must be the
     * Activity because the picker is drawn on top of it. It is only used during this call.
     *
     * R.string.default_web_client_id is generated by the google-services plugin from
     * google-services.json (the "Web client ID" in the Firebase console). It tells Google which
     * backend the token is for, so Firebase will accept it.
     */
    fun onGoogleSignInClick(activityContext: Context) {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, loginError = null, emailError = null, passwordError = null) }

        viewModelScope.launch {
            try {
                val googleOption = GetSignInWithGoogleOption
                    .Builder(activityContext.getString(R.string.default_web_client_id))
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build()
                val result = CredentialManager.create(activityContext)
                    .getCredential(activityContext, request)

                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    authRepository.signInWithGoogle(idToken)
                    _uiState.value = LoginUiState()
                } else {
                    _uiState.update { it.copy(isLoading = false, loginError = R.string.login_error_google_failed) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GetCredentialCancellationException) {
                // Usually the user closed the account picker, which is not an error. But Google
                // also reports a misconfigured app (the signing key's SHA-1 not registered in
                // Firebase) as a cancellation *after* an account is picked, so log it: that is the
                // only clue in Logcat when "nothing happens". These messages hold no secrets.
                Log.w(TAG, "Google sign-in cancelled: ${e.message}")
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: NoCredentialException) {
                Log.w(TAG, "Google sign-in found no credential: ${e.message}")
                _uiState.update { it.copy(isLoading = false, loginError = R.string.login_error_google_no_account) }
            } catch (e: GetCredentialException) {
                Log.w(TAG, "Google sign-in failed: ${e.type}: ${e.message}")
                _uiState.update { it.copy(isLoading = false, loginError = R.string.login_error_google_failed) }
            } catch (e: Exception) {
                Log.w(TAG, "Firebase rejected the Google credential: ${e.javaClass.simpleName}: ${e.message}")
                _uiState.update { it.copy(isLoading = false, loginError = messageFor(e)) }
            }
        }
    }

    // Translate Firebase exceptions into messages a user can act on. Wrong password and
    // unknown email share one message so the app does not reveal which accounts exist.
    @StringRes
    private fun messageFor(error: Exception): Int = when (error) {
        // WeakPassword is a subclass of InvalidCredentials, so it must be checked first.
        is FirebaseAuthWeakPasswordException -> R.string.signup_error_password_short
        is FirebaseAuthUserCollisionException -> R.string.signup_error_email_in_use
        is FirebaseAuthInvalidCredentialsException,
        is FirebaseAuthInvalidUserException -> R.string.login_error_invalid_credentials
        is FirebaseNetworkException -> R.string.login_error_network
        is FirebaseTooManyRequestsException -> R.string.login_error_too_many_requests
        else -> R.string.login_error_unknown
    }

    private companion object {
        const val TAG = "LoginViewModel"
        const val MIN_PASSWORD_LENGTH = 6
    }
}
